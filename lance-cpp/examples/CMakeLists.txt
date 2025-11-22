# SPDX-License-Identifier: Apache-2.0

# Basic read example
add_executable(basic_read basic_read.cpp)
target_link_libraries(basic_read PRIVATE lance_cpp)

# Parallel read example
add_executable(parallel_read parallel_read.cpp)
target_link_libraries(parallel_read PRIVATE lance_cpp)

# Install examples
install(TARGETS basic_read parallel_read
    RUNTIME DESTINATION bin
)

