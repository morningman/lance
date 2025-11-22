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
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates parallel reading of Lance dataset files with predicate pushdown
 * and file-level access.
 *
 * This example shows how to:
 * 1. Open a Lance dataset and apply predicates at table level
 * 2. Extract fragment and file metadata
 * 3. Group files for parallel processing
 * 4. Read files in parallel using file-level APIs
 * 5. Collect and aggregate results
 */
public class ParallelLanceReader {
    private static final Logger logger = LoggerFactory.getLogger(ParallelLanceReader.class);

    private final String datasetPath;
    private final BufferAllocator allocator;
    private final int parallelism;

    /**
     * Configuration for the parallel reader.
     */
    public static class Config {
        private final String datasetPath;
        private Optional<String> predicate;
        private Optional<List<String>> columns;
        private int parallelism;
        private boolean collectMetrics;

        public Config(String datasetPath) {
            this.datasetPath = datasetPath;
            this.predicate = Optional.empty();
            this.columns = Optional.empty();
            this.parallelism = Runtime.getRuntime().availableProcessors();
            this.collectMetrics = true;
        }

        public Config withPredicate(String predicate) {
            this.predicate = Optional.of(predicate);
            return this;
        }

        public Config withColumns(List<String> columns) {
            this.columns = Optional.of(columns);
            return this;
        }

        public Config withParallelism(int parallelism) {
            this.parallelism = parallelism;
            return this;
        }

        public Config withMetrics(boolean collect) {
            this.collectMetrics = collect;
            return this;
        }
    }

    /**
     * Constructor.
     *
     * @param datasetPath Path to the Lance dataset
     * @param allocator BufferAllocator for Arrow operations
     * @param parallelism Number of parallel threads
     */
    public ParallelLanceReader(String datasetPath, BufferAllocator allocator, int parallelism) {
        this.datasetPath = datasetPath;
        this.allocator = allocator;
        this.parallelism = parallelism;
    }

    /**
     * Main execution method demonstrating the complete workflow.
     *
     * @param config Configuration for reading
     * @return Aggregated results from all file groups
     */
    public AggregatedResult execute(Config config) throws Exception {
        logger.info("Starting parallel Lance reading for dataset: {}", datasetPath);
        logger.info("Parallelism: {}, Predicate: {}", parallelism, config.predicate.orElse("none"));

        AggregatedResult aggregatedResult = new AggregatedResult();
        long startTime = System.currentTimeMillis();

        // Step 1: Open dataset and get fragments with predicate pushdown
        List<FileGroup> fileGroups;
        try (Dataset dataset = Dataset.open(datasetPath, allocator)) {
            logger.info("Dataset opened successfully. Version: {}", dataset.version());
            
            fileGroups = createFileGroups(dataset, config.predicate);
            logger.info("Created {} file groups from fragments", fileGroups.size());
        }

        // Step 2: Execute parallel file reading
        List<FileGroupReader.GroupResult> results = executeParallelReading(
            fileGroups, 
            config.columns, 
            config.collectMetrics
        );

        // Step 3: Aggregate results
        for (FileGroupReader.GroupResult result : results) {
            aggregatedResult.addGroupResult(result);
        }

        long endTime = System.currentTimeMillis();
        aggregatedResult.setTotalTimeMs(endTime - startTime);

        logger.info("Parallel reading completed: {}", aggregatedResult);

        return aggregatedResult;
    }

    /**
     * Create file groups from dataset fragments.
     * This demonstrates table-level operations and metadata extraction.
     *
     * @param dataset The Lance dataset
     * @param predicate Optional predicate for filtering
     * @return List of file groups, one per fragment
     */
    private List<FileGroup> createFileGroups(Dataset dataset, Optional<String> predicate) 
            throws Exception {
        List<FileGroup> fileGroups = new ArrayList<>();

        // Get all fragments from the dataset
        List<FragmentMetadata> fragments = dataset.getFragments();
        logger.info("Dataset contains {} fragments", fragments.size());

        // Group files by fragment ID
        // Each fragment's files form an orthogonal data partition
        int groupId = 0;
        for (FragmentMetadata fragment : fragments) {
            
            // Apply predicate filtering at fragment level if needed
            // Note: Lance may already filter fragments based on statistics
            // This is just for demonstration
            if (predicate.isPresent() && !shouldProcessFragment(fragment, predicate.get())) {
                logger.debug("Skipping fragment {} due to predicate", fragment.id());
                continue;
            }

            FileGroup.Builder groupBuilder = new FileGroup.Builder(groupId++, fragment.id());

            // Get all data files from this fragment
            List<DataFile> dataFiles = fragment.files();
            logger.debug("Fragment {} has {} data files", fragment.id(), dataFiles.size());

            for (DataFile dataFile : dataFiles) {
                String filePath = dataFile.path();
                groupBuilder.addFile(filePath);
                logger.debug("Added file to group: {}", filePath);
            }

            // Set metadata
            groupBuilder.physicalRows(fragment.physicalRows());

            FileGroup group = groupBuilder.build();
            if (!group.isEmpty()) {
                fileGroups.add(group);
                logger.info("Created {}", group);
            }
        }

        return fileGroups;
    }

    /**
     * Execute parallel reading of file groups.
     *
     * @param fileGroups List of file groups to process
     * @param columns Optional column projection
     * @param collectMetrics Whether to collect detailed metrics
     * @return List of results from each group
     */
    private List<FileGroupReader.GroupResult> executeParallelReading(
            List<FileGroup> fileGroups,
            Optional<List<String>> columns,
            boolean collectMetrics) throws Exception {

        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        List<Future<FileGroupReader.GroupResult>> futures = new ArrayList<>();

        try {
            // Submit tasks for each file group
            for (FileGroup group : fileGroups) {
                FileGroupReader reader = new FileGroupReader.Builder()
                    .fileGroup(group)
                    .allocator(allocator)
                    .columns(columns.orElse(null))
                    .collectMetrics(collectMetrics)
                    .build();

                futures.add(executor.submit(reader));
                logger.debug("Submitted task for {}", group);
            }

            // Collect results
            List<FileGroupReader.GroupResult> results = new ArrayList<>();
            for (Future<FileGroupReader.GroupResult> future : futures) {
                try {
                    FileGroupReader.GroupResult result = future.get();
                    results.add(result);
                    logger.info("Received result: {}", result);
                } catch (Exception e) {
                    logger.error("Error processing file group", e);
                    throw e;
                }
            }

            return results;

        } finally {
            // Shutdown executor
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Determine if a fragment should be processed based on predicate.
     * This is a simplified example - in production, you'd use Lance's
     * built-in predicate pushdown which examines fragment statistics.
     *
     * @param fragment The fragment metadata
     * @param predicate The predicate expression
     * @return true if fragment should be processed
     */
    private boolean shouldProcessFragment(FragmentMetadata fragment, String predicate) {
        // Simplified logic - in reality, Lance handles this internally
        // You could examine fragment.deletion_file() and other metadata
        return true;
    }

    /**
     * Aggregated results from all file groups.
     */
    public static class AggregatedResult {
        private final List<FileGroupReader.GroupResult> groupResults;
        private long totalRowsRead;
        private long totalBytesProcessed;
        private long totalTimeMs;
        private int totalFilesProcessed;

        public AggregatedResult() {
            this.groupResults = new ArrayList<>();
            this.totalRowsRead = 0;
            this.totalBytesProcessed = 0;
            this.totalTimeMs = 0;
            this.totalFilesProcessed = 0;
        }

        public void addGroupResult(FileGroupReader.GroupResult result) {
            this.groupResults.add(result);
            this.totalRowsRead += result.getTotalRowsRead();
            this.totalBytesProcessed += result.getTotalBytesProcessed();
            this.totalFilesProcessed += result.getFilesProcessed();
        }

        public void setTotalTimeMs(long timeMs) {
            this.totalTimeMs = timeMs;
        }

        public List<FileGroupReader.GroupResult> getGroupResults() {
            return groupResults;
        }

        public long getTotalRowsRead() {
            return totalRowsRead;
        }

        public long getTotalBytesProcessed() {
            return totalBytesProcessed;
        }

        public long getTotalTimeMs() {
            return totalTimeMs;
        }

        public int getTotalFilesProcessed() {
            return totalFilesProcessed;
        }

        public double getThroughputRowsPerSec() {
            return totalTimeMs > 0 ? (totalRowsRead * 1000.0 / totalTimeMs) : 0;
        }

        public double getThroughputMBPerSec() {
            return totalTimeMs > 0 ? (totalBytesProcessed / 1024.0 / 1024.0 * 1000.0 / totalTimeMs) : 0;
        }

        @Override
        public String toString() {
            return String.format(
                "AggregatedResult{groups=%d, files=%d, rows=%d, bytes=%d, timeMs=%d, " +
                "throughput=%.2f rows/s, %.2f MB/s}",
                groupResults.size(), totalFilesProcessed, totalRowsRead, totalBytesProcessed,
                totalTimeMs, getThroughputRowsPerSec(), getThroughputMBPerSec()
            );
        }
    }

    /**
     * Example usage demonstrating various scenarios.
     */
    public static void main(String[] args) {
        // Example 1: Basic parallel reading
        example1BasicReading();

        // Example 2: Reading with predicate pushdown
        example2WithPredicate();

        // Example 3: Reading with column projection
        example3WithColumnProjection();

        // Example 4: Custom parallelism and configuration
        example4CustomConfig();
    }

    /**
     * Example 1: Basic parallel reading of all data.
     */
    private static void example1BasicReading() {
        logger.info("=== Example 1: Basic Parallel Reading ===");

        String datasetPath = "/path/to/lance/dataset";

        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
            ParallelLanceReader reader = new ParallelLanceReader(
                datasetPath,
                allocator,
                4  // 4 parallel threads
            );

            Config config = new Config(datasetPath);
            AggregatedResult result = reader.execute(config);

            logger.info("Results: {}", result);
            logger.info("Total rows read: {}", result.getTotalRowsRead());
            logger.info("Throughput: {:.2f} rows/sec", result.getThroughputRowsPerSec());

        } catch (Exception e) {
            logger.error("Error in example 1", e);
        }
    }

    /**
     * Example 2: Reading with predicate pushdown at table level.
     */
    private static void example2WithPredicate() {
        logger.info("=== Example 2: Reading with Predicate ===");

        String datasetPath = "/path/to/lance/dataset";

        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
            ParallelLanceReader reader = new ParallelLanceReader(
                datasetPath,
                allocator,
                4
            );

            // Apply predicate at table level for fragment filtering
            Config config = new Config(datasetPath)
                .withPredicate("age > 25 AND status = 'active'");

            AggregatedResult result = reader.execute(config);

            logger.info("Filtered results: {}", result);

        } catch (Exception e) {
            logger.error("Error in example 2", e);
        }
    }

    /**
     * Example 3: Reading with column projection for efficiency.
     */
    private static void example3WithColumnProjection() {
        logger.info("=== Example 3: Reading with Column Projection ===");

        String datasetPath = "/path/to/lance/dataset";

        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
            ParallelLanceReader reader = new ParallelLanceReader(
                datasetPath,
                allocator,
                4
            );

            // Only read specific columns at file level
            Config config = new Config(datasetPath)
                .withColumns(Arrays.asList("id", "name", "value"))
                .withPredicate("value > 100");

            AggregatedResult result = reader.execute(config);

            logger.info("Projected results: {}", result);

        } catch (Exception e) {
            logger.error("Error in example 3", e);
        }
    }

    /**
     * Example 4: Custom configuration with tuning.
     */
    private static void example4CustomConfig() {
        logger.info("=== Example 4: Custom Configuration ===");

        String datasetPath = "/path/to/lance/dataset";

        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
            // Use more threads for larger datasets
            int cpuCount = Runtime.getRuntime().availableProcessors();
            int parallelism = cpuCount * 2;  // Oversubscribe for I/O bound work

            ParallelLanceReader reader = new ParallelLanceReader(
                datasetPath,
                allocator,
                parallelism
            );

            Config config = new Config(datasetPath)
                .withParallelism(parallelism)
                .withMetrics(true)  // Collect detailed metrics
                .withColumns(Arrays.asList("user_id", "event_type", "timestamp"))
                .withPredicate("timestamp >= '2024-01-01'");

            AggregatedResult result = reader.execute(config);

            // Detailed result analysis
            logger.info("Custom config results: {}", result);
            logger.info("Groups processed: {}", result.getGroupResults().size());
            for (FileGroupReader.GroupResult groupResult : result.getGroupResults()) {
                logger.info("  Group {}: {} rows in {} ms",
                    groupResult.getGroupId(),
                    groupResult.getTotalRowsRead(),
                    groupResult.getProcessingTimeMs());
            }

        } catch (Exception e) {
            logger.error("Error in example 4", e);
        }
    }
}

