package fr.openobject.ai.syleks.tui;

import static fr.openobject.ai.syleks.tui.TerminalCanvas.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SyntaxHighlighterTest {
    @Test
    void colorsKeywordsStringsNumbersAndCommentsWithoutChangingSource() {
        var line = "public class Example { String s = \"hi\"; int n = 42; } // comment";
        var tokens = new SyntaxHighlighter("java").highlight(line);
        assertEquals(line, text(tokens));
        assertEquals(PURPLE, color(tokens, "public"));
        assertEquals(YELLOW, color(tokens, "Example"));
        assertNotEquals(WHITE, color(tokens, "\"hi\""));
        assertNotEquals(WHITE, color(tokens, "42"));
        assertNotEquals(PURPLE, color(tokens, "// comment"));
    }

    @Test
    void supportsLanguageAliasesAndFileNames() {
        for (var entry :
                Map.of(
                                "js",
                                "const x = 1;",
                                "typescript",
                                "interface Example {}",
                                "Example.java",
                                "public class X {}",
                                "py",
                                "def example():",
                                "go",
                                "func example() {}",
                                "rs",
                                "fn example() {}",
                                "sql",
                                "SELECT x FROM t")
                        .entrySet()) {
            var tokens = new SyntaxHighlighter(entry.getKey()).highlight(entry.getValue());
            assertEquals(entry.getValue(), text(tokens));
            assertTrue(tokens.stream().anyMatch(t -> t.color() == PURPLE), entry.getKey());
        }
        for (var language :
                List.of("json", "yaml", "toml", "bash", "ruby", "kotlin", "cpp", "csharp", "css")) {
            var tokens = new SyntaxHighlighter(language).highlight("value = \"hello\" 42");
            assertTrue(tokens.stream().anyMatch(t -> t.color() != WHITE), language);
        }
    }

    @Test
    void preservesMultilineCommentAndStringStateWithinOneBlock() {
        var java = new SyntaxHighlighter("java");
        int comment = color(java.highlight("/* first"), "/*");
        assertEquals(comment, color(java.highlight("public class StillComment"), "public"));
        assertEquals(PURPLE, color(java.highlight("end */ public class Actual {}"), "public"));
        java.highlight("String text = \"\"\"");
        assertNotEquals(PURPLE, color(java.highlight("public class InTextBlock"), "public"));
        assertEquals(PURPLE, color(java.highlight("\"\"\"; public class After {}"), "public"));
        assertEquals(
                PURPLE,
                color(new SyntaxHighlighter("java").highlight("public class Fresh {}"), "public"));
    }

    @Test
    void colorsXmlTagsAttributesAndMultilineComments() {
        var xml = new SyntaxHighlighter("xml");
        var tokens = xml.highlight("<project version=\"1\">content</project>");
        assertEquals(BLUE, color(tokens, "project"));
        assertNotEquals(WHITE, color(tokens, "\"1\""));
        xml.highlight("<!-- comment");
        assertNotEquals(BLUE, color(xml.highlight("<hidden>"), "hidden"));
        assertEquals(BLUE, color(xml.highlight("--> <visible>"), "visible"));
    }

    @Test
    void preservesMarkdownAndAsciidocSourceButRendersInlineMarkup() {
        for (var language : List.of("md", "asciidoc", "adoc")) {
            var source = "A **bold** word, `code` and [link](https://example.com).";
            assertEquals(source, text(new SyntaxHighlighter(language).highlight(source)));
        }
        assertTrue(new SyntaxHighlighter("markdown").highlight("# Heading").getFirst().bold());
        assertTrue(new SyntaxHighlighter("adoc").highlight("= Heading").getFirst().bold());
        var inline =
                SyntaxHighlighter.inline(
                        "**Bold** and `code` with [docs](https://example.com)", WHITE, false);
        assertEquals("Bold and code with docs (https://example.com)", text(inline));
        assertTrue(inline.getFirst().bold());
        assertEquals(BLUE, color(inline, "code"));
    }

    @Test
    void usesPlainTextForUnknownLanguagesAndPreservesDiffColors() {
        var text = "a 🧪 + public \"value\"";
        assertEquals(
                List.of(new SyntaxHighlighter.Token(text, WHITE, false)),
                new SyntaxHighlighter("unknown").highlight(text));
        assertEquals(GREEN, new SyntaxHighlighter("diff").highlight("+ added").getFirst().color());
        assertEquals(RED, new SyntaxHighlighter("diff").highlight("- removed").getFirst().color());
    }

    private String text(List<SyntaxHighlighter.Token> tokens) {
        return tokens.stream().map(SyntaxHighlighter.Token::text).collect(Collectors.joining());
    }

    private int color(List<SyntaxHighlighter.Token> tokens, String fragment) {
        return tokens.stream()
                .filter(t -> t.text().contains(fragment))
                .findFirst()
                .orElseThrow()
                .color();
    }
}
