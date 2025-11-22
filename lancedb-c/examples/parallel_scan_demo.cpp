/*
 * SPDX-License-Identifier: Apache-2.0
 * SPDX-FileCopyrightText: Copyright The LanceDB Authors
 * 
 * Demonstration of parallel/split scanning strategies for LanceDB tables
 * 
 * This example shows multiple approaches to scan a table in parallel using splits:
 * 1. Using LIMIT + OFFSET for logical splits
 * 2. Using WHERE conditions for range-based splits
 * 3. Using row count to determine optimal split sizes
 */

#include <iostream>
#include <memory>
#include <vector>
#include <thread>
#include <functional>
#include <arrow/api.h>
#include <arrow/c/bridge.h>
#include "lancedb.h"

/**
 * Split descriptor for parallel scanning
 */
struct ScanSplit {
    size_t split_id;
    size_t offset;
    size_t limit;
    std::string filter;  // Optional WHERE clause
    
    ScanSplit(size_t id, size_t off, size_t lim, const std::string& flt = "")
        : split_id(id), offset(off), limit(lim), filter(flt) {}
};

/**
 * Strategy 1: Split table using LIMIT + OFFSET
 * 
 * This creates logical splits by dividing the total row count into chunks.
 * Each split scans a portion of the table using offset and limit.
 */
class LimitOffsetSplitScanner {
private:
    LanceDBConnection* connection_;
    LanceDBTable* table_;
    std::string table_name_;
    size_t rows_per_split_;
    
public:
    LimitOffsetSplitScanner(LanceDBConnection* conn, const std::string& table_name, size_t rows_per_split)
        : connection_(conn), table_(nullptr), table_name_(table_name), rows_per_split_(rows_per_split) {}
    
    ~LimitOffsetSplitScanner() {
        if (table_) {
            lancedb_table_free(table_);
        }
    }
    
    /**
     * Open the table
     */
    bool openTable() {
        table_ = lancedb_connection_open_table(connection_, table_name_.c_str());
        if (!table_) {
            std::cerr << "Failed to open table: " << table_name_ << std::endl;
            return false;
        }
        return true;
    }
    
    /**
     * Calculate splits based on total row count
     */
    std::vector<ScanSplit> calculateSplits() {
        std::vector<ScanSplit> splits;
        
        if (!table_) {
            std::cerr << "Table not opened" << std::endl;
            return splits;
        }
        
        // Get total row count
        unsigned long long total_rows = lancedb_table_count_rows(table_);
        std::cout << "Total rows in table: " << total_rows << std::endl;
        
        if (total_rows == 0) {
            return splits;
        }
        
        // Calculate number of splits
        size_t num_splits = (total_rows + rows_per_split_ - 1) / rows_per_split_;
        std::cout << "Creating " << num_splits << " splits with ~" << rows_per_split_ << " rows each" << std::endl;
        
        // Create splits
        for (size_t i = 0; i < num_splits; i++) {
            size_t offset = i * rows_per_split_;
            size_t limit = std::min(rows_per_split_, static_cast<size_t>(total_rows - offset));
            splits.emplace_back(i, offset, limit);
        }
        
        return splits;
    }
    
    /**
     * Scan a single split
     */
    bool scanSplit(const ScanSplit& split, const std::string& where_filter = "") {
        std::cout << "\n--- Scanning Split " << split.split_id << " ---" << std::endl;
        std::cout << "Offset: " << split.offset << ", Limit: " << split.limit << std::endl;
        
        // Create query
        LanceDBQuery* query = lancedb_query_new(table_);
        if (!query) {
            std::cerr << "Failed to create query" << std::endl;
            return false;
        }
        
        char* error_message = nullptr;
        
        // Set LIMIT
        if (lancedb_query_limit(query, split.limit, &error_message) != LANCEDB_SUCCESS) {
            std::cerr << "Failed to set limit" << std::endl;
            if (error_message) {
                std::cerr << ": " << error_message << std::endl;
                lancedb_free_string(error_message);
            }
            lancedb_query_free(query);
            return false;
        }
        
        // Set OFFSET
        if (lancedb_query_offset(query, split.offset, &error_message) != LANCEDB_SUCCESS) {
            std::cerr << "Failed to set offset" << std::endl;
            if (error_message) {
                std::cerr << ": " << error_message << std::endl;
                lancedb_free_string(error_message);
            }
            lancedb_query_free(query);
            return false;
        }
        
        // Set WHERE filter if provided
        if (!where_filter.empty()) {
            if (lancedb_query_where_filter(query, where_filter.c_str(), &error_message) != LANCEDB_SUCCESS) {
                std::cerr << "Failed to set WHERE filter" << std::endl;
                if (error_message) {
                    std::cerr << ": " << error_message << std::endl;
                    lancedb_free_string(error_message);
                }
                lancedb_query_free(query);
                return false;
            }
            std::cout << "WHERE filter: " << where_filter << std::endl;
        }
        
        // Execute query
        LanceDBQueryResult* result = lancedb_query_execute(query);
        if (!result) {
            std::cerr << "Failed to execute query" << std::endl;
            return false;
        }
        
        // Convert to Arrow
        struct ArrowArray** result_arrays = nullptr;
        struct ArrowSchema* result_schema = nullptr;
        size_t count_out = 0;
        
        LanceDBError err = lancedb_query_result_to_arrow(
            result,
            reinterpret_cast<FFI_ArrowArray***>(&result_arrays),
            reinterpret_cast<FFI_ArrowSchema**>(&result_schema),
            &count_out,
            &error_message
        );
        
        if (err != LANCEDB_SUCCESS) {
            std::cerr << "Failed to convert results to Arrow" << std::endl;
            if (error_message) {
                std::cerr << ": " << error_message << std::endl;
                lancedb_free_string(error_message);
            }
            return false;
        }
        
        // Process results
        int total_rows_in_split = 0;
        for (size_t i = 0; i < count_out; i++) {
            auto schema_result = arrow::ImportSchema(result_schema);
            if (schema_result.ok()) {
                auto batch_result = arrow::ImportRecordBatch(result_arrays[i], *schema_result);
                if (batch_result.ok()) {
                    total_rows_in_split += (*batch_result)->num_rows();
                }
            }
        }
        
        std::cout << "Split " << split.split_id << " returned " << total_rows_in_split << " rows" << std::endl;
        
        // Cleanup
        if (result_arrays) {
            lancedb_free_arrow_arrays(reinterpret_cast<FFI_ArrowArray**>(result_arrays), count_out);
        }
        if (result_schema) {
            lancedb_free_arrow_schema(reinterpret_cast<FFI_ArrowSchema*>(result_schema));
        }
        
        return true;
    }
    
    /**
     * Scan all splits sequentially
     */
    void scanAllSplits(const std::string& where_filter = "") {
        auto splits = calculateSplits();
        
        for (const auto& split : splits) {
            scanSplit(split, where_filter);
        }
    }
    
    /**
     * Scan all splits in parallel (simulated)
     * Note: True parallel execution would require thread-safe table access
     */
    void scanAllSplitsParallel(const std::string& where_filter = "") {
        auto splits = calculateSplits();
        
        std::cout << "\n=== Parallel Scan (Simulated) ===" << std::endl;
        std::cout << "Note: True parallel execution requires multiple table connections" << std::endl;
        
        // In real implementation, you would:
        // 1. Create multiple table connections
        // 2. Launch threads with independent table handles
        // 3. Each thread scans its assigned split
        // 4. Aggregate results
        
        for (const auto& split : splits) {
            scanSplit(split, where_filter);
        }
    }
};

/**
 * Strategy 2: Split table using WHERE conditions (Range-based partitioning)
 * 
 * This is more efficient as it leverages indexes and doesn't require offset scanning.
 * Requires a suitable partition key (e.g., timestamp, id, hash).
 */
class RangeBasedSplitScanner {
private:
    LanceDBConnection* connection_;
    LanceDBTable* table_;
    std::string table_name_;
    std::string partition_column_;
    
public:
    RangeBasedSplitScanner(LanceDBConnection* conn, const std::string& table_name, 
                          const std::string& partition_column)
        : connection_(conn), table_(nullptr), table_name_(table_name), 
          partition_column_(partition_column) {}
    
    ~RangeBasedSplitScanner() {
        if (table_) {
            lancedb_table_free(table_);
        }
    }
    
    bool openTable() {
        table_ = lancedb_connection_open_table(connection_, table_name_.c_str());
        if (!table_) {
            std::cerr << "Failed to open table: " << table_name_ << std::endl;
            return false;
        }
        return true;
    }
    
    /**
     * Create range-based splits
     * Example: Split by ID ranges [0-999], [1000-1999], [2000-2999], etc.
     */
    std::vector<ScanSplit> createRangeSplits(int64_t min_value, int64_t max_value, size_t num_splits) {
        std::vector<ScanSplit> splits;
        
        int64_t range_size = (max_value - min_value + num_splits) / num_splits;
        
        for (size_t i = 0; i < num_splits; i++) {
            int64_t range_start = min_value + i * range_size;
            int64_t range_end = (i == num_splits - 1) ? max_value : range_start + range_size - 1;
            
            std::string filter = partition_column_ + " >= " + std::to_string(range_start) + 
                               " AND " + partition_column_ + " <= " + std::to_string(range_end);
            
            splits.emplace_back(i, 0, 0, filter);
        }
        
        return splits;
    }
    
    /**
     * Create hash-based splits
     * Example: Split by hash(id) % num_splits = split_id
     */
    std::vector<ScanSplit> createHashSplits(size_t num_splits) {
        std::vector<ScanSplit> splits;
        
        for (size_t i = 0; i < num_splits; i++) {
            // Note: This requires modulo operator support in LanceDB SQL
            // Alternative: use range-based partitioning with pre-computed hash column
            std::string filter = "hash(" + partition_column_ + ") % " + 
                               std::to_string(num_splits) + " = " + std::to_string(i);
            
            splits.emplace_back(i, 0, 0, filter);
        }
        
        return splits;
    }
    
    /**
     * Scan a split using WHERE condition
     */
    bool scanSplit(const ScanSplit& split) {
        std::cout << "\n--- Scanning Split " << split.split_id << " ---" << std::endl;
        std::cout << "Filter: " << split.filter << std::endl;
        
        // Create query
        LanceDBQuery* query = lancedb_query_new(table_);
        if (!query) {
            std::cerr << "Failed to create query" << std::endl;
            return false;
        }
        
        char* error_message = nullptr;
        
        // Set WHERE filter
        if (lancedb_query_where_filter(query, split.filter.c_str(), &error_message) != LANCEDB_SUCCESS) {
            std::cerr << "Failed to set WHERE filter" << std::endl;
            if (error_message) {
                std::cerr << ": " << error_message << std::endl;
                lancedb_free_string(error_message);
            }
            lancedb_query_free(query);
            return false;
        }
        
        // Execute query
        LanceDBQueryResult* result = lancedb_query_execute(query);
        if (!result) {
            std::cerr << "Failed to execute query" << std::endl;
            return false;
        }
        
        // Convert to Arrow
        struct ArrowArray** result_arrays = nullptr;
        struct ArrowSchema* result_schema = nullptr;
        size_t count_out = 0;
        
        LanceDBError err = lancedb_query_result_to_arrow(
            result,
            reinterpret_cast<FFI_ArrowArray***>(&result_arrays),
            reinterpret_cast<FFI_ArrowSchema**>(&result_schema),
            &count_out,
            &error_message
        );
        
        if (err != LANCEDB_SUCCESS) {
            std::cerr << "Failed to convert results to Arrow" << std::endl;
            if (error_message) {
                std::cerr << ": " << error_message << std::endl;
                lancedb_free_string(error_message);
            }
            return false;
        }
        
        // Process results
        int total_rows = 0;
        for (size_t i = 0; i < count_out; i++) {
            auto schema_result = arrow::ImportSchema(result_schema);
            if (schema_result.ok()) {
                auto batch_result = arrow::ImportRecordBatch(result_arrays[i], *schema_result);
                if (batch_result.ok()) {
                    total_rows += (*batch_result)->num_rows();
                }
            }
        }
        
        std::cout << "Split " << split.split_id << " returned " << total_rows << " rows" << std::endl;
        
        // Cleanup
        if (result_arrays) {
            lancedb_free_arrow_arrays(reinterpret_cast<FFI_ArrowArray**>(result_arrays), count_out);
        }
        if (result_schema) {
            lancedb_free_arrow_schema(reinterpret_cast<FFI_ArrowSchema*>(result_schema));
        }
        
        return true;
    }
};

/**
 * Main demonstration
 */
int main() {
    std::cout << "======================================" << std::endl;
    std::cout << "LanceDB Parallel Scan Demonstration" << std::endl;
    std::cout << "======================================\n" << std::endl;
    
    // Connect to database
    const std::string db_uri = "data/my-lancedb";
    const std::string table_name = "my_table";
    
    LanceDBConnectBuilder* builder = lancedb_connect(db_uri.c_str());
    if (!builder) {
        std::cerr << "Failed to create connection builder" << std::endl;
        return 1;
    }
    
    LanceDBConnection* conn = lancedb_connect_builder_execute(builder);
    if (!conn) {
        std::cerr << "Failed to connect to database" << std::endl;
        lancedb_connect_builder_free(builder);
        return 1;
    }
    
    std::cout << "Connected to database: " << db_uri << std::endl;
    
    // ==========================================
    // Strategy 1: LIMIT + OFFSET Split Scanning
    // ==========================================
    std::cout << "\n\n========== Strategy 1: LIMIT + OFFSET Splits ==========" << std::endl;
    {
        LimitOffsetSplitScanner scanner(conn, table_name, 50);  // 50 rows per split
        
        if (scanner.openTable()) {
            // Sequential scan
            std::cout << "\n--- Sequential Scan ---" << std::endl;
            scanner.scanAllSplits();
            
            // Parallel scan (simulated)
            std::cout << "\n--- Parallel Scan ---" << std::endl;
            scanner.scanAllSplitsParallel();
            
            // With WHERE filter
            std::cout << "\n--- With WHERE Filter ---" << std::endl;
            scanner.scanAllSplits("id < 100");
        }
    }
    
    // ==========================================
    // Strategy 2: Range-Based Split Scanning
    // ==========================================
    std::cout << "\n\n========== Strategy 2: Range-Based Splits ==========" << std::endl;
    {
        RangeBasedSplitScanner scanner(conn, table_name, "id");
        
        if (scanner.openTable()) {
            // Create 4 range-based splits: [0-24], [25-49], [50-74], [75-99]
            auto splits = scanner.createRangeSplits(0, 99, 4);
            
            std::cout << "Created " << splits.size() << " range-based splits" << std::endl;
            
            for (const auto& split : splits) {
                scanner.scanSplit(split);
            }
        }
    }
    
    // ==========================================
    // Recommendations
    // ==========================================
    std::cout << "\n\n========== Recommendations ==========" << std::endl;
    std::cout << "1. For small-medium tables (< 10M rows): Use LIMIT+OFFSET strategy" << std::endl;
    std::cout << "2. For large tables with partition key: Use range-based strategy" << std::endl;
    std::cout << "3. For true parallel execution: Open multiple table connections" << std::endl;
    std::cout << "4. Consider creating indexes on partition columns" << std::endl;
    std::cout << "5. Monitor query performance and adjust split size accordingly" << std::endl;
    
    // Cleanup
    lancedb_connection_free(conn);
    
    std::cout << "\n\nDemo completed!" << std::endl;
    return 0;
}

