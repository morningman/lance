// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The Lance Authors

#include "lance/scanner.hpp"

#include <arrow/c/bridge.h>

extern "C" {
#include "lance_ffi.h"
}

namespace lance {

namespace {

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

Scanner::Scanner(void* handle) : handle_(handle) {}

Scanner::~Scanner() {
    if (handle_) {
        lance_scanner_free(static_cast<LanceScanner*>(handle_));
        handle_ = nullptr;
    }
}

Scanner::Scanner(Scanner&& other) noexcept
    : handle_(other.handle_), current_batch_(std::move(other.current_batch_)) {
    other.handle_ = nullptr;
}

Scanner& Scanner::operator=(Scanner&& other) noexcept {
    if (this != &other) {
        if (handle_) {
            lance_scanner_free(static_cast<LanceScanner*>(handle_));
        }
        handle_ = other.handle_;
        current_batch_ = std::move(other.current_batch_);
        other.handle_ = nullptr;
    }
    return *this;
}

Result<std::shared_ptr<arrow::Schema>> Scanner::schema() const {
    try {
        FFI_ArrowSchema c_schema{};
        char* error_msg = nullptr;

        auto code = lance_scanner_schema(
            static_cast<const LanceScanner*>(handle_),
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

Result<bool> Scanner::load_next_batch() {
    try {
        bool has_next = false;
        char* error_msg = nullptr;

        auto code = lance_scanner_load_next_batch(
            static_cast<LanceScanner*>(handle_),
            &has_next,
            &error_msg
        );

        check_error(code, error_msg);

        if (has_next) {
            // Get the current batch
            FFI_ArrowArray c_array{};
            FFI_ArrowSchema c_schema{};
            error_msg = nullptr;

            code = lance_scanner_current_batch(
                static_cast<const LanceScanner*>(handle_),
                &c_array,
                &c_schema,
                &error_msg
            );

            check_error(code, error_msg);

            // Import RecordBatch from C ABI
            auto arrow_batch = arrow::ImportRecordBatch(&c_array, &c_schema);
            if (!arrow_batch.ok()) {
                throw error_from_code(ErrorCode::Arrow, arrow_batch.status().message().c_str());
            }

            current_batch_ = *arrow_batch;
        } else {
            current_batch_.reset();
        }

        return Result<bool>(has_next);
    } catch (const LanceException& e) {
        return Result<bool>(e);
    }
}

Result<std::shared_ptr<arrow::RecordBatch>> Scanner::current_batch() const {
    try {
        if (!current_batch_) {
            throw error_from_code(
                ErrorCode::InvalidArgument,
                "No current batch. Call load_next_batch() first."
            );
        }

        return Result<std::shared_ptr<arrow::RecordBatch>>(current_batch_);
    } catch (const LanceException& e) {
        return Result<std::shared_ptr<arrow::RecordBatch>>(e);
    }
}

Scanner::Iterator::Iterator(Scanner* scanner, bool is_end)
    : scanner_(scanner), is_end_(is_end) {
    if (!is_end_ && scanner_) {
        auto result = scanner_->load_next_batch();
        if (result.is_ok() && result.value()) {
            current_batch_ = scanner_->current_batch().unwrap();
        } else {
            is_end_ = true;
        }
    }
}

Scanner::Iterator::reference Scanner::Iterator::operator*() {
    return current_batch_;
}

Scanner::Iterator::pointer Scanner::Iterator::operator->() {
    return &current_batch_;
}

Scanner::Iterator& Scanner::Iterator::operator++() {
    if (!is_end_ && scanner_) {
        auto result = scanner_->load_next_batch();
        if (result.is_ok() && result.value()) {
            current_batch_ = scanner_->current_batch().unwrap();
        } else {
            is_end_ = true;
        }
    }
    return *this;
}

bool Scanner::Iterator::operator==(const Iterator& other) const {
    if (is_end_ && other.is_end_) {
        return true;
    }
    if (is_end_ != other.is_end_) {
        return false;
    }
    return scanner_ == other.scanner_;
}

bool Scanner::Iterator::operator!=(const Iterator& other) const {
    return !(*this == other);
}

Scanner::Iterator Scanner::begin() {
    return Iterator(this, false);
}

Scanner::Iterator Scanner::end() {
    return Iterator(nullptr, true);
}

}  // namespace lance

