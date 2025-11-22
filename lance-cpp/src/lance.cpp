// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The Lance Authors

#include "lance/lance.hpp"

extern "C" {
#include "lance_ffi.h"
}

namespace lance {

void init_logger() {
    lance_init_logger();
}

const char* version() {
    return lance_version();
}

}  // namespace lance

