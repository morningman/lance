// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The LanceDB Authors

//! Lance Scanner FFI bindings
//!
//! This module provides scanner functionality for reading data from Lance datasets.

use std::ffi::CStr;
use std::os::raw::c_char;
use std::ptr;
use std::sync::Arc;

use arrow_array::RecordBatch;
use arrow_schema::ffi::{FFI_ArrowArray, FFI_ArrowSchema};
use futures::StreamExt;
use lance::dataset::scanner::Scanner as LanceScanner;

use crate::connection::get_runtime;
use crate::dataset::Dataset;
use crate::error::{set_invalid_argument_message, LanceDBError};

/// Opaque handle to a Lance Scanner
#[repr(C)]
pub struct Scanner {
    pub(crate) stream: Arc<tokio::sync::Mutex<lance::dataset::scanner::DatasetRecordBatchStream>>,
    pub(crate) current_batch: Option<RecordBatch>,
}

/// Scan options structure
#[repr(C)]
pub struct LanceScanOptions {
    /// Optional filter expression (SQL-like WHERE clause)
    pub filter: *const c_char,
    /// Optional column projection (comma-separated column names)
    pub columns: *const *const c_char,
    /// Number of columns in projection
    pub columns_count: usize,
    /// Optional fragment IDs to scan
    pub fragment_ids: *const i32,
    /// Number of fragment IDs
    pub fragment_ids_count: usize,
    /// Optional limit on number of rows
    pub limit: i64, // -1 means no limit
    /// Optional offset
    pub offset: i64, // -1 means no offset
    /// Batch size for reading
    pub batch_size: i64, // -1 means default
}

/// Create scanner for dataset
///
/// # Safety
/// - `dataset` must be a valid pointer from `lance_dataset_open`
/// - `options` can be NULL for default options
/// - `scanner_out` must be a valid pointer
/// - Returned scanner must be freed with `lance_scanner_free`
///
/// # Returns
/// - Success with scanner in `scanner_out`
/// - Error code on failure
#[no_mangle]
pub unsafe extern "C" fn lance_dataset_create_scanner(
    dataset: *const Dataset,
    options: *const LanceScanOptions,
    scanner_out: *mut *mut Scanner,
    error_message: *mut *mut c_char,
) -> LanceDBError {
    if dataset.is_null() || scanner_out.is_null() {
        set_invalid_argument_message(error_message);
        return LanceDBError::InvalidArgument;
    }

    let dataset = &(*dataset).inner;
    let mut scan = dataset.scan();

    // Apply scan options if provided
    if !options.is_null() {
        let opts = &*options;

        // Apply filter
        if !opts.filter.is_null() {
            match CStr::from_ptr(opts.filter).to_str() {
                Ok(filter_str) => {
                    if let Err(e) = scan.filter(filter_str) {
                        if !error_message.is_null() {
                            if let Ok(c_str) = std::ffi::CString::new(format!("{}", e)) {
                                *error_message = c_str.into_raw();
                            }
                        }
                        return LanceDBError::InvalidInput;
                    }
                }
                Err(_) => {
                    set_invalid_argument_message(error_message);
                    return LanceDBError::InvalidInput;
                }
            }
        }

        // Apply column projection
        if !opts.columns.is_null() && opts.columns_count > 0 {
            let column_ptrs = std::slice::from_raw_parts(opts.columns, opts.columns_count);
            let mut columns: Vec<String> = Vec::new();

            for ptr in column_ptrs {
                match CStr::from_ptr(*ptr).to_str() {
                    Ok(s) => columns.push(s.to_string()),
                    Err(_) => {
                        set_invalid_argument_message(error_message);
                        return LanceDBError::InvalidInput;
                    }
                }
            }

            if let Err(e) = scan.project(&columns) {
                if !error_message.is_null() {
                    if let Ok(c_str) = std::ffi::CString::new(format!("{}", e)) {
                        *error_message = c_str.into_raw();
                    }
                }
                return LanceDBError::InvalidInput;
            }
        }

        // Apply fragment IDs
        if !opts.fragment_ids.is_null() && opts.fragment_ids_count > 0 {
            let fragment_ids = std::slice::from_raw_parts(opts.fragment_ids, opts.fragment_ids_count);
            scan.with_fragments(fragment_ids.iter().map(|&id| id as usize));
        }

        // Apply limit and offset
        if opts.limit >= 0 {
            let offset = if opts.offset >= 0 { opts.offset as usize } else { 0 };
            if let Err(e) = scan.limit(Some(opts.limit as usize), offset) {
                if !error_message.is_null() {
                    if let Ok(c_str) = std::ffi::CString::new(format!("{}", e)) {
                        *error_message = c_str.into_raw();
                    }
                }
                return LanceDBError::InvalidInput;
            }
        }

        // Apply batch size
        if opts.batch_size > 0 {
            scan.batch_size(opts.batch_size as usize);
        }
    }

    let runtime = get_runtime();
    match runtime.block_on(async { scan.try_into_stream().await }) {
        Ok(stream) => {
            let scanner = Scanner {
                stream: Arc::new(tokio::sync::Mutex::new(stream)),
                current_batch: None,
            };
            let boxed = Box::new(scanner);
            *scanner_out = Box::into_raw(boxed);
            LanceDBError::Success
        }
        Err(e) => {
            if !error_message.is_null() {
                if let Ok(c_str) = std::ffi::CString::new(format!("{}", e)) {
                    *error_message = c_str.into_raw();
                }
            }
            LanceDBError::Lance
        }
    }
}

/// Load next batch from scanner
///
/// # Safety
/// - `scanner` must be a valid pointer from `lance_dataset_create_scanner`
/// - `has_next_out` must be a valid pointer
///
/// # Returns
/// - Success with true in `has_next_out` if batch loaded
/// - Success with false in `has_next_out` if no more batches
/// - Error code on failure
#[no_mangle]
pub unsafe extern "C" fn lance_scanner_load_next_batch(
    scanner: *mut Scanner,
    has_next_out: *mut bool,
    error_message: *mut *mut c_char,
) -> LanceDBError {
    if scanner.is_null() || has_next_out.is_null() {
        set_invalid_argument_message(error_message);
        return LanceDBError::InvalidArgument;
    }

    let scanner = &mut *scanner;
    let runtime = get_runtime();

    let result = runtime.block_on(async {
        let mut stream = scanner.stream.lock().await;
        stream.next().await
    });

    match result {
        Some(Ok(batch)) => {
            scanner.current_batch = Some(batch);
            *has_next_out = true;
            LanceDBError::Success
        }
        Some(Err(e)) => {
            scanner.current_batch = None;
            *has_next_out = false;
            if !error_message.is_null() {
                if let Ok(c_str) = std::ffi::CString::new(format!("{}", e)) {
                    *error_message = c_str.into_raw();
                }
            }
            LanceDBError::Lance
        }
        None => {
            scanner.current_batch = None;
            *has_next_out = false;
            LanceDBError::Success
        }
    }
}

/// Get current batch as Arrow C ABI
///
/// # Safety
/// - `scanner` must be a valid pointer from `lance_dataset_create_scanner`
/// - Must be called after `lance_scanner_load_next_batch` returns true
/// - `array_out` and `schema_out` must be valid pointers
/// - Caller is responsible for releasing arrays using Arrow C ABI
///
/// # Returns
/// - Success with batch data in `array_out` and `schema_out`
/// - Error code on failure
#[no_mangle]
pub unsafe extern "C" fn lance_scanner_to_arrow(
    scanner: *const Scanner,
    array_out: *mut *mut FFI_ArrowArray,
    schema_out: *mut *mut FFI_ArrowSchema,
    error_message: *mut *mut c_char,
) -> LanceDBError {
    if scanner.is_null() || array_out.is_null() || schema_out.is_null() {
        set_invalid_argument_message(error_message);
        return LanceDBError::InvalidArgument;
    }

    let scanner = &*scanner;

    let batch = match &scanner.current_batch {
        Some(b) => b,
        None => {
            set_invalid_argument_message(error_message);
            return LanceDBError::InvalidInput;
        }
    };

    // Export schema
    match FFI_ArrowSchema::try_from(batch.schema().as_ref()) {
        Ok(ffi_schema) => {
            let boxed_schema = Box::new(ffi_schema);
            *schema_out = Box::into_raw(boxed_schema);
        }
        Err(e) => {
            if !error_message.is_null() {
                if let Ok(c_str) = std::ffi::CString::new(format!("{}", e)) {
                    *error_message = c_str.into_raw();
                }
            }
            return LanceDBError::Arrow;
        }
    }

    // Export array
    let ffi_array = FFI_ArrowArray::new(batch.into());
    let boxed_array = Box::new(ffi_array);
    *array_out = Box::into_raw(boxed_array);

    LanceDBError::Success
}

/// Free scanner
///
/// # Safety
/// - `scanner` must be a valid pointer from `lance_dataset_create_scanner`
/// - `scanner` must not be used after calling this function
#[no_mangle]
pub unsafe extern "C" fn lance_scanner_free(scanner: *mut Scanner) {
    if !scanner.is_null() {
        drop(Box::from_raw(scanner));
    }
}

