// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The Lance Authors

#pragma once

#include <memory>
#include <string>
#include <vector>

#include <arrow/type_fwd.h>

#include "error.hpp"
#include "types.hpp"

namespace lance {

// Forward declaration
class Scanner;

/// Lance Dataset
///
/// A dataset is a collection of fragments, each containing data files.
/// This is the main entry point for reading Lance data.
class Dataset {
public:
    /// Open a Lance dataset
    ///
    /// @param path URI to the dataset (local path, s3://, gs://, etc.)
    /// @return Dataset instance on success
    static Result<Dataset> open(const std::string& path);

    /// Destructor
    ~Dataset();

    /// Move constructor
    Dataset(Dataset&& other) noexcept;

    /// Move assignment
    Dataset& operator=(Dataset&& other) noexcept;

    /// Copy constructor (deleted)
    Dataset(const Dataset&) = delete;

    /// Copy assignment (deleted)
    Dataset& operator=(const Dataset&) = delete;

    /// Get the Arrow schema of the dataset
    ///
    /// @return Shared pointer to Arrow schema
    Result<std::shared_ptr<arrow::Schema>> schema() const;

    /// Count the number of rows in the dataset
    ///
    /// @return Total number of rows
    Result<int64_t> count_rows() const;

    /// Get the version of the dataset
    ///
    /// @return Dataset version number
    int64_t version() const;

    /// Get the URI of the dataset
    ///
    /// @return Dataset URI as string
    Result<std::string> uri() const;

    /// Get fragment metadata
    ///
    /// @return Vector of fragment metadata
    Result<std::vector<FragmentMetadata>> get_fragments() const;

    /// Create a scanner to read data
    ///
    /// @param options Scan options (filter, projection, etc.)
    /// @return Scanner instance
    Result<Scanner> create_scanner(const ScanOptions& options = ScanOptions()) const;

private:
    /// Private constructor (use open() instead)
    explicit Dataset(void* handle);

    /// Opaque handle to Rust dataset
    void* handle_;

    friend class Scanner;
};

}  // namespace lance

