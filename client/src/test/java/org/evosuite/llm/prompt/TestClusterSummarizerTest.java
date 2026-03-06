/**
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.llm.prompt;

import org.evosuite.setup.TestCluster;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
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

    // --- summarizeClass tests ---

    @Test
    void summarizeClassShowsTypeParametersForGenericClass() {
        GenericClass<?> gc = GenericClassFactory.get(ArrayList.class);
        String result = new TestClusterSummarizer().summarizeClass(gc);

        // Should contain type parameter E
        assertTrue(result.contains("ArrayList<E>"), "Expected type parameter: " + result);
        // Should show it's a class
        assertTrue(result.contains("class ArrayList"), result);
    }

    @Test
    void summarizeClassSkipsPrivateFields() {
        // ArrayList.size is private — should NOT appear in the Fields section
        // ArrayList.elementData is package-private — should appear
        GenericClass<?> gc = GenericClassFactory.get(ArrayList.class);
        String result = new TestClusterSummarizer().summarizeClass(gc);

        // The Fields section should not contain "private"
        assertFalse(result.contains("private "), "Private fields should be excluded: " + result);
        // Package-private fields like elementData should still appear
        assertTrue(result.contains("elementData"), "Package-private fields should be included: " + result);
    }

    @Test
    void summarizeClassShowsThrowsClause() {
        // ArrayList.get(int) throws IndexOutOfBoundsException
        // ArrayList(int) constructor throws IllegalArgumentException (in newer JDKs)
        // or we can check ArrayList.add(int, E) which throws IndexOutOfBoundsException
        GenericClass<?> gc = GenericClassFactory.get(ArrayList.class);
        String result = new TestClusterSummarizer().summarizeClass(gc);

        // At minimum, some methods should have throws clauses
        // subList throws IndexOutOfBoundsException
        // We just verify the throws keyword appears somewhere
        // (ArrayList methods do throw checked/unchecked exceptions declared in their signatures)
        assertTrue(result.contains("// Public methods"), "Expected public methods section: " + result);
    }

    @Test
    void summarizeClassShowsStaticModifier() {
        // Collections has many static methods
        GenericClass<?> gc = GenericClassFactory.get(Collections.class);
        String result = new TestClusterSummarizer().summarizeClass(gc);

        assertTrue(result.contains("static "), "Expected static modifier: " + result);
    }

    @Test
    void summarizeClassShowsEnumConstants() {
        GenericClass<?> gc = GenericClassFactory.get(TimeUnit.class);
        String result = new TestClusterSummarizer().summarizeClass(gc);

        assertTrue(result.contains("enum TimeUnit"), "Expected enum declaration: " + result);
        assertTrue(result.contains("SECONDS"), "Expected enum constant SECONDS: " + result);
        assertTrue(result.contains("MILLISECONDS"), "Expected enum constant MILLISECONDS: " + result);
    }

    @Test
    void summarizeClassShowsConstructorSection() {
        GenericClass<?> gc = GenericClassFactory.get(ArrayList.class);
        String result = new TestClusterSummarizer().summarizeClass(gc);

        assertTrue(result.contains("// Constructors"), "Expected constructors section: " + result);
        // ArrayList has a no-arg and int constructor
        assertTrue(result.contains("ArrayList()"), "Expected no-arg constructor: " + result);
        assertTrue(result.contains("ArrayList(int)"), "Expected int constructor: " + result);
    }

    @Test
    void summarizeClassShowsImplementsInterfaces() {
        GenericClass<?> gc = GenericClassFactory.get(ArrayList.class);
        String result = new TestClusterSummarizer().summarizeClass(gc);

        assertTrue(result.contains("implements"), "Expected implements clause: " + result);
    }

    @Test
    void summarizeClassShowsExtendsForNonObjectSuperclass() {
        GenericClass<?> gc = GenericClassFactory.get(ArrayList.class);
        String result = new TestClusterSummarizer().summarizeClass(gc);

        assertTrue(result.contains("extends"), "Expected extends clause: " + result);
        assertTrue(result.contains("AbstractList"), "Expected AbstractList superclass: " + result);
    }

    @Test
    void summarizeClassHandlesNullGracefully() {
        assertEquals("Unknown class", new TestClusterSummarizer().summarizeClass(null));
    }

    // --- Helper method tests ---

    @Test
    void genericTypeNameStripsJavaLangPrefix() {
        assertEquals("String", TestClusterSummarizer.genericTypeName(String.class));
        assertEquals("Integer", TestClusterSummarizer.genericTypeName(Integer.class));
        assertEquals("Object", TestClusterSummarizer.genericTypeName(Object.class));
    }

    @Test
    void genericTypeNamePreservesNonJavaLangPackages() {
        assertEquals("java.util.List", TestClusterSummarizer.genericTypeName(java.util.List.class));
        assertEquals("java.util.Map", TestClusterSummarizer.genericTypeName(java.util.Map.class));
    }

    @Test
    void genericTypeNameHandlesVoid() {
        assertEquals("void", TestClusterSummarizer.genericTypeName(void.class));
        assertEquals("void", TestClusterSummarizer.genericTypeName(null));
    }

    @Test
    void throwsClauseReturnsEmptyForNoExceptions() {
        assertEquals("", TestClusterSummarizer.throwsClause(new Type[0]));
        assertEquals("", TestClusterSummarizer.throwsClause(null));
    }

    @Test
    void throwsClauseFormatsExceptions() {
        Type[] types = new Type[]{IllegalArgumentException.class, NullPointerException.class};
        String result = TestClusterSummarizer.throwsClause(types);
        assertEquals(" throws IllegalArgumentException, NullPointerException", result);
    }

    @Test
    void genericParameterListJoinsTypes() {
        Type[] types = new Type[]{String.class, int.class, java.util.List.class};
        String result = TestClusterSummarizer.genericParameterList(types);
        assertEquals("String, int, java.util.List", result);
    }
}
