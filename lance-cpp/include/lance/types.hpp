// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The Lance Authors

#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace lance {

/// Fragment metadata
struct FragmentMetadata {
    int32_t id;
    int64_t physical_rows;
    int64_t num_deletions;

    FragmentMetadata() = default;
    FragmentMetadata(int32_t id, int64_t physical_rows, int64_t num_deletions)
        : id(id), physical_rows(physical_rows), num_deletions(num_deletions) {}
};

/// Scan options for querying datasets
struct ScanOptions {
    /// Optional filter expression (SQL-like)
    std::optional<std::string> filter;

    /// Optional column projection
    std::optional<std::vector<std::string>> columns;

    /// Optional fragment IDs to scan
    std::optional<std::vector<int32_t>> fragment_ids;

    /// Optional limit on number of rows
    std::optional<size_t> limit;

    /// Optional offset
    std::optional<size_t> offset;

    /// Include row ID in results
    bool with_row_id = false;

    /// Include row address in results
    bool with_row_address = false;

    /// Batch size for reading
    std::optional<size_t> batch_size;

    ScanOptions() = default;

    /// Builder pattern methods
    ScanOptions& set_filter(std::string f) {
        filter = std::move(f);
        return *this;
    }

    ScanOptions& set_columns(std::vector<std::string> cols) {
        columns = std::move(cols);
        return *this;
    }

    ScanOptions& set_fragment_ids(std::vector<int32_t> ids) {
        fragment_ids = std::move(ids);
        return *this;
    }

    ScanOptions& set_limit(size_t lim) {
        limit = lim;
        return *this;
    }

    ScanOptions& set_offset(size_t off) {
        offset = off;
        return *this;
    }

    ScanOptions& set_with_row_id(bool enable = true) {
        with_row_id = enable;
        return *this;
    }

    ScanOptions& set_with_row_address(bool enable = true) {
        with_row_address = enable;
        return *this;
    }

    ScanOptions& set_batch_size(size_t size) {
        batch_size = size;
        return *this;
    }
};

}  // namespace lance

