#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Verification script for Lance C++ SDK build

set -e

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

ERRORS=0
WARNINGS=0

check_pass() {
    echo -e "${GREEN}✓${NC} $1"
}

check_fail() {
    echo -e "${RED}✗${NC} $1"
    ((ERRORS++))
}

check_warn() {
    echo -e "${YELLOW}⚠${NC} $1"
    ((WARNINGS++))
}

echo "Lance C++ SDK - Build Verification"
echo "===================================="
echo ""

# Check C++ library
echo "Checking C++ library..."
if [ -f "build/liblance_cpp.a" ]; then
    SIZE=$(du -h build/liblance_cpp.a | cut -f1)
    check_pass "C++ library exists (${SIZE})"
    
    # Check symbols
    if nm build/liblance_cpp.a | grep -q "Dataset"; then
        check_pass "C++ library contains Dataset symbols"
    else
        check_fail "C++ library missing Dataset symbols"
    fi
else
    check_fail "C++ library not found: build/liblance_cpp.a"
fi

# Check Rust FFI library
echo ""
echo "Checking Rust FFI library..."
if [ -f "rust/target/release/liblance_cpp_ffi.a" ]; then
    SIZE=$(du -h rust/target/release/liblance_cpp_ffi.a | cut -f1)
    check_pass "Rust FFI library exists (${SIZE})"
    
    # Check symbols
    if nm rust/target/release/liblance_cpp_ffi.a | grep -q "lance_dataset_open"; then
        check_pass "Rust FFI library contains lance_dataset_open"
    else
        check_fail "Rust FFI library missing lance_dataset_open"
    fi
elif [ -f "rust/target/debug/liblance_cpp_ffi.a" ]; then
    SIZE=$(du -h rust/target/debug/liblance_cpp_ffi.a | cut -f1)
    check_warn "Using debug Rust FFI library (${SIZE})"
else
    check_fail "Rust FFI library not found"
fi

# Check headers
echo ""
echo "Checking header files..."
HEADERS=(
    "include/lance/lance.hpp"
    "include/lance/dataset.hpp"
    "include/lance/scanner.hpp"
    "include/lance/error.hpp"
    "include/lance/types.hpp"
)

for header in "${HEADERS[@]}"; do
    if [ -f "$header" ]; then
        check_pass "Header exists: $header"
    else
        check_fail "Header missing: $header"
    fi
done

# Check FFI header (optional, generated during build)
if [ -f "rust/include/lance/ffi/lance_ffi.h" ]; then
    check_pass "FFI header generated: rust/include/lance/ffi/lance_ffi.h"
else
    check_warn "FFI header not found (will be generated on first CMake run)"
fi

# Check examples
echo ""
echo "Checking example programs..."
if [ -f "build/examples/basic_read" ]; then
    SIZE=$(du -h build/examples/basic_read | cut -f1)
    check_pass "basic_read exists (${SIZE})"
    
    # Try to run (expect it to fail without dataset, but should not crash)
    if ./build/examples/basic_read 2>&1 | grep -q "Usage"; then
        check_pass "basic_read shows usage message"
    else
        check_warn "basic_read may have issues"
    fi
else
    check_fail "basic_read not found"
fi

if [ -f "build/examples/parallel_read" ]; then
    SIZE=$(du -h build/examples/parallel_read | cut -f1)
    check_pass "parallel_read exists (${SIZE})"
else
    check_fail "parallel_read not found"
fi

# Check Arrow dependency
echo ""
echo "Checking Arrow dependency..."
if pkg-config --exists arrow 2>/dev/null; then
    VERSION=$(pkg-config --modversion arrow)
    check_pass "Arrow found via pkg-config (version $VERSION)"
elif [ -f "/usr/local/lib/cmake/Arrow/ArrowConfig.cmake" ] || [ -f "/opt/homebrew/lib/cmake/Arrow/ArrowConfig.cmake" ]; then
    check_pass "Arrow CMake config found"
else
    check_warn "Arrow not found in standard locations (may still work)"
fi

# Summary
echo ""
echo "===================================="
echo "Verification Summary"
echo "===================================="

if [ $ERRORS -eq 0 ]; then
    echo -e "${GREEN}✓ Build verification PASSED${NC}"
    echo ""
    echo "All checks passed! Your build is ready to use."
    echo ""
    echo "Next steps:"
    echo "  1. Run examples:"
    echo "     ./build/examples/basic_read /path/to/dataset"
    echo "  2. See QUICKSTART.md for usage examples"
    echo "  3. See README.md for API documentation"
    exit 0
else
    echo -e "${RED}✗ Build verification FAILED${NC}"
    echo ""
    echo "Errors: $ERRORS"
    if [ $WARNINGS -gt 0 ]; then
        echo "Warnings: $WARNINGS"
    fi
    echo ""
    echo "Please fix the errors above and rebuild:"
    echo "  ./build.sh --clean"
    echo ""
    echo "For help, see:"
    echo "  - BUILD_GUIDE.md"
    echo "  - STANDALONE_BUILD.md"
    echo "  - VERIFY_BUILD.md"
    exit 1
fi

