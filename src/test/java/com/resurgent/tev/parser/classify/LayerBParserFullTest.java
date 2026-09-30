package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class LayerBParserFullTest {

    @Test
    void testFullBatchResponseParsing() throws Exception {
        // Actual response from om_arham log - full response
        String jsonResponse = "{\"cells\":[" +
            "{\"kind\":\"money\",\"scale\":\"lakh\",\"unit\":\"\",\"currency\":\"INR\",\"confidence\":0.98}," +
            "{\"kind\":\"money\",\"scale\":\"lakh\",\"unit\":\"\",\"currency\":\"INR\",\"confidence\":0.95}" +
            "]}";
        
        System.out.println("\n=== Full Batch Response Parsing Test ===");
        System.out.println("Response length: " + jsonResponse.length());
        System.out.println("");
        
        ObjectMapper MAPPER = new ObjectMapper();
        List<CellTypeResponse> responses = new ArrayList<>();
        
        try {
            JsonNode root = MAPPER.readTree(jsonResponse);
            
            JsonNode nodes;
            if (root.isArray()) {
                nodes = root;
            } else if (root.isObject()) {
                if (root.has("cells")) {
                    nodes = root.get("cells");
                } else if (root.has("array")) {
                    nodes = root.get("array");
                } else if (root.has("results")) {
                    nodes = root.get("results");
                } else if (root.has("data")) {
                    nodes = root.get("data");
                } else {
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
                if (!nodes.isArray()) {
                    throw new IllegalArgumentException("Expected array value, got: " + nodes.getNodeType());
                }
            } else {
                throw new IllegalArgumentException("Expected JSON array or object with array, got: " + 
                    jsonResponse.substring(0, Math.min(100, jsonResponse.length())));
            }
            
            System.out.println("✓ Parsed structure successfully");
            System.out.println("✓ Array size: " + nodes.size());
            System.out.println("");
            
            // Now parse each cell - THIS is where errors might occur
            for (int i = 0; i < nodes.size(); i++) {
                System.out.println("Processing cell " + i + "...");
                JsonNode node = nodes.get(i);
                
                try {
                    String kind = node.get("kind").asText();
                    String scale = node.get("scale").asText();
                    String unit = node.get("unit").asText("");
                    String currency = node.get("currency").asText("");
                    double confidence = node.get("confidence").asDouble(0.0);
                    
                    System.out.println("  kind=" + kind + ", scale=" + scale + ", confidence=" + confidence);
                    
                    responses.add(new CellTypeResponse(kind, scale, unit, currency, confidence));
                } catch (Exception e) {
                    System.out.println("  ERROR: " + e.getMessage());
                    throw e;
                }
            }
            
            System.out.println("");
            System.out.println("✓ Successfully parsed " + responses.size() + " cells");
            
        } catch (Exception e) {
            System.out.println("ERROR during parsing: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    record CellTypeResponse(String kind, String scale, String unit, String currency, double confidence) {}
}
