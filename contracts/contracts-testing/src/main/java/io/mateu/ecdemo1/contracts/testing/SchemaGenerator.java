package io.mateu.ecdemo1.contracts.testing;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaBuilder;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A topic's JSON Schema (draft 2020-12), generated from the records that are its messages: every
 * record component is required — but one annotated {@code @JsonInclude(NON_NULL)}, written only with a value
 * — and, unless primitive, nullable (the services write nulls); java.time
 * values are ISO strings; enums are their names; a polymorphic message is a {@code oneOf} of its
 * variants, each with its discriminator as a constant. Every object is a definition in
 * {@code $defs}, named after its class without the package.
 */
public final class SchemaGenerator {

    static final String DRAFT = "https://json-schema.org/draft/2020-12/schema";

    private SchemaGenerator() {
    }

    static boolean omittedWhenNull(com.github.victools.jsonschema.generator.FieldScope field) {
        var include = field.getAnnotationConsideringFieldAndGetter(com.fasterxml.jackson.annotation.JsonInclude.class);
        return include != null && include.value() == com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
    }

    public static ObjectNode generate(TopicSpec spec) {
        var mapper = ContractJson.mapper();
        var config = new SchemaGeneratorConfigBuilder(mapper, SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .with(new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
                        JacksonOption.IGNORE_TYPE_INFO_TRANSFORM, JacksonOption.SKIP_SUBTYPE_LOOKUP,
                        JacksonOption.INCLUDE_ONLY_JSONPROPERTY_ANNOTATED_METHODS))
                .with(Option.DEFINITIONS_FOR_ALL_OBJECTS, Option.ADDITIONAL_FIXED_TYPES,
                        Option.EXTRA_OPEN_API_FORMAT_VALUES, Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES)
                .without(Option.SCHEMA_VERSION_INDICATOR);
        if (spec.closed) {
            config.with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);
        }
        config.forFields()
                // a component written only when it has a value (@JsonInclude(NON_NULL)) is optional: how a
                // property is added to a message without breaking what older producers wrote
                .withRequiredCheck(field -> !field.isFakeContainerItemScope() && !omittedWhenNull(field))
                .withNullableCheck(field -> !field.getType().isPrimitive());
        config.forMethods().withRequiredCheck(method -> true);
        config.forTypesInGeneral()
                .withDefinitionNamingStrategy((key, context) -> name(key.getType().getErasedType()))
                // In declaration order: a record's components as it lists them, as Jackson writes them.
                .withPropertySorter((a, b) -> 0);
        var generator = new com.github.victools.jsonschema.generator.SchemaGenerator(config.build());

        var variants = variants(spec);
        SchemaBuilder builder = generator.buildMultipleSchemaDefinitions();
        var refs = new LinkedHashMap<String, ObjectNode>();
        if (variants.isEmpty()) {
            refs.put("", builder.createSchemaReference(spec.root));
        } else {
            variants.forEach((name, type) -> refs.put(name, builder.createSchemaReference(type)));
        }
        var defs = builder.collectDefinitions("$defs");

        var schema = mapper.createObjectNode();
        schema.put("$schema", DRAFT);
        schema.put("$id", "urn:ec-demo1:topic:%s:v%d".formatted(spec.topic, spec.version));
        schema.put("title", "%s v%d".formatted(spec.topic, spec.version));
        if (spec.description != null) {
            schema.put("description", spec.description);
        }
        schema.put("x-topic", spec.topic);
        schema.put("x-version", spec.version);
        schema.put("x-owner", spec.owner);
        schema.put("x-key", spec.key);
        spec.producers.forEach(schema.putArray("x-producers")::add);
        spec.consumers.forEach(schema.putArray("x-consumers")::add);
        schema.put("x-java-type", (spec.root != null ? spec.root : variants.values().iterator().next()).getName());
        if (variants.isEmpty()) {
            schema.setAll(refs.get(""));
        } else {
            var discriminator = discriminator(spec);
            schema.put("x-discriminator", discriminator);
            ArrayNode oneOf = schema.putArray("oneOf");
            refs.forEach((name, ref) -> {
                var target = target(ref, defs);
                var properties = target.has("properties") ? (ObjectNode) target.get("properties") : target.putObject("properties");
                var constant = mapper.createObjectNode().put("const", name);
                // The discriminator first, as Jackson writes it.
                var reordered = mapper.createObjectNode();
                reordered.set(discriminator, constant);
                properties.fields().forEachRemaining(e -> {
                    if (!e.getKey().equals(discriminator)) {
                        reordered.set(e.getKey(), e.getValue());
                    }
                });
                target.set("properties", reordered);
                var required = target.has("required") ? (ArrayNode) target.get("required") : target.putArray("required");
                var names = new java.util.ArrayList<String>();
                names.add(discriminator);
                required.forEach(n -> {
                    if (!n.asText().equals(discriminator)) {
                        names.add(n.asText());
                    }
                });
                required.removeAll();
                names.forEach(required::add);
                oneOf.add(ref);
            });
        }
        schema.set("$defs", defs);
        return schema;
    }

    /** The definition a reference points at, or the node itself when it is inline. */
    static ObjectNode target(ObjectNode ref, ObjectNode defs) {
        var pointer = ref.path("$ref").asText("");
        if (pointer.startsWith("#/$defs/")) {
            return (ObjectNode) defs.get(pointer.substring("#/$defs/".length()));
        }
        return ref;
    }

    static Map<String, Class<?>> variants(TopicSpec spec) {
        if (!spec.variants.isEmpty()) {
            return spec.variants;
        }
        var result = new LinkedHashMap<String, Class<?>>();
        var subTypes = spec.root.getAnnotation(JsonSubTypes.class);
        if (spec.root.getAnnotation(JsonTypeInfo.class) != null && subTypes != null) {
            for (var type : subTypes.value()) {
                var name = !type.name().isEmpty() ? type.name()
                        : type.value().getAnnotation(JsonTypeName.class) != null
                        ? type.value().getAnnotation(JsonTypeName.class).value() : type.value().getSimpleName();
                result.put(name, type.value());
            }
        }
        return result;
    }

    static String discriminator(TopicSpec spec) {
        if (spec.discriminator != null) {
            return spec.discriminator;
        }
        var info = spec.root.getAnnotation(JsonTypeInfo.class);
        return info.property().isEmpty() ? "@type" : info.property();
    }

    static String name(Class<?> type) {
        var name = type.getName();
        var pkg = type.getPackageName();
        return (pkg.isEmpty() ? name : name.substring(pkg.length() + 1)).replace('$', '.');
    }

    static JsonNode examples(TopicSpec spec) {
        var mapper = ContractJson.mapper();
        var array = mapper.createArrayNode();
        var writer = spec.root != null ? mapper.writerFor(spec.root) : mapper.writer();
        for (var example : spec.examples) {
            try {
                // A String is JSON as the producer wrote it — for messages only its own mapper can write.
                array.add(mapper.readTree(example instanceof String json ? json : writer.writeValueAsString(example)));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot write the example " + example, e);
            }
        }
        return array;
    }
}
