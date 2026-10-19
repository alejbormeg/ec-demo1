package io.mateu.ecdemo1.iaagent.workflow.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.ecdemo1.iaagent.workflow.application.WorkflowPorts.Codec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class JacksonWorkflowCodec implements Codec {
    private final ObjectMapper mapper;
    public JacksonWorkflowCodec(ObjectMapper mapper){this.mapper=mapper;}
    @Override public String encode(Object value){try{return mapper.writeValueAsString(value);}catch(Exception ex){throw new IllegalArgumentException("JSON encoding failed",ex);}}
    @Override public Map<String,Object> object(String json){try{return mapper.readValue(json,new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception ex){throw new IllegalArgumentException("JSON object required",ex);}}
    @Override public String digest(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(canonical(mapper.convertValue(value,Object.class))).getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalArgumentException(ex);}}
    private Object canonical(Object value){if(value instanceof Map<?,?> map){var out=new TreeMap<String,Object>();map.forEach((k,v)->out.put(String.valueOf(k),canonical(v)));return out;}if(value instanceof List<?> list)return list.stream().map(this::canonical).toList();return value;}
    @Override public void validate(Map<String,Object> schema,String json){
        // Networknt 3 uses Jackson 3 internally. Parse locally; remote $ref resolution disabled.
        try {
            var node=new tools.jackson.databind.ObjectMapper().readTree(json);
            if(!node.isObject())throw new IllegalArgumentException("Agent must return a JSON object");
            var schemaNode=new tools.jackson.databind.ObjectMapper().readTree(encode(schema));
            rejectRemoteRefs(schema);
            var registry=com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12);
            var compiled=registry.getSchema(schemaNode);
            var errors=compiled.validate(node);
            if(!errors.isEmpty())throw new IllegalArgumentException("Agent output violates outputSchema: "+errors);
        }catch(IllegalArgumentException ex){throw ex;}catch(Exception ex){throw new IllegalArgumentException("Agent output is invalid JSON or schema",ex);}
    }
    private void rejectRemoteRefs(Object v){if(v instanceof Map<?,?>m){for(String keyword:List.of("$ref","$dynamicRef")){Object ref=m.get(keyword);if(ref instanceof String r&&!r.startsWith("#"))throw new IllegalArgumentException("Remote schema refs unsupported");}m.values().forEach(this::rejectRemoteRefs);}else if(v instanceof List<?> l)l.forEach(this::rejectRemoteRefs);}
}
