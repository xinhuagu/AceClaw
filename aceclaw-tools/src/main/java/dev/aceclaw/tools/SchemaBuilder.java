package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for JSON Schema objects used in tool definitions.
 *
 * <p>Generates JSON Schema compatible with the Claude tool_use API format.
 */
final class SchemaBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectNode propertiesNode;
    private final List<String> requiredFields;

    private SchemaBuilder() {
        this.propertiesNode = MAPPER.createObjectNode();
        this.requiredFields = new ArrayList<>();
    }

    /**
     * Starts building an object-type JSON Schema.
     */
    static SchemaBuilder object() {
        return new SchemaBuilder();
    }

    /**
     * Creates a string-type property schema.
     */
    static ObjectNode string(String description) {
        var node = MAPPER.createObjectNode();
        node.put("type", "string");
        node.put("description", description);
        return node;
    }

    /**
     * Creates a boolean-type property schema.
     */
    static ObjectNode bool(String description) {
        var node = MAPPER.createObjectNode();
        node.put("type", "boolean");
        node.put("description", description);
        return node;
    }

    /**
     * Creates an integer-type property schema.
     */
    static ObjectNode integer(String description) {
        var node = MAPPER.createObjectNode();
        node.put("type", "integer");
        node.put("description", description);
        return node;
    }

    /**
     * Adds a required property.
     */
    SchemaBuilder requiredProperty(String name, ObjectNode schema) {
        propertiesNode.set(name, schema);
        requiredFields.add(name);
        return this;
    }

    /**
     * Adds an optional property.
     */
    SchemaBuilder optionalProperty(String name, ObjectNode schema) {
        propertiesNode.set(name, schema);
        return this;
    }

    /**
     * Builds the complete JSON Schema object.
     */
    JsonNode build() {
        var schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", propertiesNode);

        if (!requiredFields.isEmpty()) {
            ArrayNode requiredArray = MAPPER.createArrayNode();
            requiredFields.forEach(requiredArray::add);
            schema.set("required", requiredArray);
        }

        return schema;
    }
}
