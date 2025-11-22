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
import com.lancedb.lance.fragment.DataFile;
import com.lancedb.lance.ipc.LanceScanner;
import com.lancedb.lance.ipc.ScanOptions;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * 演示如何结合 ScanOptions 谓词和并行读取。
 * 
 * 虽然 Lance Java SDK 不直接提供"根据谓词获取裁剪后的 fragments"的 API，
 * 但我们可以通过以下策略实现类似效果：
 * 
 * 策略 1：手动实现谓词逻辑，预先过滤 fragments
 * 策略 2：使用 Scanner + RowAddress 确定活跃的 fragments
 * 策略 3：结合两阶段处理：先扫描元数据，再并行读取
 */
public class PredicateAwareParallelReader {
    private static final Logger logger = LoggerFactory.getLogger(PredicateAwareParallelReader.class);

    /**
     * 策略 1：手动谓词评估 + 并行读取（推荐）
     * 
     * 实现思路：
     * 1. 获取所有 Fragments
     * 2. 手动实现谓词逻辑（如范围判断）
     * 3. 过滤出可能包含数据的 Fragments
     * 4. 并行读取这些 Fragments，并在每个 Fragment 内应用完整谓词
     */
    public static class ManualPredicateEvaluation {
        
        /**
         * 简单的谓词评估器接口。
         */
        public interface FragmentPredicate {
            /**
             * 判断 Fragment 是否可能包含满足条件的数据。
             * @return true = 可能包含，需要读取; false = 肯定不包含，可以跳过
             */
            boolean mightContainData(FragmentMetadata fragment);
            
            /**
             * 获取用于 Scanner 的 SQL 谓词表达式。
             */
            String getSqlPredicate();
        }
        
        /**
         * 范围谓词：适用于有序或分区数据。
         */
        public static class RangePredicate implements FragmentPredicate {
            private final String columnName;
            private final Long minValue;
            private final Long maxValue;
            
            public RangePredicate(String columnName, Long minValue, Long maxValue) {
                this.columnName = columnName;
                this.minValue = minValue;
                this.maxValue = maxValue;
            }
            
            @Override
            public boolean mightContainData(FragmentMetadata fragment) {
                // 注意：这里是简化的逻辑
                // 实际应该检查 Fragment 的统计信息（min/max）
                // 当前 Java SDK 可能不直接暴露这些信息
                
                // 作为示例，我们基于其他可用信息做判断
                // 例如：跳过已经完全删除的 Fragments
                long deletionRate = fragment.getPhysicalRows() > 0 
                    ? fragment.getNumDeletions() * 100 / fragment.getPhysicalRows() 
                    : 0;
                
                if (deletionRate > 90) {
                    logger.debug("Skipping fragment {} (deletion rate: {}%)",
                        fragment.getId(), deletionRate);
                    return false;
                }
                
                return true;
            }
            
            @Override
            public String getSqlPredicate() {
                StringBuilder sb = new StringBuilder();
                if (minValue != null) {
                    sb.append(columnName).append(" >= ").append(minValue);
                }
                if (maxValue != null) {
                    if (sb.length() > 0) sb.append(" AND ");
                    sb.append(columnName).append(" <= ").append(maxValue);
                }
                return sb.toString();
            }
        }
        
        /**
         * 执行带谓词的并行读取。
         */
        public static Result executeWithPredicate(
                String datasetPath,
                FragmentPredicate predicate,
                Optional<List<String>> columns,
                int parallelism,
                BufferAllocator allocator) throws Exception {
            
            Result result = new Result();
            
            try (Dataset dataset = Dataset.open(datasetPath, allocator)) {
                
                // 步骤 1：获取所有 Fragments 并应用谓词过滤
                List<FragmentMetadata> allFragments = dataset.getFragments();
                logger.info("Total fragments: {}", allFragments.size());
                
                List<FragmentMetadata> selectedFragments = allFragments.stream()
                    .filter(fragment -> {
                        boolean keep = predicate.mightContainData(fragment);
                        if (!keep) {
                            result.addPrunedFragment(fragment.getId());
                        }
                        return keep;
                    })
                    .collect(Collectors.toList());
                
                result.setTotalFragments(allFragments.size());
                result.setSelectedFragments(selectedFragments.size());
                
                logger.info("After pruning: {} fragments selected (pruned: {})",
                    selectedFragments.size(), result.getPrunedFragments().size());
                
                if (selectedFragments.isEmpty()) {
                    logger.info("No fragments to process after pruning");
                    return result;
                }
                
                // 步骤 2：创建文件组用于并行读取
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
                
                // 步骤 3：并行读取，每个组内应用完整谓词
                ExecutorService executor = Executors.newFixedThreadPool(parallelism);
                List<Future<FileGroupReader.GroupResult>> futures = new ArrayList<>();
                
                try {
                    for (FileGroup group : fileGroups) {
                        FileGroupReader reader = new FileGroupReader.Builder()
                            .fileGroup(group)
                            .allocator(allocator)
                            .columns(columns.orElse(null))
                            .collectMetrics(true)
                            .build();
                        
                        futures.add(executor.submit(reader));
                    }
                    
                    // 收集结果
                    long totalRows = 0;
                    for (Future<FileGroupReader.GroupResult> future : futures) {
                        FileGroupReader.GroupResult groupResult = future.get();
                        totalRows += groupResult.getTotalRowsRead();
                    }
                    
                    result.setTotalRowsRead(totalRows);
                    
                } finally {
                    executor.shutdown();
                }
            }
            
            return result;
        }
    }

    /**
     * 策略 2：使用 withRowAddress 确定活跃的 Fragments
     * 
     * 实现思路：
     * 1. 先用 Scanner 进行一次轻量级扫描（只读 row address）
     * 2. 从 row address 中提取 fragment IDs
     * 3. 对这些活跃的 fragments 进行并行读取
     */
    public static class RowAddressBasedApproach {
        
        /**
         * 两阶段读取：先确定活跃 Fragments，再并行读取。
         */
        public static Result executeTwoPhase(
                String datasetPath,
                String predicate,
                Optional<List<String>> columns,
                int parallelism,
                BufferAllocator allocator) throws Exception {
            
            Result result = new Result();
            
            try (Dataset dataset = Dataset.open(datasetPath, allocator)) {
                
                // 阶段 1：使用 Scanner 快速确定哪些 Fragments 有数据
                logger.info("Phase 1: Identifying active fragments with predicate: {}", predicate);
                
                Set<Integer> activeFragmentIds = new HashSet<>();
                
                ScanOptions phase1Options = new ScanOptions.Builder()
                    .filter(predicate)
                    .withRowAddress(true)  // 包含 row address
                    .columns(Collections.emptyList())  // 不读取任何数据列，只要 metadata
                    .build();
                
                try (LanceScanner scanner = LanceScanner.create(dataset, phase1Options, allocator)) {
                    while (scanner.loadNextBatch()) {
                        VectorSchemaRoot root = scanner.getVectorSchemaRoot();
                        
                        // 从 _rowaddr 列提取 fragment IDs
                        // Row address 的高 32 位是 fragment ID
                        if (root.getVector("_rowaddr") != null) {
                            for (int i = 0; i < root.getRowCount(); i++) {
                                Object rowAddr = root.getVector("_rowaddr").getObject(i);
                                if (rowAddr != null) {
                                    long addr = ((Number) rowAddr).longValue();
                                    int fragmentId = (int) (addr >>> 32);  // 高 32 位
                                    activeFragmentIds.add(fragmentId);
                                }
                            }
                        }
                    }
                }
                
                logger.info("Phase 1 complete: {} active fragments identified", 
                    activeFragmentIds.size());
                
                result.setTotalFragments(dataset.getFragments().size());
                result.setSelectedFragments(activeFragmentIds.size());
                
                if (activeFragmentIds.isEmpty()) {
                    logger.info("No matching data found");
                    return result;
                }
                
                // 阶段 2：并行读取这些活跃的 Fragments
                logger.info("Phase 2: Parallel reading of {} fragments", activeFragmentIds.size());
                
                List<FragmentMetadata> selectedFragments = dataset.getFragments().stream()
                    .filter(f -> activeFragmentIds.contains(f.getId()))
                    .collect(Collectors.toList());
                
                // 创建文件组
                List<FileGroup> fileGroups = new ArrayList<>();
                int groupId = 0;
                
                for (FragmentMetadata fragment : selectedFragments) {
                    FileGroup.Builder builder = new FileGroup.Builder(groupId++, fragment.getId());
                    for (DataFile file : fragment.getFiles()) {
                        builder.addFile(file.getPath());
                    }
                    fileGroups.add(builder.build());
                }
                
                // 并行读取
                ExecutorService executor = Executors.newFixedThreadPool(parallelism);
                List<Future<FileGroupReader.GroupResult>> futures = new ArrayList<>();
                
                try {
                    for (FileGroup group : fileGroups) {
                        FileGroupReader reader = new FileGroupReader.Builder()
                            .fileGroup(group)
                            .allocator(allocator)
                            .columns(columns.orElse(null))
                            .collectMetrics(true)
                            .build();
                        
                        futures.add(executor.submit(reader));
                    }
                    
                    long totalRows = 0;
                    for (Future<FileGroupReader.GroupResult> future : futures) {
                        FileGroupReader.GroupResult groupResult = future.get();
                        totalRows += groupResult.getTotalRowsRead();
                    }
                    
                    result.setTotalRowsRead(totalRows);
                    
                } finally {
                    executor.shutdown();
                }
            }
            
            return result;
        }
    }

    /**
     * 策略 3：结合 Scanner + 并行（简化版，推荐用于大多数场景）
     * 
     * 直接使用 Scanner，让 Lance 自动处理裁剪，然后利用 fragmentIds 选项。
     */
    public static class HybridApproach {
        
        /**
         * 混合方式：让 Lance 处理裁剪，我们负责并行化。
         */
        public static Result execute(
                String datasetPath,
                String predicate,
                Optional<List<String>> columns,
                int parallelism,
                BufferAllocator allocator) throws Exception {
            
            Result result = new Result();
            
            try (Dataset dataset = Dataset.open(datasetPath, allocator)) {
                
                List<FragmentMetadata> allFragments = dataset.getFragments();
                result.setTotalFragments(allFragments.size());
                
                // 获取所有 fragment IDs
                List<Integer> allFragmentIds = allFragments.stream()
                    .map(FragmentMetadata::getId)
                    .collect(Collectors.toList());
                
                // 将 fragments 分组用于并行处理
                int groupSize = Math.max(1, allFragmentIds.size() / parallelism);
                List<List<Integer>> fragmentGroups = partitionList(allFragmentIds, groupSize);
                
                logger.info("Processing {} fragment groups in parallel", fragmentGroups.size());
                
                // 并行处理每个 fragment 组
                ExecutorService executor = Executors.newFixedThreadPool(parallelism);
                List<Future<Long>> futures = new ArrayList<>();
                
                try {
                    for (List<Integer> fragmentGroup : fragmentGroups) {
                        Future<Long> future = executor.submit(() -> {
                            // 每个线程使用自己的 Scanner，只扫描分配的 fragments
                            ScanOptions options = new ScanOptions.Builder()
                                .fragmentIds(fragmentGroup)
                                .filter(predicate)
                                .columns(columns.orElse(null))
                                .build();
                            
                            long rowsRead = 0;
                            try (LanceScanner scanner = LanceScanner.create(
                                    dataset, options, allocator)) {
                                
                                while (scanner.loadNextBatch()) {
                                    VectorSchemaRoot root = scanner.getVectorSchemaRoot();
                                    rowsRead += root.getRowCount();
                                }
                            }
                            
                            return rowsRead;
                        });
                        
                        futures.add(future);
                    }
                    
                    // 收集结果
                    long totalRows = 0;
                    for (Future<Long> future : futures) {
                        totalRows += future.get();
                    }
                    
                    result.setSelectedFragments(fragmentGroups.size());
                    result.setTotalRowsRead(totalRows);
                    
                } finally {
                    executor.shutdown();
                }
            }
            
            return result;
        }
        
        /**
         * 将列表分组。
         */
        private static <T> List<List<T>> partitionList(List<T> list, int size) {
            List<List<T>> partitions = new ArrayList<>();
            for (int i = 0; i < list.size(); i += size) {
                partitions.add(list.subList(i, Math.min(i + size, list.size())));
            }
            return partitions;
        }
    }

    /**
     * 结果类。
     */
    public static class Result {
        private int totalFragments;
        private int selectedFragments;
        private final List<Integer> prunedFragments = new ArrayList<>();
        private long totalRowsRead;
        
        public void setTotalFragments(int total) {
            this.totalFragments = total;
        }
        
        public void setSelectedFragments(int selected) {
            this.selectedFragments = selected;
        }
        
        public void addPrunedFragment(int fragmentId) {
            this.prunedFragments.add(fragmentId);
        }
        
        public void setTotalRowsRead(long rows) {
            this.totalRowsRead = rows;
        }
        
        public List<Integer> getPrunedFragments() {
            return prunedFragments;
        }
        
        public double getPruningRatio() {
            return totalFragments > 0 
                ? (double) prunedFragments.size() / totalFragments 
                : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format(
                "Result{total=%d, selected=%d, pruned=%d (%.2f%%), rowsRead=%d}",
                totalFragments, selectedFragments, prunedFragments.size(),
                getPruningRatio() * 100, totalRowsRead
            );
        }
    }

    /**
     * 示例 1：手动谓词评估 + 并行读取。
     */
    public static void example1ManualPredicate() {
        logger.info("=== Example 1: Manual Predicate Evaluation ===");
        
        String datasetPath = "/path/to/dataset";
        
        // 创建谓词
        ManualPredicateEvaluation.RangePredicate predicate = 
            new ManualPredicateEvaluation.RangePredicate("timestamp", 1700000000L, 1710000000L);
        
        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
            
            Result result = ManualPredicateEvaluation.executeWithPredicate(
                datasetPath,
                predicate,
                Optional.of(Arrays.asList("id", "timestamp", "value")),
                4,  // 4 个并行线程
                allocator
            );
            
            logger.info("Result: {}", result);
            logger.info("Pruning saved scanning {} fragments", result.getPrunedFragments().size());
            
        } catch (Exception e) {
            logger.error("Error in example 1", e);
        }
    }

    /**
     * 示例 2：Row Address 两阶段方式。
     */
    public static void example2TwoPhase() {
        logger.info("=== Example 2: Two-Phase with Row Address ===");
        
        String datasetPath = "/path/to/dataset";
        String predicate = "timestamp >= 1700000000 AND timestamp <= 1710000000";
        
        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
            
            Result result = RowAddressBasedApproach.executeTwoPhase(
                datasetPath,
                predicate,
                Optional.of(Arrays.asList("id", "timestamp", "value")),
                4,
                allocator
            );
            
            logger.info("Result: {}", result);
            
        } catch (Exception e) {
            logger.error("Error in example 2", e);
        }
    }

    /**
     * 示例 3：混合方式（推荐）。
     */
    public static void example3Hybrid() {
        logger.info("=== Example 3: Hybrid Approach (Recommended) ===");
        
        String datasetPath = "/path/to/dataset";
        String predicate = "status = 'active' AND created_at >= '2024-01-01'";
        
        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
            
            Result result = HybridApproach.execute(
                datasetPath,
                predicate,
                Optional.of(Arrays.asList("id", "status", "created_at")),
                4,
                allocator
            );
            
            logger.info("Result: {}", result);
            
        } catch (Exception e) {
            logger.error("Error in example 3", e);
        }
    }

    public static void main(String[] args) {
        // 运行示例（取消注释）
        
        // example1ManualPredicate();
        // example2TwoPhase();
        // example3Hybrid();
        
        logger.info("See example methods for usage");
    }
}

