# Lance C++ SDK - Standalone Build Guide

This guide explains how to build Lance C++ SDK as a standalone project, independent of the Lance monorepo.

## Quick Start

The project is configured for standalone compilation. Simply run:

```bash
./build.sh
```

This will:
1. Check prerequisites
2. Build Rust FFI library (downloads dependencies from GitHub)
3. Build C++ wrapper library
4. Build example programs
5. Output `liblance_cpp.a` and `liblance_cpp_ffi.a`

## Build Script Options

```bash
./build.sh [OPTIONS]

OPTIONS:
    -h, --help              Show help message
    -d, --debug             Build in Debug mode (default: Release)
    -c, --clean             Clean build before building
    -j, --jobs NUM          Number of parallel jobs (default: auto)
    --skip-rust             Skip Rust build (use existing library)
    --no-examples           Don't build example programs
    -v, --verbose           Verbose output
    --build-dir DIR         Custom build directory (default: build)
```

## Examples

### Standard Release Build

```bash
./build.sh
```

**Output:**
- `build/liblance_cpp.a` - C++ wrapper library (~500KB)
- `rust/target/release/liblance_cpp_ffi.a` - Rust FFI library (~50MB)
- `build/examples/basic_read` - Example executable
- `build/examples/parallel_read` - Example executable

### Clean Release Build

```bash
./build.sh --clean
```

### Debug Build

```bash
./build.sh --debug
```

### Fast Rebuild (Skip Rust)

If you've already built the Rust library and only changed C++ code:

```bash
./build.sh --skip-rust
```

### Parallel Build

```bash
./build.sh -j 16
```

## Prerequisites

### Required Tools

1. **CMake** (>= 3.20)
   ```bash
   # Ubuntu/Debian
   sudo apt install cmake
   
   # macOS
   brew install cmake
   ```

2. **Rust Toolchain** (>= 1.75)
   ```bash
   curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
   source $HOME/.cargo/env
   ```

3. **C++20 Compiler**
   ```bash
   # Ubuntu/Debian
   sudo apt install g++-10 build-essential
   
   # macOS
   xcode-select --install
   # or
   brew install llvm
   ```

4. **Apache Arrow C++** (>= 15.0)
   ```bash
   # Ubuntu/Debian
   # See BUILD_GUIDE.md for detailed instructions
   
   # macOS
   brew install apache-arrow
   ```

## Standalone Compilation Details

### Dependency Management

The project uses **Git dependencies** instead of local path dependencies in `rust/Cargo.toml`:

```toml
[dependencies]
# Git repository dependencies (for standalone compilation)
lance = { git = "https://github.com/lancedb/lance.git", branch = "main", features = ["substrait"] }
lance-datafusion = { git = "https://github.com/lancedb/lance.git", branch = "main" }
# ... other dependencies
```

This allows the project to be built anywhere without requiring the full Lance monorepo.

### First Build

The first build will:
1. Clone Lance dependencies from GitHub
2. Compile all Rust crates (can take 10-30 minutes)
3. Build C++ wrapper
4. Link everything together

**Be patient!** Rust compilation is slow on first build but subsequent builds are fast.

### Cargo Cache

Rust dependencies are cached in:
- Linux/macOS: `~/.cargo`
- Windows: `%USERPROFILE%\.cargo`

To clear cache and force rebuild:
```bash
rm -rf ~/.cargo/registry
rm -rf ~/.cargo/git
rm -rf rust/target
./build.sh --clean
```

## Offline Build

For offline or air-gapped environments:

1. **Prepare on connected machine:**
   ```bash
   # Build once to download dependencies
   ./build.sh
   
   # Package cargo cache
   tar czf cargo-cache.tar.gz ~/.cargo/registry ~/.cargo/git
   
   # Package project with artifacts
   tar czf lance-cpp-built.tar.gz lance-cpp/
   ```

2. **On offline machine:**
   ```bash
   # Extract cargo cache
   tar xzf cargo-cache.tar.gz -C ~/
   
   # Extract project
   tar xzf lance-cpp-built.tar.gz
   cd lance-cpp
   
   # Build (will use cached dependencies)
   ./build.sh
   ```

## Using a Specific Lance Version

### Option 1: Use a specific branch

Edit `rust/Cargo.toml`:
```toml
lance = { git = "https://github.com/lancedb/lance.git", branch = "v1.0", features = ["substrait"] }
```

### Option 2: Use a specific tag

```toml
lance = { git = "https://github.com/lancedb/lance.git", tag = "v0.35.0", features = ["substrait"] }
```

### Option 3: Use a specific commit

```toml
lance = { git = "https://github.com/lancedb/lance.git", rev = "abc1234", features = ["substrait"] }
```

### Option 4: Use local path (for development)

If you have Lance source code locally:

```toml
# Comment out git dependencies
# lance = { git = "...", features = ["substrait"] }

# Use local path
lance = { path = "../../rust/lance", features = ["substrait"] }
```

Then rebuild:
```bash
./build.sh --clean
```

## Deployment

### Copying to Another Machine

The entire `lance-cpp/` directory is self-contained and can be copied:

```bash
# On source machine
cd /path/to/lance-cpp
tar czf lance-cpp.tar.gz .

# On target machine
tar xzf lance-cpp.tar.gz
cd lance-cpp
./build.sh
```

### Distribution

To distribute the built libraries:

```bash
# Package build artifacts
mkdir -p lance-cpp-dist/lib
mkdir -p lance-cpp-dist/include

cp build/liblance_cpp.a lance-cpp-dist/lib/
cp rust/target/release/liblance_cpp_ffi.a lance-cpp-dist/lib/
cp -r include/lance lance-cpp-dist/include/

tar czf lance-cpp-dist.tar.gz lance-cpp-dist/
```

Users can then link against these libraries:
```bash
g++ -std=c++20 myapp.cpp \
    -I./lance-cpp-dist/include \
    -L./lance-cpp-dist/lib \
    -llance_cpp -llance_cpp_ffi \
    -larrow -lpthread -ldl -lm \
    -o myapp
```

## Troubleshooting

### Issue: Rust build takes too long

**Solution:** 
```bash
# Use more parallel jobs (if you have RAM)
export CARGO_BUILD_JOBS=16
./build.sh
```

### Issue: Out of memory during Rust compilation

**Solution:**
```bash
# Reduce parallel jobs
export CARGO_BUILD_JOBS=1
./build.sh
```

### Issue: Arrow not found

**Solution:**
```bash
# Specify Arrow location
cmake .. -DArrow_DIR=/path/to/arrow/lib/cmake/Arrow

# Or set PKG_CONFIG_PATH
export PKG_CONFIG_PATH=/path/to/arrow/lib/pkgconfig:$PKG_CONFIG_PATH
```

### Issue: Git clone fails

**Solution:**
```bash
# Use SSH instead of HTTPS
# Edit rust/Cargo.toml:
lance = { git = "git@github.com:lancedb/lance.git", branch = "main", features = ["substrait"] }

# Or configure git to use SSH
git config --global url."git@github.com:".insteadOf "https://github.com/"
```

### Issue: Build fails with "error: linker cc not found"

**Solution:**
```bash
# Install C compiler
sudo apt install gcc  # Ubuntu/Debian
xcode-select --install  # macOS
```

## Performance Tips

### 1. Use Release Build

Always use Release build for production:
```bash
./build.sh  # defaults to Release
```

Release builds are 10-100x faster than Debug builds.

### 2. Incremental Builds

After first build, only rebuild what changed:
```bash
# Only rebuild C++ (if Rust unchanged)
./build.sh --skip-rust

# Or use CMake directly
cd build
cmake --build . -j$(nproc)
```

### 3. Use sccache (Rust compilation cache)

```bash
# Install sccache
cargo install sccache

# Configure
export RUSTC_WRAPPER=sccache

# Build
./build.sh
```

### 4. Link-Time Optimization (LTO)

Already enabled in Release builds via `rust/Cargo.toml`:
```toml
[profile.release]
lto = true
codegen-units = 1
```

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Build Lance C++

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Install dependencies
        run: |
          sudo apt update
          sudo apt install -y cmake g++-10 libarrow-dev
      
      - name: Install Rust
        uses: actions-rs/toolchain@v1
        with:
          toolchain: stable
          override: true
      
      - name: Build
        run: ./build.sh
      
      - name: Test
        run: |
          # Add your tests here
          ./build/examples/basic_read --help
```

### Docker Build

```dockerfile
FROM ubuntu:22.04

RUN apt update && apt install -y \
    cmake g++-10 build-essential \
    curl git \
    libarrow-dev

RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
ENV PATH="/root/.cargo/bin:${PATH}"

WORKDIR /app
COPY . .

RUN ./build.sh

CMD ["./build/examples/basic_read"]
```

## Verification

After successful build, verify artifacts:

```bash
# Check libraries exist
ls -lh build/liblance_cpp.a
ls -lh rust/target/release/liblance_cpp_ffi.a

# Check symbols in library
nm build/liblance_cpp.a | grep lance_dataset_open

# Run examples
./build/examples/basic_read --help
```

## Next Steps

- See [README.md](README.md) for API documentation
- See [BUILD_GUIDE.md](BUILD_GUIDE.md) for detailed build instructions
- See [QUICKSTART.md](QUICKSTART.md) for usage examples

## Support

For issues or questions:
- GitHub Issues: https://github.com/lancedb/lance/issues
- Discord: https://discord.gg/lancedb

