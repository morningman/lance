// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The Lance Authors

//! Scanner FFI functions

use std::os::raw::c_char;
use std::ptr;
use std::sync::Arc;

use arrow::ffi::{FFI_ArrowArray, FFI_ArrowSchema};
use arrow_array::RecordBatch;
use lance::dataset::{Dataset as LanceDataset, Scanner as LanceScanner};

use crate::error::{Error, ErrorCode, Result};
use crate::ffi::{c_str_to_string, export_record_batch};
use crate::types::{Dataset, ScanOptions, Scanner};
use crate::{check_null, result_to_code, RUNTIME};

/// Internal scanner state
pub struct ScannerState {
    scanner: Arc<LanceScanner>,
    current_batch: Option<RecordBatch>,
}

/// Create a scanner for the dataset
///
/// # Safety
/// - `dataset` must be a valid pointer
/// - `options` must be valid (null pointers in options are allowed)
/// - Returned scanner must be freed with `lance_scanner_free`
#[no_mangle]
pub unsafe extern "C" fn lance_scanner_create(
    dataset: *const Dataset,
    options: *const ScanOptions,
    scanner_out: *mut *mut Scanner,
    error_out: *mut *mut c_char,
) -> ErrorCode {
    check_null!(dataset);
    check_null!(scanner_out);

    let dataset = &*(dataset as *const LanceDataset);

    let result = (|| -> Result<*mut Scanner> {
        let mut scan = dataset.scan();

        // Apply scan options if provided
        if !options.is_null() {
            let opts = &*options;

            // Apply filter
            if !opts.filter.is_null() {
                let filter_str = c_str_to_string(opts.filter)?;
                scan.filter(&filter_str)?;
            }

            // Apply column projection
            if !opts.columns.is_null() && opts.columns_count > 0 {
                let column_ptrs = std::slice::from_raw_parts(opts.columns, opts.columns_count);
                let columns: Result<Vec<String>> = column_ptrs
                    .iter()
                    .map(|ptr| c_str_to_string(*ptr))
                    .collect();
                scan.project(&columns?)?;
            }

            // Apply fragment IDs
            if !opts.fragment_ids.is_null() && opts.fragment_ids_count > 0 {
                let fragment_ids =
                    std::slice::from_raw_parts(opts.fragment_ids, opts.fragment_ids_count);
                scan.with_fragments(fragment_ids.iter().map(|&id| id as usize));
            }

            // Apply limit
            if opts.limit >= 0 {
                scan.limit(Some(opts.limit as usize), opts.offset.max(0) as usize)?;
            }

            // Apply row ID/address options
            if opts.with_row_id {
                scan.with_row_id();
            }
            if opts.with_row_address {
                scan.with_row_address();
            }

            // Apply batch size
            if opts.batch_size > 0 {
                scan.batch_size(opts.batch_size as usize);
            }
        }

        let lance_scanner = RUNTIME.block_on(async { scan.try_into_stream().await })?;

        let state = ScannerState {
            scanner: Arc::new(lance_scanner),
            current_batch: None,
        };

        let boxed = Box::new(state);
        Ok(Box::into_raw(boxed) as *mut Scanner)
    })();

    let ptr = result_to_code!(result, error_out);
    *scanner_out = ptr;
    ErrorCode::Success
}

/// Free a scanner
///
/// # Safety
/// - `scanner` must be a valid pointer returned from `lance_scanner_create`
/// - `scanner` must not be used after calling this function
#[no_mangle]
pub unsafe extern "C" fn lance_scanner_free(scanner: *mut Scanner) {
    if !scanner.is_null() {
        drop(Box::from_raw(scanner as *mut ScannerState));
    }
}

/// Load the next batch from the scanner
///
/// # Safety
/// - `scanner` must be a valid pointer
/// - Returns true if a batch was loaded, false if no more batches
#[no_mangle]
pub unsafe extern "C" fn lance_scanner_load_next_batch(
    scanner: *mut Scanner,
    has_next_out: *mut bool,
    error_out: *mut *mut c_char,
) -> ErrorCode {
    check_null!(scanner);
    check_null!(has_next_out);

    let state = &mut *(scanner as *mut ScannerState);

    let result = (|| -> Result<bool> {
        use futures::StreamExt;

        let batch_option = RUNTIME.block_on(async {
            state.scanner.next().await
        });

        match batch_option {
            Some(Ok(batch)) => {
                state.current_batch = Some(batch);
                Ok(true)
            }
            Some(Err(e)) => Err(Error::from(e)),
            None => {
                state.current_batch = None;
                Ok(false)
            }
        }
    })();

    let has_next = result_to_code!(result, error_out);
    *has_next_out = has_next;
    ErrorCode::Success
}

/// Get the current batch as Arrow C ABI
///
/// # Safety
/// - `scanner` must be a valid pointer
/// - Must be called after `lance_scanner_load_next_batch` returns true
/// - Caller must release the array and schema using Arrow C ABI
#[no_mangle]
pub unsafe extern "C" fn lance_scanner_current_batch(
    scanner: *const Scanner,
    array_out: *mut FFI_ArrowArray,
    schema_out: *mut FFI_ArrowSchema,
    error_out: *mut *mut c_char,
) -> ErrorCode {
    check_null!(scanner);
    check_null!(array_out);
    check_null!(schema_out);

    let state = &*(scanner as *const ScannerState);

    let result = (|| -> Result<()> {
        let batch = state
            .current_batch
            .as_ref()
            .ok_or_else(|| Error::invalid_argument("No current batch"))?;

        let (array_ffi, schema_ffi) = export_record_batch(batch)?;

        ptr::write(array_out, array_ffi);
        ptr::write(schema_out, schema_ffi);
        Ok(())
    })();

    result_to_code!(result, error_out);
    ErrorCode::Success
}

/// Get the schema of the scanner
///
/// # Safety
/// - `scanner` must be a valid pointer
/// - Caller must release the schema using Arrow C ABI
#[no_mangle]
pub unsafe extern "C" fn lance_scanner_schema(
    scanner: *const Scanner,
    schema_out: *mut FFI_ArrowSchema,
    error_out: *mut *mut c_char,
) -> ErrorCode {
    check_null!(scanner);
    check_null!(schema_out);

    let state = &*(scanner as *const ScannerState);

    let result = (|| -> Result<()> {
        let schema = RUNTIME.block_on(async { state.scanner.schema().await })?;

        let ffi_schema = crate::ffi::export_schema(&schema)?;
        ptr::write(schema_out, ffi_schema);
        Ok(())
    })();

    result_to_code!(result, error_out);
    ErrorCode::Success
}

