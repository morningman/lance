// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The Lance Authors

/// @file basic_read.cpp
/// @brief Basic example of reading a Lance dataset

#include <lance/lance.hpp>

#include <arrow/io/api.h>
#include <arrow/ipc/api.h>
#include <arrow/record_batch.h>

#include <iostream>
#include <string>

using namespace lance;

int main(int argc, char* argv[]) {
    // Initialize logger
    lance::init_logger();

    // Check arguments
    if (argc < 2) {
        std::cerr << "Usage: " << argv[0] << " <dataset_path>" << std::endl;
        std::cerr << "\nExample:" << std::endl;
        std::cerr << "  " << argv[0] << " /path/to/dataset" << std::endl;
        return 1;
    }

    std::string dataset_path = argv[1];

    try {
        // Open the dataset
        std::cout << "Opening dataset: " << dataset_path << std::endl;
        auto dataset = Dataset::open(dataset_path).unwrap();

        // Get basic info
        std::cout << "\n=== Dataset Info ===" << std::endl;
        std::cout << "URI: " << dataset.uri().unwrap() << std::endl;
        std::cout << "Version: " << dataset.version() << std::endl;
        std::cout << "Total rows: " << dataset.count_rows().unwrap() << std::endl;

        // Get schema
        auto schema = dataset.schema().unwrap();
        std::cout << "\nSchema:" << std::endl;
        std::cout << schema->ToString() << std::endl;

        // Get fragments
        auto fragments = dataset.get_fragments().unwrap();
        std::cout << "\n=== Fragments ===" << std::endl;
        std::cout << "Fragment count: " << fragments.size() << std::endl;
        for (const auto& fragment : fragments) {
            std::cout << "  Fragment " << fragment.id
                      << ": " << fragment.physical_rows << " rows"
                      << ", " << fragment.num_deletions << " deletions"
                      << std::endl;
        }

        // Create a scanner with optional filter
        std::cout << "\n=== Scanning Data ===" << std::endl;

        ScanOptions options;
        // Uncomment to add filter:
        // options.set_filter("age > 25");

        // Uncomment to add column projection:
        // options.set_columns({"id", "name", "age"});

        // Uncomment to limit results:
        // options.set_limit(100);

        auto scanner = dataset.create_scanner(options).unwrap();

        // Read and print batches
        int64_t total_rows = 0;
        int batch_count = 0;

        while (scanner.load_next_batch().unwrap()) {
            auto batch = scanner.current_batch().unwrap();
            batch_count++;
            total_rows += batch->num_rows();

            std::cout << "Batch " << batch_count << ": "
                      << batch->num_rows() << " rows, "
                      << batch->num_columns() << " columns"
                      << std::endl;

            // Print first few rows of first batch
            if (batch_count == 1 && batch->num_rows() > 0) {
                std::cout << "\nFirst 5 rows of first batch:" << std::endl;
                int rows_to_print = std::min(5, static_cast<int>(batch->num_rows()));
                std::cout << batch->Slice(0, rows_to_print)->ToString() << std::endl;
            }
        }

        std::cout << "\n=== Summary ===" << std::endl;
        std::cout << "Total batches read: " << batch_count << std::endl;
        std::cout << "Total rows read: " << total_rows << std::endl;

        // Alternative: Use range-based for loop
        std::cout << "\n=== Using Iterator (first 3 batches) ===" << std::endl;
        auto scanner2 = dataset.create_scanner(ScanOptions().set_limit(300)).unwrap();
        
        int iter_count = 0;
        for (const auto& batch : scanner2) {
            std::cout << "Batch " << (++iter_count) << ": "
                      << batch->num_rows() << " rows" << std::endl;
            if (iter_count >= 3) break;
        }

    } catch (const LanceException& e) {
        std::cerr << "Error: " << e.what() << std::endl;
        std::cerr << "Error code: " << static_cast<int>(e.code()) << std::endl;
        return 1;
    }

    return 0;
}

