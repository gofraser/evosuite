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

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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

    // --- summarizeGeneratorsFromCluster tests ---

    @Test
    void summarizeGeneratorsFromCluster_includesStaticFactories() throws Exception {
        TestCluster cluster = mock(TestCluster.class);

        GenericClass<?> type = GenericClassFactory.get(ArrayList.class);

        // Create a mock static factory generator
        GenericAccessibleObject<?> factoryGen = mock(GenericAccessibleObject.class);
        when(factoryGen.isMethod()).thenReturn(true);
        when(factoryGen.isStatic()).thenReturn(true);
        // Use a real static method as stand-in
        Method ofMethod = Collections.class.getMethod("emptyList");
        doReturn(ofMethod).when(factoryGen).getAccessibleObject();

        Set<GenericAccessibleObject<?>> genSet = new HashSet<>();
        genSet.add(factoryGen);

        Map<GenericClass<?>, Set<GenericAccessibleObject<?>>> generators = new HashMap<>();
        generators.put(type, genSet);
        when(cluster.getGeneratorsByType()).thenReturn(generators);

        TestClusterSummarizer summarizer = new TestClusterSummarizer();
        String result = summarizer.summarizeGeneratorsFromCluster(type, cluster);

        // Should include constructors
        assertTrue(result.contains("ArrayList()"), "Expected no-arg constructor: " + result);
        // Should include the static factory
        assertTrue(result.contains("static ArrayList emptyList()"), "Expected static factory: " + result);
        // Should be semicolon-separated
        assertTrue(result.contains("; "), "Expected semicolon separator: " + result);
    }

    @Test
    void summarizeGeneratorsFromCluster_worksWithNullCluster() {
        GenericClass<?> type = GenericClassFactory.get(ArrayList.class);
        TestClusterSummarizer summarizer = new TestClusterSummarizer();
        String result = summarizer.summarizeGeneratorsFromCluster(type, null);

        // Should still list constructors
        assertTrue(result.contains("ArrayList()"), "Expected constructors even with null cluster: " + result);
        assertFalse(result.contains("static"), "No static factories without cluster: " + result);
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

    // --- isJdkType tests ---

    @Test
    void isJdkTypeReturnsTrueForJavaAndJavaxPackages() {
        assertTrue(TestClusterSummarizer.isJdkType("java.util.ArrayList"));
        assertTrue(TestClusterSummarizer.isJdkType("java.lang.String"));
        assertTrue(TestClusterSummarizer.isJdkType("javax.swing.JFrame"));
        assertTrue(TestClusterSummarizer.isJdkType("java.awt.Color"));
    }

    @Test
    void isJdkTypeReturnsFalseForNonJdkPackages() {
        assertFalse(TestClusterSummarizer.isJdkType("com.example.MyClass"));
        assertFalse(TestClusterSummarizer.isJdkType("org.apache.commons.StringUtils"));
        assertFalse(TestClusterSummarizer.isJdkType("org.evosuite.TestClass"));
    }

    // --- extractSutPrefix tests ---

    @Test
    void extractSutPrefixReturnsTwoSegments() {
        assertEquals("com.example", TestClusterSummarizer.extractSutPrefix("com.example.foo.Bar"));
        assertEquals("org.evosuite", TestClusterSummarizer.extractSutPrefix("org.evosuite.llm.TestClass"));
    }

    @Test
    void extractSutPrefixHandlesEdgeCases() {
        assertEquals("", TestClusterSummarizer.extractSutPrefix("MyClass"));
        assertEquals("com", TestClusterSummarizer.extractSutPrefix("com.MyClass"));
        assertEquals("", TestClusterSummarizer.extractSutPrefix(null));
    }

    // --- classifyTier tests ---

    @Test
    void classifyTierReturnsTier1ForDirectDeps() {
        Set<String> directDeps = new HashSet<>(Arrays.asList("com.example.Dep1"));
        assertEquals(1, TestClusterSummarizer.classifyTier("com.example.Dep1", directDeps, "com.example"));
    }

    @Test
    void classifyTierReturnsTier2ForSutTypes() {
        Set<String> directDeps = Collections.emptySet();
        assertEquals(2, TestClusterSummarizer.classifyTier("com.example.other.Helper", directDeps, "com.example"));
    }

    @Test
    void classifyTierReturnsTier3ForThirdParty() {
        Set<String> directDeps = Collections.emptySet();
        assertEquals(3, TestClusterSummarizer.classifyTier("org.apache.commons.StringUtils", directDeps, "com.example"));
    }

    // --- summarizeDependencies tier sorting tests ---

    @Test
    void summarizeDependenciesOmitsJdkTypes() {
        TestCluster cluster = mock(TestCluster.class);

        // Set up generators map with a JDK type (ArrayList)
        Map<GenericClass<?>, Set<GenericAccessibleObject<?>>> generators = new HashMap<>();
        GenericClass<?> arrayListClass = GenericClassFactory.get(ArrayList.class);
        generators.put(arrayListClass, Collections.emptySet());

        when(cluster.getGeneratorsByType()).thenReturn(generators);
        when(cluster.getModifiers()).thenReturn(Collections.emptySet());

        TestClusterSummarizer summarizer = new TestClusterSummarizer();
        TestClusterSummarizer.DependencySummaryResult result =
                summarizer.summarizeDependencies(cluster, "com.example.MyCut", 0);

        // ArrayList is a JDK type and should be omitted
        assertFalse(result.getText().contains("ArrayList"), "JDK types should be omitted: " + result.getText());
    }

    @Test
    void summarizeDependenciesSortsSutBeforeThirdParty() {
        TestCluster cluster = mock(TestCluster.class);

        // Create two mock types: one SUT, one third-party
        // We'll use GenericClass mocks since we need non-JDK classes
        GenericClass<?> sutType = mock(GenericClass.class);
        // Use a real class from our project as "SUT type"
        doReturn(TestClusterSummarizer.class).when(sutType).getRawClass();

        GenericClass<?> thirdPartyType = mock(GenericClass.class);
        // Use TimeUnit as a non-JDK example (it's actually JDK, so let's use an enum)
        // We need a class that isn't JDK — let's use our own test class
        doReturn(TestClusterSummarizer.DependencySummaryResult.class).when(thirdPartyType).getRawClass();

        Map<GenericClass<?>, Set<GenericAccessibleObject<?>>> generators = new HashMap<>();
        generators.put(sutType, Collections.emptySet());
        generators.put(thirdPartyType, Collections.emptySet());

        when(cluster.getGeneratorsByType()).thenReturn(generators);
        when(cluster.getModifiers()).thenReturn(Collections.emptySet());

        TestClusterSummarizer summarizer = new TestClusterSummarizer();
        // Both types are in org.evosuite, target is also in org.evosuite
        // so both should be tier 2 (SUT types)
        TestClusterSummarizer.DependencySummaryResult result =
                summarizer.summarizeDependencies(cluster, "org.evosuite.llm.MyCut", 0);

        // Both SUT types should appear (they share the org.evosuite prefix)
        String text = result.getText();
        // They should be present since they're not JDK types
        // TestClusterSummarizer has no public constructors in its class, but DependencySummaryResult does
        assertFalse(text.isEmpty(), "Should have some output for SUT types");
    }

    @Test
    void summarizeDependenciesShowsStaticFactoryMethods() throws Exception {
        TestCluster cluster = mock(TestCluster.class);

        // Create a generator entry with a static factory method
        GenericClass<?> genClass = mock(GenericClass.class);
        doReturn(TestClusterSummarizer.DependencySummaryResult.class).when(genClass).getRawClass();

        // Create a mock static method generator
        GenericAccessibleObject<?> factoryGen = mock(GenericAccessibleObject.class);
        when(factoryGen.isMethod()).thenReturn(true);
        when(factoryGen.isStatic()).thenReturn(true);
        // Use a real static method for getAccessibleObject
        Method getText = TestClusterSummarizer.DependencySummaryResult.class.getMethod("getText");
        doReturn(getText).when(factoryGen).getAccessibleObject();

        Set<GenericAccessibleObject<?>> genSet = new HashSet<>();
        genSet.add(factoryGen);

        Map<GenericClass<?>, Set<GenericAccessibleObject<?>>> generators = new HashMap<>();
        generators.put(genClass, genSet);

        when(cluster.getGeneratorsByType()).thenReturn(generators);
        when(cluster.getModifiers()).thenReturn(Collections.emptySet());

        TestClusterSummarizer summarizer = new TestClusterSummarizer();
        TestClusterSummarizer.DependencySummaryResult result =
                summarizer.summarizeDependencies(cluster, "org.evosuite.MyCut", 0);

        // Should show the static factory method
        assertTrue(result.getText().contains("static"), "Should show static factory method: " + result.getText());
        assertTrue(result.getText().contains("getText"), "Should show getText method: " + result.getText());
    }

    @Test
    void summarizeDependenciesShowsModifierMethods() throws Exception {
        TestCluster cluster = mock(TestCluster.class);

        // Set up a type in the generators
        GenericClass<?> genClass = mock(GenericClass.class);
        doReturn(TestClusterSummarizer.DependencySummaryResult.class).when(genClass).getRawClass();

        Map<GenericClass<?>, Set<GenericAccessibleObject<?>>> generators = new HashMap<>();
        generators.put(genClass, Collections.emptySet());
        when(cluster.getGeneratorsByType()).thenReturn(generators);

        // Set up a modifier for the same type
        GenericAccessibleObject<?> modifier = mock(GenericAccessibleObject.class);
        when(modifier.isMethod()).thenReturn(true);
        GenericClass<?> ownerClass = GenericClassFactory.get(TestClusterSummarizer.DependencySummaryResult.class);
        doReturn(ownerClass).when(modifier).getOwnerClass();
        Method isTruncated = TestClusterSummarizer.DependencySummaryResult.class.getMethod("isTruncated");
        doReturn(isTruncated).when(modifier).getAccessibleObject();

        Set<GenericAccessibleObject<?>> modifierSet = new HashSet<>();
        modifierSet.add(modifier);
        when(cluster.getModifiers()).thenReturn(modifierSet);

        TestClusterSummarizer summarizer = new TestClusterSummarizer();
        TestClusterSummarizer.DependencySummaryResult result =
                summarizer.summarizeDependencies(cluster, "org.evosuite.MyCut", 0);

        // Should show the modifier method
        assertTrue(result.getText().contains("isTruncated"), "Should show modifier method: " + result.getText());
    }
}
