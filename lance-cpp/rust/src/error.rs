// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The Lance Authors

//! Error handling for FFI

use std::ffi::CString;
use std::os::raw::c_char;
use std::ptr;

/// Error codes for C API
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorCode {
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
}

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug)]
pub struct Error {
    code: ErrorCode,
    message: String,
}

impl Error {
    pub fn new(code: ErrorCode, message: String) -> Self {
        Self { code, message }
    }

    pub fn invalid_argument(msg: impl Into<String>) -> Self {
        Self::new(ErrorCode::InvalidArgument, msg.into())
    }

    pub fn io_error(msg: impl Into<String>) -> Self {
        Self::new(ErrorCode::IOError, msg.into())
    }

    pub fn code(&self) -> ErrorCode {
        self.code
    }

    pub fn message(&self) -> &str {
        &self.message
    }

    /// Convert to C string (caller must free)
    pub fn to_c_string(&self) -> *mut c_char {
        match CString::new(self.message.as_str()) {
            Ok(c_str) => c_str.into_raw(),
            Err(_) => ptr::null_mut(),
        }
    }
}

impl From<lance::Error> for Error {
    fn from(err: lance::Error) -> Self {
        let code = match &err {
            lance::Error::InvalidInput { .. } => ErrorCode::InvalidInput,
            lance::Error::NotFound { .. } => ErrorCode::NotFound,
            lance::Error::AlreadyExists { .. } => ErrorCode::AlreadyExists,
            lance::Error::IO { .. } => ErrorCode::IOError,
            lance::Error::Schema { .. } => ErrorCode::Schema,
            lance::Error::Arrow { .. } => ErrorCode::Arrow,
            lance::Error::NotSupported { .. } => ErrorCode::NotSupported,
            _ => ErrorCode::Other,
        };
        Error::new(code, err.to_string())
    }
}

impl From<std::io::Error> for Error {
    fn from(err: std::io::Error) -> Self {
        Error::io_error(err.to_string())
    }
}

impl From<arrow::error::ArrowError> for Error {
    fn from(err: arrow::error::ArrowError) -> Self {
        Error::new(ErrorCode::Arrow, err.to_string())
    }
}

/// Free a C string allocated by Rust
///
/// # Safety
/// - `s` must be a valid pointer returned from a Rust function
/// - `s` must not be used after calling this function
#[no_mangle]
pub unsafe extern "C" fn lance_string_free(s: *mut c_char) {
    if !s.is_null() {
        drop(CString::from_raw(s));
    }
}

/// Convert error code to human-readable message
///
/// # Safety
/// The returned string is valid for the lifetime of the program.
#[no_mangle]
pub extern "C" fn lance_error_message(code: ErrorCode) -> *const c_char {
    let msg = match code {
        ErrorCode::Success => "Success",
        ErrorCode::InvalidArgument => "Invalid argument",
        ErrorCode::InvalidInput => "Invalid input",
        ErrorCode::NotFound => "Not found",
        ErrorCode::AlreadyExists => "Already exists",
        ErrorCode::IOError => "I/O error",
        ErrorCode::Runtime => "Runtime error",
        ErrorCode::Schema => "Schema error",
        ErrorCode::Arrow => "Arrow error",
        ErrorCode::NotSupported => "Not supported",
        ErrorCode::Other => "Other error",
        ErrorCode::Unknown => "Unknown error",
    };

    // Safe to return static string
    msg.as_ptr() as *const c_char
}

