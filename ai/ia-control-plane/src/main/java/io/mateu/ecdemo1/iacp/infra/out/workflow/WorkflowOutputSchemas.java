package io.mateu.ecdemo1.iacp.infra.out.workflow;

import com.networknt.schema.*;
import io.mateu.ecdemo1.iacp.infra.out.persistence.JsonDocuments;
import java.util.*;

/** Same dialect as the workflow runtime; schema definitions cannot fetch remote references. */
public final class WorkflowOutputSchemas {
    private WorkflowOutputSchemas() {}
    public static void validate(Map<String, Object> schema) {
        if (schema == null || !"object".equals(schema.get("type")))
            throw new IllegalArgumentException("Workflow outputSchema must declare type object");
        rejectRemoteRefs(schema);
        try {
            var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
            var node = new tools.jackson.databind.ObjectMapper().readTree(JsonDocuments.write(schema));
            var meta = registry.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
            if (!meta.validate(node).isEmpty()) throw new IllegalArgumentException("Invalid workflow outputSchema");
            registry.getSchema(node);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Invalid workflow outputSchema"); }
    }
    private static void rejectRemoteRefs(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var key : List.of("$ref", "$dynamicRef")) {
                Object ref = map.get(key);
                if (ref instanceof String s && !s.startsWith("#"))
                    throw new IllegalArgumentException("Workflow schema references must be local");
            }
            if (map.containsKey("$schema") && !"https://json-schema.org/draft/2020-12/schema".equals(map.get("$schema")))
                throw new IllegalArgumentException("Workflow schemas use Draft 2020-12");
            map.values().forEach(WorkflowOutputSchemas::rejectRemoteRefs);
        } else if (value instanceof List<?> list) list.forEach(WorkflowOutputSchemas::rejectRemoteRefs);
    }
}
