# Lance Parallel Reader Demo

A comprehensive demonstration of parallel file reading from Lance datasets using the Lance Java SDK. This project shows how to efficiently read Lance data files in parallel by leveraging both table-level and file-level APIs.

## Overview

This demo implements a complete workflow for parallel processing of Lance datasets:

1. **Table-level operations**: Use Lance Dataset API for metadata extraction and predicate pushdown
2. **File grouping**: Organize files into orthogonal groups based on fragments
3. **Parallel reading**: Use file-level APIs to read groups concurrently
4. **Data processing**: Process Arrow record batches and collect metrics

## Key Features

- **Namespace Integration**: Automatic table discovery using Lance Namespace (see [NAMESPACE_GUIDE.md](NAMESPACE_GUIDE.md))
- **Predicate Pushdown & Pruning**: File-level pruning to skip unnecessary data (see [PREDICATE_PRUNING_GUIDE.md](PREDICATE_PRUNING_GUIDE.md))
- **Orthogonal Partitioning**: Files grouped by fragment ID ensure no data overlap
- **Multi-threaded Processing**: Configurable parallelism for optimal throughput
- **Column Projection**: Read only required columns for efficiency
- **Resource Management**: Proper Arrow allocator hierarchy and cleanup
- **Metrics Collection**: Track rows, bytes, and processing time

## Project Structure

```
java-demo/
├── pom.xml                                  # Maven configuration
├── README.md                                # This file
├── NAMESPACE_GUIDE.md                       # Namespace usage guide (中文)
├── PREDICATE_PRUNING_GUIDE.md               # Predicate pruning guide (中文)
└── src/main/java/com/lance/demo/
    ├── ParallelLanceReader.java            # Main demo and orchestrator
    ├── NamespaceAwareReader.java           # Namespace integration examples
    ├── PredicatePruning.java               # Predicate pruning strategies
    ├── FileGroup.java                      # File grouping model
    ├── FileGroupReader.java                # Worker for parallel reading
    └── LanceDataProcessor.java             # Data processing utilities
```

## Architecture

### Data Flow

```
Lance Dataset (Table Level)
    ↓
Get Fragments + Apply Predicate
    ↓
Create File Groups (One per Fragment)
    ↓
Distribute to Thread Pool
    ↓
File-Level Reading (LanceFileReader)
    ↓
Process Arrow Batches
    ↓
Aggregate Results
```

### Key Concepts

#### 1. Fragment-based Grouping

Lance datasets are organized into **fragments**, which are logical partitions of data. Each fragment contains one or more data files. By grouping files by fragment ID, we ensure:

- **No data overlap** between groups (orthogonal partitioning)
- **Balanced workload** as fragments typically have similar sizes
- **Natural parallelism** that aligns with Lance's internal structure

#### 2. Two-Level API Usage

**Table Level (Dataset API)**:
- Open dataset: `Dataset.open(path, allocator)`
- Get fragments: `dataset.getFragments()`
- Extract metadata: fragment IDs, file paths, row counts
- Apply predicates for fragment pruning

**File Level (LanceFileReader API)**:
- Open individual files: `LanceFileReader.open(filePath, allocator)`
- Create scanners: `reader.newScan()` or `reader.newScan(columns)`
- Read batches: `scanner.loadNextBatch()`
- Access data: `scanner.getVectorSchemaRoot()`

#### 3. Parallel Execution Model

```java
ExecutorService executor = Executors.newFixedThreadPool(parallelism);

for (FileGroup group : fileGroups) {
    FileGroupReader reader = new FileGroupReader(group, allocator);
    Future<GroupResult> future = executor.submit(reader);
    futures.add(future);
}

// Collect results
for (Future<GroupResult> future : futures) {
    GroupResult result = future.get();
    aggregatedResult.add(result);
}
```

Each thread:
- Gets its own child allocator for isolation
- Processes one file group independently
- Returns results through Future

## Usage Examples

### Using Namespace for Automatic Table Discovery

Instead of hardcoding dataset paths, you can use **Lance Namespace** to automatically discover table locations. See [NAMESPACE_GUIDE.md](NAMESPACE_GUIDE.md) for detailed documentation (中文).

```java
// Traditional way - manual path
String datasetPath = "/data/lance/user_events";

// With Namespace - automatic discovery
DirectoryNamespace namespace = new DirectoryNamespace();
Map<String, String> properties = new HashMap<>();
properties.put("root", "/data/lance");
namespace.initialize(properties, allocator);

DescribeTableRequest request = new DescribeTableRequest();
request.setTableName("user_events");
DescribeTableResponse response = namespace.describeTable(request);
String datasetPath = response.getLocation(); // Automatically retrieved!
```

**Benefits:**
- No hardcoded paths
- Support for S3, Azure, GCS
- Dynamic table discovery
- Easy multi-environment configuration

For complete examples with S3, Azure, REST catalog, and more, see [NamespaceAwareReader.java](src/main/java/com/lance/demo/NamespaceAwareReader.java).

### Example 1: Basic Parallel Reading

```java
String datasetPath = "/path/to/lance/dataset";

try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
    ParallelLanceReader reader = new ParallelLanceReader(
        datasetPath,
        allocator,
        4  // 4 parallel threads
    );

    ParallelLanceReader.Config config = 
        new ParallelLanceReader.Config(datasetPath);
    
    ParallelLanceReader.AggregatedResult result = reader.execute(config);

    System.out.println("Total rows read: " + result.getTotalRowsRead());
    System.out.println("Throughput: " + result.getThroughputRowsPerSec() + " rows/sec");
}
```

### Example 2: Reading with Predicate Pushdown

```java
try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
    ParallelLanceReader reader = new ParallelLanceReader(
        datasetPath,
        allocator,
        4
    );

    // Apply predicate at table level for fragment filtering
    ParallelLanceReader.Config config = 
        new ParallelLanceReader.Config(datasetPath)
            .withPredicate("age > 25 AND status = 'active'");

    ParallelLanceReader.AggregatedResult result = reader.execute(config);
}
```

### Example 3: Reading with Column Projection

```java
try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
    ParallelLanceReader reader = new ParallelLanceReader(
        datasetPath,
        allocator,
        4
    );

    // Only read specific columns at file level
    ParallelLanceReader.Config config = 
        new ParallelLanceReader.Config(datasetPath)
            .withColumns(Arrays.asList("id", "name", "value"))
            .withPredicate("value > 100");

    ParallelLanceReader.AggregatedResult result = reader.execute(config);
}
```

### Example 4: Custom Parallelism and Configuration

```java
try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
    int cpuCount = Runtime.getRuntime().availableProcessors();
    int parallelism = cpuCount * 2;  // Oversubscribe for I/O bound work

    ParallelLanceReader reader = new ParallelLanceReader(
        datasetPath,
        allocator,
        parallelism
    );

    ParallelLanceReader.Config config = 
        new ParallelLanceReader.Config(datasetPath)
            .withParallelism(parallelism)
            .withMetrics(true)
            .withColumns(Arrays.asList("user_id", "event_type", "timestamp"))
            .withPredicate("timestamp >= '2024-01-01'");

    ParallelLanceReader.AggregatedResult result = reader.execute(config);

    // Detailed result analysis
    for (FileGroupReader.GroupResult groupResult : result.getGroupResults()) {
        System.out.printf("Group %d: %d rows in %d ms%n",
            groupResult.getGroupId(),
            groupResult.getTotalRowsRead(),
            groupResult.getProcessingTimeMs());
    }
}
```

## Building and Running

### Prerequisites

- Java 11 or later
- Maven 3.6+
- Lance Java SDK 0.35.0 (or compatible version)

### Build

```bash
cd java-demo
mvn clean package
```

### Run Examples

```bash
# Run the main demo
mvn exec:java -Dexec.mainClass="com.lance.demo.ParallelLanceReader"

# Or run the compiled JAR
java -cp target/lance-parallel-reader-demo-1.0.0.jar com.lance.demo.ParallelLanceReader
```

### Configuration

Adjust these parameters based on your environment:

- **Parallelism**: Number of concurrent threads
  - CPU-bound: Set to number of CPU cores
  - I/O-bound: Can oversubscribe (2x or 3x CPU cores)
  
- **Allocator Memory**: Total memory for Arrow operations
  - Example: `new RootAllocator(10L * 1024 * 1024 * 1024)` for 10GB

- **Predicate**: SQL-like filter expression
  - Examples: `"age > 25"`, `"status = 'active' AND created_at >= '2024-01-01'"`

- **Columns**: List of column names to read
  - Example: `Arrays.asList("id", "name", "email")`

## Performance Tuning

### 1. Parallelism Selection

```java
// For CPU-intensive processing
int parallelism = Runtime.getRuntime().availableProcessors();

// For I/O-intensive workloads (reading from disk/network)
int parallelism = Runtime.getRuntime().availableProcessors() * 2;
```

### 2. Memory Management

```java
// Use child allocators for each thread to track memory usage
try (BufferAllocator childAllocator = 
        parentAllocator.newChildAllocator("worker-" + threadId, 0, limit)) {
    // Perform operations
    // Memory automatically released when closed
}
```

### 3. Column Projection

Reading only required columns can significantly improve performance:

```java
config.withColumns(Arrays.asList("id", "timestamp", "value"));
// Instead of reading all columns
```

### 4. Predicate Pushdown

Apply filters early to reduce data scanned:

```java
config.withPredicate("date >= '2024-01-01' AND category = 'sales'");
```

## API Reference

### ParallelLanceReader

Main orchestrator class for parallel reading.

**Constructor**:
```java
ParallelLanceReader(String datasetPath, BufferAllocator allocator, int parallelism)
```

**Methods**:
- `execute(Config config)`: Execute parallel reading with given configuration
- Returns `AggregatedResult` with statistics and results

### FileGroup

Model representing a group of files to be processed together.

**Key Methods**:
- `getGroupId()`: Unique group identifier
- `getFragmentId()`: Lance fragment ID
- `getFilePaths()`: List of file paths in this group
- `getEstimatedRowCount()`: Estimated number of rows

### FileGroupReader

Worker that reads files in a group (implements `Callable`).

**Constructor**:
```java
new FileGroupReader.Builder()
    .fileGroup(group)
    .allocator(allocator)
    .columns(columnList)  // Optional
    .collectMetrics(true)
    .build()
```

**Returns**: `GroupResult` with processing statistics

### LanceDataProcessor

Utility class for processing Arrow data and collecting metrics.

**Key Methods**:
- `processData(VectorSchemaRoot)`: Process batch and collect statistics
- `extractColumnValues(VectorSchemaRoot, String)`: Extract column as array
- `printSampleRows(VectorSchemaRoot, int)`: Print sample rows for debugging

## Design Patterns

### 1. Builder Pattern

Used for flexible object construction:

```java
FileGroup group = new FileGroup.Builder(groupId, fragmentId)
    .addFiles(filePaths)
    .estimatedRowCount(count)
    .build();
```

### 2. Resource Management

Proper cleanup using try-with-resources:

```java
try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
     Dataset dataset = Dataset.open(path, allocator);
     LanceFileReader reader = LanceFileReader.open(filePath, allocator)) {
    // Use resources
    // Automatically closed
}
```

### 3. Future Pattern

Asynchronous result collection:

```java
List<Future<GroupResult>> futures = new ArrayList<>();
for (FileGroup group : groups) {
    futures.add(executor.submit(new FileGroupReader(group, allocator)));
}

for (Future<GroupResult> future : futures) {
    GroupResult result = future.get();  // Blocking wait
    processResult(result);
}
```

## Extension Points

### Custom Data Processing

Extend `LanceDataProcessor` or create your own:

```java
public class CustomProcessor {
    public static CustomResult processData(VectorSchemaRoot root) {
        // Your custom processing logic
        return new CustomResult();
    }
}
```

### Alternative Grouping Strategies

Current implementation groups by fragment ID. You could implement:

- **Size-based grouping**: Balance workload by file size
- **Locality-based grouping**: Group files on same node/disk
- **Dynamic grouping**: Adjust groups based on runtime metrics

### Distributed Execution

The design supports distributed processing:

1. Serialize `FileGroup` objects
2. Distribute to worker nodes
3. Each node processes its groups
4. Aggregate results at coordinator

## Troubleshooting

### Out of Memory

Reduce parallelism or allocator limit:

```java
long memoryLimit = 4L * 1024 * 1024 * 1024;  // 4GB
try (BufferAllocator allocator = new RootAllocator(memoryLimit)) {
    // ...
}
```

### Slow Performance

- Increase parallelism for I/O-bound workloads
- Use column projection to reduce data transfer
- Apply predicates to filter at source

### File Not Found

Ensure file paths are absolute or correctly relative:

```java
// Fragment files use absolute paths by default
// If needed, resolve relative to dataset base path
String absolutePath = Paths.get(datasetPath, filePath).toString();
```

## License

Licensed under the Apache License, Version 2.0.

## Contributing

This is a demo project. For production use, consider:

- Error recovery and retry logic
- Progress reporting and monitoring
- Streaming results instead of collecting all in memory
- Integration with distributed computing frameworks (Spark, Flink, etc.)

## See Also

- [Lance Format Documentation](https://lancedb.github.io/lance/)
- [Apache Arrow Java Documentation](https://arrow.apache.org/docs/java/)
- [LanceDB Documentation](https://lancedb.github.io/lancedb/)

