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

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** Navigable view of Fusion JSON's maps, lists and scalar values. Child views share mutations. */
public final class JsonValue implements Iterable<JsonValue> {
    private static final Object MISSING = new Object();
    private static final JsonValue ABSENT = new JsonValue(MISSING);
    private static final JsonIO JSON = new JsonIO();
    private final Object value;

    private JsonValue(Object value) {
        this.value = value;
    }

    public static JsonValue of(Object value) {
        return value instanceof JsonValue json ? json : new JsonValue(value);
    }

    public Object value() {
        return value == MISSING ? null : value;
    }

    static Object unwrap(Object value) {
        return value instanceof JsonValue json ? json.value() : value;
    }

    static Object copy(Object value) {
        value = unwrap(value);
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, child) -> result.put((String) key, copy(child)));
            return result;
        }
        if (value instanceof Collection<?> list)
            return list.stream()
                    .map(JsonValue::copy)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number number) {
            if ((number instanceof Double || number instanceof Float)
                    && !Double.isFinite(number.doubleValue()))
                throw new IllegalArgumentException("Expected a finite JSON number");
            return value;
        }
        throw new IllegalArgumentException("Expected a JSON value");
    }

    public JsonValue path(String key) {
        return value instanceof Map<?, ?> map && map.containsKey(key) ? of(map.get(key)) : ABSENT;
    }

    public JsonValue get(String key) {
        var child = path(key);
        return child == ABSENT ? null : child;
    }

    public JsonValue get(int index) {
        return value instanceof List<?> list && index >= 0 && index < list.size()
                ? of(list.get(index))
                : null;
    }

    public boolean has(String key) {
        return value instanceof Map<?, ?> map && map.containsKey(key);
    }

    public boolean hasNonNull(String key) {
        return has(key) && !path(key).isNull();
    }

    public boolean isObject() {
        return value instanceof Map<?, ?>;
    }

    public boolean isArray() {
        return value instanceof List<?>;
    }

    public boolean isTextual() {
        return value instanceof String;
    }

    public boolean isNumber() {
        return value instanceof Number;
    }

    public boolean isBoolean() {
        return value instanceof Boolean;
    }

    public boolean isNull() {
        return value == null;
    }

    public boolean isMissingNode() {
        return value == MISSING;
    }

    public boolean isIntegralNumber() {
        return value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger
                || value instanceof Short
                || value instanceof Byte;
    }

    public boolean canConvertToInt() {
        return inRange(Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public boolean canConvertToLong() {
        return inRange(Long.MIN_VALUE, Long.MAX_VALUE);
    }

    private boolean inRange(long min, long max) {
        if (!(value instanceof Number)) return false;
        var number = new BigDecimal(value.toString());
        return number.compareTo(BigDecimal.valueOf(min)) >= 0
                && number.compareTo(BigDecimal.valueOf(max)) <= 0;
    }

    public String asText() {
        return asText(value == null ? "null" : "");
    }

    public String asText(String fallback) {
        return value == null || value == MISSING
                ? fallback
                : value instanceof String || value instanceof Number || value instanceof Boolean
                        ? value.toString()
                        : "";
    }

    public int asInt() {
        return value instanceof Number n ? n.intValue() : 0;
    }

    public long asLong() {
        return value instanceof Number n ? n.longValue() : 0;
    }

    public double asDouble() {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    public int intValue() {
        return asInt();
    }

    public long longValue() {
        return asLong();
    }

    public boolean booleanValue() {
        return asBoolean();
    }

    public boolean asBoolean() {
        return asBoolean(false);
    }

    public boolean asBoolean(boolean fallback) {
        return value instanceof Boolean b ? b : fallback;
    }

    public int size() {
        return value instanceof Map<?, ?> map
                ? map.size()
                : value instanceof List<?> list ? list.size() : 0;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object() {
        if (!(value instanceof Map<?, ?>))
            throw new IllegalStateException("Expected a JSON object");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> array() {
        if (!(value instanceof List<?>)) throw new IllegalStateException("Expected a JSON array");
        return (List<Object>) value;
    }

    public JsonValue put(String key, Object child) {
        object().put(key, unwrap(child));
        return this;
    }

    public JsonValue set(String key, JsonValue child) {
        return put(key, child);
    }

    public JsonValue putNull(String key) {
        return put(key, null);
    }

    public JsonValue putObject(String key) {
        var child = of(new LinkedHashMap<String, Object>());
        put(key, child);
        return child;
    }

    public JsonValue putArray(String key) {
        var child = of(new ArrayList<>());
        put(key, child);
        return child;
    }

    public JsonValue withArray(String key) {
        return has(key) ? path(key) : putArray(key);
    }

    public JsonValue add(Object child) {
        array().add(unwrap(child));
        return this;
    }

    public JsonValue addObject() {
        var child = of(new LinkedHashMap<String, Object>());
        add(child);
        return child;
    }

    public void remove(String key) {
        object().remove(key);
    }

    public void remove(int index) {
        array().remove(index);
    }

    public JsonValue deepCopy() {
        return of(copy(value));
    }

    public Iterator<Map.Entry<String, JsonValue>> fields() {
        return object().entrySet().stream()
                .map(
                        entry ->
                                new AbstractMap.SimpleImmutableEntry<>(
                                        entry.getKey(), of(entry.getValue())))
                .map(entry -> (Map.Entry<String, JsonValue>) entry)
                .iterator();
    }

    public Iterator<JsonValue> elements() {
        return iterator();
    }

    @Override
    public Iterator<JsonValue> iterator() {
        Collection<?> values =
                value instanceof Map<?, ?> map
                        ? map.values()
                        : value instanceof List<?> list ? list : List.of();
        return values.stream().map(JsonValue::of).iterator();
    }

    @Override
    public String toString() {
        try {
            return JSON.write(value());
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JsonValue json && Objects.equals(value, json.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }
}
