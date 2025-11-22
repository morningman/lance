# LanceDB 并行扫描策略文档

## 概述

当前 LanceDB C 绑定库（v1.0）**没有直接暴露底层 Lance Fragment API**，但可以通过多种策略实现表的分片扫描和并行读取。

## Lance 格式的 Fragment 概念

Lance 表在物理存储层面由多个 **fragments**（数据片段）组成，每个 fragment 对应一个数据文件。这是 Lance 格式内置的分区机制：

- 每个 fragment 通常包含一批插入的数据
- Fragments 在底层是独立的数据文件
- Lance 支持在 fragment 级别进行并行扫描

## 当前 API 的限制

目前的 C API 没有暴露以下功能：
- ❌ `list_fragments()` - 列出表的所有 fragments
- ❌ `scan_fragment()` - 扫描特定的 fragment
- ❌ `get_fragment_count()` - 获取 fragment 数量
- ❌ `create_scanner()` - 创建可分片的 scanner

## 可行的替代方案

### 方案 1：使用 LIMIT + OFFSET 实现逻辑分片 ✅

这是**目前最实用的方案**，使用现有的 `lancedb_query_limit()` 和 `lancedb_query_offset()` API。

**优点：**
- ✅ 使用现有 API，无需修改
- ✅ 简单易实现
- ✅ 支持任意大小的分片

**缺点：**
- ⚠️ 不是真正的物理分片，有一定性能开销
- ⚠️ OFFSET 较大时性能下降

**适用场景：**
- 表数据量中等（< 1亿行）
- 不需要极致的性能
- 快速原型开发

### 方案 2：使用 WHERE 条件进行范围分片 ✅

如果数据有合适的分区键（如时间戳、ID 范围），可以使用 WHERE 条件分片。

**优点：**
- ✅ 性能较好，利用索引
- ✅ 真正的并行扫描
- ✅ 使用现有 API

**缺点：**
- ⚠️ 需要合适的分区键
- ⚠️ 需要预先知道数据分布

**适用场景：**
- 数据有自然的分区键（时间、ID、hash 等）
- 大规模数据并行处理
- 需要较好的性能

### 方案 3：扩展 C API 支持 Fragment API ⭐（推荐长期方案）

**需要在 Rust 侧添加的新 API：**

```rust
// 获取表的 fragment 信息
pub unsafe extern "C" fn lancedb_table_get_fragments(
    table: *const LanceDBTable,
    fragments_out: *mut *mut LanceDBFragment,
    count_out: *mut usize,
    error_message: *mut *mut c_char,
) -> LanceDBError;

// 扫描指定的 fragment
pub unsafe extern "C" fn lancedb_table_scan_fragment(
    table: *const LanceDBTable,
    fragment_id: usize,
    filter: *const c_char,  // optional WHERE clause
    columns: *const *const c_char,  // optional column selection
    num_columns: usize,
    result_out: *mut *mut LanceDBQueryResult,
    error_message: *mut *mut c_char,
) -> LanceDBError;

// 获取 fragment 的行数
pub unsafe extern "C" fn lancedb_fragment_count_rows(
    fragment: *const LanceDBFragment,
) -> u64;
```

**优点：**
- ✅ 真正的物理分片
- ✅ 最佳性能
- ✅ 完全利用 Lance 格式的特性

**缺点：**
- ⚠️ 需要修改 C 绑定库
- ⚠️ 增加 API 复杂度

### 方案 4：流式扫描结果天然分批 ✅

`LanceDBQueryResult` 返回的是 Arrow RecordBatch 流，每个 batch 可以看作一个分片。

**优点：**
- ✅ 已经内置在 API 中
- ✅ 适合流式处理
- ✅ 内存友好

**缺点：**
- ⚠️ 分片大小由系统控制
- ⚠️ 不支持真正的并行执行

## 性能对比

| 方案 | 并行度 | 性能 | 实现难度 | 当前可用 |
|------|--------|------|----------|----------|
| LIMIT+OFFSET | 中 | 中 | 低 | ✅ |
| WHERE 条件分片 | 高 | 高 | 中 | ✅ |
| Fragment API | 最高 | 最高 | 高 | ❌ 需要扩展 |
| RecordBatch 流 | 低 | 中 | 低 | ✅ |

## 建议

1. **短期方案**：使用方案 1 (LIMIT+OFFSET) 或方案 2 (WHERE 条件)
2. **长期方案**：向 LanceDB 项目提交 PR，添加 Fragment API 支持
3. **生产环境**：结合多种方案，根据数据特征选择最优策略

## 相关资源

- [LanceDB Rust API 文档](https://docs.rs/lancedb/)
- [Lance 格式规范](https://github.com/lancedb/lance)
- [Arrow C Data Interface](https://arrow.apache.org/docs/format/CDataInterface.html)

