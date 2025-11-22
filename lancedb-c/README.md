# LanceDB C Bindings

This directory contains comprehensive C FFI bindings for LanceDB, allowing you to use all LanceDB functionality from C and C++ applications.

## Project Structure

```
├── src/                    # Rust FFI implementation
│   ├── lib.rs              # Main library entry point
│   ├── connection.rs       # Connection management
│   ├── table.rs            # Table operations and data manipulation
│   ├── query.rs            # Complete query API implementation
│   ├── index.rs            # Index management
│   ├── dataset.rs          # Lance Dataset operations (NEW)
│   ├── scanner.rs          # Lance Scanner operations (NEW)
│   ├── error.rs            # Error handling and reporting
│   └── types.rs            # Type definitions and conversions
├── include/
│   └── lancedb.h           # Complete C header file with Arrow C ABI
├── examples/
│   ├── full.cpp            # C++ example using Arrow. Covering most of the API
│   ├── simple.cpp          # C++ example using Arrow. Similar to rust/examples/simple.rs
│   ├── dataset_read.cpp    # Lance Dataset basic reading example (NEW)
│   └── parallel_scan.cpp   # Parallel fragment reading example (NEW)
├── tests/                  # C++ unit tests using Catch2
├── Cargo.toml              # Rust crate configuration
├── CMakeLists.txt          # CMake build configuration
└── README.md               # This file
```

## Building

### Prerequisites

- Rust toolchain (rustc, cargo)
- CMake (3.15 or later)
- C++ compiler with C++20 support (gcc, clang)
- Apache Arrow C++ library
- pkg-config

### Quick Start

1. **Build everything using CMake:**
   ```bash
   mkdir -p build
   cd build
   cmake ..
   make
   ```

2. **Run the examples:**
   ```bash
   # LanceDB examples
   ./simple
   ./full
   
   # Lance Dataset examples (direct file access)
   ./dataset_read /path/to/lance/dataset
   ./parallel_scan /path/to/lance/dataset 4
   ```

### Manual Build Process

If you prefer to build manually:

1. **Build the Rust library:**
   ```bash
   cargo build --release
   ```

2. **Compile the C++ simple example with Arrow:**
   ```bash
   g++ -std=c++20 -Wall -Wextra -O2 \
       -I./include \
       $(pkg-config --cflags arrow) \
       -o examples/simple examples/simple.cpp \
       -L./target/release -llancedb \
       $(pkg-config --libs arrow) \
       -Wl,-rpath,./target/release
   ```

3. **Run the  simple example:**
   ```bash
   ./examples/simple
   ```

## API Overview

The C API provides comprehensive LanceDB functionality, plus direct Lance dataset access:

### Connection Management
- `lancedb_connect()` - Create connection builder
- `lancedb_connect_builder_execute()` - Execute connection
- `lancedb_connection_free()` - Free connection resources

### Database Operations
- `lancedb_connection_table_names()` - List all tables
- `lancedb_connection_open_table()` - Open existing table
- `lancedb_connection_drop_table()` - Delete table
- `lancedb_connection_rename_table()` - Rename table (Cloud only)
- `lancedb_connection_drop_all_tables()` - Delete all tables

### Table Operations
- `lancedb_table_create()` - Create new table with Arrow schema (returns table object)
- `lancedb_table_arrow_schema()` - Get table schema as Arrow C ABI
- `lancedb_table_version()` - Get table version
- `lancedb_table_count_rows()` - Count table rows
- `lancedb_table_add()` - Add data from Arrow RecordBatchReader
- `lancedb_table_merge_insert()` - Upsert data (insert new, update existing)
- `lancedb_table_delete()` - Delete rows with predicate
- `lancedb_table_nearest_to()` - Simple vector search function
- `lancedb_table_free()` - Free table resources

### Query Operations
- `lancedb_query_new()` - Create general query
- `lancedb_vector_query_new()` - Create vector query
- `lancedb_query_limit()` / `lancedb_vector_query_limit()` - Set result limit
- `lancedb_query_offset()` / `lancedb_vector_query_offset()` - Set result offset
- `lancedb_query_select()` / `lancedb_vector_query_select()` - Set column projection
- `lancedb_query_where_filter()` / `lancedb_vector_query_where_filter()` - Add WHERE clause
- `lancedb_vector_query_column()` - Set vector column
- `lancedb_vector_query_distance_type()` - Set distance metric
- `lancedb_vector_query_nprobes()` - Set search probes
- `lancedb_vector_query_refine_factor()` - Set refine factor
- `lancedb_vector_query_ef()` - Set HNSW ef parameter
- `lancedb_query_execute()` / `lancedb_vector_query_execute()` - Execute query
- `lancedb_query_result_to_arrow()` - Convert results to Arrow C ABI
- `lancedb_query_free()` / `lancedb_vector_query_free()` - Free query resources

### Index Management
- `lancedb_table_create_vector_index()` - Create vector index
- `lancedb_table_create_scalar_index()` - Create scalar index
- `lancedb_table_create_fts_index()` - Create full-text search index
- `lancedb_table_list_indices()` - List all table indices
- `lancedb_table_drop_index()` - Drop specific index
- `lancedb_table_optimize()` - Optimize table (compact/prune/rebuild indices)
- `lancedb_free_index_list()` - Free index list

### Lance Dataset Operations (NEW!)
Direct file-level access to Lance datasets, bypassing the LanceDB layer:

- `lance_dataset_open()` - Open dataset from file path or URI (local, S3, etc.)
- `lance_dataset_schema()` - Get dataset schema as Arrow C ABI
- `lance_dataset_count_rows()` - Count rows in dataset
- `lance_dataset_version()` - Get dataset version
- `lance_dataset_uri()` - Get dataset URI
- `lance_dataset_get_fragments()` - Get fragment metadata (for parallel reading)
- `lance_dataset_create_scanner()` - Create scanner with filters and projections
- `lance_dataset_free()` - Free dataset resources
- `lance_fragments_free()` - Free fragment array

### Scanner Operations (NEW!)
Read data from Lance datasets using scanners:

- `lance_scanner_load_next_batch()` - Load next batch from scanner
- `lance_scanner_to_arrow()` - Get current batch as Arrow C ABI
- `lance_scanner_free()` - Free scanner resources

**Use Cases for Lance Dataset API:**
- Direct file access without LanceDB database layer
- Fine-grained control over fragment-level reading
- Parallel multi-threaded reading of fragments
- Custom predicate pushdown and column projection
- Reading from object storage (S3, Azure, GCS) directly

### Error Handling
- All functions that return `LanceDBError` now accept an optional `error_message` parameter
- When provided (non-NULL), detailed error messages are populated for debugging
- Use `lancedb_free_string()` to free error message strings
- `lancedb_error_to_message()` - Convert error codes to human-readable messages

### Utility Functions
- `lancedb_connection_uri()` - Get database URI
- `lancedb_free_table_names()` - Free table names array
- `lancedb_record_batch_reader_from_arrow()` - Create reader from Arrow C ABI
- `lancedb_free_arrow_arrays()` - Free Arrow arrays
- `lancedb_free_arrow_schema()` - Free Arrow schema
- `lancedb_free_string()` - Free strings returned by LanceDB functions

