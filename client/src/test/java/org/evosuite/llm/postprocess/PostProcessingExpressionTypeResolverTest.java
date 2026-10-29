package org.evosuite.llm.postprocess;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.Expression;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PostProcessingExpressionTypeResolverTest {

    private final PostProcessingExpressionTypeResolver resolver =
            new PostProcessingExpressionTypeResolver(null, null);

    @Test
    void anOperandTextIsParsedOnce() {
        Expression first = resolver.parse("v1.size() + 1");

        assertNotNull(first);
        assertSame(first, resolver.parse("v1.size() + 1"));
    }

    @Test
    void textThatIsNotAnExpressionHasNoTree() {
        assertNull(resolver.parse("v1 +"));
        assertNull(resolver.parse("v1 +"));
        assertNull(resolver.parse("  "));
    }

    @Test
    void aRememberedTreeIsFoundInsteadOfParsingAgain() {
        Expression parsed = StaticJavaParser.parseExpression("v1.isEmpty()");

        resolver.remember("v1.isEmpty()", parsed);

        assertSame(parsed, resolver.parse("v1.isEmpty()"));
    }

    @Test
    void resolvingAnOperandTextUsesTheSameTree() {
        assertEquals("int", resolver.resolve("42").typeName);
        assertFalse(resolver.resolve("42 +").isKnown());
    }
}
