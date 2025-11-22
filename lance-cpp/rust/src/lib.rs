// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The Lance Authors

//! FFI bindings for Lance C++ SDK
//!
//! This crate provides C-compatible FFI functions that wrap the Lance Rust library,
//! allowing C++ applications to use Lance functionality.

use std::sync::LazyLock;

pub mod dataset;
pub mod error;
pub mod ffi;
pub mod scanner;
pub mod types;

pub use error::{Error, ErrorCode, Result};

/// Global Tokio runtime for handling async operations
pub static RUNTIME: LazyLock<tokio::runtime::Runtime> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("Failed to create tokio runtime")
});

/// Initialize the Lance logger
///
/// This should be called once at the start of the program.
/// Log level can be controlled via the LANCE_LOG environment variable.
///
/// # Safety
/// This function is safe to call multiple times, but only the first call will have effect.
#[no_mangle]
pub extern "C" fn lance_init_logger() {
    let env = env_logger::Env::new()
        .filter_or("LANCE_LOG", "warn")
        .write_style("LANCE_LOG_STYLE");
    
    let _ = env_logger::Builder::from_env(env).try_init();
}

/// Get the version of the Lance library
///
/// # Safety
/// The returned string is valid for the lifetime of the program.
#[no_mangle]
pub extern "C" fn lance_version() -> *const std::os::raw::c_char {
    static VERSION: LazyLock<std::ffi::CString> = LazyLock::new(|| {
        std::ffi::CString::new(env!("CARGO_PKG_VERSION")).unwrap()
    });
    VERSION.as_ptr()
}

