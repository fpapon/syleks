/*
 * Copyright (c) 2026 - present - Francois Papon - francois.papon@openobject.fr
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package fr.openobject.ai.syleks.json;

import io.yupiik.fusion.json.schema.validation.JsonSchemaValidatorFactory;
import io.yupiik.fusion.json.schema.validation.ValidationResult.ValidationError;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Fusion validation with the structural keywords used by configuration and tool schemas. */
public final class SchemaValidator {
    private static final Set<String> STRUCTURAL =
            Set.of(
                    "$ref",
                    "$defs",
                    "definitions",
                    "properties",
                    "patternProperties",
                    "additionalProperties",
                    "propertyNames",
                    "items",
                    "contains",
                    "required",
                    "allOf",
                    "anyOf",
                    "oneOf",
                    "not",
                    "if",
                    "then",
                    "else",
                    "const",
                    "enum");
    // Do not silently weaken extension schemas that need draft features unavailable in Fusion.
    private static final Set<String> UNSUPPORTED =
            Set.of(
                    "$dynamicRef",
                    "$recursiveRef",
                    "dependentRequired",
                    "dependentSchemas",
                    "dependencies",
                    "prefixItems",
                    "additionalItems",
                    "unevaluatedItems",
                    "unevaluatedProperties",
                    "minContains",
                    "maxContains");
    private final Predicate<Object> validator;

    public SchemaValidator(JsonValue schema) {
        this(new JsonIO().convert(schema, Map.class));
    }

    public SchemaValidator(Map<String, Object> schema) {
        validator = new Compiler(schema).compile(schema);
    }

    public List<ValidationError> validate(JsonValue value) {
        return validate(value.value());
    }

    public List<ValidationError> validate(Object value) {
        return validator.test(value)
                ? List.of()
                : List.of(new ValidationError("", "Value does not match schema"));
    }

    private static final class Compiler {
        private final Map<String, Object> root;
        private final JsonSchemaValidatorFactory fusion = new JsonSchemaValidatorFactory();
        private final Map<Object, Predicate<Object>> compiled = new IdentityHashMap<>();

        private Compiler(Map<String, Object> root) {
            this.root = root;
        }

        @SuppressWarnings("unchecked")
        private Predicate<Object> compile(Object definition) {
            if (definition instanceof Boolean allowed) return value -> allowed;
            if (!(definition instanceof Map<?, ?>))
                throw new IllegalArgumentException("Expected an object or boolean schema");
            var cached = compiled.get(definition);
            if (cached != null) return cached;
            // Resolve recursive local references only when validating an instance.
            var target = new ArrayList<Predicate<Object>>(1);
            Predicate<Object> deferred = value -> target.getFirst().test(value);
            compiled.put(definition, deferred);
            var schema = (Map<String, Object>) definition;
            if (schema.keySet().stream().anyMatch(UNSUPPORTED::contains))
                throw new IllegalArgumentException("Unsupported JSON schema validation keyword");
            var checks = new ArrayList<Predicate<Object>>();
            if (schema.containsKey("$ref")) checks.add(compile(reference(schema.get("$ref"))));

            var scalar = new LinkedHashMap<>(schema);
            STRUCTURAL.forEach(scalar::remove);
            var type = scalar.remove("type");
            // Fusion treats null as absent and requires a type even for an untyped schema.
            // Validate present nulls and type unions explicitly, and let Fusion check each value.
            var leaves = new HashMap<String, Predicate<Object>>();
            for (String kind : List.of("object", "array", "string", "number", "boolean", "null")) {
                var leaf = new LinkedHashMap<>(scalar);
                leaf.put("type", kind);
                var validator = fusion.newInstance(leaf);
                leaves.put(kind, value -> validator.apply(value).isSuccess());
            }
            checks.add(
                    value -> {
                        String actual = typeOf(value);
                        if (type != null) {
                            var types = type instanceof List<?> list ? list : List.of(type);
                            boolean matches =
                                    types.contains(actual)
                                            || (actual.equals("number")
                                                    && types.contains("integer")
                                                    && new java.math.BigDecimal(value.toString())
                                                                    .stripTrailingZeros()
                                                                    .scale()
                                                            <= 0);
                            if (!matches) return false;
                        }
                        return leaves.get(actual).test(value);
                    });
            if (schema.containsKey("const"))
                checks.add(value -> equalJson(value, schema.get("const")));
            if (schema.get("enum") instanceof List<?> values)
                checks.add(
                        value ->
                                values.stream().anyMatch(candidate -> equalJson(value, candidate)));
            for (String keyword : List.of("allOf", "anyOf", "oneOf")) {
                if (schema.get(keyword) instanceof List<?> alternatives) {
                    var branches = alternatives.stream().map(this::compile).toList();
                    checks.add(
                            value -> {
                                long count =
                                        branches.stream()
                                                .filter(branch -> branch.test(value))
                                                .count();
                                return switch (keyword) {
                                    case "allOf" -> count == branches.size();
                                    case "anyOf" -> count > 0;
                                    default -> count == 1;
                                };
                            });
                }
            }
            if (schema.containsKey("not")) checks.add(compile(schema.get("not")).negate());
            if (schema.containsKey("if")) {
                var condition = compile(schema.get("if"));
                var thenBranch = compile(schema.getOrDefault("then", true));
                var elseBranch = compile(schema.getOrDefault("else", true));
                checks.add(value -> (condition.test(value) ? thenBranch : elseBranch).test(value));
            }
            var properties = new LinkedHashMap<String, Predicate<Object>>();
            if (schema.get("properties") instanceof Map<?, ?> props)
                props.forEach((key, child) -> properties.put((String) key, compile(child)));
            var patterns = new LinkedHashMap<Pattern, Predicate<Object>>();
            if (schema.get("patternProperties") instanceof Map<?, ?> props)
                props.forEach(
                        (key, child) ->
                                patterns.put(Pattern.compile((String) key), compile(child)));
            var additional = compile(schema.getOrDefault("additionalProperties", true));
            var names = compile(schema.getOrDefault("propertyNames", true));
            var required = (List<String>) schema.getOrDefault("required", List.of());
            checks.add(
                    value -> {
                        if (!(value instanceof Map<?, ?> object)) return true;
                        if (!object.keySet().containsAll(required)) return false;
                        for (var entry : object.entrySet()) {
                            String key = (String) entry.getKey();
                            Object child = entry.getValue();
                            if (!names.test(key)) return false;
                            boolean matched = properties.containsKey(key);
                            if (matched && !properties.get(key).test(child)) return false;
                            for (var pattern : patterns.entrySet()) {
                                if (pattern.getKey().matcher(key).find()) {
                                    matched = true;
                                    if (!pattern.getValue().test(child)) return false;
                                }
                            }
                            if (!matched && !additional.test(child)) return false;
                        }
                        return true;
                    });
            if (schema.containsKey("items")) {
                var items = compile(schema.get("items"));
                checks.add(
                        value -> !(value instanceof List<?> list) || list.stream().allMatch(items));
            }
            if (schema.containsKey("contains")) {
                var contains = compile(schema.get("contains"));
                checks.add(
                        value ->
                                !(value instanceof List<?> list)
                                        || list.stream().anyMatch(contains));
            }
            Predicate<Object> result =
                    value -> checks.stream().allMatch(check -> check.test(value));
            target.add(result);
            return deferred;
        }

        private Object reference(Object ref) {
            if (!(ref instanceof String pointer) || !pointer.startsWith("#/"))
                throw new IllegalArgumentException(
                        "Only local JSON schema references are supported");
            Object value = root;
            for (String part : pointer.substring(2).split("/", -1)) {
                String key = part.replace("~1", "/").replace("~0", "~");
                value = value instanceof Map<?, ?> map ? map.get(key) : null;
            }
            if (value == null)
                throw new IllegalArgumentException("Unresolved JSON schema reference");
            return value;
        }
    }

    private static String typeOf(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?>) return "object";
        if (value instanceof List<?>) return "array";
        if (value instanceof String) return "string";
        if (value instanceof Number) return "number";
        if (value instanceof Boolean) return "boolean";
        throw new IllegalArgumentException("Expected a JSON value");
    }

    private static boolean equalJson(Object left, Object right) {
        if (left instanceof Number && right instanceof Number)
            return new java.math.BigDecimal(left.toString())
                            .compareTo(new java.math.BigDecimal(right.toString()))
                    == 0;
        if (left instanceof List<?> a && right instanceof List<?> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!equalJson(a.get(i), b.get(i))) return false;
            return true;
        }
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b)
            return a.keySet().equals(b.keySet())
                    && a.entrySet().stream()
                            .allMatch(entry -> equalJson(entry.getValue(), b.get(entry.getKey())));
        return Objects.equals(left, right);
    }
}
