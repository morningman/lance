# Lance C++ SDK - Build Guide

Complete guide for building and using Lance C++ SDK.

## Prerequisites

### 1. Rust Toolchain

```bash
# Install Rust (if not already installed)
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env

# Verify installation
rustc --version
cargo --version
```

### 2. C++ Compiler (C++20 support required)

#### Ubuntu/Debian

```bash
sudo apt update
sudo apt install -y build-essential cmake g++-10

# Verify C++20 support
g++-10 --version
```

#### macOS

```bash
# Install Xcode Command Line Tools
xcode-select --install

# Or install via Homebrew
brew install cmake llvm

# Verify
clang++ --version
```

#### Windows

- Install Visual Studio 2019 or later with C++ support
- Install CMake from https://cmake.org/download/

### 3. Apache Arrow C++

#### Ubuntu/Debian

```bash
# Method 1: From package manager (recommended)
wget https://apache.jfrog.io/artifactory/arrow/$(lsb_release --id --short | tr 'A-Z' 'a-z')/apache-arrow-apt-source-latest-$(lsb_release --codename --short).deb
sudo apt install -y ./apache-arrow-apt-source-latest-*.deb
sudo apt update
sudo apt install -y libarrow-dev

# Method 2: Build from source
git clone https://github.com/apache/arrow.git
cd arrow/cpp
mkdir build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release -DARROW_COMPUTE=ON -DARROW_CSV=ON
make -j$(nproc)
sudo make install
```

#### macOS

```bash
# Using Homebrew
brew install apache-arrow

# Or build from source
git clone https://github.com/apache/arrow.git
cd arrow/cpp
mkdir build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release
make -j$(sysctl -n hw.ncpu)
sudo make install
```

#### Windows

```bash
# Using vcpkg
vcpkg install arrow:x64-windows

# Set environment variable
set Arrow_DIR=C:\vcpkg\installed\x64-windows\share\arrow
```

### 4. CMake (>= 3.20)

```bash
# Ubuntu/Debian
sudo apt install -y cmake

# macOS
brew install cmake

# Verify
cmake --version
```

## Building Lance C++ SDK

### Quick Build

```bash
cd lance-cpp

# Create build directory
mkdir build && cd build

# Configure (Release build)
cmake .. -DCMAKE_BUILD_TYPE=Release

# Build (this will build Rust FFI first, then C++)
cmake --build . -j$(nproc)

# The static library will be at:
# build/liblance_cpp.a (Linux/macOS)
# build/lance_cpp.lib (Windows)
```

### Build Options

```bash
# Debug build (includes debug symbols)
cmake .. -DCMAKE_BUILD_TYPE=Debug

# Release build with optimizations (recommended)
cmake .. -DCMAKE_BUILD_TYPE=Release

# Skip building examples
cmake .. -DBUILD_EXAMPLES=OFF

# Custom Rust target directory
cmake .. -DRUST_TARGET_DIR=/custom/path

# Custom Arrow installation
cmake .. -DArrow_DIR=/path/to/arrow/lib/cmake/Arrow

# Specify C++ compiler
cmake .. -DCMAKE_CXX_COMPILER=g++-10
```

### Build Steps Explained

The build process consists of two stages:

1. **Rust FFI Build**: CMake invokes `cargo build` to compile the Rust FFI layer
   - Output: `rust/target/release/liblance_cpp_ffi.a`
   - This includes all Lance core functionality

2. **C++ Wrapper Build**: CMake compiles C++ wrapper code
   - Output: `build/liblance_cpp.a`
   - This links against the Rust FFI library

### Verifying the Build

```bash
# Check libraries exist
ls -lh build/liblance_cpp.a
ls -lh rust/target/release/liblance_cpp_ffi.a

# Check examples built
ls -lh build/examples/basic_read
ls -lh build/examples/parallel_read

# Run basic example (requires a Lance dataset)
./build/examples/basic_read /path/to/dataset
```

## Using Lance C++ SDK in Your Project

### Method 1: CMake Integration (Recommended)

Create `CMakeLists.txt` in your project:

```cmake
cmake_minimum_required(VERSION 3.20)
project(MyApp)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find dependencies
find_package(Arrow REQUIRED)

# Add Lance C++ as subdirectory (if in same repo)
add_subdirectory(lance-cpp)

# Or link to installed library
# find_package(lance_cpp REQUIRED)

# Your executable
add_executable(myapp main.cpp)

target_link_libraries(myapp PRIVATE
    lance_cpp
    Arrow::arrow_shared
)
```

Build your project:

```bash
mkdir build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release
cmake --build .
```

### Method 2: Direct Compilation

```bash
# Compile your application
g++ -std=c++20 \
    -I/path/to/lance-cpp/include \
    -I/path/to/arrow/include \
    -L/path/to/lance-cpp/build \
    -L/path/to/arrow/lib \
    -L/path/to/lance-cpp/rust/target/release \
    main.cpp \
    -llance_cpp \
    -llance_cpp_ffi \
    -larrow \
    -lpthread -ldl -lm \
    -o myapp

# macOS additional flags
# -framework Security -framework CoreFoundation -framework SystemConfiguration -lresolv

# Run
./myapp
```

### Method 3: pkg-config (Linux)

Create `lance-cpp.pc`:

```ini
prefix=/usr/local
exec_prefix=${prefix}
libdir=${exec_prefix}/lib
includedir=${prefix}/include

Name: lance-cpp
Description: Lance C++ SDK
Version: 0.1.0
Libs: -L${libdir} -llance_cpp -llance_cpp_ffi
Cflags: -I${includedir}
Requires: arrow
```

Use in your build:

```bash
g++ -std=c++20 main.cpp $(pkg-config --cflags --libs lance-cpp) -o myapp
```

## Troubleshooting

### Problem: Arrow not found

```bash
# Solution 1: Specify Arrow location
cmake .. -DArrow_DIR=/usr/local/lib/cmake/Arrow

# Solution 2: Set PKG_CONFIG_PATH
export PKG_CONFIG_PATH=/usr/local/lib/pkgconfig:$PKG_CONFIG_PATH

# Solution 3: Install Arrow to standard location
sudo make install  # in Arrow build directory
sudo ldconfig      # update library cache (Linux)
```

### Problem: Rust build fails

```bash
# Clean Rust build
cd rust
cargo clean
cd ..

# Rebuild
cd build
rm -rf *
cmake .. -DCMAKE_BUILD_TYPE=Release
cmake --build .
```

### Problem: C++20 features not available

```bash
# Specify newer compiler
cmake .. -DCMAKE_CXX_COMPILER=g++-10

# Or set environment variable
export CXX=g++-10
cmake ..
```

### Problem: Linker errors with system libraries

**Linux**: Make sure you link required system libraries:
```bash
-lpthread -ldl -lm
```

**macOS**: Add required frameworks:
```bash
-framework Security -framework CoreFoundation -framework SystemConfiguration -lresolv
```

**Windows**: Link with:
```bash
ws2_32.lib userenv.lib bcrypt.lib ntdll.lib
```

### Problem: Runtime "cannot open shared object file"

```bash
# Linux: Update library path
export LD_LIBRARY_PATH=/path/to/arrow/lib:$LD_LIBRARY_PATH

# macOS: Update library path
export DYLD_LIBRARY_PATH=/path/to/arrow/lib:$DYLD_LIBRARY_PATH

# Or add to ldconfig (Linux, permanent)
echo "/path/to/arrow/lib" | sudo tee /etc/ld.so.conf.d/arrow.conf
sudo ldconfig
```

### Problem: cbindgen errors during build

```bash
# Install cbindgen
cargo install cbindgen

# Or update if already installed
cargo install --force cbindgen
```

## Performance Tips

### 1. Use Release Builds

Always use Release builds for production:

```bash
cmake .. -DCMAKE_BUILD_TYPE=Release
```

Release builds enable:
- Rust optimizations (including LTO)
- C++ optimizations (-O3)
- No debug symbols (smaller binaries)

### 2. Enable Link-Time Optimization

Rust FFI already uses LTO. For C++:

```cmake
set(CMAKE_INTERPROCEDURAL_OPTIMIZATION TRUE)
```

### 3. Profile-Guided Optimization (Advanced)

```bash
# 1. Build with instrumentation
cmake .. -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS="-fprofile-generate"
cmake --build .

# 2. Run with representative workload
./myapp

# 3. Rebuild with profiling data
cmake .. -DCMAKE_CXX_FLAGS="-fprofile-use"
cmake --build .
```

## Cross-Compilation

### ARM64 on x86_64

```bash
# Install cross-compilation tools
rustup target add aarch64-unknown-linux-gnu
sudo apt install -y g++-aarch64-linux-gnu

# Build
cd rust
cargo build --release --target aarch64-unknown-linux-gnu
cd ..

mkdir build-arm64 && cd build-arm64
cmake .. \
    -DCMAKE_SYSTEM_NAME=Linux \
    -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
    -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc \
    -DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++ \
    -DRUST_TARGET_DIR=../rust/target/aarch64-unknown-linux-gnu/release
cmake --build .
```

## Installation

```bash
cd build
sudo cmake --install .

# Libraries installed to:
# /usr/local/lib/liblance_cpp.a
# /usr/local/lib/liblance_cpp_ffi.a

# Headers installed to:
# /usr/local/include/lance/*.hpp
```

## Next Steps

- Read the [README.md](README.md) for API documentation
- Check out [examples/](examples/) for usage examples
- See the main Lance documentation at https://lancedb.github.io/lance/

## Getting Help

- GitHub Issues: https://github.com/lancedb/lance/issues
- Discord: https://discord.gg/lancedb

