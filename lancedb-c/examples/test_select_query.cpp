/*
 * SPDX-License-Identifier: Apache-2.0
 * SPDX-FileCopyrightText: Copyright The LanceDB Authors
 * 
 * Test class for implementing SELECT * FROM table WHERE col=1 query
 */

#include <iostream>
#include <memory>
#include <vector>
#include <arrow/api.h>
#include <arrow/c/bridge.h>
#include "lancedb.h"

/**
 * Test class for LanceDB query operations
 * Demonstrates how to implement: SELECT * FROM table WHERE col=1
 */
class LanceDBSelectQueryTest {
private:
    LanceDBConnection* connection_;
    LanceDBTable* table_;
    std::string db_uri_;
    std::string table_name_;

public:
    /**
     * Constructor
     * @param db_uri Database URI (e.g., "/path/to/database")
     * @param table_name Name of the table to query
     */
    LanceDBSelectQueryTest(const std::string& db_uri, const std::string& table_name)
        : connection_(nullptr), table_(nullptr), db_uri_(db_uri), table_name_(table_name) {}

    /**
     * Destructor - cleanup resources
     */
    ~LanceDBSelectQueryTest() {
        cleanup();
    }

    /**
     * Connect to the LanceDB database
     * @return true on success, false on failure
     */
    bool connect() {
        // Create connection builder
        LanceDBConnectBuilder* builder = lancedb_connect(db_uri_.c_str());
        if (!builder) {
            std::cerr << "Failed to create connection builder for URI: " << db_uri_ << std::endl;
            return false;
        }

        // Execute connection
        connection_ = lancedb_connect_builder_execute(builder);
        if (!connection_) {
            std::cerr << "Failed to execute connection" << std::endl;
            lancedb_connect_builder_free(builder);
            return false;
        }

        std::cout << "Successfully connected to database: " << db_uri_ << std::endl;
        return true;
    }

    /**
     * Open the table
     * @return true on success, false on failure
     */
    bool openTable() {
        if (!connection_) {
            std::cerr << "No active connection. Call connect() first." << std::endl;
            return false;
        }

        table_ = lancedb_connection_open_table(connection_, table_name_.c_str());
        if (!table_) {
            std::cerr << "Failed to open table: " << table_name_ << std::endl;
            return false;
        }

        std::cout << "Successfully opened table: " << table_name_ << std::endl;
        return true;
    }

    /**
     * Execute SELECT * FROM table WHERE col=1 query
     * 
     * @param column_name The column name to filter on
     * @param value The value to filter for (e.g., 1)
     * @return true on success, false on failure
     */
    bool executeSelectQuery(const std::string& column_name, int value) {
        if (!table_) {
            std::cerr << "No table opened. Call openTable() first." << std::endl;
            return false;
        }

        // Step 1: Create a new query
        LanceDBQuery* query = lancedb_query_new(table_);
        if (!query) {
            std::cerr << "Failed to create query" << std::endl;
            return false;
        }

        // Step 2: Build WHERE clause: "col=1"
        std::string where_clause = column_name + " = " + std::to_string(value);
        std::cout << "Executing query with WHERE clause: " << where_clause << std::endl;

        char* error_message = nullptr;
        LanceDBError result = lancedb_query_where_filter(query, where_clause.c_str(), &error_message);
        if (result != LANCEDB_SUCCESS) {
            std::cerr << "Failed to set WHERE filter: " << lancedb_error_to_message(result);
            if (error_message) {
                std::cerr << " - " << error_message;
                lancedb_free_string(error_message);
            }
            std::cerr << std::endl;
            lancedb_query_free(query);
            return false;
        }

        // Step 3: Execute the query
        LanceDBQueryResult* query_result = lancedb_query_execute(query);
        if (!query_result) {
            std::cerr << "Failed to execute query" << std::endl;
            return false;
        }

        // Step 4: Convert results to Arrow format
        struct ArrowArray** result_arrays = nullptr;
        struct ArrowSchema* result_schema = nullptr;
        size_t count_out = 0;

        result = lancedb_query_result_to_arrow(
            query_result,
            reinterpret_cast<FFI_ArrowArray***>(&result_arrays),
            reinterpret_cast<FFI_ArrowSchema**>(&result_schema),
            &count_out,
            &error_message
        );

        if (result != LANCEDB_SUCCESS) {
            std::cerr << "Failed to convert query result to Arrow: " << lancedb_error_to_message(result);
            if (error_message) {
                std::cerr << " - " << error_message;
                lancedb_free_string(error_message);
            }
            std::cerr << std::endl;
            return false;
        }

        // Step 5: Process and display results
        std::cout << "Query returned " << count_out << " batch(es)" << std::endl;
        
        bool success = displayResults(result_arrays, result_schema, count_out);

        // Step 6: Cleanup
        if (result_arrays) {
            lancedb_free_arrow_arrays(
                reinterpret_cast<FFI_ArrowArray**>(result_arrays),
                count_out
            );
        }
        if (result_schema) {
            lancedb_free_arrow_schema(reinterpret_cast<FFI_ArrowSchema*>(result_schema));
        }

        return success;
    }

    /**
     * Execute a more complex SELECT query with multiple conditions
     * Example: SELECT * FROM table WHERE col1=1 AND col2 > 10
     * 
     * @param where_clause Full SQL WHERE clause (e.g., "col1 = 1 AND col2 > 10")
     * @param limit Optional limit on number of results (0 = no limit)
     * @param offset Optional offset for pagination (0 = no offset)
     * @return true on success, false on failure
     */
    bool executeComplexSelectQuery(
        const std::string& where_clause,
        size_t limit = 0,
        size_t offset = 0
    ) {
        if (!table_) {
            std::cerr << "No table opened. Call openTable() first." << std::endl;
            return false;
        }

        // Step 1: Create a new query
        LanceDBQuery* query = lancedb_query_new(table_);
        if (!query) {
            std::cerr << "Failed to create query" << std::endl;
            return false;
        }

        char* error_message = nullptr;

        // Step 2: Set WHERE filter
        std::cout << "Executing query with WHERE clause: " << where_clause << std::endl;
        LanceDBError result = lancedb_query_where_filter(query, where_clause.c_str(), &error_message);
        if (result != LANCEDB_SUCCESS) {
            std::cerr << "Failed to set WHERE filter: " << lancedb_error_to_message(result);
            if (error_message) {
                std::cerr << " - " << error_message;
                lancedb_free_string(error_message);
            }
            std::cerr << std::endl;
            lancedb_query_free(query);
            return false;
        }

        // Step 3: Set LIMIT if specified
        if (limit > 0) {
            result = lancedb_query_limit(query, limit, &error_message);
            if (result != LANCEDB_SUCCESS) {
                std::cerr << "Failed to set LIMIT: " << lancedb_error_to_message(result);
                if (error_message) {
                    std::cerr << " - " << error_message;
                    lancedb_free_string(error_message);
                }
                std::cerr << std::endl;
                lancedb_query_free(query);
                return false;
            }
            std::cout << "Set LIMIT: " << limit << std::endl;
        }

        // Step 4: Set OFFSET if specified
        if (offset > 0) {
            result = lancedb_query_offset(query, offset, &error_message);
            if (result != LANCEDB_SUCCESS) {
                std::cerr << "Failed to set OFFSET: " << lancedb_error_to_message(result);
                if (error_message) {
                    std::cerr << " - " << error_message;
                    lancedb_free_string(error_message);
                }
                std::cerr << std::endl;
                lancedb_query_free(query);
                return false;
            }
            std::cout << "Set OFFSET: " << offset << std::endl;
        }

        // Step 5: Execute the query
        LanceDBQueryResult* query_result = lancedb_query_execute(query);
        if (!query_result) {
            std::cerr << "Failed to execute query" << std::endl;
            return false;
        }

        // Step 6: Convert results to Arrow format
        struct ArrowArray** result_arrays = nullptr;
        struct ArrowSchema* result_schema = nullptr;
        size_t count_out = 0;

        result = lancedb_query_result_to_arrow(
            query_result,
            reinterpret_cast<FFI_ArrowArray***>(&result_arrays),
            reinterpret_cast<FFI_ArrowSchema**>(&result_schema),
            &count_out,
            &error_message
        );

        if (result != LANCEDB_SUCCESS) {
            std::cerr << "Failed to convert query result to Arrow: " << lancedb_error_to_message(result);
            if (error_message) {
                std::cerr << " - " << error_message;
                lancedb_free_string(error_message);
            }
            std::cerr << std::endl;
            return false;
        }

        // Step 7: Process and display results
        std::cout << "Query returned " << count_out << " batch(es)" << std::endl;
        
        bool success = displayResults(result_arrays, result_schema, count_out);

        // Step 8: Cleanup
        if (result_arrays) {
            lancedb_free_arrow_arrays(
                reinterpret_cast<FFI_ArrowArray**>(result_arrays),
                count_out
            );
        }
        if (result_schema) {
            lancedb_free_arrow_schema(reinterpret_cast<FFI_ArrowSchema*>(result_schema));
        }

        return success;
    }

    /**
     * Execute SELECT with specific columns
     * Example: SELECT col1, col2 FROM table WHERE col3=1
     * 
     * @param columns List of column names to select
     * @param where_clause SQL WHERE clause
     * @return true on success, false on failure
     */
    bool executeSelectWithColumns(
        const std::vector<std::string>& columns,
        const std::string& where_clause
    ) {
        if (!table_) {
            std::cerr << "No table opened. Call openTable() first." << std::endl;
            return false;
        }

        // Step 1: Create a new query
        LanceDBQuery* query = lancedb_query_new(table_);
        if (!query) {
            std::cerr << "Failed to create query" << std::endl;
            return false;
        }

        char* error_message = nullptr;

        // Step 2: Set column selection
        if (!columns.empty()) {
            std::vector<const char*> column_ptrs;
            for (const auto& col : columns) {
                column_ptrs.push_back(col.c_str());
            }

            LanceDBError result = lancedb_query_select(
                query,
                column_ptrs.data(),
                column_ptrs.size(),
                &error_message
            );

            if (result != LANCEDB_SUCCESS) {
                std::cerr << "Failed to set SELECT columns: " << lancedb_error_to_message(result);
                if (error_message) {
                    std::cerr << " - " << error_message;
                    lancedb_free_string(error_message);
                }
                std::cerr << std::endl;
                lancedb_query_free(query);
                return false;
            }

            std::cout << "Selected columns: ";
            for (const auto& col : columns) {
                std::cout << col << " ";
            }
            std::cout << std::endl;
        }

        // Step 3: Set WHERE filter
        if (!where_clause.empty()) {
            LanceDBError result = lancedb_query_where_filter(query, where_clause.c_str(), &error_message);
            if (result != LANCEDB_SUCCESS) {
                std::cerr << "Failed to set WHERE filter: " << lancedb_error_to_message(result);
                if (error_message) {
                    std::cerr << " - " << error_message;
                    lancedb_free_string(error_message);
                }
                std::cerr << std::endl;
                lancedb_query_free(query);
                return false;
            }
            std::cout << "WHERE clause: " << where_clause << std::endl;
        }

        // Step 4: Execute the query
        LanceDBQueryResult* query_result = lancedb_query_execute(query);
        if (!query_result) {
            std::cerr << "Failed to execute query" << std::endl;
            return false;
        }

        // Step 5: Convert results to Arrow format
        struct ArrowArray** result_arrays = nullptr;
        struct ArrowSchema* result_schema = nullptr;
        size_t count_out = 0;

        LanceDBError result = lancedb_query_result_to_arrow(
            query_result,
            reinterpret_cast<FFI_ArrowArray***>(&result_arrays),
            reinterpret_cast<FFI_ArrowSchema**>(&result_schema),
            &count_out,
            &error_message
        );

        if (result != LANCEDB_SUCCESS) {
            std::cerr << "Failed to convert query result to Arrow: " << lancedb_error_to_message(result);
            if (error_message) {
                std::cerr << " - " << error_message;
                lancedb_free_string(error_message);
            }
            std::cerr << std::endl;
            return false;
        }

        // Step 6: Process and display results
        std::cout << "Query returned " << count_out << " batch(es)" << std::endl;
        
        bool success = displayResults(result_arrays, result_schema, count_out);

        // Step 7: Cleanup
        if (result_arrays) {
            lancedb_free_arrow_arrays(
                reinterpret_cast<FFI_ArrowArray**>(result_arrays),
                count_out
            );
        }
        if (result_schema) {
            lancedb_free_arrow_schema(reinterpret_cast<FFI_ArrowSchema*>(result_schema));
        }

        return success;
    }

    /**
     * Get table information
     */
    void printTableInfo() {
        if (!table_) {
            std::cerr << "No table opened." << std::endl;
            return;
        }

        // Get table version
        unsigned long long version = lancedb_table_version(table_);
        std::cout << "Table version: " << version << std::endl;

        // Get row count
        unsigned long long row_count = lancedb_table_count_rows(table_);
        std::cout << "Total rows: " << row_count << std::endl;

        // Get schema
        FFI_ArrowSchema* schema_out = nullptr;
        char* error_message = nullptr;
        LanceDBError result = lancedb_table_arrow_schema(table_, &schema_out, &error_message);
        
        if (result == LANCEDB_SUCCESS && schema_out) {
            auto schema_result = arrow::ImportSchema(schema_out);
            if (schema_result.ok()) {
                std::cout << "Table schema:" << std::endl;
                std::cout << (*schema_result)->ToString() << std::endl;
            } else {
                std::cerr << "Failed to import schema: " << schema_result.status() << std::endl;
            }
            lancedb_free_arrow_schema(schema_out);
        } else {
            std::cerr << "Failed to get table schema: " << lancedb_error_to_message(result);
            if (error_message) {
                std::cerr << " - " << error_message;
                lancedb_free_string(error_message);
            }
            std::cerr << std::endl;
        }
    }

private:
    /**
     * Display query results
     * @param result_arrays Array of Arrow arrays
     * @param result_schema Arrow schema
     * @param count Number of batches
     * @return true on success
     */
    bool displayResults(
        struct ArrowArray** result_arrays,
        struct ArrowSchema* result_schema,
        size_t count
    ) {
        if (count == 0) {
            std::cout << "No results found." << std::endl;
            return true;
        }

        // Import schema
        auto schema_result = arrow::ImportSchema(result_schema);
        if (!schema_result.ok()) {
            std::cerr << "Failed to import result schema: " << schema_result.status() << std::endl;
            return false;
        }

        std::cout << "\n===== Query Results =====" << std::endl;
        std::cout << "Result schema:" << std::endl;
        std::cout << (*schema_result)->ToString() << std::endl;
        std::cout << "\n";

        // Process each batch
        int total_rows = 0;
        for (size_t i = 0; i < count; i++) {
            std::cout << "--- Batch " << (i + 1) << " ---" << std::endl;

            auto batch_result = arrow::ImportRecordBatch(
                result_arrays[i],
                *schema_result
            );

            if (batch_result.ok()) {
                auto batch = *batch_result;
                total_rows += batch->num_rows();
                
                std::cout << "Rows in batch: " << batch->num_rows() << std::endl;
                std::cout << "Columns: " << batch->num_columns() << std::endl;
                
                // Print column data
                for (int col_idx = 0; col_idx < batch->num_columns(); col_idx++) {
                    auto column = batch->column(col_idx);
                    std::cout << "Column '" << batch->schema()->field(col_idx)->name() 
                              << "': " << column->ToString() << std::endl;
                }
                std::cout << std::endl;
            } else {
                std::cerr << "Failed to import record batch " << i 
                          << ": " << batch_result.status() << std::endl;
                return false;
            }
        }

        std::cout << "Total rows returned: " << total_rows << std::endl;
        std::cout << "=========================" << std::endl;

        return true;
    }

    /**
     * Cleanup resources
     */
    void cleanup() {
        if (table_) {
            lancedb_table_free(table_);
            table_ = nullptr;
        }
        if (connection_) {
            lancedb_connection_free(connection_);
            connection_ = nullptr;
        }
    }
};

/**
 * Example usage of the test class
 */
int main() {
    // Example 1: Simple SELECT with WHERE clause
    std::cout << "========== Example 1: SELECT * FROM table WHERE id=1 ==========" << std::endl;
    {
        LanceDBSelectQueryTest test("data/my-lancedb", "my_table");
        
        if (!test.connect()) {
            return 1;
        }
        
        if (!test.openTable()) {
            return 1;
        }
        
        test.printTableInfo();
        
        // Execute: SELECT * FROM my_table WHERE id=1
        test.executeSelectQuery("id", 1);
    }

    std::cout << "\n\n";

    // Example 2: Complex SELECT with multiple conditions
    std::cout << "========== Example 2: Complex SELECT with LIMIT ==========" << std::endl;
    {
        LanceDBSelectQueryTest test("data/my-lancedb", "my_table");
        
        if (test.connect() && test.openTable()) {
            // Execute: SELECT * FROM my_table WHERE id > 10 AND id < 20 LIMIT 5
            test.executeComplexSelectQuery("id > 10 AND id < 20", 5, 0);
        }
    }

    std::cout << "\n\n";

    // Example 3: SELECT specific columns
    std::cout << "========== Example 3: SELECT specific columns ==========" << std::endl;
    {
        LanceDBSelectQueryTest test("data/my-lancedb", "my_table");
        
        if (test.connect() && test.openTable()) {
            // Execute: SELECT id, name FROM my_table WHERE id = 1
            std::vector<std::string> columns = {"id", "name"};
            test.executeSelectWithColumns(columns, "id = 1");
        }
    }

    std::cout << "\n\nAll tests completed!" << std::endl;
    return 0;
}

