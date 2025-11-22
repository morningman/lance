// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The Lance Authors

//! Dataset FFI functions

use std::ffi::CStr;
use std::os::raw::c_char;
use std::ptr;

use arrow::ffi::FFI_ArrowSchema;
use lance::dataset::Dataset as LanceDataset;

use crate::error::{Error, ErrorCode, Result};
use crate::ffi::{c_str_to_string, export_schema, string_to_c_str};
use crate::types::{Dataset, FragmentMetadataC};
use crate::{check_null, result_to_code, RUNTIME};

/// Open a Lance dataset
///
/// # Safety
/// - `path` must be a valid null-terminated C string
/// - The returned pointer must be freed with `lance_dataset_free`
///
/// # Returns
/// - Non-null pointer on success
/// - Null pointer on failure (check error_out)
#[no_mangle]
pub unsafe extern "C" fn lance_dataset_open(
    path: *const c_char,
    dataset_out: *mut *mut Dataset,
    error_out: *mut *mut c_char,
) -> ErrorCode {
    check_null!(path);
    check_null!(dataset_out);

    let result = (|| -> Result<*mut Dataset> {
        let path_str = c_str_to_string(path)?;

        let dataset = RUNTIME.block_on(async {
            LanceDataset::open(&path_str).await
        })?;

        let boxed = Box::new(dataset);
        Ok(Box::into_raw(boxed) as *mut Dataset)
    })();

    let ptr = result_to_code!(result, error_out);
    *dataset_out = ptr;
    ErrorCode::Success
}

/// Close and free a dataset
///
/// # Safety
/// - `dataset` must be a valid pointer returned from `lance_dataset_open`
/// - `dataset` must not be used after calling this function
#[no_mangle]
pub unsafe extern "C" fn lance_dataset_free(dataset: *mut Dataset) {
    if !dataset.is_null() {
        drop(Box::from_raw(dataset as *mut LanceDataset));
    }
}

/// Get the schema of the dataset as Arrow C ABI
///
/// # Safety
/// - `dataset` must be a valid pointer
/// - `schema_out` must be a valid pointer
/// - Caller must release the schema using Arrow C ABI
#[no_mangle]
pub unsafe extern "C" fn lance_dataset_schema(
    dataset: *const Dataset,
    schema_out: *mut FFI_ArrowSchema,
    error_out: *mut *mut c_char,
) -> ErrorCode {
    check_null!(dataset);
    check_null!(schema_out);

    let dataset = &*(dataset as *const LanceDataset);

    let result = (|| -> Result<()> {
        let schema = RUNTIME.block_on(async {
            dataset.schema().await
        })?;

        let ffi_schema = export_schema(&schema)?;
        ptr::write(schema_out, ffi_schema);
        Ok(())
    })();

    result_to_code!(result, error_out);
    ErrorCode::Success
}

/// Get the number of rows in the dataset
///
/// # Safety
/// - `dataset` must be a valid pointer
#[no_mangle]
pub unsafe extern "C" fn lance_dataset_count_rows(
    dataset: *const Dataset,
    count_out: *mut i64,
    error_out: *mut *mut c_char,
) -> ErrorCode {
    check_null!(dataset);
    check_null!(count_out);

    let dataset = &*(dataset as *const LanceDataset);

    let result = (|| -> Result<i64> {
        let count = RUNTIME.block_on(async {
            dataset.count_rows(None).await
        })?;

        Ok(count as i64)
    })();

    let count = result_to_code!(result, error_out);
    *count_out = count;
    ErrorCode::Success
}

/// Get the version of the dataset
///
/// # Safety
/// - `dataset` must be a valid pointer
#[no_mangle]
pub unsafe extern "C" fn lance_dataset_version(
    dataset: *const Dataset,
    version_out: *mut i64,
) -> ErrorCode {
    check_null!(dataset);
    check_null!(version_out);

    let dataset = &*(dataset as *const LanceDataset);
    *version_out = dataset.version().version as i64;

    ErrorCode::Success
}

/// Get the URI of the dataset
///
/// # Safety
/// - `dataset` must be a valid pointer
/// - Returned string must be freed with `lance_string_free`
#[no_mangle]
pub unsafe extern "C" fn lance_dataset_uri(
    dataset: *const Dataset,
    uri_out: *mut *mut c_char,
    error_out: *mut *mut c_char,
) -> ErrorCode {
    check_null!(dataset);
    check_null!(uri_out);

    let dataset = &*(dataset as *const LanceDataset);

    let result = string_to_c_str(&dataset.uri());
    let uri = result_to_code!(result, error_out);
    *uri_out = uri;

    ErrorCode::Success
}

/// Get fragment metadata from the dataset
///
/// # Safety
/// - `dataset` must be a valid pointer
/// - `fragments_out` will be allocated and must be freed with `lance_fragments_free`
#[no_mangle]
pub unsafe extern "C" fn lance_dataset_get_fragments(
    dataset: *const Dataset,
    fragments_out: *mut *mut FragmentMetadataC,
    count_out: *mut usize,
    error_out: *mut *mut c_char,
) -> ErrorCode {
    check_null!(dataset);
    check_null!(fragments_out);
    check_null!(count_out);

    let dataset = &*(dataset as *const LanceDataset);

    let result = (|| -> Result<Vec<FragmentMetadataC>> {
        let fragments = RUNTIME.block_on(async {
            dataset.get_fragments()
        });

        let fragment_metadata: Vec<FragmentMetadataC> = fragments
            .iter()
            .map(|f| FragmentMetadataC {
                id: f.id() as i32,
                physical_rows: f.physical_rows() as i64,
                num_deletions: f.count_deletions().unwrap_or(0) as i64,
            })
            .collect();

        Ok(fragment_metadata)
    })();

    let fragments = result_to_code!(result, error_out);
    *count_out = fragments.len();

    if fragments.is_empty() {
        *fragments_out = ptr::null_mut();
    } else {
        let boxed = fragments.into_boxed_slice();
        *fragments_out = Box::into_raw(boxed) as *mut FragmentMetadataC;
    }

    ErrorCode::Success
}

/// Free fragment metadata array
///
/// # Safety
/// - `fragments` must be a valid pointer returned from `lance_dataset_get_fragments`
/// - `count` must match the count returned from `lance_dataset_get_fragments`
#[no_mangle]
pub unsafe extern "C" fn lance_fragments_free(fragments: *mut FragmentMetadataC, count: usize) {
    if !fragments.is_null() && count > 0 {
        drop(Box::from_raw(std::slice::from_raw_parts_mut(
            fragments, count,
        )));
    }
}

