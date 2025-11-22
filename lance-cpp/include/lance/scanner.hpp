// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The Lance Authors

#pragma once

#include <memory>

#include <arrow/type_fwd.h>

#include "error.hpp"

namespace lance {

// Forward declaration
class Dataset;

/// Scanner for reading Lance data
///
/// A scanner iterates over batches of data from a Lance dataset.
/// It applies filters, projections, and other query operations.
class Scanner {
public:
    /// Destructor
    ~Scanner();

    /// Move constructor
    Scanner(Scanner&& other) noexcept;

    /// Move assignment
    Scanner& operator=(Scanner&& other) noexcept;

    /// Copy constructor (deleted)
    Scanner(const Scanner&) = delete;

    /// Copy assignment (deleted)
    Scanner& operator=(const Scanner&) = delete;

    /// Get the schema of the scanner result
    ///
    /// @return Shared pointer to Arrow schema
    Result<std::shared_ptr<arrow::Schema>> schema() const;

    /// Load the next batch of data
    ///
    /// @return true if a batch was loaded, false if no more data
    Result<bool> load_next_batch();

    /// Get the current batch
    ///
    /// Must be called after load_next_batch() returns true.
    ///
    /// @return Shared pointer to current RecordBatch
    Result<std::shared_ptr<arrow::RecordBatch>> current_batch() const;

    /// Iterator interface for range-based for loops
    class Iterator {
    public:
        using iterator_category = std::input_iterator_tag;
        using value_type = std::shared_ptr<arrow::RecordBatch>;
        using difference_type = std::ptrdiff_t;
        using pointer = value_type*;
        using reference = value_type&;

        explicit Iterator(Scanner* scanner, bool is_end = false);

        reference operator*();
        pointer operator->();
        Iterator& operator++();
        bool operator==(const Iterator& other) const;
        bool operator!=(const Iterator& other) const;

    private:
        Scanner* scanner_;
        bool is_end_;
        std::shared_ptr<arrow::RecordBatch> current_batch_;
    };

    /// Begin iterator
    Iterator begin();

    /// End iterator
    Iterator end();

private:
    /// Private constructor (use Dataset::create_scanner() instead)
    explicit Scanner(void* handle);

    /// Opaque handle to Rust scanner
    void* handle_;

    /// Cached current batch
    mutable std::shared_ptr<arrow::RecordBatch> current_batch_;

    friend class Dataset;
};

}  // namespace lance

