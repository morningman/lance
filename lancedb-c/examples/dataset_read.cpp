// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The LanceDB Authors

/// @file dataset_read.cpp
/// @brief Basic example of reading a Lance dataset using the C API

#include "lancedb.h"

#include <arrow/c/bridge.h>
#include <arrow/api.h>
#include <iostream>
#include <memory>

int main(int argc, char* argv[]) {
    // Check command line arguments
    if (argc < 2) {
        std::cerr << "Usage: " << argv[0] << " <dataset_path>" << std::endl;
        std::cerr << "\nExample:" << std::endl;
        std::cerr << "  " << argv[0] << " /path/to/lance/dataset" << std::endl;
        std::cerr << "  " << argv[0] << " s3://bucket/dataset" << std::endl;
        return 1;
    }

    const char* dataset_path = argv[1];
    char* error_msg = nullptr;

    std::cout << "=== Lance Dataset Reader ===" << std::endl;
    std::cout << "Dataset path: " << dataset_path << std::endl << std::endl;

    // Step 1: Open the dataset
    std::cout << "[1] Opening dataset..." << std::endl;
    LanceDataset* dataset = nullptr;
    LanceDBError ret = lance_dataset_open(dataset_path, &dataset, &error_msg);
    
    if (ret != LANCEDB_SUCCESS) {
        std::cerr << "Error opening dataset: " << lancedb_error_to_message(ret) << std::endl;
        if (error_msg) {
            std::cerr << "Details: " << error_msg << std::endl;
            lancedb_free_string(error_msg);
        }
        return 1;
    }
    std::cout << "✓ Dataset opened successfully" << std::endl << std::endl;

    // Step 2: Get dataset metadata
    std::cout << "[2] Getting dataset metadata..." << std::endl;
    
    // Get URI
    char* uri = nullptr;
    if (lance_dataset_uri(dataset, &uri, &error_msg) == LANCEDB_SUCCESS) {
        std::cout << "URI: " << uri << std::endl;
        lancedb_free_string(uri);
    }
    
    // Get version
    int64_t version = 0;
    if (lance_dataset_version(dataset, &version) == LANCEDB_SUCCESS) {
        std::cout << "Version: " << version << std::endl;
    }
    
    // Get row count
    int64_t row_count = 0;
    if (lance_dataset_count_rows(dataset, &row_count, &error_msg) == LANCEDB_SUCCESS) {
        std::cout << "Total rows: " << row_count << std::endl;
    } else if (error_msg) {
        std::cerr << "Warning: Could not get row count: " << error_msg << std::endl;
        lancedb_free_string(error_msg);
        error_msg = nullptr;
    }
    std::cout << std::endl;

    // Step 3: Get schema
    std::cout << "[3] Getting schema..." << std::endl;
    FFI_ArrowSchema* c_schema = nullptr;
    ret = lance_dataset_schema(dataset, &c_schema, &error_msg);
    
    if (ret != LANCEDB_SUCCESS) {
        std::cerr << "Error getting schema: " << lancedb_error_to_message(ret) << std::endl;
        if (error_msg) {
            std::cerr << "Details: " << error_msg << std::endl;
            lancedb_free_string(error_msg);
        }
        lance_dataset_free(dataset);
        return 1;
    }
    
    // Import schema using Arrow C++
    auto schema_result = arrow::ImportSchema(c_schema);
    if (!schema_result.ok()) {
        std::cerr << "Error importing schema: " << schema_result.status().message() << std::endl;
        lance_dataset_free(dataset);
        return 1;
    }
    
    auto schema = schema_result.ValueOrDie();
    std::cout << "Schema:" << std::endl;
    std::cout << schema->ToString() << std::endl << std::endl;

    // Step 4: Get fragments
    std::cout << "[4] Getting fragments..." << std::endl;
    LanceFragment* fragments = nullptr;
    size_t fragment_count = 0;
    ret = lance_dataset_get_fragments(dataset, &fragments, &fragment_count, &error_msg);
    
    if (ret == LANCEDB_SUCCESS) {
        std::cout << "Fragment count: " << fragment_count << std::endl;
        for (size_t i = 0; i < fragment_count; i++) {
            std::cout << "  Fragment " << fragments[i].id << ": "
                      << fragments[i].physical_rows << " rows, "
                      << fragments[i].num_deletions << " deletions" << std::endl;
        }
        lance_fragments_free(fragments, fragment_count);
    } else if (error_msg) {
        std::cerr << "Warning: Could not get fragments: " << error_msg << std::endl;
        lancedb_free_string(error_msg);
        error_msg = nullptr;
    }
    std::cout << std::endl;

    // Step 5: Create scanner and read data
    std::cout << "[5] Scanning data..." << std::endl;
    
    // Create scanner with optional filter
    LanceScanOptions scan_opts = {
        .filter = nullptr,  // Set to "column > value" for filtering
        .columns = nullptr,
        .columns_count = 0,
        .fragment_ids = nullptr,
        .fragment_ids_count = 0,
        .limit = 10,  // Read only 10 rows for demo
        .offset = 0,
        .batch_size = -1
    };
    
    LanceScanner* scanner = nullptr;
    ret = lance_dataset_create_scanner(dataset, &scan_opts, &scanner, &error_msg);
    
    if (ret != LANCEDB_SUCCESS) {
        std::cerr << "Error creating scanner: " << lancedb_error_to_message(ret) << std::endl;
        if (error_msg) {
            std::cerr << "Details: " << error_msg << std::endl;
            lancedb_free_string(error_msg);
        }
        lance_dataset_free(dataset);
        return 1;
    }
    std::cout << "✓ Scanner created" << std::endl;
    
    // Read batches
    int batch_num = 0;
    int64_t total_rows_read = 0;
    
    while (true) {
        bool has_next = false;
        ret = lance_scanner_load_next_batch(scanner, &has_next, &error_msg);
        
        if (ret != LANCEDB_SUCCESS) {
            std::cerr << "Error loading batch: " << lancedb_error_to_message(ret) << std::endl;
            if (error_msg) {
                std::cerr << "Details: " << error_msg << std::endl;
                lancedb_free_string(error_msg);
            }
            break;
        }
        
        if (!has_next) {
            break;  // No more batches
        }
        
        // Get the batch as Arrow C ABI
        FFI_ArrowArray* c_array = nullptr;
        FFI_ArrowSchema* c_batch_schema = nullptr;
        ret = lance_scanner_to_arrow(scanner, &c_array, &c_batch_schema, &error_msg);
        
        if (ret != LANCEDB_SUCCESS) {
            std::cerr << "Error getting batch: " << lancedb_error_to_message(ret) << std::endl;
            if (error_msg) {
                std::cerr << "Details: " << error_msg << std::endl;
                lancedb_free_string(error_msg);
            }
            break;
        }
        
        // Import batch using Arrow C++
        auto batch_result = arrow::ImportRecordBatch(c_array, c_batch_schema);
        if (!batch_result.ok()) {
            std::cerr << "Error importing batch: " << batch_result.status().message() << std::endl;
            break;
        }
        
        auto batch = batch_result.ValueOrDie();
        batch_num++;
        total_rows_read += batch->num_rows();
        
        std::cout << "Batch " << batch_num << ": "
                  << batch->num_rows() << " rows, "
                  << batch->num_columns() << " columns" << std::endl;
        
        // Print first batch content
        if (batch_num == 1) {
            std::cout << "\nFirst batch preview:" << std::endl;
            std::cout << batch->ToString() << std::endl;
        }
    }
    
    std::cout << "\n✓ Read " << batch_num << " batches, " 
              << total_rows_read << " total rows" << std::endl << std::endl;
    
    // Cleanup
    lance_scanner_free(scanner);
    lance_dataset_free(dataset);
    
    std::cout << "=== Complete ===" << std::endl;
    
    return 0;
}

