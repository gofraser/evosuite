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

    public static class BrokerAttribute {
    }

    public static class Broker {
        public BrokerAttribute[] getConnectionAttributesArray() {
            return new BrokerAttribute[]{new BrokerAttribute()};
        }

        public void release(BrokerAttribute attr) {
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

    @Test
    void llmTryFlatteningParsesTryResourcesBeforeBody() {
        TestParser parser = new TestParser(getClass().getClassLoader());
        parser.setMarkParsedFromLlm(true);

        ParseResult r = parser.parseTestMethodBody(
                "try (java.io.StringReader reader0 = new java.io.StringReader(\"x\")) {\n"
                        + "  reader0.toString();\n"
                        + "}\n"
                        + "assertTrue(true);",
                List.of("import static org.junit.jupiter.api.Assertions.*;"));

        assertFalse(r.hasErrors(), "LLM try-flattening with resources should stay parseable: " + r.getDiagnostics());

        TestCodeVisitor visitor = new TestCodeVisitor();
        r.getTestCase().accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("new StringReader("),
                "Try-with-resources declarations must be parsed before flattened try-body statements:\n" + code);
        assertTrue(code.contains("toString()"),
                "Flattened try-body should still execute resource-variable method call without unresolved-variable fallback:\n" + code);
    }

    @Test
    void llmIfFlatteningEliminatesLambdaCaptureInAssertions() {
        TestParser parser = new TestParser(getClass().getClassLoader());
        parser.setMarkParsedFromLlm(true);

        ParseResult r = parser.parseTestMethodBody(
                "org.evosuite.testparser.StatementParserTryFlatteningTest.Broker dbConnectionBroker0 = "
                        + "new org.evosuite.testparser.StatementParserTryFlatteningTest.Broker();\n"
                        + "org.evosuite.testparser.StatementParserTryFlatteningTest.BrokerAttribute[] dbConnectionAttributesArray0 = "
                        + "dbConnectionBroker0.getConnectionAttributesArray();\n"
                        + "org.evosuite.testparser.StatementParserTryFlatteningTest.BrokerAttribute attr = null;\n"
                        + "org.evosuite.testparser.StatementParserTryFlatteningTest.BrokerAttribute dbConnectionAttributes0 = attr;\n"
                        + "if (dbConnectionAttributesArray0.length > 0) {\n"
                        + "  dbConnectionAttributes0 = dbConnectionAttributesArray0[0];\n"
                        + "}\n"
                        + "if (dbConnectionAttributes0 != null) {\n"
                        + "  assertDoesNotThrow(() -> dbConnectionBroker0.release(dbConnectionAttributes0));\n"
                        + "} else {\n"
                        + "  assertThrows(NullPointerException.class, () -> dbConnectionBroker0.release(null));\n"
                        + "}\n",
                List.of("import static org.junit.jupiter.api.Assertions.*;"));

        assertFalse(r.hasErrors(), "LLM if-flattening should avoid raw lambda assertion preservation: " + r.getDiagnostics());

        TestCodeVisitor visitor = new TestCodeVisitor();
        r.getTestCase().accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("release("),
                "Expected flattened assertion lambda body call to be emitted directly:\n" + code);
        assertFalse(code.contains("assertDoesNotThrow"),
                "assertDoesNotThrow should be flattened to its body in LLM mode:\n" + code);
        assertFalse(code.contains("->"),
                "No raw lambda should remain after LLM if/assertion flattening:\n" + code);
    }
}
