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
package fr.openobject.ai.syleks.i18n;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolves the interface language without starting the application container. A saved value wins.
 * {@code system} follows {@code LC_ALL}, then {@code LC_MESSAGES}, then {@code LANG}, then the JVM
 * default. Encoding and modifiers are ignored. {@code en} and {@code fr} select the shipped
 * catalogs.
 */
public final class InterfaceLanguage {
    private static final Pattern TAG =
            Pattern.compile(
                    "system|[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,8})*", Pattern.CASE_INSENSITIVE);

    public record Selection(String tag, String catalog) {
        public String displayName() {
            String name = locale(tag).getDisplayLanguage(locale(catalog));
            return name == null || name.isBlank() ? tag : name;
        }
    }

    private InterfaceLanguage() {}

    public static boolean wellFormed(String value) {
        return value != null && TAG.matcher(value).matches();
    }

    public static Selection resolve(String configured, Map<String, String> environment) {
        String raw = configured == null || configured.isBlank() ? "system" : configured.strip();
        if (!wellFormed(raw)) throw new IllegalArgumentException("Invalid language");
        String tag = raw.equalsIgnoreCase("system") ? fromEnvironment(environment) : canonical(raw);
        return new Selection(tag, catalog(tag));
    }

    /**
     * Best-effort read for text shown before configuration is validated. A missing, unreadable, or
     * invalid language value follows the operating system.
     */
    public static Selection load(Path home, Map<String, String> environment) {
        String configured = readConfigured(home);
        if (configured == null || !wellFormed(configured)) return resolve("system", environment);
        return resolve(configured, environment);
    }

    public static Locale locale(String tag) {
        String[] parts = tag.split("_", 3);
        if (parts.length == 1) return Locale.of(parts[0]);
        return Locale.of(parts[0], parts[1]);
    }

    static String canonical(String raw) {
        String value = stripPosix(raw);
        if (value == null) return "en_US";
        value = value.replace('-', '_');
        String[] parts = value.split("_");
        var tag = new StringBuilder(parts[0].toLowerCase(Locale.ROOT));
        for (int index = 1; index < parts.length; index++) {
            String part = parts[index];
            if (part.isEmpty()) continue;
            tag.append('_');
            tag.append(
                    part.length() == 2 && part.chars().allMatch(Character::isLetter)
                            ? part.toUpperCase(Locale.ROOT)
                            : part.toLowerCase(Locale.ROOT));
        }
        return switch (tag.toString()) {
            case "en" -> "en_US";
            case "fr" -> "fr_FR";
            default -> tag.toString();
        };
    }

    static String catalog(String tag) {
        if (tag.equals("fr_FR")) return "fr_FR";
        return "en_US";
    }

    private static String fromEnvironment(Map<String, String> environment) {
        Map<String, String> env = environment == null ? Map.of() : environment;
        for (String key : new String[] {"LC_ALL", "LC_MESSAGES", "LANG"}) {
            String tag = stripPosix(env.get(key));
            if (tag != null) return canonical(tag);
        }
        String fallback = stripPosix(Locale.getDefault().toLanguageTag());
        return fallback == null ? "en_US" : canonical(fallback);
    }

    private static String stripPosix(String raw) {
        if (raw == null) return null;
        String value = raw.strip();
        int encoding = value.indexOf('.');
        if (encoding >= 0) value = value.substring(0, encoding);
        int modifier = value.indexOf('@');
        if (modifier >= 0) value = value.substring(0, modifier);
        if (value.isEmpty() || value.equalsIgnoreCase("C") || value.equalsIgnoreCase("POSIX"))
            return null;
        return value;
    }

    private static String readConfigured(Path home) {
        if (home == null) return null;
        Path file = home.resolve(".config/syleks/agent-config.json");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.length() > 1024 * 1024) return null;
            return topLevelString(content, "language");
        } catch (IOException | UncheckedIOException e) {
            return null;
        }
    }

    static String topLevelString(String json, String key) {
        var cursor = new Cursor(json);
        cursor.skipWs();
        if (!cursor.eat('{')) return null;
        while (true) {
            cursor.skipWs();
            if (cursor.eat('}')) return null;
            String name = cursor.string();
            if (name == null) return null;
            cursor.skipWs();
            if (!cursor.eat(':')) return null;
            cursor.skipWs();
            if (name.equals(key)) return cursor.peek() == '"' ? cursor.string() : null;
            if (!cursor.skipValue()) return null;
            cursor.skipWs();
            if (cursor.eat('}')) return null;
            if (!cursor.eat(',')) return null;
        }
    }

    private static final class Cursor {
        private final String text;
        private int index;

        private Cursor(String text) {
            this.text = text;
        }

        private int peek() {
            return index < text.length() ? text.charAt(index) : -1;
        }

        private void skipWs() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        }

        private boolean eat(char expected) {
            if (peek() != expected) return false;
            index++;
            return true;
        }

        private String string() {
            if (!eat('"')) return null;
            var value = new StringBuilder();
            while (index < text.length()) {
                char current = text.charAt(index++);
                if (current == '"') return value.toString();
                if (current != '\\') {
                    value.append(current);
                    continue;
                }
                if (index >= text.length()) return null;
                char escaped = text.charAt(index++);
                value.append(
                        switch (escaped) {
                            case '"', '\\', '/' -> escaped;
                            case 'b' -> '\b';
                            case 'f' -> '\f';
                            case 'n' -> '\n';
                            case 'r' -> '\r';
                            case 't' -> '\t';
                            case 'u' -> unicode();
                            default -> '\uFFFF';
                        });
                if (value.charAt(value.length() - 1) == '\uFFFF') return null;
            }
            return null;
        }

        private char unicode() {
            if (index + 4 > text.length()) return '\uFFFF';
            try {
                int code = Integer.parseInt(text.substring(index, index + 4), 16);
                index += 4;
                return (char) code;
            } catch (NumberFormatException e) {
                return '\uFFFF';
            }
        }

        private boolean skipValue() {
            return switch (peek()) {
                case '"' -> string() != null;
                case '{' -> skipContainer('{', '}');
                case '[' -> skipContainer('[', ']');
                case 't' -> literal("true");
                case 'f' -> literal("false");
                case 'n' -> literal("null");
                case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> number();
                default -> false;
            };
        }

        private boolean literal(String expected) {
            if (!text.startsWith(expected, index)) return false;
            index += expected.length();
            return true;
        }

        private boolean number() {
            int start = index;
            if (eat('-')) {
                // Sign is optional.
            }
            while (index < text.length() && "0123456789.eE+-".indexOf(text.charAt(index)) >= 0)
                index++;
            return index > start;
        }

        private boolean skipContainer(char open, char close) {
            if (!eat(open)) return false;
            int depth = 1;
            boolean inString = false;
            boolean escape = false;
            while (index < text.length() && depth > 0) {
                char current = text.charAt(index++);
                if (inString) {
                    if (escape) escape = false;
                    else if (current == '\\') escape = true;
                    else if (current == '"') inString = false;
                    continue;
                }
                if (current == '"') inString = true;
                else if (current == open) depth++;
                else if (current == close) depth--;
            }
            return depth == 0;
        }
    }
}
