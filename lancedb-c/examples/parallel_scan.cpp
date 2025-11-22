// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The LanceDB Authors

/// @file parallel_scan.cpp
/// @brief Example of parallel reading of Lance dataset fragments

#include "lancedb.h"

#include <arrow/c/bridge.h>
#include <arrow/api.h>
#include <iostream>
#include <memory>
#include <thread>
#include <vector>
#include <future>
#include <chrono>
#include <atomic>
#include <mutex>

// Thread-safe cout
std::mutex cout_mutex;

/// Read a specific fragment from the dataset
int64_t read_fragment(const char* dataset_path, int fragment_id, int thread_id) {
    char* error_msg = nullptr;
    int64_t rows_read = 0;
    
    // Each thread opens its own dataset handle
    LanceDataset* dataset = nullptr;
    LanceDBError ret = lance_dataset_open(dataset_path, &dataset, &error_msg);
    
    if (ret != LANCEDB_SUCCESS) {
        std::lock_guard<std::mutex> lock(cout_mutex);
        std::cerr << "[Thread " << thread_id << "] Error opening dataset: " 
                  << lancedb_error_to_message(ret) << std::endl;
        if (error_msg) {
            std::cerr << "Details: " << error_msg << std::endl;
            lancedb_free_string(error_msg);
        }
        return -1;
    }
    
    // Create scanner for this specific fragment
    int fragment_ids[] = { fragment_id };
    LanceScanOptions scan_opts = {
        .filter = nullptr,
        .columns = nullptr,
        .columns_count = 0,
        .fragment_ids = fragment_ids,
        .fragment_ids_count = 1,
        .limit = -1,  // Read all rows
        .offset = 0,
        .batch_size = -1
    };
    
    LanceScanner* scanner = nullptr;
    ret = lance_dataset_create_scanner(dataset, &scan_opts, &scanner, &error_msg);
    
    if (ret != LANCEDB_SUCCESS) {
        std::lock_guard<std::mutex> lock(cout_mutex);
        std::cerr << "[Thread " << thread_id << "] Error creating scanner: " 
                  << lancedb_error_to_message(ret) << std::endl;
        if (error_msg) {
            std::cerr << "Details: " << error_msg << std::endl;
            lancedb_free_string(error_msg);
        }
        lance_dataset_free(dataset);
        return -1;
    }
    
    {
        std::lock_guard<std::mutex> lock(cout_mutex);
        std::cout << "[Thread " << thread_id << "] Reading fragment " << fragment_id << "..." << std::endl;
    }
    
    auto start_time = std::chrono::high_resolution_clock::now();
    int batch_count = 0;
    
    // Read all batches from this fragment
    while (true) {
        bool has_next = false;
        ret = lance_scanner_load_next_batch(scanner, &has_next, &error_msg);
        
        if (ret != LANCEDB_SUCCESS) {
            std::lock_guard<std::mutex> lock(cout_mutex);
            std::cerr << "[Thread " << thread_id << "] Error loading batch: " 
                      << lancedb_error_to_message(ret) << std::endl;
            if (error_msg) {
                std::cerr << "Details: " << error_msg << std::endl;
                lancedb_free_string(error_msg);
            }
            break;
        }
        
        if (!has_next) {
            break;
        }
        
        // Get the batch
        FFI_ArrowArray* c_array = nullptr;
        FFI_ArrowSchema* c_schema = nullptr;
        ret = lance_scanner_to_arrow(scanner, &c_array, &c_schema, &error_msg);
        
        if (ret != LANCEDB_SUCCESS) {
            std::lock_guard<std::mutex> lock(cout_mutex);
            std::cerr << "[Thread " << thread_id << "] Error getting batch: " 
                      << lancedb_error_to_message(ret) << std::endl;
            if (error_msg) {
                std::cerr << "Details: " << error_msg << std::endl;
                lancedb_free_string(error_msg);
            }
            break;
        }
        
        // Import batch
        auto batch_result = arrow::ImportRecordBatch(c_array, c_schema);
        if (!batch_result.ok()) {
            std::lock_guard<std::mutex> lock(cout_mutex);
            std::cerr << "[Thread " << thread_id << "] Error importing batch: " 
                      << batch_result.status().message() << std::endl;
            break;
        }
        
        auto batch = batch_result.ValueOrDie();
        rows_read += batch->num_rows();
        batch_count++;
    }
    
    auto end_time = std::chrono::high_resolution_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time);
    
    {
        std::lock_guard<std::mutex> lock(cout_mutex);
        std::cout << "[Thread " << thread_id << "] Fragment " << fragment_id 
                  << " complete: " << rows_read << " rows, " 
                  << batch_count << " batches, "
                  << duration.count() << " ms" << std::endl;
    }
    
    // Cleanup
    lance_scanner_free(scanner);
    lance_dataset_free(dataset);
    
    return rows_read;
}

int main(int argc, char* argv[]) {
    if (argc < 2) {
        std::cerr << "Usage: " << argv[0] << " <dataset_path> [num_threads]" << std::endl;
        std::cerr << "\nExample:" << std::endl;
        std::cerr << "  " << argv[0] << " /path/to/lance/dataset 4" << std::endl;
        return 1;
    }

    const char* dataset_path = argv[1];
    int max_threads = argc > 2 ? std::atoi(argv[2]) : std::thread::hardware_concurrency();
    char* error_msg = nullptr;

    std::cout << "=== Parallel Lance Scanner ===" << std::endl;
    std::cout << "Dataset: " << dataset_path << std::endl;
    std::cout << "Max threads: " << max_threads << std::endl << std::endl;

    // Step 1: Open dataset to get fragment list
    std::cout << "[1] Opening dataset and getting fragments..." << std::endl;
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

    // Get fragments
    LanceFragment* fragments = nullptr;
    size_t fragment_count = 0;
    ret = lance_dataset_get_fragments(dataset, &fragments, &fragment_count, &error_msg);
    
    if (ret != LANCEDB_SUCCESS) {
        std::cerr << "Error getting fragments: " << lancedb_error_to_message(ret) << std::endl;
        if (error_msg) {
            std::cerr << "Details: " << error_msg << std::endl;
            lancedb_free_string(error_msg);
        }
        lance_dataset_free(dataset);
        return 1;
    }
    
    if (fragment_count == 0) {
        std::cout << "Dataset has no fragments. Nothing to read." << std::endl;
        lance_dataset_free(dataset);
        return 0;
    }
    
    std::cout << "Found " << fragment_count << " fragments:" << std::endl;
    for (size_t i = 0; i < fragment_count; i++) {
        std::cout << "  Fragment " << fragments[i].id << ": "
                  << fragments[i].physical_rows << " rows" << std::endl;
    }
    std::cout << std::endl;

    // Copy fragment IDs for parallel reading
    std::vector<int> fragment_ids;
    for (size_t i = 0; i < fragment_count; i++) {
        fragment_ids.push_back(fragments[i].id);
    }
    
    // Cleanup fragment array (we have the IDs now)
    lance_fragments_free(fragments, fragment_count);
    lance_dataset_free(dataset);  // Close initial dataset handle

    // Step 2: Read fragments in parallel
    std::cout << "[2] Reading fragments in parallel..." << std::endl;
    auto total_start = std::chrono::high_resolution_clock::now();
    
    // Launch threads
    std::vector<std::future<int64_t>> futures;
    int thread_id = 0;
    
    for (int frag_id : fragment_ids) {
        futures.push_back(std::async(std::launch::async, 
                                     read_fragment, 
                                     dataset_path, 
                                     frag_id, 
                                     thread_id++));
        
        // Limit concurrent threads
        if (futures.size() >= static_cast<size_t>(max_threads)) {
            // Wait for at least one to complete
            for (auto& fut : futures) {
                if (fut.valid() && fut.wait_for(std::chrono::milliseconds(0)) == std::future_status::ready) {
                    fut.get();
                }
            }
            // Remove completed futures
            futures.erase(
                std::remove_if(futures.begin(), futures.end(),
                              [](std::future<int64_t>& f) { 
                                  return f.wait_for(std::chrono::milliseconds(0)) == std::future_status::ready; 
                              }),
                futures.end()
            );
        }
    }
    
    // Wait for all threads to complete and collect results
    int64_t total_rows = 0;
    int successful = 0;
    
    for (auto& fut : futures) {
        int64_t rows = fut.get();
        if (rows >= 0) {
            total_rows += rows;
            successful++;
        }
    }
    
    auto total_end = std::chrono::high_resolution_clock::now();
    auto total_duration = std::chrono::duration_cast<std::chrono::milliseconds>(total_end - total_start);
    
    std::cout << "\n=== Summary ===" << std::endl;
    std::cout << "Fragments read: " << successful << " / " << fragment_ids.size() << std::endl;
    std::cout << "Total rows: " << total_rows << std::endl;
    std::cout << "Total time: " << total_duration.count() << " ms" << std::endl;
    
    if (total_duration.count() > 0) {
        double throughput = static_cast<double>(total_rows) / total_duration.count() * 1000.0;
        std::cout << "Throughput: " << static_cast<int64_t>(throughput) << " rows/sec" << std::endl;
    }
    
    std::cout << "\n=== Complete ===" << std::endl;
    
    return successful == static_cast<int>(fragment_ids.size()) ? 0 : 1;
}

