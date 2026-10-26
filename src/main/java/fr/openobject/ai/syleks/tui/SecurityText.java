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
package fr.openobject.ai.syleks.tui;

/** Presentation only: never use this representation as a policy target or executable payload. */
public final class SecurityText {
    private SecurityText() {}

    /**
     * Label C0/C1, bidi controls, invisible separators/joiners, soft hyphen, BOM, fillers,
     * Unicode line separators and invisible tags. Preserve letters (including RTL), combining
     * marks, variation selectors and emoji. LF is both labelled and retained as a layout break.
     */
    public static String present(String value) {
        if (value == null) return "";
        var result = new StringBuilder();
        value.codePoints()
                .forEach(
                        cp -> {
                            if (labelled(cp)) {
                                result.append("[U+%04X]".formatted(cp));
                                if (cp == '\n') result.append('\n');
                            } else result.appendCodePoint(cp);
                        });
        return result.toString();
    }

    static boolean labelled(int cp) {
        return Character.isISOControl(cp)
                || cp == 0x00AD
                || cp == 0x034F
                || cp == 0x061C
                || cp == 0x115F
                || cp == 0x1160
                || cp == 0x17B4
                || cp == 0x17B5
                || cp == 0x180E
                || cp >= 0x200B && cp <= 0x200F
                || cp >= 0x2028 && cp <= 0x202E
                || cp >= 0x2060 && cp <= 0x206F
                || cp == 0x2800
                || cp == 0x3164
                || cp >= 0xD800 && cp <= 0xDFFF
                || cp == 0xFEFF
                || cp == 0xFFA0
                || cp >= 0xFFF9 && cp <= 0xFFFB
                || cp >= 0xE0000 && cp <= 0xE007F;
    }
}
