package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class LayerBParserDebugTest {

    @Test
    void testBatchResponseWithCellsWrapper() throws Exception {
        // Actual response from om_arham log
        String jsonResponse = "{\"cells\":[" +
            "{\"kind\":\"money\",\"scale\":\"lakh\",\"unit\":\"\",\"currency\":\"INR\",\"confidence\":0.98}," +
            "{\"kind\":\"money\",\"scale\":\"lakh\",\"unit\":\"\",\"currency\":\"INR\",\"confidence\":0.95}" +
            "]}";
        
        System.out.println("\n=== Testing Layer B Parser ===");
        System.out.println("Input: " + jsonResponse.substring(0, 80) + "...");
        System.out.println("");
        
        ObjectMapper MAPPER = new ObjectMapper();
        JsonNode root = MAPPER.readTree(jsonResponse);
        
        System.out.println("root.isArray(): " + root.isArray());
        System.out.println("root.isObject(): " + root.isObject());
        System.out.println("root.has(\"cells\"): " + root.has("cells"));
        
        // Simulate the parser logic
        JsonNode nodes;
        if (root.isArray()) {
            System.out.println("-> Taking root as array");
            nodes = root;
        } else if (root.isObject()) {
            System.out.println("-> root is object, checking for wrapper keys...");
            if (root.has("cells")) {
                System.out.println("   Found 'cells' key!");
                nodes = root.get("cells");
            } else if (root.has("array")) {
                System.out.println("   Found 'array' key");
                nodes = root.get("array");
            } else if (root.has("results")) {
                System.out.println("   Found 'results' key");
                nodes = root.get("results");
            } else if (root.has("data")) {
                System.out.println("   Found 'data' key");
                nodes = root.get("data");
            } else {
                System.out.println("   No wrapper key found, scanning for any array...");
                nodes = null;
                for (JsonNode field : root) {
                    if (field.isArray()) {
                        nodes = field;
                        break;
                    }
                }
                if (nodes == null) {
                    throw new IllegalArgumentException("No array found in response object");
                }
            }
            
            assertThat(nodes.isArray())
                    .as("nodes should be an array")
                    .isTrue();
        } else {
            throw new IllegalArgumentException("Expected JSON array or object with array, got: " + 
                jsonResponse.substring(0, Math.min(100, jsonResponse.length())));
        }
        
        System.out.println("✓ Successfully parsed!");
        System.out.println("✓ nodes type: " + (nodes.isArray() ? "ARRAY" : "NOT ARRAY"));
        System.out.println("✓ Array size: " + nodes.size());
        
        assertThat(nodes.size()).isEqualTo(2);
    }
}
