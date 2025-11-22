// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The Lance Authors

/// @file parallel_read.cpp
/// @brief Example of parallel reading using fragments

#include <lance/lance.hpp>

#include <arrow/record_batch.h>

#include <algorithm>
#include <future>
#include <iostream>
#include <string>
#include <thread>
#include <vector>

using namespace lance;

/// Result of reading a fragment group
struct ReadResult {
    int group_id;
    std::vector<int32_t> fragment_ids;
    int64_t rows_read;
    int batch_count;
};

/// Read a group of fragments
ReadResult read_fragment_group(
    const std::string& dataset_path,
    int group_id,
    const std::vector<int32_t>& fragment_ids,
    const std::optional<std::string>& filter) {
    
    ReadResult result;
    result.group_id = group_id;
    result.fragment_ids = fragment_ids;
    result.rows_read = 0;
    result.batch_count = 0;

    try {
        // Open dataset in this thread
        auto dataset = Dataset::open(dataset_path).unwrap();

        // Create scanner for specific fragments
        ScanOptions options;
        options.set_fragment_ids(fragment_ids);
        if (filter) {
            options.set_filter(*filter);
        }

        auto scanner = dataset.create_scanner(options).unwrap();

        // Read all batches
        while (scanner.load_next_batch().unwrap()) {
            auto batch = scanner.current_batch().unwrap();
            result.rows_read += batch->num_rows();
            result.batch_count++;
        }

    } catch (const LanceException& e) {
        std::cerr << "Error in group " << group_id << ": " << e.what() << std::endl;
    }

    return result;
}

/// Partition fragments into groups for parallel reading
std::vector<std::vector<int32_t>> partition_fragments(
    const std::vector<FragmentMetadata>& fragments,
    int num_groups) {
    
    std::vector<std::vector<int32_t>> groups(num_groups);
    
    // Simple round-robin partitioning
    for (size_t i = 0; i < fragments.size(); ++i) {
        groups[i % num_groups].push_back(fragments[i].id);
    }

    return groups;
}

int main(int argc, char* argv[]) {
    lance::init_logger();

    if (argc < 2) {
        std::cerr << "Usage: " << argv[0] << " <dataset_path> [num_threads] [filter]" << std::endl;
        std::cerr << "\nExample:" << std::endl;
        std::cerr << "  " << argv[0] << " /path/to/dataset" << std::endl;
        std::cerr << "  " << argv[0] << " /path/to/dataset 4" << std::endl;
        std::cerr << "  " << argv[0] << " /path/to/dataset 4 \"age > 25\"" << std::endl;
        return 1;
    }

    std::string dataset_path = argv[1];
    
    int num_threads = std::thread::hardware_concurrency();
    if (argc >= 3) {
        num_threads = std::stoi(argv[2]);
    }
    
    std::optional<std::string> filter;
    if (argc >= 4) {
        filter = argv[3];
    }

    try {
        // Open dataset to get fragments
        std::cout << "Opening dataset: " << dataset_path << std::endl;
        auto dataset = Dataset::open(dataset_path).unwrap();

        std::cout << "Dataset version: " << dataset.version() << std::endl;
        std::cout << "Total rows: " << dataset.count_rows().unwrap() << std::endl;

        // Get all fragments
        auto fragments = dataset.get_fragments().unwrap();
        std::cout << "\n=== Fragment Info ===" << std::endl;
        std::cout << "Total fragments: " << fragments.size() << std::endl;

        if (fragments.empty()) {
            std::cout << "No fragments to read" << std::endl;
            return 0;
        }

        // Partition fragments into groups
        num_threads = std::min(num_threads, static_cast<int>(fragments.size()));
        auto fragment_groups = partition_fragments(fragments, num_threads);

        std::cout << "\n=== Parallel Reading ===" << std::endl;
        std::cout << "Using " << num_threads << " threads" << std::endl;
        if (filter) {
            std::cout << "Filter: " << *filter << std::endl;
        }
        std::cout << std::endl;

        // Launch parallel reads
        auto start_time = std::chrono::high_resolution_clock::now();

        std::vector<std::future<ReadResult>> futures;
        for (int i = 0; i < num_threads; ++i) {
            if (!fragment_groups[i].empty()) {
                futures.push_back(std::async(
                    std::launch::async,
                    read_fragment_group,
                    dataset_path,
                    i,
                    fragment_groups[i],
                    filter
                ));
            }
        }

        // Collect results
        std::vector<ReadResult> results;
        for (auto& future : futures) {
            results.push_back(future.get());
        }

        auto end_time = std::chrono::high_resolution_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(
            end_time - start_time).count();

        // Print results
        std::cout << "=== Results ===" << std::endl;
        int64_t total_rows = 0;
        int total_batches = 0;

        for (const auto& result : results) {
            std::cout << "Group " << result.group_id << ": "
                      << result.fragment_ids.size() << " fragments, "
                      << result.batch_count << " batches, "
                      << result.rows_read << " rows"
                      << std::endl;
            
            total_rows += result.rows_read;
            total_batches += result.batch_count;
        }

        std::cout << "\n=== Summary ===" << std::endl;
        std::cout << "Total rows read: " << total_rows << std::endl;
        std::cout << "Total batches: " << total_batches << std::endl;
        std::cout << "Time: " << duration << " ms" << std::endl;
        
        if (duration > 0) {
            double throughput = (total_rows * 1000.0) / duration;
            std::cout << "Throughput: " << static_cast<int64_t>(throughput) << " rows/sec" << std::endl;
        }

        // Compare with single-threaded read
        std::cout << "\n=== Single-threaded comparison ===" << std::endl;
        start_time = std::chrono::high_resolution_clock::now();

        ScanOptions options;
        if (filter) {
            options.set_filter(*filter);
        }
        auto scanner = dataset.create_scanner(options).unwrap();

        int64_t single_rows = 0;
        while (scanner.load_next_batch().unwrap()) {
            auto batch = scanner.current_batch().unwrap();
            single_rows += batch->num_rows();
        }

        end_time = std::chrono::high_resolution_clock::now();
        auto single_duration = std::chrono::duration_cast<std::chrono::milliseconds>(
            end_time - start_time).count();

        std::cout << "Single-threaded time: " << single_duration << " ms" << std::endl;
        
        if (single_duration > 0 && duration > 0) {
            double speedup = static_cast<double>(single_duration) / duration;
            std::cout << "Speedup: " << speedup << "x" << std::endl;
        }

    } catch (const LanceException& e) {
        std::cerr << "Error: " << e.what() << std::endl;
        return 1;
    }

    return 0;
}

