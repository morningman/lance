/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lance.demo;

import com.lancedb.lance.Dataset;
import com.lancedb.lance.FragmentMetadata;
import com.lancedb.lance.file.LanceFileReader;
import com.lancedb.lance.fragment.DataFile;
import com.lancedb.lance.ipc.LanceScanner;
import com.lancedb.lance.ipc.ScanOptions;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 演示在 Lance Java SDK 中如何实现文件级别的谓词裁剪（Predicate Pruning）。
 * 
 * 文件级别裁剪是一个重要的性能优化技术，通过分析文件/Fragment 的元数据
 * （如 min/max 统计信息），可以跳过不满足查询条件的文件，避免不必要的 I/O。
 */
public class PredicatePruning {
    private static final Logger logger = LoggerFactory.getLogger(PredicatePruning.class);

    /**
     * 简单的谓词接口，用于判断是否需要读取某个文件/Fragment。
     */
    public interface Predicate {
        /**
         * 判断是否需要处理这个 Fragment。
         * 
         * @param fragment Fragment 元数据
         * @return true 表示需要处理，false 表示可以跳过
         */
        boolean shouldProcess(FragmentMetadata fragment);

        /**
         * 获取谓词的 SQL 表达式（用于 Scanner）。
         */
        String toSqlExpression();
    }

    /**
     * 范围谓词：column >= minValue AND column <= maxValue
     */
    public static class RangePredicate implements Predicate {
        private final String columnName;
        private final Long minValue;
        private final Long maxValue;

        public RangePredicate(String columnName, Long minValue, Long maxValue) {
            this.columnName = columnName;
            this.minValue = minValue;
            this.maxValue = maxValue;
        }

        @Override
        public boolean shouldProcess(FragmentMetadata fragment) {
            // 注意：当前 Lance Java SDK 的 FragmentMetadata 不直接提供列级统计信息
            // 这里展示的是逻辑框架，实际使用时需要：
            // 1. 从 Fragment 元数据中获取统计信息（如果可用）
            // 2. 或者通过扫描文件的 metadata 来获取 min/max
            // 3. 或者使用 deletion file 信息进行粗粒度过滤
            
            // 目前我们使用物理行数作为简单的过滤条件示例
            // 实际应用中，应该获取列的 min/max 统计信息
            return fragment.getPhysicalRows() > 0;
        }

        @Override
        public String toSqlExpression() {
            StringBuilder sb = new StringBuilder();
            if (minValue != null) {
                sb.append(columnName).append(" >= ").append(minValue);
            }
            if (maxValue != null) {
                if (sb.length() > 0) {
                    sb.append(" AND ");
                }
                sb.append(columnName).append(" <= ").append(maxValue);
            }
            return sb.toString();
        }
    }

    /**
     * 方式 1：基于 Dataset Scanner 的谓词下推（推荐）
     * 
     * 这是最简单和推荐的方式，Lance 会自动处理 Fragment 级别的裁剪。
     */
    public static class DatasetLevelPruning {
        
        /**
         * 使用 Dataset Scanner 进行表级谓词下推。
         * Lance 内部会自动进行 Fragment 级别的裁剪。
         */
        public static long scanWithPredicate(
                String datasetPath,
                String predicateExpression,
                BufferAllocator allocator) throws Exception {
            
            logger.info("Using Dataset-level predicate pushdown: {}", predicateExpression);
            
            try (Dataset dataset = Dataset.open(datasetPath, allocator)) {
                
                // 创建扫描选项，包含谓词
                ScanOptions.Builder scanBuilder = new ScanOptions.Builder();
                scanBuilder.filter(predicateExpression);
                
                ScanOptions scanOptions = scanBuilder.build();
                
                // 创建 Scanner
                try (LanceScanner scanner = LanceScanner.create(dataset, scanOptions, allocator)) {
                    
                    long totalRows = 0;
                    
                    // Lance 会自动根据谓词裁剪 Fragment
                    while (scanner.loadNextBatch()) {
                        VectorSchemaRoot root = scanner.getVectorSchemaRoot();
                        totalRows += root.getRowCount();
                        
                        logger.debug("Read batch with {} rows", root.getRowCount());
                    }
                    
                    logger.info("Total rows scanned with predicate: {}", totalRows);
                    return totalRows;
                }
            }
        }
    }

    /**
     * 方式 2：手动 Fragment 级别裁剪
     * 
     * 适用于需要更细粒度控制的场景，比如：
     * - 自定义的裁剪逻辑
     * - 需要在应用层收集裁剪统计信息
     * - 分布式执行中的任务分配
     */
    public static class ManualFragmentPruning {
        
        /**
         * 手动过滤 Fragments 并读取。
         */
        public static PruningResult scanWithManualPruning(
                String datasetPath,
                Predicate predicate,
                BufferAllocator allocator) throws Exception {
            
            logger.info("Using manual fragment pruning");
            
            PruningResult result = new PruningResult();
            
            try (Dataset dataset = Dataset.open(datasetPath, allocator)) {
                
                // 1. 获取所有 Fragments
                List<FragmentMetadata> allFragments = dataset.getFragments();
                logger.info("Total fragments in dataset: {}", allFragments.size());
                
                // 2. 应用谓词过滤 Fragments
                List<FragmentMetadata> selectedFragments = allFragments.stream()
                    .filter(fragment -> {
                        boolean shouldProcess = predicate.shouldProcess(fragment);
                        if (!shouldProcess) {
                            result.addPrunedFragment(fragment.getId());
                            logger.debug("Pruned fragment {} (physical rows: {})",
                                fragment.getId(), fragment.getPhysicalRows());
                        }
                        return shouldProcess;
                    })
                    .collect(Collectors.toList());
                
                result.setTotalFragments(allFragments.size());
                result.setSelectedFragments(selectedFragments.size());
                
                logger.info("Fragments after pruning: {} / {} (pruned: {})",
                    selectedFragments.size(),
                    allFragments.size(),
                    result.getPrunedFragments().size());
                
                // 3. 使用 Dataset Scanner 读取选中的 Fragments
                if (!selectedFragments.isEmpty()) {
                    // 构建 fragment IDs 列表
                    List<Integer> fragmentIds = selectedFragments.stream()
                        .map(FragmentMetadata::getId)
                        .collect(Collectors.toList());
                    
                    ScanOptions.Builder scanBuilder = new ScanOptions.Builder();
                    scanBuilder.fragmentIds(fragmentIds);
                    
                    // 同时应用谓词进行进一步过滤
                    String sqlExpression = predicate.toSqlExpression();
                    if (sqlExpression != null && !sqlExpression.isEmpty()) {
                        scanBuilder.filter(sqlExpression);
                    }
                    
                    ScanOptions scanOptions = scanBuilder.build();
                    
                    try (LanceScanner scanner = LanceScanner.create(dataset, scanOptions, allocator)) {
                        long totalRows = 0;
                        
                        while (scanner.loadNextBatch()) {
                            VectorSchemaRoot root = scanner.getVectorSchemaRoot();
                            totalRows += root.getRowCount();
                        }
                        
                        result.setRowsRead(totalRows);
                        logger.info("Total rows read: {}", totalRows);
                    }
                }
            }
            
            return result;
        }
    }

    /**
     * 方式 3：文件级别的裁剪和并行读取
     * 
     * 结合 Fragment 裁剪和文件级别读取，适用于需要完全控制读取过程的场景。
     */
    public static class FileLevelPruning {
        
        /**
         * 在文件级别进行裁剪和读取。
         */
        public static PruningResult scanFilesWithPruning(
                String datasetPath,
                Predicate predicate,
                Optional<List<String>> columns,
                BufferAllocator allocator) throws Exception {
            
            logger.info("Using file-level pruning and reading");
            
            PruningResult result = new PruningResult();
            
            try (Dataset dataset = Dataset.open(datasetPath, allocator)) {
                
                // 1. 获取并过滤 Fragments
                List<FragmentMetadata> allFragments = dataset.getFragments();
                List<FragmentMetadata> selectedFragments = allFragments.stream()
                    .filter(predicate::shouldProcess)
                    .collect(Collectors.toList());
                
                result.setTotalFragments(allFragments.size());
                result.setSelectedFragments(selectedFragments.size());
                
                logger.info("Selected {} fragments out of {}",
                    selectedFragments.size(), allFragments.size());
                
                // 2. 遍历选中的 Fragments，读取其文件
                long totalRows = 0;
                int filesRead = 0;
                
                for (FragmentMetadata fragment : selectedFragments) {
                    List<DataFile> dataFiles = fragment.getFiles();
                    
                    for (DataFile dataFile : dataFiles) {
                        String filePath = dataFile.getPath();
                        
                        // 使用 LanceFileReader 读取文件
                        try (LanceFileReader fileReader = LanceFileReader.open(filePath, allocator)) {
                            
                            long fileRows = fileReader.numRows();
                            logger.debug("Reading file: {} ({} rows)", filePath, fileRows);
                            
                            // 读取文件数据
                            try (ArrowReader reader = fileReader.readAll(
                                    columns.orElse(null),
                                    null,  // ranges
                                    1024   // batch size
                            )) {
                                while (reader.loadNextBatch()) {
                                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                                    
                                    // 在内存中应用谓词过滤
                                    int filteredRows = applyPredicateInMemory(
                                        root,
                                        predicate.toSqlExpression()
                                    );
                                    
                                    totalRows += filteredRows;
                                }
                            }
                            
                            filesRead++;
                        }
                    }
                }
                
                result.setRowsRead(totalRows);
                result.setFilesRead(filesRead);
                
                logger.info("Read {} files, {} rows total", filesRead, totalRows);
            }
            
            return result;
        }
        
        /**
         * 在内存中应用简单的谓词过滤（示例）。
         * 实际应用中，应该使用更复杂的表达式解析和评估。
         */
        private static int applyPredicateInMemory(VectorSchemaRoot root, String predicate) {
            // 这是一个简化的示例
            // 实际应用中，应该：
            // 1. 解析 SQL 表达式
            // 2. 评估每一行
            // 3. 创建过滤后的 RecordBatch
            
            // 目前只返回所有行
            return root.getRowCount();
        }
    }

    /**
     * 方式 4：基于统计信息的高级裁剪（概念示例）
     * 
     * 展示如何利用列级统计信息进行更精确的裁剪。
     * 注意：需要 Lance 支持暴露列级统计信息。
     */
    public static class StatisticsBasedPruning {
        
        /**
         * 列统计信息（概念模型）。
         */
        public static class ColumnStatistics {
            private final String columnName;
            private final Object minValue;
            private final Object maxValue;
            private final long nullCount;
            
            public ColumnStatistics(String columnName, Object minValue, Object maxValue, long nullCount) {
                this.columnName = columnName;
                this.minValue = minValue;
                this.maxValue = maxValue;
                this.nullCount = nullCount;
            }
            
            public boolean canPruneForRange(Object queryMin, Object queryMax) {
                // 如果文件的 max < query_min 或 文件的 min > query_max，则可以裁剪
                if (minValue instanceof Comparable && maxValue instanceof Comparable) {
                    @SuppressWarnings("unchecked")
                    Comparable<Object> min = (Comparable<Object>) minValue;
                    @SuppressWarnings("unchecked")
                    Comparable<Object> max = (Comparable<Object>) maxValue;
                    
                    if (queryMin != null && max.compareTo(queryMin) < 0) {
                        return true;  // max < query_min
                    }
                    if (queryMax != null && min.compareTo(queryMax) > 0) {
                        return true;  // min > query_max
                    }
                }
                return false;
            }
        }
        
        /**
         * 获取 Fragment 的列统计信息（概念示例）。
         * 
         * 注意：这需要 Lance SDK 支持，当前版本可能不直接提供此功能。
         * 可以通过以下方式实现：
         * 1. 读取文件的 Parquet/Lance metadata
         * 2. 从 Fragment manifest 中提取统计信息
         * 3. 维护外部的统计信息存储
         */
        public static Map<String, ColumnStatistics> getFragmentStatistics(
                FragmentMetadata fragment,
                BufferAllocator allocator) {
            
            // 这是一个概念示例，实际实现需要：
            // 1. 访问 Fragment 的内部元数据
            // 2. 或者扫描文件的一小部分来收集统计信息
            
            Map<String, ColumnStatistics> stats = new HashMap<>();
            
            // 示例：假设我们有这些统计信息
            // stats.put("id", new ColumnStatistics("id", 1000L, 2000L, 0));
            // stats.put("timestamp", new ColumnStatistics("timestamp", ..., ..., 0));
            
            return stats;
        }
    }

    /**
     * 裁剪结果统计。
     */
    public static class PruningResult {
        private int totalFragments;
        private int selectedFragments;
        private final List<Integer> prunedFragments = new ArrayList<>();
        private long rowsRead;
        private int filesRead;
        
        public void setTotalFragments(int total) {
            this.totalFragments = total;
        }
        
        public void setSelectedFragments(int selected) {
            this.selectedFragments = selected;
        }
        
        public void addPrunedFragment(int fragmentId) {
            this.prunedFragments.add(fragmentId);
        }
        
        public void setRowsRead(long rows) {
            this.rowsRead = rows;
        }
        
        public void setFilesRead(int files) {
            this.filesRead = files;
        }
        
        public int getTotalFragments() {
            return totalFragments;
        }
        
        public int getSelectedFragments() {
            return selectedFragments;
        }
        
        public List<Integer> getPrunedFragments() {
            return prunedFragments;
        }
        
        public int getPrunedCount() {
            return prunedFragments.size();
        }
        
        public double getPruningRatio() {
            return totalFragments > 0 ? (double) getPrunedCount() / totalFragments : 0.0;
        }
        
        public long getRowsRead() {
            return rowsRead;
        }
        
        public int getFilesRead() {
            return filesRead;
        }
        
        @Override
        public String toString() {
            return String.format(
                "PruningResult{total=%d, selected=%d, pruned=%d (%.2f%%), rows=%d, files=%d}",
                totalFragments, selectedFragments, getPrunedCount(),
                getPruningRatio() * 100, rowsRead, filesRead
            );
        }
    }

    /**
     * 示例 1：使用 Dataset Scanner 的自动裁剪（推荐）。
     */
    public static void example1DatasetScannerPruning() {
        logger.info("=== Example 1: Dataset Scanner with Automatic Pruning ===");
        
        String datasetPath = "/path/to/dataset";
        String predicate = "age > 25 AND status = 'active'";
        
        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
            
            long rowsRead = DatasetLevelPruning.scanWithPredicate(
                datasetPath,
                predicate,
                allocator
            );
            
            logger.info("Rows read: {}", rowsRead);
            
        } catch (Exception e) {
            logger.error("Error in example 1", e);
        }
    }

    /**
     * 示例 2：手动 Fragment 裁剪。
     */
    public static void example2ManualFragmentPruning() {
        logger.info("=== Example 2: Manual Fragment Pruning ===");
        
        String datasetPath = "/path/to/dataset";
        
        // 创建范围谓词
        RangePredicate predicate = new RangePredicate("timestamp", 1700000000L, 1710000000L);
        
        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
            
            PruningResult result = ManualFragmentPruning.scanWithManualPruning(
                datasetPath,
                predicate,
                allocator
            );
            
            logger.info("Pruning result: {}", result);
            logger.info("Pruning ratio: {:.2f}%", result.getPruningRatio() * 100);
            
        } catch (Exception e) {
            logger.error("Error in example 2", e);
        }
    }

    /**
     * 示例 3：文件级别裁剪和并行读取。
     */
    public static void example3FileLevelPruning() {
        logger.info("=== Example 3: File-Level Pruning ===");
        
        String datasetPath = "/path/to/dataset";
        RangePredicate predicate = new RangePredicate("user_id", 10000L, 20000L);
        
        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
            
            PruningResult result = FileLevelPruning.scanFilesWithPruning(
                datasetPath,
                predicate,
                Optional.of(Arrays.asList("user_id", "event_type", "timestamp")),
                allocator
            );
            
            logger.info("File-level pruning result: {}", result);
            
        } catch (Exception e) {
            logger.error("Error in example 3", e);
        }
    }

    /**
     * 示例 4：结合 ParallelLanceReader 的裁剪。
     */
    public static void example4PruningWithParallelReader() {
        logger.info("=== Example 4: Pruning with Parallel Reader ===");
        
        String datasetPath = "/path/to/dataset";
        String predicate = "date >= '2024-01-01' AND date < '2024-02-01'";
        
        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
            
            // 先进行裁剪，获取需要读取的 Fragment 列表
            try (Dataset dataset = Dataset.open(datasetPath, allocator)) {
                List<FragmentMetadata> allFragments = dataset.getFragments();
                
                // 应用自定义裁剪逻辑
                RangePredicate rangePredicate = new RangePredicate("timestamp", 1704067200L, 1706745599L);
                List<FragmentMetadata> selectedFragments = allFragments.stream()
                    .filter(rangePredicate::shouldProcess)
                    .collect(Collectors.toList());
                
                logger.info("Selected {} fragments for parallel reading", selectedFragments.size());
                
                // 创建文件组
                List<FileGroup> fileGroups = new ArrayList<>();
                int groupId = 0;
                for (FragmentMetadata fragment : selectedFragments) {
                    FileGroup.Builder builder = new FileGroup.Builder(groupId++, fragment.getId());
                    for (DataFile file : fragment.getFiles()) {
                        builder.addFile(file.getPath());
                    }
                    builder.physicalRows(fragment.getPhysicalRows());
                    fileGroups.add(builder.build());
                }
                
                // 使用 ParallelLanceReader 的逻辑处理这些文件组
                logger.info("Created {} file groups for parallel processing", fileGroups.size());
            }
            
        } catch (Exception e) {
            logger.error("Error in example 4", e);
        }
    }

    public static void main(String[] args) {
        // 运行示例（取消注释以运行）
        
        // example1DatasetScannerPruning();
        // example2ManualFragmentPruning();
        // example3FileLevelPruning();
        // example4PruningWithParallelReader();
        
        logger.info("See example methods for usage patterns");
    }
}

