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

import com.lancedb.lance.file.LanceFileReader;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Worker thread that reads data from a group of Lance files.
 * Implements Callable to support parallel execution via ExecutorService.
 */
public class FileGroupReader implements Callable<FileGroupReader.GroupResult> {
    private static final Logger logger = LoggerFactory.getLogger(FileGroupReader.class);

    private final FileGroup fileGroup;
    private final Optional<List<String>> columnsToRead;
    private final boolean collectMetrics;
    private final BufferAllocator parentAllocator;

    /**
     * Result of reading a file group.
     */
    public static class GroupResult {
        private final int groupId;
        private final int fragmentId;
        private long totalRowsRead;
        private long totalBytesProcessed;
        private final List<LanceDataProcessor.ProcessResult> batchResults;
        private long processingTimeMs;
        private int filesProcessed;

        public GroupResult(int groupId, int fragmentId) {
            this.groupId = groupId;
            this.fragmentId = fragmentId;
            this.totalRowsRead = 0;
            this.totalBytesProcessed = 0;
            this.batchResults = new ArrayList<>();
            this.processingTimeMs = 0;
            this.filesProcessed = 0;
        }

        public void addBatchResult(LanceDataProcessor.ProcessResult result) {
            this.batchResults.add(result);
            this.totalRowsRead += result.getRowsProcessed();
            this.totalBytesProcessed += result.getBytesProcessed();
        }

        public void setProcessingTimeMs(long timeMs) {
            this.processingTimeMs = timeMs;
        }

        public void incrementFilesProcessed() {
            this.filesProcessed++;
        }

        public int getGroupId() {
            return groupId;
        }

        public int getFragmentId() {
            return fragmentId;
        }

        public long getTotalRowsRead() {
            return totalRowsRead;
        }

        public long getTotalBytesProcessed() {
            return totalBytesProcessed;
        }

        public List<LanceDataProcessor.ProcessResult> getBatchResults() {
            return batchResults;
        }

        public long getProcessingTimeMs() {
            return processingTimeMs;
        }

        public int getFilesProcessed() {
            return filesProcessed;
        }

        @Override
        public String toString() {
            return String.format(
                "GroupResult{groupId=%d, fragmentId=%d, files=%d, rows=%d, bytes=%d, timeMs=%d}",
                groupId, fragmentId, filesProcessed, totalRowsRead, totalBytesProcessed, processingTimeMs
            );
        }
    }

    /**
     * Constructor for FileGroupReader.
     *
     * @param fileGroup The group of files to read
     * @param parentAllocator Parent allocator for creating child allocators
     * @param columnsToRead Optional list of columns to project (null = all columns)
     * @param collectMetrics Whether to collect detailed metrics
     */
    public FileGroupReader(
            FileGroup fileGroup,
            BufferAllocator parentAllocator,
            Optional<List<String>> columnsToRead,
            boolean collectMetrics) {
        this.fileGroup = fileGroup;
        this.parentAllocator = parentAllocator;
        this.columnsToRead = columnsToRead;
        this.collectMetrics = collectMetrics;
    }

    /**
     * Simplified constructor that reads all columns and collects metrics.
     */
    public FileGroupReader(FileGroup fileGroup, BufferAllocator parentAllocator) {
        this(fileGroup, parentAllocator, Optional.empty(), true);
    }

    @Override
    public GroupResult call() throws Exception {
        long startTime = System.currentTimeMillis();
        GroupResult result = new GroupResult(fileGroup.getGroupId(), fileGroup.getFragmentId());

        logger.info("Starting to process {}", fileGroup);

        // Create a child allocator for this thread
        try (BufferAllocator allocator = parentAllocator.newChildAllocator(
                "file-group-" + fileGroup.getGroupId(),
                0,
                parentAllocator.getLimit())) {

            // Process each file in the group
            for (String filePath : fileGroup.getFilePaths()) {
                try {
                    processFile(filePath, allocator, result);
                    result.incrementFilesProcessed();
                } catch (Exception e) {
                    logger.error("Error processing file {}: {}", filePath, e.getMessage(), e);
                    // Continue with other files or rethrow based on requirements
                    throw e;
                }
            }
        }

        long endTime = System.currentTimeMillis();
        result.setProcessingTimeMs(endTime - startTime);

        logger.info("Completed processing {}: {}", fileGroup, result);

        return result;
    }

    /**
     * Process a single Lance file.
     *
     * @param filePath Path to the Lance file
     * @param allocator BufferAllocator for Arrow data
     * @param result GroupResult to accumulate results
     */
    private void processFile(String filePath, BufferAllocator allocator, GroupResult result)
            throws Exception {
        logger.debug("Reading file: {}", filePath);

        // Open the Lance file reader
        try (LanceFileReader fileReader = LanceFileReader.open(filePath, allocator)) {
            
            // Get file metadata
            Schema schema = fileReader.getSchema();
            logger.debug("File schema: {}", schema);

            // Create a scanner for reading data
            // Note: LanceFileReader.newScan() might need additional parameters
            // depending on the actual API. Adjust as needed.
            try (ArrowReader reader = createReader(fileReader, allocator)) {
                
                // Read batches
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    
                    if (collectMetrics) {
                        // Process and collect metrics
                        LanceDataProcessor.ProcessResult batchResult = 
                            LanceDataProcessor.processData(root);
                        result.addBatchResult(batchResult);
                    } else {
                        // Just count rows
                        LanceDataProcessor.ProcessResult batchResult = 
                            new LanceDataProcessor.ProcessResult();
                        batchResult.addRows(root.getRowCount());
                        result.addBatchResult(batchResult);
                    }
                }
            }
        }
    }

    /**
     * Create an ArrowReader from the LanceFileReader.
     * This method demonstrates file-level reading with optional column projection.
     *
     * @param fileReader The Lance file reader
     * @param allocator Buffer allocator
     * @return ArrowReader for streaming data
     */
    private ArrowReader createReader(LanceFileReader fileReader, BufferAllocator allocator) 
            throws Exception {
        // Option 1: Use newScan() if available
        // This is the preferred method for Lance file-level scanning
        if (columnsToRead.isPresent() && !columnsToRead.get().isEmpty()) {
            // With column projection
            return fileReader.newScan(columnsToRead.get());
        } else {
            // Read all columns
            return fileReader.newScan();
        }

        // Option 2: Use take() for specific row ranges (not shown here)
        // List<Long> indices = ...;
        // return fileReader.take(indices, columns);
    }

    /**
     * Builder for creating FileGroupReader instances.
     */
    public static class Builder {
        private FileGroup fileGroup;
        private BufferAllocator allocator;
        private Optional<List<String>> columns = Optional.empty();
        private boolean collectMetrics = true;

        public Builder fileGroup(FileGroup group) {
            this.fileGroup = group;
            return this;
        }

        public Builder allocator(BufferAllocator allocator) {
            this.allocator = allocator;
            return this;
        }

        public Builder columns(List<String> columns) {
            this.columns = Optional.of(columns);
            return this;
        }

        public Builder collectMetrics(boolean collect) {
            this.collectMetrics = collect;
            return this;
        }

        public FileGroupReader build() {
            if (fileGroup == null || allocator == null) {
                throw new IllegalStateException("FileGroup and Allocator are required");
            }
            return new FileGroupReader(fileGroup, allocator, columns, collectMetrics);
        }
    }
}

