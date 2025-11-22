# Lance C++ SDK

Modern C++20 SDK for reading [Lance](https://lancedb.github.io/lance/) columnar data format, optimized for ML workflows.

## Overview

Lance C++ SDK provides a high-performance C++ interface to the Lance data format through Rust FFI. It offers:

- **Modern C++ API**: C++20 with RAII, Result types, and iterators
- **Zero-copy data exchange**: Using Apache Arrow C Data Interface
- **High performance**: Direct FFI to Rust core with minimal overhead
- **Static library**: Easy integration into C++ projects
- **Full Lance features**: Scans, filters, projections, fragments

## Architecture

```
C++ Application
    ↓ (uses)
Lance C++ Wrapper (include/lance/*.hpp, src/*.cpp)
    ↓ (calls)
Rust FFI Layer (rust/src/*.rs)
    ↓ (uses)
Lance Core (Rust)
```

## Requirements

### Build Dependencies

- **CMake** >= 3.20
- **C++20 compiler**: GCC 10+, Clang 12+, or MSVC 2019+
- **Rust** >= 1.75 (with cargo)
- **Apache Arrow C++** >= 15.0

### Install Arrow (Ubuntu/Debian)

```bash
# Add Apache Arrow repository
sudo apt update
sudo apt install -y lsb-release wget
wget https://apache.jfrog.io/artifactory/arrow/$(lsb_release --id --short | tr 'A-Z' 'a-z')/apache-arrow-apt-source-latest-$(lsb_release --codename --short).deb
sudo apt install -y ./apache-arrow-apt-source-latest-*.deb
sudo apt update

# Install Arrow C++
sudo apt install -y libarrow-dev
```

### Install Arrow (macOS)

```bash
brew install apache-arrow
```

## Building

### Quick Build (Recommended)

Use the provided build script:

```bash
# Standard release build
./build.sh

# Clean build
./build.sh --clean

# Debug build
./build.sh --debug

# Show all options
./build.sh --help
```

The build script will:
1. Check all prerequisites
2. Build Rust FFI library
3. Build C++ wrapper library
4. Build example programs
5. Display all output artifacts

**Outputs:**
- `build/liblance_cpp.a` - C++ wrapper library
- `rust/target/release/liblance_cpp_ffi.a` - Rust FFI library
- `build/examples/basic_read` - Basic example
- `build/examples/parallel_read` - Parallel example

### Manual Build

```bash
# Create build directory
mkdir build && cd build

# Configure
cmake .. -DCMAKE_BUILD_TYPE=Release

# Build (this will build Rust FFI first, then C++ wrapper)
cmake --build . -j$(nproc)

# Optional: Run examples
./examples/basic_read /path/to/dataset
./examples/parallel_read /path/to/dataset 4
```

### Build Options

**Using build.sh:**
```bash
./build.sh -d          # Debug build
./build.sh -c          # Clean build
./build.sh -j 16       # Use 16 parallel jobs
./build.sh --no-examples  # Skip examples
./build.sh --skip-rust    # Skip Rust, rebuild C++ only
```

**Using CMake directly:**
- `-DBUILD_EXAMPLES=ON` (default): Build example programs
- `-DBUILD_TESTS=OFF` (default): Build tests
- `-DCMAKE_BUILD_TYPE=Release`: Release build (recommended)
- `-DCMAKE_BUILD_TYPE=Debug`: Debug build

## Usage

### Basic Example

```cpp
#include <lance/lance.hpp>
#include <iostream>

int main() {
    lance::init_logger();
    
    try {
        // Open dataset
        auto dataset = lance::Dataset::open("/path/to/dataset").unwrap();
        
        // Get basic info
        std::cout << "Rows: " << dataset.count_rows().unwrap() << std::endl;
        std::cout << "Version: " << dataset.version() << std::endl;
        
        // Create scanner
        lance::ScanOptions options;
        options.set_filter("age > 25")
               .set_columns({"id", "name", "age"})
               .set_limit(100);
        
        auto scanner = dataset.create_scanner(options).unwrap();
        
        // Read data using iterator
        for (const auto& batch : scanner) {
            std::cout << "Batch: " << batch->num_rows() << " rows" << std::endl;
        }
        
    } catch (const lance::LanceException& e) {
        std::cerr << "Error: " << e.what() << std::endl;
        return 1;
    }
    
    return 0;
}
```

### Compile Your Application

```bash
# Using CMake
find_package(Arrow REQUIRED)

add_executable(myapp main.cpp)
target_link_libraries(myapp PRIVATE lance_cpp Arrow::arrow_shared)
target_compile_features(myapp PRIVATE cxx_std_20)
```

```bash
# Or using g++ directly
g++ -std=c++20 main.cpp \
    -I/path/to/lance-cpp/include \
    -L/path/to/lance-cpp/build \
    -llance_cpp -larrow \
    -o myapp
```

### Parallel Reading Example

```cpp
#include <lance/lance.hpp>
#include <future>
#include <vector>

lance::Result<int64_t> read_fragments(
    const std::string& path,
    const std::vector<int32_t>& fragment_ids) {
    
    auto dataset = lance::Dataset::open(path).unwrap();
    
    lance::ScanOptions options;
    options.set_fragment_ids(fragment_ids)
           .set_filter("status = 'active'");
    
    auto scanner = dataset.create_scanner(options).unwrap();
    
    int64_t total_rows = 0;
    while (scanner.load_next_batch().unwrap()) {
        auto batch = scanner.current_batch().unwrap();
        total_rows += batch->num_rows();
    }
    
    return lance::Result<int64_t>(total_rows);
}

int main() {
    auto dataset = lance::Dataset::open("/path/to/dataset").unwrap();
    auto fragments = dataset.get_fragments().unwrap();
    
    // Split fragments into groups
    std::vector<std::future<lance::Result<int64_t>>> futures;
    
    for (size_t i = 0; i < fragments.size(); i += 10) {
        std::vector<int32_t> group;
        for (size_t j = i; j < std::min(i + 10, fragments.size()); ++j) {
            group.push_back(fragments[j].id);
        }
        
        futures.push_back(std::async(
            std::launch::async,
            read_fragments,
            "/path/to/dataset",
            group
        ));
    }
    
    // Collect results
    int64_t total = 0;
    for (auto& future : futures) {
        total += future.get().unwrap();
    }
    
    std::cout << "Total rows: " << total << std::endl;
}
```

## API Reference

### Dataset

```cpp
class Dataset {
    // Open a dataset
    static Result<Dataset> open(const std::string& path);
    
    // Get schema
    Result<std::shared_ptr<arrow::Schema>> schema() const;
    
    // Count rows
    Result<int64_t> count_rows() const;
    
    // Get version
    int64_t version() const;
    
    // Get URI
    Result<std::string> uri() const;
    
    // Get fragments
    Result<std::vector<FragmentMetadata>> get_fragments() const;
    
    // Create scanner
    Result<Scanner> create_scanner(const ScanOptions& options) const;
};
```

### Scanner

```cpp
class Scanner {
    // Get schema
    Result<std::shared_ptr<arrow::Schema>> schema() const;
    
    // Load next batch
    Result<bool> load_next_batch();
    
    // Get current batch
    Result<std::shared_ptr<arrow::RecordBatch>> current_batch() const;
    
    // Iterator support
    Iterator begin();
    Iterator end();
};
```

### ScanOptions

```cpp
struct ScanOptions {
    // Filter expression (SQL-like)
    std::optional<std::string> filter;
    
    // Column projection
    std::optional<std::vector<std::string>> columns;
    
    // Fragment IDs to scan
    std::optional<std::vector<int32_t>> fragment_ids;
    
    // Limit and offset
    std::optional<size_t> limit;
    std::optional<size_t> offset;
    
    // Include metadata
    bool with_row_id = false;
    bool with_row_address = false;
    
    // Batch size
    std::optional<size_t> batch_size;
    
    // Builder methods
    ScanOptions& set_filter(std::string f);
    ScanOptions& set_columns(std::vector<std::string> cols);
    ScanOptions& set_fragment_ids(std::vector<int32_t> ids);
    ScanOptions& set_limit(size_t lim);
    // ... more setters
};
```

## Error Handling

The SDK uses a `Result<T>` type for error handling:

```cpp
// Check result
auto result = dataset.count_rows();
if (result.is_ok()) {
    int64_t count = result.value();
}

// Unwrap (throws on error)
int64_t count = dataset.count_rows().unwrap();

// Exception handling
try {
    auto dataset = lance::Dataset::open(path).unwrap();
} catch (const lance::LanceException& e) {
    std::cerr << "Error: " << e.what() << std::endl;
    std::cerr << "Code: " << static_cast<int>(e.code()) << std::endl;
}
```

## Examples

See the `examples/` directory for complete examples:

- **basic_read.cpp**: Basic dataset reading
- **parallel_read.cpp**: Multi-threaded fragment reading

## Performance Tips

1. **Parallel Reading**: Split fragments across threads for better throughput
2. **Column Projection**: Only read needed columns using `set_columns()`
3. **Predicate Pushdown**: Use filters to reduce data transfer
4. **Batch Size**: Tune `set_batch_size()` for your workload
5. **Release Build**: Always use `-DCMAKE_BUILD_TYPE=Release` for production

## Comparison with Java SDK

| Feature | Java SDK | C++ SDK |
|---------|----------|---------|
| Dataset.open() | ✓ | ✓ |
| Scanner | ✓ | ✓ |
| Filters | ✓ | ✓ |
| Projections | ✓ | ✓ |
| Fragments | ✓ | ✓ |
| FileReader | ✓ | Future |
| Indexes | ✓ | Future |
| Write | ✓ | Future |

## Troubleshooting

### Build Issues

**Problem**: Cannot find Arrow

```bash
# Specify Arrow location
cmake .. -DArrow_DIR=/path/to/arrow/lib/cmake/Arrow
```

**Problem**: Rust build fails

```bash
# Clean Rust build
cd rust && cargo clean && cd ..
```

### Runtime Issues

**Problem**: Cannot load Lance dataset

- Check path is correct
- Verify Lance version compatibility
- Set `LANCE_LOG=debug` for detailed logs

## Contributing

Contributions are welcome! Please follow the Lance project guidelines.

## License

Apache License 2.0

## Standalone Build

This project can be built independently without the Lance monorepo:

```bash
# Copy project to any location
cp -r lance-cpp /path/to/anywhere

# Build
cd /path/to/anywhere
./build.sh
```

The project uses Git dependencies and will automatically download Lance from GitHub.

See [STANDALONE_BUILD.md](STANDALONE_BUILD.md) for detailed information.

## Resources

- [Lance Format](https://lancedb.github.io/lance/)
- [Lance GitHub](https://github.com/lancedb/lance)
- [Arrow C++ Documentation](https://arrow.apache.org/docs/cpp/)
- [STANDALONE_BUILD.md](STANDALONE_BUILD.md) - Standalone build guide

