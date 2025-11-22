// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The LanceDB Authors

//! Lance Dataset FFI bindings
//!
//! This module provides direct access to Lance datasets at the file format level,
//! bypassing the LanceDB database layer. Useful for reading Lance files directly.

use std::ffi::CStr;
use std::os::raw::c_char;
use std::ptr;

use arrow_schema::ffi::FFI_ArrowSchema;
use lance::dataset::Dataset as LanceDataset;

use crate::connection::get_runtime;
use crate::error::{set_invalid_argument_message, LanceDBError};

/// Opaque handle to a Lance Dataset
#[repr(C)]
pub struct Dataset {
    pub(crate) inner: LanceDataset,
}

/// Fragment metadata structure
#[repr(C)]
#[derive(Debug, Clone)]
pub struct LanceFragment {
    pub id: i32,
    pub physical_rows: usize,
    pub num_deletions: usize,
}

/// Open a Lance dataset from file path or URI
///
/// # Safety
/// - `path` must be a valid null-terminated C string
/// - `dataset_out` must be a valid pointer
/// - The returned dataset must be freed with `lance_dataset_free`
///
/// # Returns
/// - Success if dataset opened successfully
/// - Error code with optional error message on failure
#[no_mangle]
pub unsafe extern "C" fn lance_dataset_open(
    path: *const c_char,
    dataset_out: *mut *mut Dataset,
    error_message: *mut *mut c_char,
) -> LanceDBError {
    if path.is_null() || dataset_out.is_null() {
        set_invalid_argument_message(error_message);
        return LanceDBError::InvalidArgument;
    }

    let path_str = match CStr::from_ptr(path).to_str() {
        Ok(s) => s,
        Err(_) => {
            set_invalid_argument_message(error_message);
            return LanceDBError::InvalidInput;
        }
    };

    let runtime = get_runtime();
    match runtime.block_on(async { LanceDataset::open(path_str).await }) {
        Ok(dataset) => {
            let boxed = Box::new(Dataset { inner: dataset });
            *dataset_out = Box::into_raw(boxed);
            LanceDBError::Success
        }
        Err(e) => {
            // Convert lance::Error to lancedb::error::Error for handle_error
            let error_msg = format!("{}", e);
            if !error_message.is_null() {
                if let Ok(c_str) = std::ffi::CString::new(error_msg) {
                    *error_message = c_str.into_raw();
                }
            }
            LanceDBError::Lance
        }
    }
}

/// Get dataset schema as Arrow C ABI
///
/// # Safety
/// - `dataset` must be a valid pointer from `lance_dataset_open`
/// - `schema_out` must be a valid pointer
/// - Caller is responsible for releasing the schema using Arrow C ABI
///
/// # Returns
/// - Success with schema in `schema_out`
/// - Error code on failure
#[no_mangle]
pub unsafe extern "C" fn lance_dataset_schema(
    dataset: *const Dataset,
    schema_out: *mut *mut FFI_ArrowSchema,
    error_message: *mut *mut c_char,
) -> LanceDBError {
    if dataset.is_null() || schema_out.is_null() {
        set_invalid_argument_message(error_message);
        return LanceDBError::InvalidArgument;
    }

    let dataset = &(*dataset).inner;
    
    match dataset.schema() {
        Ok(schema) => {
            match FFI_ArrowSchema::try_from(&*schema) {
                Ok(ffi_schema) => {
                    let boxed = Box::new(ffi_schema);
                    *schema_out = Box::into_raw(boxed);
                    LanceDBError::Success
                }
                Err(e) => {
                    if !error_message.is_null() {
                        if let Ok(c_str) = std::ffi::CString::new(format!("{}", e)) {
                            *error_message = c_str.into_raw();
                        }
                    }
                    LanceDBError::Arrow
                }
            }
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

/// Count rows in dataset
///
/// # Safety
/// - `dataset` must be a valid pointer from `lance_dataset_open`
/// - `count_out` must be a valid pointer
///
/// # Returns
/// - Success with row count in `count_out`
/// - Error code on failure
#[no_mangle]
pub unsafe extern "C" fn lance_dataset_count_rows(
    dataset: *const Dataset,
    count_out: *mut i64,
    error_message: *mut *mut c_char,
) -> LanceDBError {
    if dataset.is_null() || count_out.is_null() {
        set_invalid_argument_message(error_message);
        return LanceDBError::InvalidArgument;
    }

    let dataset = &(*dataset).inner;
    let runtime = get_runtime();

    match runtime.block_on(async { dataset.count_rows(None).await }) {
        Ok(count) => {
            *count_out = count as i64;
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

/// Get dataset version
///
/// # Safety
/// - `dataset` must be a valid pointer from `lance_dataset_open`
/// - `version_out` must be a valid pointer
///
/// # Returns
/// - Success with version in `version_out`
#[no_mangle]
pub unsafe extern "C" fn lance_dataset_version(
    dataset: *const Dataset,
    version_out: *mut i64,
) -> LanceDBError {
    if dataset.is_null() || version_out.is_null() {
        return LanceDBError::InvalidArgument;
    }

    let dataset = &(*dataset).inner;
    *version_out = dataset.version().version as i64;
    LanceDBError::Success
}

/// Get dataset URI
///
/// # Safety
/// - `dataset` must be a valid pointer from `lance_dataset_open`
/// - `uri_out` must be a valid pointer
/// - Caller must free the returned string with `lancedb_free_string`
///
/// # Returns
/// - Success with URI string in `uri_out`
/// - Error code on failure
#[no_mangle]
pub unsafe extern "C" fn lance_dataset_uri(
    dataset: *const Dataset,
    uri_out: *mut *mut c_char,
    error_message: *mut *mut c_char,
) -> LanceDBError {
    if dataset.is_null() || uri_out.is_null() {
        set_invalid_argument_message(error_message);
        return LanceDBError::InvalidArgument;
    }

    let dataset = &(*dataset).inner;
    let uri = dataset.uri();

    match std::ffi::CString::new(uri) {
        Ok(c_str) => {
            *uri_out = c_str.into_raw();
            LanceDBError::Success
        }
        Err(_) => {
            set_invalid_argument_message(error_message);
            LanceDBError::InvalidInput
        }
    }
}

/// Get fragments from dataset
///
/// # Safety
/// - `dataset` must be a valid pointer from `lance_dataset_open`
/// - `fragments_out` must be a valid pointer
/// - `count_out` must be a valid pointer
/// - Caller must free the array with `lance_fragments_free`
///
/// # Returns
/// - Success with fragments array in `fragments_out` and count in `count_out`
/// - Error code on failure
#[no_mangle]
pub unsafe extern "C" fn lance_dataset_get_fragments(
    dataset: *const Dataset,
    fragments_out: *mut *mut LanceFragment,
    count_out: *mut usize,
    error_message: *mut *mut c_char,
) -> LanceDBError {
    if dataset.is_null() || fragments_out.is_null() || count_out.is_null() {
        set_invalid_argument_message(error_message);
        return LanceDBError::InvalidArgument;
    }

    let dataset = &(*dataset).inner;
    let runtime = get_runtime();

    let fragments = runtime.block_on(async { dataset.get_fragments() });

    let mut fragment_metadata: Vec<LanceFragment> = Vec::new();
    for f in fragments.iter() {
        let physical_rows = runtime.block_on(async { f.physical_rows().await.unwrap_or(0) });
        let num_deletions = runtime.block_on(async { f.count_deletions().await.unwrap_or(0) });
        fragment_metadata.push(LanceFragment {
            id: f.id() as i32,
            physical_rows,
            num_deletions,
        });
    }

    *count_out = fragment_metadata.len();

    if fragment_metadata.is_empty() {
        *fragments_out = ptr::null_mut();
    } else {
        let boxed = fragment_metadata.into_boxed_slice();
        *fragments_out = Box::into_raw(boxed) as *mut LanceFragment;
    }

    LanceDBError::Success
}

/// Free fragments array
///
/// # Safety
/// - `fragments` must be a valid pointer from `lance_dataset_get_fragments`
/// - `count` must match the count returned from `lance_dataset_get_fragments`
#[no_mangle]
pub unsafe extern "C" fn lance_fragments_free(fragments: *mut LanceFragment, count: usize) {
    if !fragments.is_null() && count > 0 {
        drop(Box::from_raw(std::slice::from_raw_parts_mut(
            fragments, count,
        )));
    }
}

/// Free dataset
///
/// # Safety
/// - `dataset` must be a valid pointer from `lance_dataset_open`
/// - `dataset` must not be used after calling this function
#[no_mangle]
pub unsafe extern "C" fn lance_dataset_free(dataset: *mut Dataset) {
    if !dataset.is_null() {
        drop(Box::from_raw(dataset));
    }
}

