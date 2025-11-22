# LanceDB C API 表分片扫描完整指南

## 快速回答

**问题：这个库能否将一个 LanceDB 的 table 拆分成多个 split，然后分别扫描每个 split 并读取数据？**

**答案：** 
- ❌ **当前版本不直接支持 Fragment API**，无法直接获取和扫描物理 fragments
- ✅ **但可以通过多种替代方案实现分片扫描**，包括使用 LIMIT+OFFSET 和 WHERE 条件
- ⭐ **长期建议**：扩展 C API 暴露底层的 Fragment API（已提供实现提案）

---

## 目录

1. [当前状态](#当前状态)
2. [可用的解决方案](#可用的解决方案)
3. [实现示例](#实现示例)
4. [性能对比](#性能对比)
5. [未来计划](#未来计划)

---

## 当前状态

### Lance 格式的 Fragment 机制

Lance 表在底层由多个 **fragments**（数据片段）组成：
- 每个 fragment 是一个独立的数据文件
- Fragment 在创建时生成（每次 `add()` 操作可能创建新 fragment）
- Fragment 是 Lance 格式的天然分区单位

### C API 的限制

目前 `lancedb-c` 没有暴露以下 API：

```c
// ❌ 这些 API 目前不存在
lancedb_table_list_fragments()      // 列出所有 fragments
lancedb_table_scan_fragment()       // 扫描指定 fragment
lancedb_fragment_get_metadata()     // 获取 fragment 元信息
```

---

## 可用的解决方案

### 方案 1：LIMIT + OFFSET 逻辑分片 ✅

**适用场景：** 中小型表（< 1000万行），快速实现

**实现步骤：**
1. 使用 `lancedb_table_count_rows()` 获取总行数
2. 计算分片数量和每个分片的 OFFSET/LIMIT
3. 为每个分片创建独立的查询

**优点：**
- ✅ 使用现有 API，无需修改
- ✅ 实现简单
- ✅ 支持任意分片大小

**缺点：**
- ⚠️ 不是真正的物理分片
- ⚠️ OFFSET 较大时性能下降
- ⚠️ 每个查询需要扫描前面的数据

**代码示例：** 见 `examples/parallel_scan_demo.cpp` 中的 `LimitOffsetSplitScanner`

### 方案 2：WHERE 条件范围分片 ✅

**适用场景：** 大型表，数据有自然分区键（如时间、ID、hash）

**实现步骤：**
1. 确定分区键列（例如 `id`, `timestamp`, `hash_key`）
2. 计算每个分片的数据范围
3. 使用 WHERE 条件过滤每个分片的数据

**优点：**
- ✅ 性能优秀，利用索引
- ✅ 真正的并行扫描
- ✅ 无 OFFSET 开销

**缺点：**
- ⚠️ 需要合适的分区键
- ⚠️ 需要了解数据分布

**分片策略：**

**A. 范围分片（Range Partitioning）**
```sql
-- Split 0: WHERE id >= 0 AND id < 1000
-- Split 1: WHERE id >= 1000 AND id < 2000
-- Split 2: WHERE id >= 2000 AND id < 3000
```

**B. 哈希分片（Hash Partitioning）**
```sql
-- Split 0: WHERE hash(id) % 4 = 0
-- Split 1: WHERE hash(id) % 4 = 1
-- Split 2: WHERE hash(id) % 4 = 2
-- Split 3: WHERE hash(id) % 4 = 3
```

**C. 时间分片（Time Partitioning）**
```sql
-- Split 0: WHERE timestamp >= '2024-01-01' AND timestamp < '2024-02-01'
-- Split 1: WHERE timestamp >= '2024-02-01' AND timestamp < '2024-03-01'
```

**代码示例：** 见 `examples/parallel_scan_demo.cpp` 中的 `RangeBasedSplitScanner`

### 方案 3：RecordBatch 流式分片 ✅

**适用场景：** 流式处理，内存受限

**说明：** 
`LanceDBQueryResult` 返回的是 Arrow RecordBatch 流，每个 batch 天然就是一个分片。

**特点：**
- ✅ 内置在 API 中
- ✅ 内存友好
- ⚠️ 分片大小由系统控制
- ⚠️ 不支持真正的并行执行（单个流）

---

## 实现示例

### 完整示例代码

我已经创建了以下文件：

1. **`examples/parallel_scan_demo.cpp`** - 完整的演示程序
   - `LimitOffsetSplitScanner` 类：LIMIT+OFFSET 方案
   - `RangeBasedSplitScanner` 类：WHERE 条件方案
   - 包含多线程扫描示例

2. **`examples/test_select_query.cpp`** - 查询测试类
   - 演示如何使用 WHERE 条件进行查询
   - 包含多种查询场景

### 基本用法

```cpp
#include "lancedb.h"

// 连接数据库
LanceDBConnectBuilder* builder = lancedb_connect("data/my-lancedb");
LanceDBConnection* conn = lancedb_connect_builder_execute(builder);
LanceDBTable* table = lancedb_connection_open_table(conn, "my_table");

// 获取总行数
unsigned long long total_rows = lancedb_table_count_rows(table);

// 计算分片参数
size_t rows_per_split = 1000;
size_t num_splits = (total_rows + rows_per_split - 1) / rows_per_split;

// 扫描每个分片
for (size_t i = 0; i < num_splits; i++) {
    size_t offset = i * rows_per_split;
    size_t limit = std::min(rows_per_split, (size_t)(total_rows - offset));
    
    // 创建查询
    LanceDBQuery* query = lancedb_query_new(table);
    lancedb_query_limit(query, limit, nullptr);
    lancedb_query_offset(query, offset, nullptr);
    
    // 执行并处理结果
    LanceDBQueryResult* result = lancedb_query_execute(query);
    // ... 处理 result ...
}
```

### 并行执行示例

```cpp
#include <thread>
#include <vector>

void scan_split_worker(
    const std::string& db_uri,
    const std::string& table_name,
    size_t offset,
    size_t limit
) {
    // 每个线程创建独立连接
    LanceDBConnectBuilder* builder = lancedb_connect(db_uri.c_str());
    LanceDBConnection* conn = lancedb_connect_builder_execute(builder);
    LanceDBTable* table = lancedb_connection_open_table(conn, table_name.c_str());
    
    // 创建查询
    LanceDBQuery* query = lancedb_query_new(table);
    lancedb_query_limit(query, limit, nullptr);
    lancedb_query_offset(query, offset, nullptr);
    
    // 执行查询
    LanceDBQueryResult* result = lancedb_query_execute(query);
    
    // 转换为 Arrow 并处理
    struct ArrowArray** arrays;
    struct ArrowSchema* schema;
    size_t count;
    lancedb_query_result_to_arrow(result, 
        reinterpret_cast<FFI_ArrowArray***>(&arrays),
        reinterpret_cast<FFI_ArrowSchema**>(&schema),
        &count, nullptr);
    
    // ... 处理数据 ...
    
    // 清理
    lancedb_free_arrow_arrays(reinterpret_cast<FFI_ArrowArray**>(arrays), count);
    lancedb_free_arrow_schema(reinterpret_cast<FFI_ArrowSchema*>(schema));
    lancedb_table_free(table);
    lancedb_connection_free(conn);
}

int main() {
    // 计算分片
    const size_t rows_per_split = 10000;
    const size_t total_rows = 100000;  // 假设已知
    const size_t num_splits = (total_rows + rows_per_split - 1) / rows_per_split;
    
    // 启动线程
    std::vector<std::thread> workers;
    for (size_t i = 0; i < num_splits; i++) {
        size_t offset = i * rows_per_split;
        size_t limit = std::min(rows_per_split, total_rows - offset);
        
        workers.emplace_back(
            scan_split_worker,
            "data/my-lancedb",
            "my_table",
            offset,
            limit
        );
    }
    
    // 等待所有线程完成
    for (auto& worker : workers) {
        worker.join();
    }
    
    return 0;
}
```

---

## 性能对比

| 方案 | 扫描方式 | 性能 | 并行度 | 实现难度 | 当前可用 |
|------|---------|------|--------|----------|----------|
| **LIMIT+OFFSET** | 逻辑分片 | ⭐⭐⭐ | 中 | 简单 | ✅ |
| **WHERE 范围** | 索引过滤 | ⭐⭐⭐⭐ | 高 | 中等 | ✅ |
| **WHERE 哈希** | 哈希分区 | ⭐⭐⭐⭐ | 高 | 中等 | ✅ |
| **Fragment API** | 物理分片 | ⭐⭐⭐⭐⭐ | 最高 | 需要扩展 | ❌ |
| **RecordBatch 流** | 流式读取 | ⭐⭐⭐ | 低 | 简单 | ✅ |

### 性能建议

**小型表（< 100万行）：**
- 使用 LIMIT+OFFSET
- 分片大小：10,000 - 50,000 行

**中型表（100万 - 1000万行）：**
- 使用 WHERE 范围分片
- 分片数量：10 - 50 个
- 建议在分区键上创建索引

**大型表（> 1000万行）：**
- 使用 WHERE 哈希或范围分片
- 分片数量：50 - 200 个
- 必须在分区键上创建索引
- 考虑扩展 Fragment API

---

## 未来计划

### Fragment API 扩展

我已经在 `docs/fragment_api_proposal.md` 中提供了完整的 API 设计提案，包括：

**新增 API：**
```c
// 列出所有 fragments
LanceDBError lancedb_table_list_fragments(
    const LanceDBTable* table,
    LanceDBFragmentMetadata** fragments_out,
    size_t* count_out,
    char** error_message
);

// 扫描指定 fragment
LanceDBError lancedb_table_scan_fragment(
    const LanceDBTable* table,
    unsigned long long fragment_id,
    const char* filter,
    const char* const* columns,
    size_t num_columns,
    LanceDBQueryResult** result_out,
    char** error_message
);

// 获取 fragment 元信息
LanceDBError lancedb_fragment_get_metadata(
    const LanceDBTable* table,
    unsigned long long fragment_id,
    LanceDBFragmentMetadata* metadata_out,
    char** error_message
);
```

**优势：**
- ✅ 真正的物理并行
- ✅ 无重复扫描开销
- ✅ 更好的负载均衡
- ✅ 支持增量处理

**实现路径：**
1. 在 LanceDB Rust 库中实现底层 API
2. 添加 C FFI 绑定（已提供实现代码）
3. 编写测试和文档
4. 提交 PR 到 LanceDB 项目

---

## 总结

### 当前推荐方案

**快速开发：** 使用 **LIMIT+OFFSET**（方案 1）
```cpp
LimitOffsetSplitScanner scanner(conn, "my_table", 10000);
scanner.scanAllSplits();
```

**生产环境：** 使用 **WHERE 范围分片**（方案 2）
```cpp
RangeBasedSplitScanner scanner(conn, "my_table", "id");
auto splits = scanner.createRangeSplits(0, 1000000, 20);
// 并行扫描 splits
```

**大规模数据：** 考虑提交 PR 添加 **Fragment API**（方案 3）

### 关键要点

1. ✅ **可以实现分片扫描**：虽然没有直接的 Fragment API，但有多种可行方案
2. ⚠️ **性能考虑**：根据数据规模选择合适的方案
3. 🔧 **并行执行**：需要为每个线程创建独立的连接和表句柄
4. 📊 **数据分布**：了解数据特征可以优化分片策略
5. 🚀 **未来改进**：Fragment API 将带来最佳性能

### 相关文件

- 📘 **详细文档**：`docs/parallel_scan_strategies.md`
- 💻 **完整示例**：`examples/parallel_scan_demo.cpp`
- 🧪 **测试代码**：`examples/test_select_query.cpp`
- 📝 **API 提案**：`docs/fragment_api_proposal.md`

