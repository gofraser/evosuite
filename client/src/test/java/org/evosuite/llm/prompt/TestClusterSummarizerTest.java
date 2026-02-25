package org.evosuite.llm.prompt;

import org.evosuite.setup.TestCluster;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class TestClusterSummarizerTest {

    @Test
    void summarizeUsesClusterSurfacesBeyondAnalyzedClasses() {
        TestCluster cluster = mock(TestCluster.class);
        when(cluster.getAnalyzedClasses()).thenReturn(Collections.<Class<?>>emptySet());

        GenericAccessibleObject<?> call = mock(GenericAccessibleObject.class);
        GenericClass<?> owner = GenericClassFactory.get(String.class);
        GenericClass<?> generated = GenericClassFactory.get(Integer.class);
        doReturn(owner).when(call).getOwnerClass();
        doReturn(generated).when(call).getGeneratedClass();

        when(cluster.getGenerators()).thenReturn(Collections.<GenericAccessibleObject<?>>singleton(call));
        when(cluster.getModifiers()).thenReturn(Collections.<GenericAccessibleObject<?>>emptySet());
        when(cluster.getTestCalls()).thenReturn(Arrays.<GenericAccessibleObject<?>>asList(call));

        TestClusterSummarizer summarizer = new TestClusterSummarizer();
        String summary = summarizer.summarize(cluster);

        assertTrue(summary.contains("java.lang.String"));
        assertTrue(summary.contains("java.lang.Integer"));
    }
}
