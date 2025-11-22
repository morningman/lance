// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The Lance Authors

#include "lance/dataset.hpp"
#include "lance/scanner.hpp"

#include <arrow/c/bridge.h>

extern "C" {
#include "lance_ffi.h"
}

namespace lance {

namespace {

/// RAII wrapper for C strings
struct CString {
    char* ptr = nullptr;
    
    ~CString() {
        if (ptr) {
            lance_string_free(ptr);
        }
    }
    
    CString(const CString&) = delete;
    CString& operator=(const CString&) = delete;
};

/// Helper to check error code and throw exception
void check_error(LanceErrorCode code, char* error_msg) {
    if (code != LanceErrorCode::Success) {
        std::string msg = error_msg ? error_msg : "Unknown error";
        if (error_msg) {
            lance_string_free(error_msg);
        }
        throw error_from_code(static_cast<ErrorCode>(code), msg.c_str());
    }
}

}  // anonymous namespace

Dataset::Dataset(void* handle) : handle_(handle) {}

Dataset::~Dataset() {
    if (handle_) {
        lance_dataset_free(static_cast<LanceDataset*>(handle_));
        handle_ = nullptr;
    }
}

Dataset::Dataset(Dataset&& other) noexcept : handle_(other.handle_) {
    other.handle_ = nullptr;
}

Dataset& Dataset::operator=(Dataset&& other) noexcept {
    if (this != &other) {
        if (handle_) {
            lance_dataset_free(static_cast<LanceDataset*>(handle_));
        }
        handle_ = other.handle_;
        other.handle_ = nullptr;
    }
    return *this;
}

Result<Dataset> Dataset::open(const std::string& path) {
    try {
        LanceDataset* dataset = nullptr;
        char* error_msg = nullptr;

        auto code = lance_dataset_open(
            path.c_str(),
            &dataset,
            &error_msg
        );

        check_error(code, error_msg);

        return Result<Dataset>(Dataset(dataset));
    } catch (const LanceException& e) {
        return Result<Dataset>(e);
    }
}

Result<std::shared_ptr<arrow::Schema>> Dataset::schema() const {
    try {
        FFI_ArrowSchema c_schema{};
        char* error_msg = nullptr;

        auto code = lance_dataset_schema(
            static_cast<const LanceDataset*>(handle_),
            &c_schema,
            &error_msg
        );

        check_error(code, error_msg);

        // Import schema from C ABI
        auto arrow_schema = arrow::ImportSchema(&c_schema);
        if (!arrow_schema.ok()) {
            throw error_from_code(ErrorCode::Arrow, arrow_schema.status().message().c_str());
        }

        return Result<std::shared_ptr<arrow::Schema>>(*arrow_schema);
    } catch (const LanceException& e) {
        return Result<std::shared_ptr<arrow::Schema>>(e);
    }
}

Result<int64_t> Dataset::count_rows() const {
    try {
        int64_t count = 0;
        char* error_msg = nullptr;

        auto code = lance_dataset_count_rows(
            static_cast<const LanceDataset*>(handle_),
            &count,
            &error_msg
        );

        check_error(code, error_msg);

        return Result<int64_t>(count);
    } catch (const LanceException& e) {
        return Result<int64_t>(e);
    }
}

int64_t Dataset::version() const {
    int64_t version = 0;
    lance_dataset_version(
        static_cast<const LanceDataset*>(handle_),
        &version
    );
    return version;
}

Result<std::string> Dataset::uri() const {
    try {
        char* uri_ptr = nullptr;
        char* error_msg = nullptr;

        auto code = lance_dataset_uri(
            static_cast<const LanceDataset*>(handle_),
            &uri_ptr,
            &error_msg
        );

        check_error(code, error_msg);

        std::string uri = uri_ptr;
        lance_string_free(uri_ptr);

        return Result<std::string>(uri);
    } catch (const LanceException& e) {
        return Result<std::string>(e);
    }
}

Result<std::vector<FragmentMetadata>> Dataset::get_fragments() const {
    try {
        LanceFragmentMetadata* fragments = nullptr;
        size_t count = 0;
        char* error_msg = nullptr;

        auto code = lance_dataset_get_fragments(
            static_cast<const LanceDataset*>(handle_),
            &fragments,
            &count,
            &error_msg
        );

        check_error(code, error_msg);

        std::vector<FragmentMetadata> result;
        result.reserve(count);

        for (size_t i = 0; i < count; ++i) {
            result.emplace_back(
                fragments[i].id,
                fragments[i].physical_rows,
                fragments[i].num_deletions
            );
        }

        lance_fragments_free(fragments, count);

        return Result<std::vector<FragmentMetadata>>(std::move(result));
    } catch (const LanceException& e) {
        return Result<std::vector<FragmentMetadata>>(e);
    }
}

Result<Scanner> Dataset::create_scanner(const ScanOptions& options) const {
    try {
        // Convert C++ options to C options
        lance_ScanOptions c_options{};
        c_options.filter = nullptr;
        c_options.columns = nullptr;
        c_options.columns_count = 0;
        c_options.fragment_ids = nullptr;
        c_options.fragment_ids_count = 0;
        c_options.limit = -1;
        c_options.offset = -1;
        c_options.with_row_id = false;
        c_options.with_row_address = false;
        c_options.batch_size = -1;

        std::string filter_str;
        std::vector<const char*> column_ptrs;
        std::vector<int32_t> fragment_ids;

        if (options.filter) {
            filter_str = *options.filter;
            c_options.filter = filter_str.c_str();
        }

        if (options.columns) {
            column_ptrs.reserve(options.columns->size());
            for (const auto& col : *options.columns) {
                column_ptrs.push_back(col.c_str());
            }
            c_options.columns = column_ptrs.data();
            c_options.columns_count = column_ptrs.size();
        }

        if (options.fragment_ids) {
            fragment_ids = *options.fragment_ids;
            c_options.fragment_ids = fragment_ids.data();
            c_options.fragment_ids_count = fragment_ids.size();
        }

        if (options.limit) {
            c_options.limit = static_cast<int64_t>(*options.limit);
        }

        if (options.offset) {
            c_options.offset = static_cast<int64_t>(*options.offset);
        }

        c_options.with_row_id = options.with_row_id;
        c_options.with_row_address = options.with_row_address;

        if (options.batch_size) {
            c_options.batch_size = static_cast<int64_t>(*options.batch_size);
        }

        LanceScanner* scanner = nullptr;
        char* error_msg = nullptr;

        auto code = lance_scanner_create(
            static_cast<const LanceDataset*>(handle_),
            &c_options,
            &scanner,
            &error_msg
        );

        check_error(code, error_msg);

        return Result<Scanner>(Scanner(scanner));
    } catch (const LanceException& e) {
        return Result<Scanner>(e);
    }
}

}  // namespace lance

