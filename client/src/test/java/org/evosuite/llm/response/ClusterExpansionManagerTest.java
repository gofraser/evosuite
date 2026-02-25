package org.evosuite.llm.response;

import org.evosuite.TestGenerationContext;
import org.evosuite.setup.TestClusterGenerator;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testparser.ParseDiagnostic;
import org.evosuite.testparser.ParseResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClusterExpansionManagerTest {

    private TestClusterGenerator previous;

    @AfterEach
    void restoreGenerator() {
        TestGenerationContext.getInstance().setTestClusterGenerator(previous);
    }

    @Test
    void extractsUnresolvedSymbolsFromDiagnostics() {
        ClusterExpansionManager manager = new ClusterExpansionManager(getClass().getClassLoader());
        ParseResult result = new ParseResult(new DefaultTestCase(), "test");
        result.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                "cannot find symbol java.util.ArrayList", 1,
                "ArrayList list = new ArrayList();"));

        Set<String> symbols = manager.extractUnresolvedSymbols(Collections.singletonList(result));

        assertTrue(symbols.contains("java.util.ArrayList"));
        assertTrue(symbols.contains("ArrayList"));
    }

    @Test
    void expandsClusterWhenResolvableClassExists() {
        previous = TestGenerationContext.getInstance().getTestClusterGenerator();
        TestClusterGenerator generator = mock(TestClusterGenerator.class);
        TestGenerationContext.getInstance().setTestClusterGenerator(generator);

        ClusterExpansionManager manager = new ClusterExpansionManager(getClass().getClassLoader());
        ParseResult result = new ParseResult(new DefaultTestCase(), "test");
        result.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                "cannot find symbol java.util.ArrayList", 1,
                "java.util.ArrayList list = new java.util.ArrayList();"));

        assertTrue(manager.tryExpandFrom(Collections.singletonList(result)));

        ArgumentCaptor<java.util.Collection<Class<?>>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(generator).addNewDependencies(captor.capture());
        assertTrue(captor.getValue().contains(java.util.ArrayList.class));
    }
}
