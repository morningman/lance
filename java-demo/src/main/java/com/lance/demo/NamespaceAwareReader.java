/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lance.demo;

import com.lancedb.lance.namespace.DirectoryNamespace;
import com.lancedb.lance.namespace.LanceNamespace;
import com.lancedb.lance.namespace.RestNamespace;
import com.lancedb.lance.namespace.model.DescribeTableRequest;
import com.lancedb.lance.namespace.model.DescribeTableResponse;
import com.lancedb.lance.namespace.model.ListTablesRequest;
import com.lancedb.lance.namespace.model.ListTablesResponse;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates using Lance Namespace to discover and access table paths automatically.
 * 
 * This eliminates the need to manually specify dataset paths by using namespace
 * catalog functionality to query table metadata.
 */
public class NamespaceAwareReader {
    private static final Logger logger = LoggerFactory.getLogger(NamespaceAwareReader.class);

    /**
     * Get table path using DirectoryNamespace (for local/S3/Azure/GCS storage).
     *
     * @param rootPath The namespace root path (e.g., /data/lance, s3://bucket/path)
     * @param tableName The table name to look up
     * @param allocator BufferAllocator for operations
     * @return The full path to the table dataset
     */
    public static String getTablePathFromDirectory(
            String rootPath,
            String tableName,
            BufferAllocator allocator) throws Exception {
        
        // Initialize DirectoryNamespace
        Map<String, String> properties = new HashMap<>();
        properties.put("root", rootPath);
        properties.put("manifest_enabled", "true");

        try (DirectoryNamespace namespace = new DirectoryNamespace()) {
            namespace.initialize(properties, allocator);
            
            logger.info("Initialized DirectoryNamespace with root: {}", rootPath);
            
            // Describe the table to get its metadata
            DescribeTableRequest request = new DescribeTableRequest();
            request.setTableName(tableName);
            
            DescribeTableResponse response = namespace.describeTable(request);
            
            // The table location/path is in the response
            String tablePath = response.getLocation();
            logger.info("Found table '{}' at path: {}", tableName, tablePath);
            
            return tablePath;
        }
    }

    /**
     * Get table path using RestNamespace (for remote Lance catalog services).
     *
     * @param apiEndpoint The REST API endpoint URL
     * @param tableName The table name to look up
     * @param authToken Optional authentication token
     * @param allocator BufferAllocator for operations
     * @return The full path to the table dataset
     */
    public static String getTablePathFromRest(
            String apiEndpoint,
            String tableName,
            String authToken,
            BufferAllocator allocator) throws Exception {
        
        // Initialize RestNamespace
        Map<String, String> properties = new HashMap<>();
        properties.put("uri", apiEndpoint);
        if (authToken != null && !authToken.isEmpty()) {
            properties.put("header.Authorization", "Bearer " + authToken);
        }

        try (RestNamespace namespace = new RestNamespace()) {
            namespace.initialize(properties, allocator);
            
            logger.info("Initialized RestNamespace with endpoint: {}", apiEndpoint);
            
            // Describe the table to get its metadata
            DescribeTableRequest request = new DescribeTableRequest();
            request.setTableName(tableName);
            
            DescribeTableResponse response = namespace.describeTable(request);
            
            String tablePath = response.getLocation();
            logger.info("Found table '{}' at path: {}", tableName, tablePath);
            
            return tablePath;
        }
    }

    /**
     * List all available tables in a namespace.
     *
     * @param namespace The initialized namespace
     * @return List of table names
     */
    public static List<String> listTables(LanceNamespace namespace) throws Exception {
        ListTablesRequest request = new ListTablesRequest();
        ListTablesResponse response = namespace.listTables(request);
        
        List<String> tableNames = response.getTableNames();
        logger.info("Found {} tables in namespace", tableNames.size());
        for (String name : tableNames) {
            logger.info("  - {}", name);
        }
        
        return tableNames;
    }

    /**
     * Example 1: Using DirectoryNamespace with local filesystem.
     */
    public static void example1LocalDirectory() {
        logger.info("=== Example 1: Local Directory Namespace ===");

        String rootPath = "/data/lance-datasets";
        String tableName = "my_table";

        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
            
            // Get table path from namespace
            String tablePath = getTablePathFromDirectory(rootPath, tableName, allocator);
            
            // Now use the path with ParallelLanceReader
            ParallelLanceReader reader = new ParallelLanceReader(
                tablePath,
                allocator,
                4
            );

            ParallelLanceReader.Config config = new ParallelLanceReader.Config(tablePath);
            ParallelLanceReader.AggregatedResult result = reader.execute(config);

            logger.info("Results: {}", result);

        } catch (Exception e) {
            logger.error("Error in example 1", e);
        }
    }

    /**
     * Example 2: Using DirectoryNamespace with S3.
     */
    public static void example2S3Directory() {
        logger.info("=== Example 2: S3 Directory Namespace ===");

        String rootPath = "s3://my-bucket/lance-data";
        String tableName = "events_table";

        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
            
            // Set up namespace with S3 configuration
            Map<String, String> properties = new HashMap<>();
            properties.put("root", rootPath);
            properties.put("manifest_enabled", "true");
            // S3 specific options
            properties.put("storage.region", "us-east-1");
            // AWS credentials from environment or IAM role

            try (DirectoryNamespace namespace = new DirectoryNamespace()) {
                namespace.initialize(properties, allocator);
                
                // List all tables first
                List<String> tables = listTables(namespace);
                
                // Get specific table path
                DescribeTableRequest request = new DescribeTableRequest();
                request.setTableName(tableName);
                DescribeTableResponse response = namespace.describeTable(request);
                
                String tablePath = response.getLocation();
                logger.info("Table location: {}", tablePath);

                // Use the path for parallel reading
                ParallelLanceReader reader = new ParallelLanceReader(
                    tablePath,
                    allocator,
                    8
                );

                ParallelLanceReader.Config config = new ParallelLanceReader.Config(tablePath)
                    .withPredicate("timestamp >= '2024-01-01'");
                
                ParallelLanceReader.AggregatedResult result = reader.execute(config);
                logger.info("Results: {}", result);
            }

        } catch (Exception e) {
            logger.error("Error in example 2", e);
        }
    }

    /**
     * Example 3: Using RestNamespace with remote catalog.
     */
    public static void example3RestCatalog() {
        logger.info("=== Example 3: REST Catalog Namespace ===");

        String catalogEndpoint = "https://catalog.example.com/api";
        String tableName = "user_events";
        String authToken = "your-api-token";

        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
            
            // Get table path from REST catalog
            String tablePath = getTablePathFromRest(
                catalogEndpoint,
                tableName,
                authToken,
                allocator
            );

            // Use the path for parallel reading
            ParallelLanceReader reader = new ParallelLanceReader(
                tablePath,
                allocator,
                4
            );

            ParallelLanceReader.Config config = new ParallelLanceReader.Config(tablePath)
                .withColumns(java.util.Arrays.asList("user_id", "event_type"))
                .withPredicate("user_id > 1000");
            
            ParallelLanceReader.AggregatedResult result = reader.execute(config);
            logger.info("Results: {}", result);

        } catch (Exception e) {
            logger.error("Error in example 3", e);
        }
    }

    /**
     * Example 4: Dynamic table discovery and processing.
     */
    public static void example4DynamicDiscovery() {
        logger.info("=== Example 4: Dynamic Table Discovery ===");

        String rootPath = "/data/lance-datasets";

        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
            
            // Initialize namespace
            Map<String, String> properties = new HashMap<>();
            properties.put("root", rootPath);
            
            try (DirectoryNamespace namespace = new DirectoryNamespace()) {
                namespace.initialize(properties, allocator);
                
                // List all tables
                List<String> tableNames = listTables(namespace);
                
                // Process each table
                for (String tableName : tableNames) {
                    logger.info("Processing table: {}", tableName);
                    
                    // Get table path
                    DescribeTableRequest request = new DescribeTableRequest();
                    request.setTableName(tableName);
                    DescribeTableResponse response = namespace.describeTable(request);
                    
                    String tablePath = response.getLocation();
                    
                    // Process this table
                    ParallelLanceReader reader = new ParallelLanceReader(
                        tablePath,
                        allocator,
                        4
                    );
                    
                    ParallelLanceReader.Config config = 
                        new ParallelLanceReader.Config(tablePath)
                            .withMetrics(true);
                    
                    ParallelLanceReader.AggregatedResult result = reader.execute(config);
                    
                    logger.info("Table '{}' processed: {} rows", 
                        tableName, result.getTotalRowsRead());
                }
            }

        } catch (Exception e) {
            logger.error("Error in example 4", e);
        }
    }

    /**
     * Example 5: Namespace with custom configuration for Azure.
     */
    public static void example5AzureBlob() {
        logger.info("=== Example 5: Azure Blob Storage Namespace ===");

        String rootPath = "az://container/lance-data";
        String tableName = "analytics_table";

        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
            
            Map<String, String> properties = new HashMap<>();
            properties.put("root", rootPath);
            properties.put("manifest_enabled", "true");
            // Azure specific options
            properties.put("storage.account_name", "myaccount");
            properties.put("storage.account_key", "your-key");
            // Or use SAS token:
            // properties.put("storage.sas_token", "your-sas-token");

            try (DirectoryNamespace namespace = new DirectoryNamespace()) {
                namespace.initialize(properties, allocator);
                
                String tablePath = getTablePathFromDirectory(rootPath, tableName, allocator);
                
                ParallelLanceReader reader = new ParallelLanceReader(
                    tablePath,
                    allocator,
                    8
                );

                ParallelLanceReader.Config config = new ParallelLanceReader.Config(tablePath);
                ParallelLanceReader.AggregatedResult result = reader.execute(config);
                
                logger.info("Results: {}", result);
            }

        } catch (Exception e) {
            logger.error("Error in example 5", e);
        }
    }

    /**
     * Helper: Create a reusable namespace-aware reader factory.
     */
    public static class NamespaceReaderFactory {
        private final LanceNamespace namespace;
        private final BufferAllocator allocator;
        private final int defaultParallelism;

        public NamespaceReaderFactory(
                LanceNamespace namespace,
                BufferAllocator allocator,
                int defaultParallelism) {
            this.namespace = namespace;
            this.allocator = allocator;
            this.defaultParallelism = defaultParallelism;
        }

        /**
         * Create a ParallelLanceReader for the specified table.
         */
        public ParallelLanceReader createReader(String tableName) throws Exception {
            DescribeTableRequest request = new DescribeTableRequest();
            request.setTableName(tableName);
            DescribeTableResponse response = namespace.describeTable(request);
            
            String tablePath = response.getLocation();
            
            return new ParallelLanceReader(
                tablePath,
                allocator,
                defaultParallelism
            );
        }
    }

    public static void main(String[] args) {
        // Uncomment examples to run

        // Local filesystem
        // example1LocalDirectory();

        // S3
        // example2S3Directory();

        // REST catalog
        // example3RestCatalog();

        // Dynamic discovery
        // example4DynamicDiscovery();

        // Azure
        // example5AzureBlob();

        logger.info("See method implementations for usage examples");
    }
}

