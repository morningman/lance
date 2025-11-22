// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The Lance Authors

#pragma once

#include <exception>
#include <memory>
#include <string>

namespace lance {

/// Error codes matching Rust FFI
enum class ErrorCode {
    Success = 0,
    InvalidArgument = 1,
    InvalidInput = 2,
    NotFound = 3,
    AlreadyExists = 4,
    IOError = 5,
    Runtime = 6,
    Schema = 7,
    Arrow = 8,
    NotSupported = 9,
    Other = 10,
    Unknown = 11,
};

/// Lance exception class
class LanceException : public std::exception {
public:
    LanceException(ErrorCode code, std::string message)
        : code_(code), message_(std::move(message)) {}

    [[nodiscard]] const char* what() const noexcept override {
        return message_.c_str();
    }

    [[nodiscard]] ErrorCode code() const noexcept { return code_; }

    [[nodiscard]] const std::string& message() const noexcept { return message_; }

private:
    ErrorCode code_;
    std::string message_;
};

/// Result type for operations that may fail
template <typename T>
class Result {
public:
    /// Success case
    explicit Result(T value) : value_(std::move(value)), error_(nullptr) {}

    /// Error case
    explicit Result(LanceException error)
        : value_(std::nullopt), error_(std::make_unique<LanceException>(std::move(error))) {}

    /// Check if the result is ok
    [[nodiscard]] bool is_ok() const noexcept { return !error_; }

    /// Check if the result is error
    [[nodiscard]] bool is_error() const noexcept { return static_cast<bool>(error_); }

    /// Get the value (throws if error)
    T& value() & {
        if (error_) {
            throw *error_;
        }
        return *value_;
    }

    /// Get the value (throws if error)
    const T& value() const& {
        if (error_) {
            throw *error_;
        }
        return *value_;
    }

    /// Get the value (throws if error)
    T&& value() && {
        if (error_) {
            throw *error_;
        }
        return std::move(*value_);
    }

    /// Get the error (throws if ok)
    const LanceException& error() const {
        if (!error_) {
            throw LanceException(ErrorCode::Other, "Result is ok, not an error");
        }
        return *error_;
    }

    /// Unwrap or throw
    T unwrap() && {
        if (error_) {
            throw *error_;
        }
        return std::move(*value_);
    }

private:
    std::optional<T> value_;
    std::unique_ptr<LanceException> error_;
};

/// Result type for void operations
template <>
class Result<void> {
public:
    /// Success case
    Result() : error_(nullptr) {}

    /// Error case
    explicit Result(LanceException error)
        : error_(std::make_unique<LanceException>(std::move(error))) {}

    /// Check if the result is ok
    [[nodiscard]] bool is_ok() const noexcept { return !error_; }

    /// Check if the result is error
    [[nodiscard]] bool is_error() const noexcept { return static_cast<bool>(error_); }

    /// Throw if error
    void value() const {
        if (error_) {
            throw *error_;
        }
    }

    /// Get the error (throws if ok)
    const LanceException& error() const {
        if (!error_) {
            throw LanceException(ErrorCode::Other, "Result is ok, not an error");
        }
        return *error_;
    }

    /// Unwrap or throw
    void unwrap() const {
        if (error_) {
            throw *error_;
        }
    }

private:
    std::unique_ptr<LanceException> error_;
};

/// Helper to convert error code to exception
LanceException error_from_code(ErrorCode code, const char* message);

}  // namespace lance

