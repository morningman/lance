# Lance 文件级别谓词裁剪指南

本指南详细介绍如何在 Lance Java SDK 中实现文件级别的谓词裁剪（Predicate Pruning），这是提升查询性能的关键技术。

## 什么是谓词裁剪？

**谓词裁剪（Predicate Pruning）** 是一种查询优化技术，通过分析文件/Fragment 的元数据（如 min/max 统计信息），在**读取数据之前**就判断哪些文件不可能包含满足查询条件的数据，从而跳过这些文件，避免不必要的 I/O 操作。

### 裁剪层级

```
1. Table Level (表级别)
   ↓ 谓词下推到 Dataset Scanner
   
2. Fragment Level (Fragment 级别)  ⭐ 主要裁剪层级
   ↓ 基于 Fragment 元数据过滤
   
3. File Level (文件级别)
   ↓ 基于文件统计信息过滤
   
4. Row Group Level (行组级别)
   ↓ 文件内部的进一步过滤
```

## 四种实现方式

### 方式 1：Dataset Scanner 自动裁剪（推荐）⭐

**最简单、最推荐的方式**。Lance 会自动在 Fragment 级别进行裁剪。

```java
String datasetPath = "/data/lance/dataset";
String predicate = "age > 25 AND status = 'active'";

try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
     Dataset dataset = Dataset.open(datasetPath, allocator)) {
    
    // 创建带谓词的 Scanner
    ScanOptions.Builder scanBuilder = new ScanOptions.Builder();
    scanBuilder.filter(predicate);  // ⬅️ 谓词会自动下推
    ScanOptions scanOptions = scanBuilder.build();
    
    try (LanceScanner scanner = LanceScanner.create(dataset, scanOptions, allocator)) {
        long totalRows = 0;
        
        // Lance 内部已经跳过了不符合条件的 Fragments
        while (scanner.loadNextBatch()) {
            VectorSchemaRoot root = scanner.getVectorSchemaRoot();
            totalRows += root.getRowCount();
        }
        
        logger.info("Total rows: {}", totalRows);
    }
}
```

**优点**：
- ✅ 简单易用，一行代码实现
- ✅ Lance 自动优化，性能最佳
- ✅ 支持复杂的 SQL 表达式
- ✅ Fragment 级别自动裁剪

**适用场景**：
- 大多数查询场景
- 不需要了解裁剪细节
- 标准 SQL 谓词

### 方式 2：手动 Fragment 裁剪

需要更细粒度控制或收集裁剪统计信息时使用。

```java
String datasetPath = "/data/lance/dataset";

try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
     Dataset dataset = Dataset.open(datasetPath, allocator)) {
    
    // 1. 获取所有 Fragments
    List<FragmentMetadata> allFragments = dataset.getFragments();
    logger.info("Total fragments: {}", allFragments.size());
    
    // 2. 手动过滤 Fragments
    List<FragmentMetadata> selectedFragments = allFragments.stream()
        .filter(fragment -> {
            // 自定义过滤逻辑
            // 例如：基于行数、删除文件、自定义元数据等
            return fragment.getPhysicalRows() > 100 &&
                   fragment.getNumDeletions() < fragment.getPhysicalRows() * 0.5;
        })
        .collect(Collectors.toList());
    
    logger.info("Selected fragments: {}", selectedFragments.size());
    
    // 3. 只扫描选中的 Fragments
    List<Integer> fragmentIds = selectedFragments.stream()
        .map(FragmentMetadata::getId)
        .collect(Collectors.toList());
    
    ScanOptions.Builder scanBuilder = new ScanOptions.Builder();
    scanBuilder.fragmentIds(fragmentIds);
    scanBuilder.filter("age > 25");  // 同时应用谓词
    
    try (LanceScanner scanner = LanceScanner.create(
            dataset, scanBuilder.build(), allocator)) {
        // 处理数据
    }
}
```

**优点**：
- ✅ 完全控制 Fragment 选择
- ✅ 可以收集裁剪统计信息
- ✅ 支持自定义过滤逻辑
- ✅ 适合分布式场景的任务分配

**适用场景**：
- 需要了解裁剪效果
- 自定义过滤条件（如基于删除率）
- 分布式执行中的任务调度

### 方式 3：文件级别裁剪

直接在文件级别进行裁剪和读取。

```java
String datasetPath = "/data/lance/dataset";

try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
     Dataset dataset = Dataset.open(datasetPath, allocator)) {
    
    List<FragmentMetadata> fragments = dataset.getFragments();
    
    for (FragmentMetadata fragment : fragments) {
        // 先判断 Fragment 是否需要处理
        if (!shouldProcessFragment(fragment)) {
            continue;  // 跳过整个 Fragment
        }
        
        // 遍历 Fragment 中的文件
        for (DataFile dataFile : fragment.getFiles()) {
            String filePath = dataFile.getPath();
            
            // 文件级别读取
            try (LanceFileReader fileReader = LanceFileReader.open(filePath, allocator)) {
                
                long numRows = fileReader.numRows();
                
                // 可以在这里进一步判断是否需要读取这个文件
                // 例如基于文件大小、列信息等
                if (numRows < 1000) {
                    continue;  // 跳过小文件
                }
                
                // 读取文件
                List<String> columns = Arrays.asList("id", "name");
                try (ArrowReader reader = fileReader.readAll(columns, null, 1024)) {
                    while (reader.loadNextBatch()) {
                        VectorSchemaRoot root = reader.getVectorSchemaRoot();
                        // 处理数据
                    }
                }
            }
        }
    }
}
```

**优点**：
- ✅ 文件级别的完全控制
- ✅ 可以实现自定义的文件选择逻辑
- ✅ 适合特殊的优化场景

**适用场景**：
- 需要文件级别的控制
- 自定义文件选择策略
- 特殊的并行处理需求

### 方式 4：基于统计信息的裁剪（高级）

利用列级统计信息（min/max）进行精确裁剪。

```java
// 概念示例：需要 Lance 暴露列级统计信息

// 假设我们有列统计信息
class ColumnStatistics {
    Object minValue;
    Object maxValue;
    long nullCount;
}

// 查询：WHERE timestamp BETWEEN 1700000000 AND 1710000000
long queryMin = 1700000000L;
long queryMax = 1710000000L;

for (FragmentMetadata fragment : allFragments) {
    // 获取 timestamp 列的统计信息
    ColumnStatistics stats = getFragmentColumnStats(fragment, "timestamp");
    
    // 如果 Fragment 的 max < query_min 或 min > query_max，跳过
    if ((Long)stats.maxValue < queryMin || (Long)stats.minValue > queryMax) {
        logger.info("Pruned fragment {} (min={}, max={})",
            fragment.getId(), stats.minValue, stats.maxValue);
        continue;
    }
    
    // 处理这个 Fragment
}
```

**注意**：当前 Lance Java SDK 可能不直接暴露列级统计信息，需要：
1. 从底层文件格式读取元数据
2. 维护外部统计信息缓存
3. 或等待 SDK 支持

## 实际应用示例

### 场景 1：时间范围查询

```java
// 查询 2024-01 的数据
String predicate = "date >= '2024-01-01' AND date < '2024-02-01'";

try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
     Dataset dataset = Dataset.open(datasetPath, allocator)) {
    
    ScanOptions options = new ScanOptions.Builder()
        .filter(predicate)
        .build();
    
    try (LanceScanner scanner = LanceScanner.create(dataset, options, allocator)) {
        // Lance 自动跳过不在时间范围内的 Fragments
        while (scanner.loadNextBatch()) {
            // 处理数据
        }
    }
}
```

**效果**：如果数据按日期分区，可能跳过 90%+ 的 Fragments！

### 场景 2：基于删除率的过滤

```java
// 跳过删除率过高的 Fragments
List<FragmentMetadata> validFragments = allFragments.stream()
    .filter(fragment -> {
        double deletionRate = (double) fragment.getNumDeletions() / 
                             fragment.getPhysicalRows();
        
        if (deletionRate > 0.5) {
            logger.info("Skipping fragment {} with {}% deletion rate",
                fragment.getId(), deletionRate * 100);
            return false;
        }
        return true;
    })
    .collect(Collectors.toList());
```

**效果**：避免读取大量已删除的数据。

### 场景 3：结合并行读取的裁剪

```java
String datasetPath = "/data/lance/dataset";
String predicate = "user_id >= 10000 AND user_id < 20000";

try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
     Dataset dataset = Dataset.open(datasetPath, allocator)) {
    
    // 1. 应用谓词获取符合条件的 Fragments
    List<FragmentMetadata> allFragments = dataset.getFragments();
    RangePredicate rangePredicate = new RangePredicate("user_id", 10000L, 20000L);
    
    List<FragmentMetadata> selectedFragments = allFragments.stream()
        .filter(rangePredicate::shouldProcess)
        .collect(Collectors.toList());
    
    logger.info("Pruned {} out of {} fragments",
        allFragments.size() - selectedFragments.size(),
        allFragments.size());
    
    // 2. 创建文件组用于并行读取
    List<FileGroup> fileGroups = new ArrayList<>();
    int groupId = 0;
    
    for (FragmentMetadata fragment : selectedFragments) {
        FileGroup.Builder builder = new FileGroup.Builder(groupId++, fragment.getId());
        for (DataFile file : fragment.getFiles()) {
            builder.addFile(file.getPath());
        }
        builder.physicalRows(fragment.getPhysicalRows());
        fileGroups.add(builder.build());
    }
    
    // 3. 并行处理文件组
    ExecutorService executor = Executors.newFixedThreadPool(4);
    List<Future<FileGroupReader.GroupResult>> futures = new ArrayList<>();
    
    for (FileGroup group : fileGroups) {
        FileGroupReader reader = new FileGroupReader(group, allocator);
        futures.add(executor.submit(reader));
    }
    
    // 收集结果
    for (Future<FileGroupReader.GroupResult> future : futures) {
        FileGroupReader.GroupResult result = future.get();
        logger.info("Processed group: {}", result);
    }
    
    executor.shutdown();
}
```

## 性能优化技巧

### 1. 选择合适的谓词

**好的谓词**：
```java
// ✅ 基于分区列
"date >= '2024-01-01' AND date < '2024-02-01'"

// ✅ 范围查询
"user_id BETWEEN 10000 AND 20000"

// ✅ 等值查询
"status = 'active'"
```

**不好的谓词**：
```java
// ❌ 函数转换（可能无法下推）
"UPPER(name) = 'JOHN'"

// ❌ 复杂计算
"price * quantity > 1000"

// ❌ OR 条件过多
"id = 1 OR id = 2 OR id = 3 OR ..."
```

### 2. 组合多种裁剪策略

```java
// 先基于 Fragment 元数据粗过滤
List<FragmentMetadata> candidates = allFragments.stream()
    .filter(f -> f.getPhysicalRows() > 100)
    .filter(f -> f.getNumDeletions() < f.getPhysicalRows() * 0.3)
    .collect(Collectors.toList());

// 再应用谓词精确过滤
ScanOptions options = new ScanOptions.Builder()
    .fragmentIds(candidates.stream()
        .map(FragmentMetadata::getId)
        .collect(Collectors.toList()))
    .filter("timestamp >= 1700000000 AND status = 'active'")
    .build();
```

### 3. 收集和监控裁剪效果

```java
int totalFragments = allFragments.size();
int selectedFragments = selectedFragments.size();
double pruningRatio = (totalFragments - selectedFragments) / (double) totalFragments;

logger.info("Pruning statistics:");
logger.info("  Total fragments: {}", totalFragments);
logger.info("  Selected fragments: {}", selectedFragments);
logger.info("  Pruned fragments: {}", totalFragments - selectedFragments);
logger.info("  Pruning ratio: {:.2f}%", pruningRatio * 100);
```

### 4. 数据布局优化

为了最大化裁剪效果，建议：

1. **按查询模式分区**：
   ```
   按日期分区：date/yyyy-mm-dd/
   按用户 ID 范围：user_id/0-10000/, user_id/10001-20000/
   ```

2. **控制 Fragment 大小**：
   - 太大：裁剪粒度粗
   - 太小：管理开销大
   - 建议：100MB - 1GB per Fragment

3. **定期合并小文件**：
   ```java
   dataset.optimize()  // 合并小 Fragments
   ```

## 常见问题

### Q1: 为什么裁剪效果不明显？

**可能原因**：
1. 数据分布不均匀
2. 谓词列不是分区键
3. Fragment 太大，粒度粗
4. 没有列级统计信息

**解决方案**：
- 重新组织数据，按查询模式分区
- 使用合适大小的 Fragment
- 检查谓词是否能有效下推

### Q2: 如何知道哪些 Fragments 被裁剪了？

```java
List<FragmentMetadata> allFragments = dataset.getFragments();
List<Integer> prunedFragments = new ArrayList<>();

for (FragmentMetadata fragment : allFragments) {
    if (!predicate.shouldProcess(fragment)) {
        prunedFragments.add(fragment.getId());
        logger.info("Pruned fragment {} (rows: {})",
            fragment.getId(), fragment.getPhysicalRows());
    }
}

logger.info("Total pruned: {} fragments", prunedFragments.size());
```

### Q3: Dataset Scanner 和手动裁剪哪个更快？

**通常 Dataset Scanner 更快**，因为：
1. Lance 内部优化的裁剪逻辑
2. 避免了额外的 JNI 调用
3. 可能利用了更多内部元数据

**但手动裁剪在以下情况有用**：
- 需要自定义过滤逻辑
- 分布式执行需要任务分配
- 需要收集详细的裁剪统计

### Q4: 能否跳过整个文件的读取？

可以！使用方式 3（文件级别裁剪）：

```java
for (DataFile file : fragment.getFiles()) {
    // 基于文件元数据判断
    if (file.getFileSizeBytes() < 1000) {
        continue;  // 跳过小文件
    }
    
    // 或基于列信息
    int[] columnIndices = file.getColumnIndices();
    if (!containsRequiredColumns(columnIndices)) {
        continue;  // 跳过不包含所需列的文件
    }
    
    // 读取文件
    try (LanceFileReader reader = LanceFileReader.open(file.getPath(), allocator)) {
        // ...
    }
}
```

## 性能对比

假设数据集有 1000 个 Fragments，每个 100MB：

| 场景 | 无裁剪 | Fragment 裁剪 | 性能提升 |
|------|--------|---------------|----------|
| 时间范围查询（查询 1 天，数据跨 365 天） | 100GB | ~274MB | **99.7%** |
| 用户 ID 范围（10% 用户） | 100GB | ~10GB | **90%** |
| 状态过滤（20% active） | 100GB | ~20GB | **80%** |

## 总结

### 推荐实践

1. **默认使用方式 1**：Dataset Scanner 自动裁剪
2. **需要控制时使用方式 2**：手动 Fragment 裁剪
3. **特殊场景使用方式 3**：文件级别裁剪
4. **结合并行读取**：裁剪后的 Fragments 分配给线程池

### 关键要点

- ✅ 谓词裁剪是免费的性能提升
- ✅ Fragment 级别是主要裁剪粒度
- ✅ 数据布局影响裁剪效果
- ✅ 监控裁剪比例来优化查询

## 参考代码

完整示例请查看：
- [PredicatePruning.java](src/main/java/com/lance/demo/PredicatePruning.java)
- [ParallelLanceReader.java](src/main/java/com/lance/demo/ParallelLanceReader.java)

