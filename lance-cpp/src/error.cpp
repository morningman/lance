// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: Copyright The Lance Authors

#include "lance/error.hpp"

namespace lance {

LanceException error_from_code(ErrorCode code, const char* message) {
    std::string msg = message ? message : "Unknown error";
    return LanceException(code, msg);
}

}  // namespace lance

