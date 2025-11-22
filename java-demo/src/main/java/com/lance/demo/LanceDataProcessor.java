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

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Example processor for Lance data that demonstrates various operations
 * on Arrow record batches.
 */
public class LanceDataProcessor {
    private static final Logger logger = LoggerFactory.getLogger(LanceDataProcessor.class);

    /**
     * Result of processing a batch of data.
     */
    public static class ProcessResult {
        private long rowsProcessed;
        private long bytesProcessed;
        private final Map<String, Object> metrics;

        public ProcessResult() {
            this.metrics = new HashMap<>();
            this.rowsProcessed = 0;
            this.bytesProcessed = 0;
        }

        public void addRows(long count) {
            this.rowsProcessed += count;
        }

        public void addBytes(long count) {
            this.bytesProcessed += count;
        }

        public void addMetric(String key, Object value) {
            this.metrics.put(key, value);
        }

        public long getRowsProcessed() {
            return rowsProcessed;
        }

        public long getBytesProcessed() {
            return bytesProcessed;
        }

        public Map<String, Object> getMetrics() {
            return metrics;
        }

        @Override
        public String toString() {
            return String.format(
                "ProcessResult{rows=%d, bytes=%d, metrics=%s}",
                rowsProcessed, bytesProcessed, metrics
            );
        }
    }

    /**
     * Process a VectorSchemaRoot and collect statistics.
     *
     * @param root The Arrow VectorSchemaRoot containing data
     * @return ProcessResult with statistics
     */
    public static ProcessResult processData(VectorSchemaRoot root) {
        ProcessResult result = new ProcessResult();
        int rowCount = root.getRowCount();
        result.addRows(rowCount);

        logger.debug("Processing batch with {} rows", rowCount);

        // Estimate bytes processed
        long estimatedBytes = 0;
        for (FieldVector vector : root.getFieldVectors()) {
            estimatedBytes += estimateVectorSize(vector);
        }
        result.addBytes(estimatedBytes);

        // Collect column-specific metrics
        for (Field field : root.getSchema().getFields()) {
            String columnName = field.getName();
            FieldVector vector = root.getVector(columnName);

            if (vector == null) {
                continue;
            }

            // Process based on vector type
            if (vector instanceof IntVector) {
                processIntVector((IntVector) vector, columnName, result);
            } else if (vector instanceof BigIntVector) {
                processBigIntVector((BigIntVector) vector, columnName, result);
            } else if (vector instanceof Float4Vector) {
                processFloat4Vector((Float4Vector) vector, columnName, result);
            } else if (vector instanceof VarCharVector) {
                processVarCharVector((VarCharVector) vector, columnName, result);
            }
        }

        return result;
    }

    /**
     * Process an integer vector and extract statistics.
     */
    private static void processIntVector(IntVector vector, String columnName, ProcessResult result) {
        int count = vector.getValueCount();
        long sum = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int nullCount = 0;

        for (int i = 0; i < count; i++) {
            if (vector.isNull(i)) {
                nullCount++;
            } else {
                int value = vector.get(i);
                sum += value;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }

        if (count > nullCount) {
            result.addMetric(columnName + "_sum", sum);
            result.addMetric(columnName + "_min", min);
            result.addMetric(columnName + "_max", max);
            result.addMetric(columnName + "_avg", sum / (double) (count - nullCount));
        }
        result.addMetric(columnName + "_nulls", nullCount);
    }

    /**
     * Process a big integer vector and extract statistics.
     */
    private static void processBigIntVector(BigIntVector vector, String columnName, ProcessResult result) {
        int count = vector.getValueCount();
        long sum = 0;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        int nullCount = 0;

        for (int i = 0; i < count; i++) {
            if (vector.isNull(i)) {
                nullCount++;
            } else {
                long value = vector.get(i);
                sum += value;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }

        if (count > nullCount) {
            result.addMetric(columnName + "_sum", sum);
            result.addMetric(columnName + "_min", min);
            result.addMetric(columnName + "_max", max);
            result.addMetric(columnName + "_avg", sum / (double) (count - nullCount));
        }
        result.addMetric(columnName + "_nulls", nullCount);
    }

    /**
     * Process a float vector and extract statistics.
     */
    private static void processFloat4Vector(Float4Vector vector, String columnName, ProcessResult result) {
        int count = vector.getValueCount();
        double sum = 0;
        float min = Float.MAX_VALUE;
        float max = Float.MIN_VALUE;
        int nullCount = 0;

        for (int i = 0; i < count; i++) {
            if (vector.isNull(i)) {
                nullCount++;
            } else {
                float value = vector.get(i);
                sum += value;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }

        if (count > nullCount) {
            result.addMetric(columnName + "_sum", sum);
            result.addMetric(columnName + "_min", min);
            result.addMetric(columnName + "_max", max);
            result.addMetric(columnName + "_avg", sum / (count - nullCount));
        }
        result.addMetric(columnName + "_nulls", nullCount);
    }

    /**
     * Process a varchar vector and extract statistics.
     */
    private static void processVarCharVector(VarCharVector vector, String columnName, ProcessResult result) {
        int count = vector.getValueCount();
        int nullCount = 0;
        long totalLength = 0;
        int maxLength = 0;

        for (int i = 0; i < count; i++) {
            if (vector.isNull(i)) {
                nullCount++;
            } else {
                byte[] value = vector.get(i);
                int length = value != null ? value.length : 0;
                totalLength += length;
                maxLength = Math.max(maxLength, length);
            }
        }

        result.addMetric(columnName + "_nulls", nullCount);
        if (count > nullCount) {
            result.addMetric(columnName + "_avg_length", totalLength / (double) (count - nullCount));
            result.addMetric(columnName + "_max_length", maxLength);
        }
    }

    /**
     * Estimate the size of a vector in bytes.
     */
    private static long estimateVectorSize(FieldVector vector) {
        // This is a rough estimate
        // In production, you might want to use vector.getBufferSize() or similar
        long valueCount = vector.getValueCount();
        long bytesPerValue;

        if (vector instanceof IntVector) {
            bytesPerValue = 4;
        } else if (vector instanceof BigIntVector) {
            bytesPerValue = 8;
        } else if (vector instanceof Float4Vector) {
            bytesPerValue = 4;
        } else if (vector instanceof VarCharVector) {
            // Average string size assumption
            bytesPerValue = 20;
        } else {
            // Default estimate
            bytesPerValue = 8;
        }

        return valueCount * bytesPerValue;
    }

    /**
     * Example: Extract specific column values as array.
     * 
     * @param root The VectorSchemaRoot
     * @param columnName The column to extract
     * @return Array of values (or null if column not found)
     */
    public static Object[] extractColumnValues(VectorSchemaRoot root, String columnName) {
        FieldVector vector = root.getVector(columnName);
        if (vector == null) {
            logger.warn("Column {} not found", columnName);
            return null;
        }

        int count = vector.getValueCount();
        Object[] values = new Object[count];

        for (int i = 0; i < count; i++) {
            if (!vector.isNull(i)) {
                values[i] = vector.getObject(i);
            }
        }

        return values;
    }

    /**
     * Example: Print sample rows from the batch.
     *
     * @param root The VectorSchemaRoot
     * @param maxRows Maximum number of rows to print
     */
    public static void printSampleRows(VectorSchemaRoot root, int maxRows) {
        int rowCount = Math.min(root.getRowCount(), maxRows);
        
        logger.info("Sample data (showing {} rows):", rowCount);
        logger.info("Schema: {}", root.getSchema());

        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            StringBuilder row = new StringBuilder("Row " + rowIndex + ": ");
            for (Field field : root.getSchema().getFields()) {
                FieldVector vector = root.getVector(field.getName());
                Object value = vector.isNull(rowIndex) ? "NULL" : vector.getObject(rowIndex);
                row.append(field.getName()).append("=").append(value).append(" ");
            }
            logger.info(row.toString());
        }
    }
}

