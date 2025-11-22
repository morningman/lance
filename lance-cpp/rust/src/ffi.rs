// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The Lance Authors

//! FFI utility functions for Arrow C Data Interface

use arrow::ffi::{FFI_ArrowArray, FFI_ArrowSchema};
use arrow_array::RecordBatch;
use arrow_schema::Schema;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::ptr;

use crate::error::{Error, Result};

/// Convert C string to Rust string
///
/// # Safety
/// `s` must be a valid null-terminated C string
pub unsafe fn c_str_to_string(s: *const c_char) -> Result<String> {
    if s.is_null() {
        return Err(Error::invalid_argument("Null string pointer"));
    }

    CStr::from_ptr(s)
        .to_str()
        .map(|s| s.to_string())
        .map_err(|e| Error::invalid_argument(format!("Invalid UTF-8: {}", e)))
}

/// Convert Rust string to C string
pub fn string_to_c_str(s: &str) -> Result<*mut c_char> {
    CString::new(s)
        .map(|c_str| c_str.into_raw())
        .map_err(|e| Error::invalid_argument(format!("String contains null byte: {}", e)))
}

/// Export Arrow schema to FFI
pub fn export_schema(schema: &Schema) -> Result<FFI_ArrowSchema> {
    FFI_ArrowSchema::try_from(schema).map_err(Error::from)
}

/// Export Arrow RecordBatch to FFI
pub fn export_record_batch(
    batch: &RecordBatch,
) -> Result<(FFI_ArrowArray, FFI_ArrowSchema)> {
    let schema_ffi = export_schema(batch.schema().as_ref())?;
    let array_ffi = FFI_ArrowArray::new(batch.into());
    Ok((array_ffi, schema_ffi))
}

/// Import Arrow schema from FFI
///
/// # Safety
/// `schema` must be a valid FFI_ArrowSchema pointer
pub unsafe fn import_schema(schema: *const FFI_ArrowSchema) -> Result<Schema> {
    if schema.is_null() {
        return Err(Error::invalid_argument("Null schema pointer"));
    }

    Schema::try_from(&*schema).map_err(Error::from)
}

/// Import Arrow RecordBatch from FFI
///
/// # Safety
/// `array` and `schema` must be valid FFI pointers
pub unsafe fn import_record_batch(
    array: *const FFI_ArrowArray,
    schema: *const FFI_ArrowSchema,
) -> Result<RecordBatch> {
    if array.is_null() || schema.is_null() {
        return Err(Error::invalid_argument("Null array or schema pointer"));
    }

    let schema = import_schema(schema)?;
    let array_data = arrow::ffi::from_ffi(array.read(), &schema)?;
    let struct_array = arrow_array::StructArray::from(array_data);

    RecordBatch::try_from(struct_array).map_err(Error::from)
}

/// Helper macro for null pointer checks
#[macro_export]
macro_rules! check_null {
    ($ptr:expr) => {
        if $ptr.is_null() {
            return $crate::error::ErrorCode::InvalidArgument;
        }
    };
}

/// Helper macro for converting Rust Result to C error code
#[macro_export]
macro_rules! result_to_code {
    ($result:expr, $error_out:expr) => {
        match $result {
            Ok(value) => {
                value
            }
            Err(err) => {
                if !$error_out.is_null() {
                    *$error_out = err.to_c_string();
                }
                return err.code();
            }
        }
    };
}

