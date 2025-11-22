# Lance C++ SDK - Quick Start Guide

Get started with Lance C++ SDK in 5 minutes!

## Prerequisites

Make sure you have:
- ✅ C++20 compiler (GCC 10+, Clang 12+)
- ✅ Rust toolchain (1.75+)
- ✅ CMake (3.20+)
- ✅ Apache Arrow C++ (15.0+)

**Quick install (Ubuntu):**

```bash
# C++ compiler and CMake
sudo apt install -y build-essential cmake g++-10

# Rust
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env

# Apache Arrow
wget https://apache.jfrog.io/artifactory/arrow/$(lsb_release --id --short | tr 'A-Z' 'a-z')/apache-arrow-apt-source-latest-$(lsb_release --codename --short).deb
sudo apt install -y ./apache-arrow-apt-source-latest-*.deb
sudo apt update
sudo apt install -y libarrow-dev
```

**Quick install (macOS):**

```bash
brew install cmake llvm apache-arrow

# Rust
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
```

## Build in 3 Steps

```bash
# 1. Navigate to lance-cpp directory
cd lance-cpp

# 2. Create build directory and configure
mkdir build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release

# 3. Build (this compiles Rust FFI + C++ wrapper)
cmake --build . -j$(nproc)
```

**Expected output:**
```
[Rust build logs...]
Building lance_cpp_ffi...
Finished release [optimized] target(s)

[C++ build logs...]
Building CXX object CMakeFiles/lance_cpp.dir/src/dataset.cpp.o
...
[100%] Built target lance_cpp
```

**Verify:**
```bash
# Check libraries
ls -lh liblance_cpp.a
ls -lh ../rust/target/release/liblance_cpp_ffi.a

# Check examples
ls -lh examples/basic_read examples/parallel_read
```

## Test with Example Data

### Option 1: Use Lance example data

```bash
# Create a test Lance dataset using Python
python3 << EOF
import lance
import pyarrow as pa

# Create sample data
table = pa.table({
    'id': range(1000),
    'name': [f'user_{i}' for i in range(1000)],
    'age': [(i % 80) + 18 for i in range(1000)],
    'score': [i * 1.5 for i in range(1000)]
})

# Write to Lance format
lance.write_dataset(table, '/tmp/test_dataset')
print("Dataset created at /tmp/test_dataset")
EOF
```

### Option 2: Use existing dataset

If you already have a Lance dataset, just note its path.

## Run Your First Query

```bash
cd build

# Run basic example
./examples/basic_read /tmp/test_dataset
```

**Expected output:**
```
Opening dataset: /tmp/test_dataset

=== Dataset Info ===
URI: /tmp/test_dataset
Version: 1
Total rows: 1000

Schema:
id: int64
name: string
age: int64
score: double

=== Fragments ===
Fragment count: 1
  Fragment 0: 1000 rows, 0 deletions

=== Scanning Data ===
Batch 1: 1000 rows, 4 columns

First 5 rows of first batch:
...

=== Summary ===
Total batches read: 1
Total rows read: 1000
```

## Run Parallel Reading Test

```bash
# Run with 4 threads
./examples/parallel_read /tmp/test_dataset 4

# Run with filter
./examples/parallel_read /tmp/test_dataset 4 "age > 25"
```

## Write Your First Program

Create `hello_lance.cpp`:

```cpp
#include <lance/lance.hpp>
#include <iostream>

int main() {
    lance::init_logger();
    
    try {
        // Open dataset
        auto dataset = lance::Dataset::open("/tmp/test_dataset").unwrap();
        
        std::cout << "✅ Opened dataset" << std::endl;
        std::cout << "📊 Rows: " << dataset.count_rows().unwrap() << std::endl;
        std::cout << "📦 Version: " << dataset.version() << std::endl;
        
        // Query with filter
        lance::ScanOptions options;
        options.set_filter("age > 30")
               .set_columns({"id", "name", "age"})
               .set_limit(10);
        
        auto scanner = dataset.create_scanner(options).unwrap();
        
        std::cout << "\n🔍 Reading filtered data:" << std::endl;
        
        int count = 0;
        for (const auto& batch : scanner) {
            count++;
            std::cout << "  Batch " << count << ": " 
                      << batch->num_rows() << " rows" << std::endl;
        }
        
        std::cout << "\n✨ Success!" << std::endl;
        
    } catch (const lance::LanceException& e) {
        std::cerr << "❌ Error: " << e.what() << std::endl;
        return 1;
    }
    
    return 0;
}
```

**Compile and run:**

```bash
# In build directory
g++ -std=c++20 \
    -I../include \
    -I/usr/include/arrow \
    -L. -L../rust/target/release \
    ../hello_lance.cpp \
    -llance_cpp -llance_cpp_ffi -larrow \
    -lpthread -ldl -lm \
    -o hello_lance

# Run
./hello_lance
```

**Expected output:**
```
✅ Opened dataset
📊 Rows: 1000
📦 Version: 1

🔍 Reading filtered data:
  Batch 1: 10 rows

✨ Success!
```

## Common Use Cases

### 1. Read All Data

```cpp
auto dataset = lance::Dataset::open(path).unwrap();
auto scanner = dataset.create_scanner().unwrap();

while (scanner.load_next_batch().unwrap()) {
    auto batch = scanner.current_batch().unwrap();
    // Process batch
}
```

### 2. Filter and Project

```cpp
lance::ScanOptions options;
options.set_filter("status = 'active' AND age > 25")
       .set_columns({"id", "name", "email"});

auto scanner = dataset.create_scanner(options).unwrap();
```

### 3. Parallel Reading by Fragments

```cpp
auto fragments = dataset.get_fragments().unwrap();

std::vector<std::future<int64_t>> futures;
for (const auto& fragment : fragments) {
    futures.push_back(std::async([&]() {
        auto options = lance::ScanOptions()
            .set_fragment_ids({fragment.id});
        
        auto scanner = dataset.create_scanner(options).unwrap();
        
        int64_t rows = 0;
        for (const auto& batch : scanner) {
            rows += batch->num_rows();
        }
        return rows;
    }));
}

// Collect results
int64_t total = 0;
for (auto& f : futures) {
    total += f.get();
}
```

### 4. Using Iterator Interface

```cpp
auto scanner = dataset.create_scanner().unwrap();

// Range-based for loop
for (const auto& batch : scanner) {
    std::cout << "Processing " << batch->num_rows() << " rows" << std::endl;
    
    // Access Arrow data
    auto id_column = batch->column(0);
    // ... process columns
}
```

## Next Steps

- 📖 Read [README.md](README.md) for full API documentation
- 🔨 See [BUILD_GUIDE.md](BUILD_GUIDE.md) for advanced build options
- 💡 Explore [examples/](examples/) for more complex use cases
- 🚀 Integrate into your C++ project using CMake

## Troubleshooting

### Build fails with "Arrow not found"

```bash
# Specify Arrow location
cmake .. -DArrow_DIR=/usr/local/lib/cmake/Arrow
```

### Runtime error: "cannot open shared object"

```bash
# Linux
export LD_LIBRARY_PATH=/usr/local/lib:$LD_LIBRARY_PATH

# macOS  
export DYLD_LIBRARY_PATH=/usr/local/lib:$DYLD_LIBRARY_PATH
```

### Compiler doesn't support C++20

```bash
# Use newer compiler
cmake .. -DCMAKE_CXX_COMPILER=g++-10
```

## Getting Help

- 📚 Documentation: https://lancedb.github.io/lance/
- 💬 Discord: https://discord.gg/lancedb
- 🐛 Issues: https://github.com/lancedb/lance/issues

**Happy coding with Lance C++! 🚀**

