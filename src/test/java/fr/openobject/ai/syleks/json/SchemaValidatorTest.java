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

import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaValidatorTest {
    private final JsonIO json = new JsonIO();

    @Test
    void preservesLocalReferencesCombinatorsAndClosedObjects() throws Exception {
        var validator =
                schema(
                        """
                {"type":"object","$defs":{"count":{"type":"integer","minimum":1}},
                 "properties":{"count":{"$ref":"#/$defs/count"},"label":{"type":"string","minLength":2}},
                 "oneOf":[{"required":["count"]},{"required":["label"]}],"additionalProperties":false}
                """);
        assertValid(validator, "{\"count\":2}", "{\"label\":\"ok\"}");
        assertInvalid(
                validator,
                "{}",
                "{\"count\":0}",
                "{\"count\":1.5}",
                "{\"count\":null}",
                "{\"label\":\"x\"}",
                "{\"count\":2,\"label\":\"ok\"}",
                "{\"count\":2,\"extra\":true}");
    }

    @Test
    void validatesConditionalSchemasAndNullPresence() throws Exception {
        var validator =
                schema(
                        """
                {"type":"object","properties":{"mode":{"enum":["local","remote"]},
                 "token":{"type":["string","null"]}},"required":["mode"],
                 "if":{"properties":{"mode":{"const":"remote"}}},
                 "then":{"required":["token"]},"else":{"not":{"required":["token"]}}}
                """);
        assertValid(validator, "{\"mode\":\"local\"}", "{\"mode\":\"remote\",\"token\":null}");
        assertInvalid(
                validator,
                "{\"mode\":null}",
                "{\"mode\":\"remote\"}",
                "{\"mode\":\"local\",\"token\":null}");
    }

    @Test
    void validatesNestedPatternsAdditionalPropertiesAndArrayItems() throws Exception {
        var validator =
                schema(
                        """
                {"type":"object","properties":{"values":{"type":"object",
                 "propertyNames":{"pattern":"^[a-z]+$"},
                 "patternProperties":{"^fixed":{"type":"string"}},
                 "additionalProperties":{"type":"array","minItems":1,"maxItems":2,
                    "uniqueItems":true,"items":{"anyOf":[{"type":"integer"},{"const":null}]}}}},
                 "additionalProperties":false}
                """);
        assertValid(validator, "{}", "{\"values\":{\"fixedkey\":\"ok\",\"other\":[1,null]}}");
        assertInvalid(
                validator,
                "{\"values\":{\"BAD\":[1]}}",
                "{\"values\":{\"fixedkey\":1}}",
                "{\"values\":{\"other\":[1,1]}}",
                "{\"values\":{\"other\":[1.5]}}",
                "{\"values\":{\"other\":[]}}",
                "{\"values\":{\"other\":[1,2,3]}}");
    }

    @Test
    void resolvesRecursiveAndEscapedLocalReferences() throws Exception {
        var validator =
                schema(
                        """
                {"$defs":{"a/b":{"type":"object","properties":{"next":{"$ref":"#/$defs/a~1b"}},
                  "additionalProperties":false}},"$ref":"#/$defs/a~1b"}
                """);
        assertValid(validator, "{}", "{\"next\":{\"next\":{}}}");
        assertInvalid(validator, "{\"next\":null}", "{\"next\":{\"unknown\":true}}");
        for (String ref :
                List.of("https://example.com/schema", "file:///tmp/schema", "#/$defs/missing"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> schema(json.write(java.util.Map.of("$ref", ref))));
    }

    @Test
    void rejectsUnsupportedAssertionsInsteadOfIgnoringThem() {
        assertThrows(
                IllegalArgumentException.class,
                () -> schema("{\"dependentRequired\":{\"a\":[\"b\"]}}"));
    }

    private SchemaValidator schema(String content) throws Exception {
        return new SchemaValidator(json.parse(content));
    }

    private void assertValid(SchemaValidator validator, String... inputs) throws Exception {
        for (String input : inputs)
            assertTrue(validator.validate(json.parse(input)).isEmpty(), input);
    }

    private void assertInvalid(SchemaValidator validator, String... inputs) throws Exception {
        for (String input : inputs)
            assertFalse(validator.validate(json.parse(input)).isEmpty(), input);
    }
}
