package io.mateu.ecdemo1.iacp.infra.out.workflow;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowOutputSchemasTest {
    @Test void acceptsLocalDraft2020SchemaWithoutExternalRequests() {
        assertDoesNotThrow(() -> WorkflowOutputSchemas.validate(Map.of("type", "object", "properties",
                Map.of("decision", Map.of("type", "string", "enum", List.of("accepted", "review"))),
                "required", List.of("decision"), "additionalProperties", false)));
    }
    @Test void rejectsInvalidDeclarationsAndRemoteReferencesBeforeCompiling() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowOutputSchemas.validate(Map.of("type", "array")));
        assertThrows(IllegalArgumentException.class, () -> WorkflowOutputSchemas.validate(Map.of("type", "object", "required", "wrong")));
        assertThrows(IllegalArgumentException.class, () -> WorkflowOutputSchemas.validate(Map.of("type", "object", "$ref", "https://example.invalid/schema")));
        assertThrows(IllegalArgumentException.class, () -> WorkflowOutputSchemas.validate(Map.of("type", "object", "$dynamicRef", "https://example.invalid/schema")));
    }
}
