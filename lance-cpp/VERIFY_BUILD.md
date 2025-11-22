# Lance C++ SDK - Build Verification Guide

This document helps verify that your build is correct and complete.

## Quick Verification

After running `./build.sh`, verify your build:

```bash
# Run verification script
./verify_build.sh
```

Or manually check:

```bash
# 1. Check libraries exist
ls -lh build/liblance_cpp.a
ls -lh rust/target/release/liblance_cpp_ffi.a

# 2. Check examples exist
ls -lh build/examples/basic_read
ls -lh build/examples/parallel_read

# 3. Check library symbols
nm build/liblance_cpp.a | grep -i dataset

# 4. Verify examples can run
./build/examples/basic_read --help 2>&1 || echo "OK if shows usage"
```

## Detailed Verification

### 1. Library Files

#### C++ Wrapper Library

```bash
file build/liblance_cpp.a
```

Expected output:
```
build/liblance_cpp.a: current ar archive
```

Size: ~300KB - 1MB

#### Rust FFI Library

```bash
file rust/target/release/liblance_cpp_ffi.a
```

Expected output:
```
rust/target/release/liblance_cpp_ffi.a: current ar archive
```

Size: ~40MB - 60MB (includes all dependencies)

### 2. Symbol Verification

#### Check C++ Symbols

```bash
# List all symbols
nm build/liblance_cpp.a

# Check for key functions
nm build/liblance_cpp.a | grep -E "Dataset|Scanner"
```

Expected symbols:
- `lance::Dataset::open`
- `lance::Dataset::create_scanner`
- `lance::Scanner::load_next_batch`
- etc.

#### Check Rust FFI Symbols

```bash
# Check for FFI functions
nm rust/target/release/liblance_cpp_ffi.a | grep lance_dataset_open
nm rust/target/release/liblance_cpp_ffi.a | grep lance_scanner_create
```

Expected symbols:
- `lance_dataset_open`
- `lance_scanner_create`
- `lance_scanner_load_next_batch`
- etc.

### 3. Header Files

```bash
# Check headers exist
ls -la include/lance/*.hpp

# Check FFI header (generated during build)
ls -la rust/include/lance/ffi/lance_ffi.h
```

Expected headers:
- `include/lance/lance.hpp`
- `include/lance/dataset.hpp`
- `include/lance/scanner.hpp`
- `include/lance/error.hpp`
- `include/lance/types.hpp`
- `rust/include/lance/ffi/lance_ffi.h` (auto-generated)

### 4. Example Programs

```bash
# Check executables exist and can run
./build/examples/basic_read --version 2>&1 || echo "OK"
./build/examples/parallel_read --version 2>&1 || echo "OK"

# Check they're dynamically linked to Arrow
ldd build/examples/basic_read | grep arrow  # Linux
otool -L build/examples/basic_read | grep arrow  # macOS
```

### 5. Compilation Test

Create a minimal test program:

```bash
cat > test_compile.cpp << 'EOF'
#include <lance/lance.hpp>
int main() {
    lance::init_logger();
    return 0;
}
EOF

# Compile
g++ -std=c++20 test_compile.cpp \
    -I./include \
    -L./build -L./rust/target/release \
    -llance_cpp -llance_cpp_ffi \
    -larrow -lpthread -ldl -lm \
    -o test_compile

# Run
./test_compile

# Clean up
rm test_compile test_compile.cpp
```

If this compiles and runs without errors, your build is correct.

## Expected Build Sizes

| Component | Size | Description |
|-----------|------|-------------|
| liblance_cpp.a | ~500KB | C++ wrapper |
| liblance_cpp_ffi.a | ~50MB | Rust FFI + all deps |
| basic_read | ~5MB | Example binary |
| parallel_read | ~5MB | Example binary |

**Note:** Sizes are approximate and vary by platform.

## Common Issues

### Issue: Libraries exist but missing symbols

**Check:**
```bash
nm build/liblance_cpp.a | wc -l
```

Should show > 100 symbols.

**Solution:** Rebuild from scratch:
```bash
./build.sh --clean
```

### Issue: Examples fail to link

**Error:**
```
undefined reference to `arrow::...`
```

**Solution:** Arrow not found. Set library path:
```bash
export LD_LIBRARY_PATH=/path/to/arrow/lib:$LD_LIBRARY_PATH  # Linux
export DYLD_LIBRARY_PATH=/path/to/arrow/lib:$DYLD_LIBRARY_PATH  # macOS
```

### Issue: Examples fail to run

**Error:**
```
error while loading shared libraries: libarrow.so: cannot open shared object file
```

**Solution:** Same as above, set `LD_LIBRARY_PATH`.

### Issue: Wrong architecture

**Check:**
```bash
file build/liblance_cpp.a
```

Should match your system (x86_64, aarch64, etc.).

**Solution:** Clean and rebuild:
```bash
./build.sh --clean
```

## Performance Verification

### Library Sizes

```bash
# Strip debug symbols from release builds
strip --strip-debug build/liblance_cpp.a
strip --strip-debug rust/target/release/liblance_cpp_ffi.a

# Check final sizes
du -h build/liblance_cpp.a
du -h rust/target/release/liblance_cpp_ffi.a
```

### Link Time

Time how long it takes to link a simple program:

```bash
time g++ -std=c++20 examples/basic_read.cpp \
    -I./include \
    -L./build -L./rust/target/release \
    -llance_cpp -llance_cpp_ffi -larrow \
    -o test_link

rm test_link
```

Should complete in < 5 seconds.

## Debugging Failed Builds

### Enable Verbose Output

```bash
./build.sh -v
```

### Check Rust Compilation

```bash
cd rust
cargo build --release --verbose
cd ..
```

### Check CMake Configuration

```bash
cd build
cmake .. -DCMAKE_BUILD_TYPE=Release -DCMAKE_VERBOSE_MAKEFILE=ON
cd ..
```

### Check Dependencies

```bash
# Rust dependencies
cd rust
cargo tree
cd ..

# CMake dependencies
cd build
cmake .. -LA
cd ..
```

## Platform-Specific Checks

### Linux

```bash
# Check GLIBC version
ldd --version

# Check for required libraries
ldd build/examples/basic_read

# Check symbols
readelf -Ws build/liblance_cpp.a
```

### macOS

```bash
# Check architecture
lipo -info build/liblance_cpp.a

# Check dependencies
otool -L build/examples/basic_read

# Check symbols
nm -g build/liblance_cpp.a
```

### Windows

```powershell
# Check library
dumpbin /ARCHIVEHEADERS build\lance_cpp.lib

# Check symbols
dumpbin /SYMBOLS build\lance_cpp.lib
```

## Success Criteria

Your build is successful if:

- [x] Both .a files exist and are > 0 bytes
- [x] Example executables exist and are > 0 bytes
- [x] `nm` shows symbols in libraries
- [x] Test compilation succeeds
- [x] Examples run (even if they show usage/error)
- [x] No "undefined symbol" errors during linking

## Next Steps

Once verified:

1. **Run examples** with real data:
   ```bash
   ./build/examples/basic_read /path/to/dataset
   ```

2. **Integrate into your project**:
   - Copy libraries to your project
   - Add include directories
   - Link against the libraries

3. **Create your application**:
   - See [QUICKSTART.md](QUICKSTART.md) for code examples
   - See [README.md](README.md) for API documentation

## Reporting Issues

If verification fails:

1. Run with verbose output:
   ```bash
   ./build.sh --clean -v
   ```

2. Collect information:
   ```bash
   uname -a
   g++ --version
   cmake --version
   rustc --version
   pkg-config --modversion arrow 2>&1 || echo "Arrow not found"
   ```

3. Report issue with:
   - Platform information
   - Build output
   - Error messages
   - Steps to reproduce

GitHub Issues: https://github.com/lancedb/lance/issues

