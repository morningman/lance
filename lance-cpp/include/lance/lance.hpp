// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The Lance Authors

/// @file lance.hpp
/// @brief Main header for Lance C++ SDK

#pragma once

#include "dataset.hpp"
#include "error.hpp"
#include "scanner.hpp"
#include "types.hpp"

namespace lance {

/// Initialize the Lance logger
///
/// This should be called once at the start of the program.
/// Log level can be controlled via the LANCE_LOG environment variable.
void init_logger();

/// Get the version of the Lance library
///
/// @return Version string
const char* version();

}  // namespace lance

