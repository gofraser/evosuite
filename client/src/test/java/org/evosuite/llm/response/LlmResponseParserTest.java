package org.evosuite.llm.response;

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmResponseParserTest {

    private final LlmResponseParser parser = new LlmResponseParser();
    private final Properties.OutputFormat originalFormat = Properties.TEST_FORMAT;

    @AfterEach
    void restoreFormat() {
        Properties.TEST_FORMAT = originalFormat;
    }

    @Test
    void extractsJavaCodeFences() {
        String response = "Here is code:\n```java\n@Test\npublic void t(){}\n```";
        List<String> blocks = parser.extractCodeBlocks(response);

        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).contains("public void t"));
    }

    @Test
    void wrapsMethodBodyIntoClass() {
        String response = "```java\n@org.junit.Test\npublic void x(){ int a = 1; }\n```";
        String code = parser.extractTestClass(response, "MyGeneratedTest");

        assertTrue(code.contains("public class MyGeneratedTest"));
        assertTrue(code.contains("public void x()"));
    }

    @Test
    void emptyFallbackUsesJUnit4ByDefault() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        String code = parser.extractTestClass("", "MyGeneratedTest");

        assertTrue(code.contains("@org.junit.Test"));
    }

    @Test
    void emptyFallbackUsesJUnit5WhenConfigured() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        String code = parser.extractTestClass("", "MyGeneratedTest");

        assertTrue(code.contains("@org.junit.jupiter.api.Test"));
        assertFalse(code.contains("@org.junit.Test"));
    }

    @Test
    void genericFenceWithProseIsNotMisdetectedAsJava() {
        String response = "```\\nIn class design discussions we assert quality and readability.\\n```";
        List<String> blocks = parser.extractCodeBlocks(response);

        assertTrue(blocks.isEmpty());
    }
}
