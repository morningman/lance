# Lance Namespace Guide

本指南介绍如何使用 Lance Namespace 来自动发现和管理 Lance 表，无需手动指定数据集路径。

## 什么是 Lance Namespace？

Lance Namespace 是一个**目录服务/元数据管理**系统，它提供：

1. **表的自动发现**：列出所有可用的表
2. **元数据查询**：获取表的位置、schema、统计信息等
3. **统一接口**：支持本地文件系统、S3、Azure、GCS 等多种存储
4. **REST API 集成**：支持远程 catalog 服务

## 为什么使用 Namespace？

### 传统方式（手动指定路径）

```java
// 需要知道确切的数据集路径
String datasetPath = "/data/lance/user_events/";
Dataset dataset = Dataset.open(datasetPath, allocator);
```

**问题**：
- 需要硬编码路径
- 路径变更需要修改代码
- 无法动态发现新表
- 多环境配置复杂（开发/测试/生产）

### 使用 Namespace

```java
// 只需要知道 namespace 根路径和表名
DirectoryNamespace namespace = new DirectoryNamespace();
namespace.initialize(properties, allocator);

// 自动获取表路径
DescribeTableRequest request = new DescribeTableRequest();
request.setTableName("user_events");
DescribeTableResponse response = namespace.describeTable(request);
String tablePath = response.getLocation();

Dataset dataset = Dataset.open(tablePath, allocator);
```

**优势**：
- 配置与代码分离
- 支持动态表发现
- 统一的元数据访问
- 易于多环境切换

## Namespace 类型

### 1. DirectoryNamespace

用于**文件系统**和**对象存储**（本地、S3、Azure、GCS）。

#### 本地文件系统示例

```java
Map<String, String> properties = new HashMap<>();
properties.put("root", "/data/lance-datasets");
properties.put("manifest_enabled", "true");

DirectoryNamespace namespace = new DirectoryNamespace();
namespace.initialize(properties, allocator);

// 使用 namespace
String tablePath = NamespaceAwareReader.getTablePathFromDirectory(
    "/data/lance-datasets",
    "my_table",
    allocator
);
```

#### S3 示例

```java
Map<String, String> properties = new HashMap<>();
properties.put("root", "s3://my-bucket/lance-data");
properties.put("manifest_enabled", "true");
// S3 配置
properties.put("storage.region", "us-east-1");
// AWS 凭证通过环境变量或 IAM role 提供

DirectoryNamespace namespace = new DirectoryNamespace();
namespace.initialize(properties, allocator);
```

#### Azure Blob Storage 示例

```java
Map<String, String> properties = new HashMap<>();
properties.put("root", "az://container/lance-data");
properties.put("manifest_enabled", "true");
// Azure 配置
properties.put("storage.account_name", "myaccount");
properties.put("storage.account_key", "your-key");
// 或使用 SAS token
// properties.put("storage.sas_token", "your-sas-token");

DirectoryNamespace namespace = new DirectoryNamespace();
namespace.initialize(properties, allocator);
```

#### Google Cloud Storage 示例

```java
Map<String, String> properties = new HashMap<>();
properties.put("root", "gs://my-bucket/lance-data");
properties.put("manifest_enabled", "true");
// GCS 凭证通过 GOOGLE_APPLICATION_CREDENTIALS 环境变量

DirectoryNamespace namespace = new DirectoryNamespace();
namespace.initialize(properties, allocator);
```

### 2. RestNamespace

用于**远程 catalog 服务**（如 LanceDB Cloud）。

```java
Map<String, String> properties = new HashMap<>();
properties.put("uri", "https://catalog.example.com/api");
properties.put("delimiter", ".");
properties.put("header.Authorization", "Bearer your-token");

RestNamespace namespace = new RestNamespace();
namespace.initialize(properties, allocator);

String tablePath = NamespaceAwareReader.getTablePathFromRest(
    "https://catalog.example.com/api",
    "my_table",
    "your-token",
    allocator
);
```

## 核心 API

### 1. 列出所有表

```java
ListTablesRequest request = new ListTablesRequest();
ListTablesResponse response = namespace.listTables(request);

for (String tableName : response.getTableNames()) {
    System.out.println("Table: " + tableName);
}
```

### 2. 获取表的详细信息

```java
DescribeTableRequest request = new DescribeTableRequest();
request.setTableName("user_events");

DescribeTableResponse response = namespace.describeTable(request);

System.out.println("Location: " + response.getLocation());
System.out.println("Schema: " + response.getSchema());
// 其他元数据...
```

### 3. 检查表是否存在

```java
TableExistsRequest request = new TableExistsRequest();
request.setTableName("my_table");

try {
    namespace.tableExists(request);
    System.out.println("Table exists");
} catch (Exception e) {
    System.out.println("Table does not exist");
}
```

## 集成到并行读取器

### 方式 1：直接使用 Namespace 获取路径

```java
try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
    // 1. 初始化 namespace
    Map<String, String> properties = new HashMap<>();
    properties.put("root", "/data/lance");
    
    try (DirectoryNamespace namespace = new DirectoryNamespace()) {
        namespace.initialize(properties, allocator);
        
        // 2. 获取表路径
        DescribeTableRequest request = new DescribeTableRequest();
        request.setTableName("events");
        DescribeTableResponse response = namespace.describeTable(request);
        String tablePath = response.getLocation();
        
        // 3. 使用路径创建并行读取器
        ParallelLanceReader reader = new ParallelLanceReader(
            tablePath,
            allocator,
            4
        );
        
        ParallelLanceReader.Config config = 
            new ParallelLanceReader.Config(tablePath);
        ParallelLanceReader.AggregatedResult result = reader.execute(config);
    }
}
```

### 方式 2：使用工厂模式

```java
try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
    Map<String, String> properties = new HashMap<>();
    properties.put("root", "/data/lance");
    
    try (DirectoryNamespace namespace = new DirectoryNamespace()) {
        namespace.initialize(properties, allocator);
        
        // 创建工厂
        NamespaceAwareReader.NamespaceReaderFactory factory = 
            new NamespaceAwareReader.NamespaceReaderFactory(
                namespace,
                allocator,
                4  // 默认并行度
            );
        
        // 通过表名直接创建读取器
        ParallelLanceReader reader = factory.createReader("events");
        
        ParallelLanceReader.Config config = 
            new ParallelLanceReader.Config("events")
                .withPredicate("timestamp >= '2024-01-01'");
        
        ParallelLanceReader.AggregatedResult result = reader.execute(config);
    }
}
```

### 方式 3：动态处理所有表

```java
try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
    Map<String, String> properties = new HashMap<>();
    properties.put("root", "/data/lance");
    
    try (DirectoryNamespace namespace = new DirectoryNamespace()) {
        namespace.initialize(properties, allocator);
        
        // 列出所有表
        ListTablesRequest request = new ListTablesRequest();
        ListTablesResponse response = namespace.listTables(request);
        
        // 处理每个表
        for (String tableName : response.getTableNames()) {
            // 获取表路径
            DescribeTableRequest descReq = new DescribeTableRequest();
            descReq.setTableName(tableName);
            DescribeTableResponse descResp = namespace.describeTable(descReq);
            String tablePath = descResp.getLocation();
            
            // 处理表
            ParallelLanceReader reader = new ParallelLanceReader(
                tablePath,
                allocator,
                4
            );
            
            ParallelLanceReader.Config config = 
                new ParallelLanceReader.Config(tablePath);
            ParallelLanceReader.AggregatedResult result = reader.execute(config);
            
            System.out.printf("Processed table '%s': %d rows%n",
                tableName, result.getTotalRowsRead());
        }
    }
}
```

## 配置选项

### DirectoryNamespace 配置

| 属性 | 说明 | 示例 |
|------|------|------|
| `root` | 根路径（必需） | `/data/lance`, `s3://bucket/path` |
| `manifest_enabled` | 启用 manifest | `"true"` / `"false"` (默认: true) |
| `dir_listing_enabled` | 启用目录列表 | `"true"` / `"false"` (默认: true) |
| `inline_optimization_enabled` | 启用内联优化 | `"true"` / `"false"` (默认: true) |
| `storage.region` | S3 区域 | `"us-east-1"` |
| `storage.account_name` | Azure 账户名 | `"myaccount"` |
| `storage.account_key` | Azure 密钥 | `"key"` |

### RestNamespace 配置

| 属性 | 说明 | 示例 |
|------|------|------|
| `uri` | REST API 端点（必需） | `"https://api.example.com"` |
| `delimiter` | Namespace 分隔符 | `"."` / `"$"` (默认: "$") |
| `header.Authorization` | 认证 Header | `"Bearer token"` |
| `tls.cert_file` | 客户端证书 | `"/path/to/cert.pem"` |
| `tls.key_file` | 客户端密钥 | `"/path/to/key.pem"` |
| `tls.ssl_ca_cert` | CA 证书 | `"/path/to/ca.pem"` |

## 最佳实践

### 1. 配置外部化

使用配置文件而不是硬编码：

```java
// application.properties
lance.namespace.type=directory
lance.namespace.root=/data/lance
lance.namespace.manifest_enabled=true

// 加载配置
Properties props = new Properties();
props.load(new FileInputStream("application.properties"));

Map<String, String> nsProperties = new HashMap<>();
nsProperties.put("root", props.getProperty("lance.namespace.root"));
nsProperties.put("manifest_enabled", 
    props.getProperty("lance.namespace.manifest_enabled"));
```

### 2. 资源管理

始终使用 try-with-resources：

```java
try (DirectoryNamespace namespace = new DirectoryNamespace()) {
    namespace.initialize(properties, allocator);
    // 使用 namespace
    // 自动关闭
}
```

### 3. 缓存表路径

如果频繁访问同一个表：

```java
Map<String, String> tablePathCache = new ConcurrentHashMap<>();

String getTablePath(String tableName) {
    return tablePathCache.computeIfAbsent(tableName, name -> {
        DescribeTableRequest request = new DescribeTableRequest();
        request.setTableName(name);
        return namespace.describeTable(request).getLocation();
    });
}
```

### 4. 多环境配置

```java
String env = System.getenv("ENVIRONMENT"); // dev, test, prod

Map<String, String> properties = new HashMap<>();
switch (env) {
    case "dev":
        properties.put("root", "/local/dev/lance");
        break;
    case "test":
        properties.put("root", "s3://test-bucket/lance");
        properties.put("storage.region", "us-west-2");
        break;
    case "prod":
        properties.put("root", "s3://prod-bucket/lance");
        properties.put("storage.region", "us-east-1");
        break;
}
```

## 完整示例

查看 `NamespaceAwareReader.java` 中的完整示例：

1. `example1LocalDirectory()` - 本地文件系统
2. `example2S3Directory()` - S3 存储
3. `example3RestCatalog()` - REST catalog
4. `example4DynamicDiscovery()` - 动态表发现
5. `example5AzureBlob()` - Azure Blob Storage

## 故障排查

### 表未找到

```java
try {
    String path = NamespaceAwareReader.getTablePathFromDirectory(
        rootPath, tableName, allocator);
} catch (Exception e) {
    // 检查表名拼写
    // 检查 namespace root 路径
    // 确认表已注册到 namespace
}
```

### 权限错误

- S3: 检查 AWS 凭证和 IAM 权限
- Azure: 检查 account key 或 SAS token
- 本地: 检查文件系统权限

### 性能优化

- 启用 manifest: `manifest_enabled=true`
- 缓存表路径避免重复查询
- 使用连接池处理多表访问

## 参考

- [Lance Namespace 文档](https://lancedb.github.io/lance/)
- [Apache Arrow Java](https://arrow.apache.org/docs/java/)
- [ParallelLanceReader.java](src/main/java/com/lance/demo/ParallelLanceReader.java)
- [NamespaceAwareReader.java](src/main/java/com/lance/demo/NamespaceAwareReader.java)

