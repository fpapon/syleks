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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonIOTest {
    private final JsonIO json = new JsonIO();

    @Test
    void roundTripsGenericValuesWithoutQuotingNumbersOrDroppingNullFields() throws Exception {
        String source =
                "{\"number\":1.25,\"integer\":42,\"null\":null,\"array\":[true,\"é😀\",null]}";
        var value = json.parse(source);
        assertEquals(value, json.parse(json.bytes(value)));
        assertEquals("1.25", json.write(new BigDecimal("1.25")));
        assertTrue(value.has("null"));
        assertTrue(value.path("null").isNull());
        assertTrue(value.path("missing").isMissingNode());
        assertNull(value.get("missing"));
        assertEquals("fallback", value.path("missing").asText("fallback"));
        assertEquals("42", value.path("integer").asText());
        assertTrue(value.path("integer").isIntegralNumber());
        assertFalse(json.parse("1.0").isIntegralNumber());
    }

    @Test
    void rejectsDuplicateKeysTrailingTokensAndMalformedDocuments() {
        for (String input :
                List.of(
                        "",
                        "{\"a\":null,\"a\":1}",
                        "{\"a\":{\"b\":1,\"b\":2}}",
                        "{} {}",
                        "{}x",
                        "null true",
                        "1 2",
                        "trueX",
                        "[]]",
                        "[1,]",
                        "{\"a\":1,}",
                        "[",
                        "{",
                        "01",
                        "NaN",
                        "Infinity",
                        "1e",
                        "\"unterminated",
                        "{}\u0000{}"))
            assertThrows(IOException.class, () -> json.parse(input), input);
    }

    @Test
    void enforcesLimitsAndRejectsMalformedUtf8() throws Exception {
        var bounded = new JsonIO(2, 4, 3);
        assertTrue(bounded.parse("{\"x\":[123]}").isObject());
        for (String input : List.of("[[[]]]", "{\"longkey\":1}", "\"12345\"", "1234", "1e99"))
            assertThrows(IOException.class, () -> bounded.parse(input), input);
        assertThrows(IOException.class, () -> json.parse(new byte[] {(byte) 0xc3, 0x28}));
    }

    @Test
    void keepsMutationsLocalToExplicitCopiesAndSerializesNestedViews() throws Exception {
        var original = json.parse("{\"child\":{\"value\":1},\"array\":[null]}");
        var copy = original.deepCopy();
        copy.path("child").put("value", 2);
        copy.path("array").add("new");
        assertEquals(1, original.path("child").path("value").asInt());
        assertEquals(1, original.path("array").size());
        assertEquals(
                2,
                json.parse(json.write(Map.of("result", copy)))
                        .path("result")
                        .path("child")
                        .path("value")
                        .asInt());
        var generic = json.convert(original, Map.class);
        generic.clear();
        assertEquals(2, original.size());
    }

    @Test
    void preservesIntegerRangeChecksAndRejectsNonFiniteNumbers() throws Exception {
        assertTrue(json.parse("9223372036854775807").canConvertToLong());
        assertFalse(json.parse("9223372036854775808").canConvertToLong());
        assertTrue(json.parse("-2147483648").canConvertToInt());
        assertFalse(json.parse("2147483648").canConvertToInt());
        assertThrows(IOException.class, () -> json.write(Double.NaN));
        assertThrows(IOException.class, () -> json.write(Map.of("bad", Double.POSITIVE_INFINITY)));
    }
}
