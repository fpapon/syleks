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

import io.yupiik.fusion.json.internal.codec.ObjectJsonCodec;
import io.yupiik.fusion.json.internal.parser.BufferProvider;
import io.yupiik.fusion.json.internal.parser.JsonParser;
import io.yupiik.fusion.json.serialization.ExtendedWriter;
import io.yupiik.fusion.json.serialization.JsonCodec;
import io.yupiik.fusion.json.spi.Parser;
import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Generic JSON trees using Fusion's parser and codec, with bounded, strict document reads. */
public final class JsonIO {
    private static final ObjectJsonCodec CODEC =
            new ObjectJsonCodec() {
                @Override
                public void write(Object value, JsonCodec.SerializationContext context)
                        throws IOException {
                    value = JsonValue.unwrap(value);
                    if ((value instanceof Double || value instanceof Float)
                            && !Double.isFinite(((Number) value).doubleValue()))
                        throw new IOException("Expected a finite JSON number");
                    super.write(value, context);
                }
            };
    private static final BufferProvider BUFFERS = new BufferProvider(8192, 32);
    private final int maxDepth;
    private final int maxString;
    private final int maxNumber;

    public JsonIO() {
        this(1000, 20_000_000, 1000);
    }

    public JsonIO(int maxDepth, int maxString, int maxNumber) {
        this.maxDepth = maxDepth;
        this.maxString = maxString;
        this.maxNumber = maxNumber;
    }

    public JsonValue parse(String text) throws IOException {
        if (text == null || text.indexOf(0) >= 0) throw new IOException("Invalid JSON document");
        // Keep trailing characters in the parser's buffer, including after a scalar root.
        try (var parser =
                new JsonParser(
                        new io.yupiik.fusion.json.deserialization.AvailableCharArrayReader(
                                (text + "\n ").toCharArray()),
                        8192,
                        BUFFERS,
                        true)) {
            Object value = value(parser, parser.next(), 0);
            if (parser.hasNext()) throw new IOException("Trailing JSON content");
            return JsonValue.of(value);
        } catch (RuntimeException e) {
            // Parser diagnostics can contain credential-bearing input.
            throw new IOException("Invalid JSON document");
        }
    }

    public JsonValue parse(byte[] bytes) throws IOException {
        String text =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
        return parse(text);
    }

    public JsonValue parse(InputStream stream) throws IOException {
        return parse(stream.readAllBytes());
    }

    public JsonValue parse(File file) throws IOException {
        return parse(Files.readAllBytes(file.toPath()));
    }

    private Object value(Parser parser, Parser.Event event, int depth) throws IOException {
        return switch (event) {
            case START_OBJECT -> {
                if (depth >= maxDepth) throw new IOException("JSON nesting limit exceeded");
                var object = new LinkedHashMap<String, Object>();
                while (true) {
                    var next = parser.next();
                    if (next == Parser.Event.END_OBJECT) break;
                    if (next != Parser.Event.KEY_NAME) throw new IOException("Expected JSON field");
                    String key = string(parser);
                    if (object.containsKey(key)) throw new IOException("Duplicate JSON field");
                    object.put(key, value(parser, parser.next(), depth + 1));
                }
                yield object;
            }
            case START_ARRAY -> {
                if (depth >= maxDepth) throw new IOException("JSON nesting limit exceeded");
                var array = new ArrayList<>();
                while (true) {
                    var next = parser.next();
                    if (next == Parser.Event.END_ARRAY) break;
                    array.add(value(parser, next, depth + 1));
                }
                yield array;
            }
            case VALUE_STRING -> string(parser);
            case VALUE_NUMBER -> {
                String token = parser.getString();
                if (token.length() > maxNumber) throw new IOException("JSON number limit exceeded");
                if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0)
                    yield new BigDecimal(token);
                var integer = new BigInteger(token);
                if (integer.bitLength() < 32) yield integer.intValue();
                if (integer.bitLength() < 64) yield integer.longValue();
                yield integer;
            }
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            case VALUE_NULL -> null;
            default -> throw new IOException("Expected JSON value");
        };
    }

    private String string(Parser parser) throws IOException {
        if (parser.getChars().remaining() > maxString)
            throw new IOException("JSON string limit exceeded");
        return parser.getString();
    }

    public String write(Object value) throws IOException {
        var writer = new StringWriter();
        CODEC.write(
                JsonValue.unwrap(value),
                new JsonCodec.SerializationContext(
                        new ExtendedWriter(writer), ignored -> null, true));
        return writer.toString();
    }

    public byte[] bytes(Object value) throws IOException {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    public <T> T read(String text, Class<T> type) throws IOException {
        return type.cast(parse(text).value());
    }

    public <T> T read(byte[] bytes, Class<T> type) throws IOException {
        return type.cast(parse(bytes).value());
    }

    public JsonValue tree(Object value) {
        return JsonValue.of(JsonValue.copy(value));
    }

    public <T> T convert(Object value, Class<T> type) {
        return type.cast(JsonValue.copy(value));
    }

    public JsonValue object() {
        return JsonValue.of(new LinkedHashMap<String, Object>());
    }

    public JsonValue nullValue() {
        return JsonValue.of(null);
    }
}
