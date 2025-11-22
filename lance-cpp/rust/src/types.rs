// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The Lance Authors

//! Type definitions for FFI

use std::os::raw::c_char;

/// Opaque handle to a Dataset
#[repr(C)]
pub struct Dataset {
    _private: [u8; 0],
}

/// Opaque handle to a Scanner
#[repr(C)]
pub struct Scanner {
    _private: [u8; 0],
}

/// Opaque handle to a FragmentMetadata
#[repr(C)]
pub struct FragmentMetadata {
    _private: [u8; 0],
}

/// Opaque handle to a DataFile
#[repr(C)]
pub struct DataFile {
    _private: [u8; 0],
}

/// Scan options for querying datasets
#[repr(C)]
pub struct ScanOptions {
    /// Optional filter expression (SQL-like)
    pub filter: *const c_char,
    /// Optional column projection
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
    /// Include row ID in results
    pub with_row_id: bool,
    /// Include row address in results
    pub with_row_address: bool,
    /// Batch size for reading
    pub batch_size: i64, // -1 means default
}

impl Default for ScanOptions {
    fn default() -> Self {
        Self {
            filter: std::ptr::null(),
            columns: std::ptr::null(),
            columns_count: 0,
            fragment_ids: std::ptr::null(),
            fragment_ids_count: 0,
            limit: -1,
            offset: -1,
            with_row_id: false,
            with_row_address: false,
            batch_size: -1,
        }
    }
}

/// Fragment metadata information
#[repr(C)]
pub struct FragmentMetadataC {
    pub id: i32,
    pub physical_rows: i64,
    pub num_deletions: i64,
}

/// DataFile information
#[repr(C)]
pub struct DataFileC {
    pub path: *mut c_char,
    pub file_size_bytes: i64,
}

