package org.evosuite.testparser;

import org.evosuite.testcase.TestCodeVisitor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementParserTryFlatteningTest {

    public static class TokenSource {
        public Object getNextToken() {
            return new Object();
        }
    }

    @Test
    void llmTryFlatteningKeepsAssertionAfterAssignment() {
        TestParser parser = new TestParser(getClass().getClassLoader());
        parser.setMarkParsedFromLlm(true);

        ParseResult r = parser.parseTestMethodBody(
                "org.evosuite.testparser.StatementParserTryFlatteningTest.TokenSource tm = "
                        + "new org.evosuite.testparser.StatementParserTryFlatteningTest.TokenSource();\n"
                        + "Object t = null;\n"
                        + "try {\n"
                        + "  t = tm.getNextToken();\n"
                        + "} catch (RuntimeException e) {\n"
                        + "  fail(\"unexpected\");\n"
                        + "}\n"
                        + "assertNotNull(t);",
                List.of("import static org.junit.jupiter.api.Assertions.*;"));

        assertFalse(r.hasErrors(), "LLM try-flattening should keep statements parseable: " + r.getDiagnostics());

        TestCodeVisitor visitor = new TestCodeVisitor();
        r.getTestCase().accept(visitor);
        String code = visitor.getCode();

        int assignIndex = code.indexOf("getNextToken()");
        int assertIndex = code.indexOf("assertNotNull(");
        assertTrue(assignIndex >= 0, "Expected token assignment call in generated code:\n" + code);
        assertTrue(assertIndex > assignIndex, "assertNotNull must stay after token assignment:\n" + code);
    }
}

