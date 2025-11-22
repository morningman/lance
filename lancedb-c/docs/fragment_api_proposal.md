# Fragment API 提案

## 背景

Lance 格式在物理存储层面使用 **fragments**（数据片段）来组织数据。每个 fragment 是一个独立的数据文件，表由多个 fragments 组成。暴露 Fragment API 可以实现真正的物理级别并行扫描。

## 提案的新 API

### 1. Fragment 相关数据结构

```c
/**
 * Opaque handle to a LanceDB Fragment
 */
typedef struct LanceDBFragment LanceDBFragment;

/**
 * Fragment metadata
 */
typedef struct {
    unsigned long long id;              // Fragment ID
    unsigned long long row_count;       // Number of rows in this fragment
    unsigned long long physical_rows;   // Physical rows (including deleted)
    const char* physical_path;          // Path to the fragment file
    int deletion_file_exists;           // Whether deletion file exists
} LanceDBFragmentMetadata;
```

### 2. 获取 Fragment 列表

```c
/**
 * Get list of fragments in the table
 *
 * @param table - pointer to LanceDBTable
 * @param fragments_out - pointer to receive array of fragment metadata
 * @param count_out - pointer to receive count of fragments
 * @param error_message - optional pointer to receive detailed error message
 * @return Error code indicating success or failure
 *
 * The caller is responsible for freeing the returned array using
 * lancedb_free_fragment_list().
 */
LanceDBError lancedb_table_list_fragments(
    const LanceDBTable* table,
    LanceDBFragmentMetadata** fragments_out,
    size_t* count_out,
    char** error_message
);

/**
 * Free fragment list returned by lancedb_table_list_fragments
 *
 * @param fragments - array returned by lancedb_table_list_fragments
 * @param count - count returned by lancedb_table_list_fragments
 */
void lancedb_free_fragment_list(
    LanceDBFragmentMetadata* fragments,
    size_t count
);
```

### 3. 扫描特定 Fragment

```c
/**
 * Create a query for scanning a specific fragment
 *
 * @param table - pointer to LanceDBTable
 * @param fragment_id - ID of the fragment to scan
 * @return Pointer to LanceDBQuery configured for the fragment, or NULL on failure
 *
 * The returned query can be further configured with filters, column selection, etc.
 * Caller must free with lancedb_query_free().
 */
LanceDBQuery* lancedb_query_fragment(
    const LanceDBTable* table,
    unsigned long long fragment_id
);

/**
 * Scan a fragment directly with optional filter
 *
 * @param table - pointer to LanceDBTable
 * @param fragment_id - ID of the fragment to scan
 * @param filter - optional SQL WHERE clause (NULL for no filter)
 * @param columns - optional array of column names (NULL for all columns)
 * @param num_columns - number of columns (ignored if columns is NULL)
 * @param result_out - pointer to receive query result
 * @param error_message - optional pointer to receive detailed error message
 * @return Error code indicating success or failure
 *
 * This is a convenience function that combines query creation, configuration,
 * and execution in one call. The caller must free the result with
 * lancedb_query_result_free().
 */
LanceDBError lancedb_table_scan_fragment(
    const LanceDBTable* table,
    unsigned long long fragment_id,
    const char* filter,
    const char* const* columns,
    size_t num_columns,
    LanceDBQueryResult** result_out,
    char** error_message
);
```

### 4. Fragment 统计信息

```c
/**
 * Get detailed statistics for a fragment
 *
 * @param table - pointer to LanceDBTable
 * @param fragment_id - ID of the fragment
 * @param metadata_out - pointer to receive fragment metadata
 * @param error_message - optional pointer to receive detailed error message
 * @return Error code indicating success or failure
 *
 * The metadata includes row counts, physical location, and other useful info.
 * The caller is responsible for freeing string fields in the metadata.
 */
LanceDBError lancedb_fragment_get_metadata(
    const LanceDBTable* table,
    unsigned long long fragment_id,
    LanceDBFragmentMetadata* metadata_out,
    char** error_message
);
```

## 使用示例

### 示例 1：列出所有 Fragments

```cpp
LanceDBFragmentMetadata* fragments;
size_t count;
char* error = nullptr;

if (lancedb_table_list_fragments(table, &fragments, &count, &error) == LANCEDB_SUCCESS) {
    std::cout << "Table has " << count << " fragments:" << std::endl;
    
    for (size_t i = 0; i < count; i++) {
        std::cout << "Fragment " << fragments[i].id 
                  << ": " << fragments[i].row_count << " rows"
                  << ", path: " << fragments[i].physical_path << std::endl;
    }
    
    lancedb_free_fragment_list(fragments, count);
} else {
    std::cerr << "Failed to list fragments: " << error << std::endl;
    if (error) lancedb_free_string(error);
}
```

### 示例 2：并行扫描所有 Fragments

```cpp
#include <thread>
#include <vector>

void scan_fragment_worker(
    LanceDBConnection* conn,
    const std::string& table_name,
    unsigned long long fragment_id
) {
    // Open table in this thread
    LanceDBTable* table = lancedb_connection_open_table(conn, table_name.c_str());
    if (!table) return;
    
    // Scan the fragment
    LanceDBQueryResult* result = nullptr;
    char* error = nullptr;
    
    if (lancedb_table_scan_fragment(
            table, fragment_id, nullptr, nullptr, 0, &result, &error
        ) == LANCEDB_SUCCESS) {
        
        // Process results...
        struct ArrowArray** arrays;
        struct ArrowSchema* schema;
        size_t count;
        
        lancedb_query_result_to_arrow(result, 
            reinterpret_cast<FFI_ArrowArray***>(&arrays),
            reinterpret_cast<FFI_ArrowSchema**>(&schema),
            &count, &error);
        
        // ... process data ...
        
        if (arrays) lancedb_free_arrow_arrays(
            reinterpret_cast<FFI_ArrowArray**>(arrays), count);
        if (schema) lancedb_free_arrow_schema(
            reinterpret_cast<FFI_ArrowSchema*>(schema));
    }
    
    lancedb_table_free(table);
}

// Main thread
LanceDBFragmentMetadata* fragments;
size_t count;

lancedb_table_list_fragments(table, &fragments, &count, nullptr);

// Launch parallel workers
std::vector<std::thread> workers;
for (size_t i = 0; i < count; i++) {
    workers.emplace_back(
        scan_fragment_worker, 
        connection, 
        "my_table", 
        fragments[i].id
    );
}

// Wait for all workers
for (auto& worker : workers) {
    worker.join();
}

lancedb_free_fragment_list(fragments, count);
```

### 示例 3：智能分片 - 按行数均衡负载

```cpp
std::vector<std::vector<unsigned long long>> balance_fragments(
    LanceDBFragmentMetadata* fragments,
    size_t count,
    size_t num_workers
) {
    std::vector<std::vector<unsigned long long>> worker_fragments(num_workers);
    std::vector<unsigned long long> worker_loads(num_workers, 0);
    
    // Sort fragments by row count (descending)
    std::vector<size_t> indices(count);
    std::iota(indices.begin(), indices.end(), 0);
    std::sort(indices.begin(), indices.end(), [&](size_t a, size_t b) {
        return fragments[a].row_count > fragments[b].row_count;
    });
    
    // Greedy assignment: assign each fragment to the worker with minimum load
    for (size_t idx : indices) {
        size_t min_worker = std::min_element(
            worker_loads.begin(), 
            worker_loads.end()
        ) - worker_loads.begin();
        
        worker_fragments[min_worker].push_back(fragments[idx].id);
        worker_loads[min_worker] += fragments[idx].row_count;
    }
    
    return worker_fragments;
}
```

## Rust 实现参考

在 `src/table.rs` 中添加：

```rust
/// Get list of fragments
#[no_mangle]
pub unsafe extern "C" fn lancedb_table_list_fragments(
    table: *const LanceDBTable,
    fragments_out: *mut *mut LanceDBFragmentMetadata,
    count_out: *mut usize,
    error_message: *mut *mut c_char,
) -> LanceDBError {
    if table.is_null() || fragments_out.is_null() || count_out.is_null() {
        set_invalid_argument_message(error_message);
        return LanceDBError::InvalidArgument;
    }

    let tbl = &(*table).inner;
    let runtime = get_runtime();

    match runtime.block_on(async {
        // Access underlying Lance dataset
        let dataset = tbl.dataset();
        let fragments = dataset.get_fragments();
        
        let mut metadata_vec = Vec::new();
        for fragment in fragments {
            let metadata = LanceDBFragmentMetadata {
                id: fragment.id(),
                row_count: fragment.count_rows().await?,
                physical_rows: fragment.physical_rows(),
                physical_path: CString::new(fragment.path())?.into_raw(),
                deletion_file_exists: fragment.deletion_file().is_some() as i32,
            };
            metadata_vec.push(metadata);
        }
        
        Ok::<Vec<LanceDBFragmentMetadata>, lancedb::error::Error>(metadata_vec)
    }) {
        Ok(metadata_vec) => {
            *count_out = metadata_vec.len();
            
            // Allocate C array
            let array = libc::malloc(
                metadata_vec.len() * std::mem::size_of::<LanceDBFragmentMetadata>()
            ) as *mut LanceDBFragmentMetadata;
            
            if array.is_null() {
                return LanceDBError::Unknown;
            }
            
            // Copy data
            std::ptr::copy_nonoverlapping(
                metadata_vec.as_ptr(),
                array,
                metadata_vec.len()
            );
            
            *fragments_out = array;
            std::mem::forget(metadata_vec); // Prevent drop
            
            LanceDBError::Success
        }
        Err(e) => handle_error(&e, error_message),
    }
}

/// Scan specific fragment
#[no_mangle]
pub unsafe extern "C" fn lancedb_table_scan_fragment(
    table: *const LanceDBTable,
    fragment_id: u64,
    filter: *const c_char,
    columns: *const *const c_char,
    num_columns: usize,
    result_out: *mut *mut LanceDBQueryResult,
    error_message: *mut *mut c_char,
) -> LanceDBError {
    if table.is_null() || result_out.is_null() {
        set_invalid_argument_message(error_message);
        return LanceDBError::InvalidArgument;
    }

    let tbl = &(*table).inner;
    let runtime = get_runtime();

    match runtime.block_on(async {
        let dataset = tbl.dataset();
        let mut scanner = dataset
            .scan()
            .with_fragments(vec![fragment_id]);
        
        // Apply filter if provided
        if !filter.is_null() {
            let filter_str = CStr::from_ptr(filter).to_str()?;
            scanner = scanner.filter(filter_str)?;
        }
        
        // Apply column selection if provided
        if !columns.is_null() && num_columns > 0 {
            let mut col_names = Vec::new();
            for i in 0..num_columns {
                let col_ptr = *columns.add(i);
                let col_str = CStr::from_ptr(col_ptr).to_str()?;
                col_names.push(col_str.to_string());
            }
            scanner = scanner.project(&col_names)?;
        }
        
        let stream = scanner.try_into_stream().await?;
        Ok::<_, lancedb::error::Error>(stream)
    }) {
        Ok(stream) => {
            let result = Box::new(LanceDBQueryResult {
                inner: Box::new(stream),
            });
            *result_out = Box::into_raw(result);
            LanceDBError::Success
        }
        Err(e) => handle_error(&e, error_message),
    }
}
```

## 优势

1. **真正的物理并行**：每个 worker 扫描独立的数据文件
2. **无重复读取**：不像 LIMIT+OFFSET 需要跳过数据
3. **更好的负载均衡**：可以根据 fragment 大小分配任务
4. **更高效的缓存**：每个 fragment 独立缓存
5. **支持增量处理**：可以只处理新的 fragments

## 兼容性

- 向后兼容：不影响现有 API
- 可选功能：客户端可以选择使用或不使用
- 跨平台：基于标准的 Lance 格式

## 下一步

1. 在 LanceDB Rust 库中实现 Rust 侧 API
2. 添加 C FFI 绑定
3. 编写测试用例
4. 更新文档和示例
5. 提交 PR 到 LanceDB 项目

