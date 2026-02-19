/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.setup;

import com.examples.with.different.packagename.setup.MemberAnalyzerFixture;
import org.evosuite.Properties;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link MemberAnalyzer}.
 */
public class MemberAnalyzerTest {

    private TestCluster cluster;
    private MemberAnalyzer memberAnalyzer;
    private Set<GenericAccessibleObject<?>> dependencyCache;
    private Set<DependencyPair> dependencies;
    private Set<GenericClass<?>> analyzedAbstractClasses;
    private Set<Class<?>> analyzedClasses;
    private InheritanceTree inheritanceTree;

    @Before
    public void setUp() throws ClassNotFoundException {
        Properties.TARGET_CLASS =
                MemberAnalyzerFixture.class.getCanonicalName();

        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
        String cp = ClassPathHandler.getInstance()
                .getTargetProjectClasspath();
        DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS,
                Arrays.asList(cp.split(File.pathSeparator)));

        TestCluster.reset();
        cluster = TestCluster.getInstance();
        inheritanceTree = DependencyAnalysis.getInheritanceTree();

        dependencyCache = new LinkedHashSet<>();
        dependencies = new LinkedHashSet<>();
        analyzedAbstractClasses = new LinkedHashSet<>();
        analyzedClasses = new LinkedHashSet<>();

        memberAnalyzer = new MemberAnalyzer(
                cluster, dependencyCache, dependencies,
                analyzedAbstractClasses, analyzedClasses,
                inheritanceTree);
    }

    @After
    public void tearDown() {
        TestCluster.reset();
    }

    // -----------------------------------------------------------------------
    // analyze() in TARGET mode
    // -----------------------------------------------------------------------

    @Test
    public void testAnalyzeTargetAddsTestCalls() {
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);
        boolean result = memberAnalyzer.analyze(
                clazz, MemberAnalyzer.AnalysisMode.TARGET, 0);

        assertTrue("analyze should return true for a usable class", result);

        List<GenericAccessibleObject<?>> testCalls = cluster.getTestCalls();
        assertFalse("Test calls should not be empty in TARGET mode",
                testCalls.isEmpty());

        // Should include constructors and methods declared in the fixture
        Set<String> callNames = testCalls.stream()
                .map(GenericAccessibleObject::getName)
                .collect(Collectors.toSet());

        // Constructor.getName() returns the FQCN
        assertTrue("Should include a constructor",
                callNames.contains(
                        MemberAnalyzerFixture.class.getName()));
        assertTrue("Should include getValue method",
                callNames.contains("getValue"));
        assertTrue("Should include setValue method",
                callNames.contains("setValue"));
    }

    @Test
    public void testAnalyzeTargetAddsGenerators() {
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);
        memberAnalyzer.analyze(
                clazz, MemberAnalyzer.AnalysisMode.TARGET, 0);

        Set<GenericAccessibleObject<?>> generators = cluster.getGenerators();
        assertFalse("Generators should not be empty", generators.isEmpty());

        // Constructors should be generators for the fixture class
        boolean hasConstructorGenerator = generators.stream()
                .anyMatch(g -> g.isConstructor()
                        && g.getDeclaringClass()
                                .equals(MemberAnalyzerFixture.class));
        assertTrue("Should have a constructor generator",
                hasConstructorGenerator);
    }

    @Test
    public void testAnalyzeTargetAddsModifiers() {
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);
        memberAnalyzer.analyze(
                clazz, MemberAnalyzer.AnalysisMode.TARGET, 0);

        Set<GenericAccessibleObject<?>> modifiers = cluster.getModifiers();

        // setValue is non-pure and should be a modifier;
        // publicField is non-final and should also be a modifier
        boolean hasSetValueModifier = modifiers.stream()
                .anyMatch(m -> m.getName().equals("setValue"));
        assertTrue("Should have setValue as modifier", hasSetValueModifier);
    }

    @Test
    public void testAnalyzeTargetAddsFieldGenerator() {
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);
        memberAnalyzer.analyze(
                clazz, MemberAnalyzer.AnalysisMode.TARGET, 0);

        Set<GenericAccessibleObject<?>> generators = cluster.getGenerators();
        boolean hasFieldGenerator = generators.stream()
                .anyMatch(g -> g.isField()
                        && g.getName().equals("publicField"));
        assertTrue("Should have publicField as generator", hasFieldGenerator);
    }

    // -----------------------------------------------------------------------
    // analyze() in DEPENDENCY mode
    // -----------------------------------------------------------------------

    @Test
    public void testAnalyzeDependencyDoesNotAddTestCalls() {
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);
        boolean result = memberAnalyzer.analyze(
                clazz, MemberAnalyzer.AnalysisMode.DEPENDENCY, 1);

        assertTrue("analyze should return true for a usable class", result);

        List<GenericAccessibleObject<?>> testCalls = cluster.getTestCalls();
        assertTrue("Test calls should be empty in DEPENDENCY mode",
                testCalls.isEmpty());
    }

    @Test
    public void testAnalyzeDependencyAddsGenerators() {
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);
        memberAnalyzer.analyze(
                clazz, MemberAnalyzer.AnalysisMode.DEPENDENCY, 1);

        Set<GenericAccessibleObject<?>> generators = cluster.getGenerators();
        assertFalse("Generators should not be empty in DEPENDENCY mode",
                generators.isEmpty());

        boolean hasConstructorGenerator = generators.stream()
                .anyMatch(g -> g.isConstructor()
                        && g.getDeclaringClass()
                                .equals(MemberAnalyzerFixture.class));
        assertTrue("Should have a constructor generator in DEPENDENCY mode",
                hasConstructorGenerator);
    }

    @Test
    public void testAnalyzeDependencyAddsModifiers() {
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);
        memberAnalyzer.analyze(
                clazz, MemberAnalyzer.AnalysisMode.DEPENDENCY, 1);

        Set<GenericAccessibleObject<?>> modifiers = cluster.getModifiers();
        assertFalse("Modifiers should not be empty in DEPENDENCY mode",
                modifiers.isEmpty());
    }

    @Test
    public void testAnalyzeDependencyMarksClassAnalyzed() {
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);
        memberAnalyzer.analyze(
                clazz, MemberAnalyzer.AnalysisMode.DEPENDENCY, 1);

        assertTrue("Class should be marked as analyzed",
                cluster.getAnalyzedClasses()
                        .contains(MemberAnalyzerFixture.class));
    }

    // -----------------------------------------------------------------------
    // analyze() return value
    // -----------------------------------------------------------------------

    @Test
    public void testAnalyzeReturnsTrueForUsableClass() {
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);
        assertTrue(memberAnalyzer.analyze(
                clazz, MemberAnalyzer.AnalysisMode.TARGET, 0));
    }

    // -----------------------------------------------------------------------
    // addDependency filtering
    // -----------------------------------------------------------------------

    @Test
    public void testAddDependencySkipsPrimitives() {
        GenericClass<?> intClass = GenericClassFactory.get(int.class);
        memberAnalyzer.addDependency(intClass, 0);

        assertTrue("Primitives should not be added as dependencies",
                dependencies.isEmpty());
    }

    @Test
    public void testAddDependencySkipsStrings() {
        GenericClass<?> stringClass = GenericClassFactory.get(String.class);
        memberAnalyzer.addDependency(stringClass, 0);

        assertTrue("Strings should not be added as dependencies",
                dependencies.isEmpty());
    }

    @Test
    public void testAddDependencySkipsEnum() {
        GenericClass<?> enumClass = GenericClassFactory.get(Enum.class);
        memberAnalyzer.addDependency(enumClass, 0);

        assertTrue("Enum base class should not be added as dependencies",
                dependencies.isEmpty());
    }

    @Test
    public void testAddDependencyHandlesArrayByUnwrapping() {
        // Arrays should recursively add the component type.
        // After unwrapping, addDependency processes the component type
        // and adds it to analyzedAbstractClasses (via ConcreteClassAnalyzer).
        GenericClass<?> arrayClass = GenericClassFactory.get(
                MemberAnalyzerFixture[].class);
        memberAnalyzer.addDependency(arrayClass, 0);

        // The component type should have been processed (added to
        // analyzedAbstractClasses or dependencies)
        GenericClass<?> componentClass = GenericClassFactory.get(
                MemberAnalyzerFixture.class);
        boolean wasProcessed =
                analyzedAbstractClasses.contains(componentClass)
                || dependencies.stream().anyMatch(dp ->
                        dp.getDependencyClass()
                                .equals(MemberAnalyzerFixture.class));
        assertTrue("Array component type should be processed",
                wasProcessed);
    }

    @Test
    public void testAddDependencySkipsDuplicates() {
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);

        memberAnalyzer.addDependency(clazz, 0);
        int sizeAfterFirst = dependencies.size();

        memberAnalyzer.addDependency(clazz, 0);
        int sizeAfterSecond = dependencies.size();

        assertEquals("Duplicate dependency should not be added",
                sizeAfterFirst, sizeAfterSecond);
    }

    @Test
    public void testAddDependencySkipsAlreadyAnalyzedClasses() {
        analyzedClasses.add(MemberAnalyzerFixture.class);
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);

        memberAnalyzer.addDependency(clazz, 0);

        assertTrue("Already-analyzed classes should not be re-added",
                dependencies.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Dependency tracking for constructors/methods/fields
    // -----------------------------------------------------------------------

    @Test
    public void testDependencyCachePreventsDuplicateAnalysis() {
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);

        // Analyze in TARGET mode (which adds items to dependencyCache)
        memberAnalyzer.analyze(
                clazz, MemberAnalyzer.AnalysisMode.TARGET, 0);

        int cacheSize = dependencyCache.size();
        assertTrue("Dependency cache should be populated after analysis",
                cacheSize > 0);

        // Re-analyzing the same constructors/methods should not grow cache
        memberAnalyzer.analyze(
                clazz, MemberAnalyzer.AnalysisMode.TARGET, 0);
        assertEquals("Cache should not grow on re-analysis",
                cacheSize, dependencyCache.size());
    }

    // -----------------------------------------------------------------------
    // Behavioral differences between TARGET and DEPENDENCY
    // -----------------------------------------------------------------------

    @Test
    public void testTargetModeReturnsPrimitiveGenerators() {
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);
        memberAnalyzer.analyze(
                clazz, MemberAnalyzer.AnalysisMode.TARGET, 0);

        Set<GenericAccessibleObject<?>> generators = cluster.getGenerators();

        // In TARGET mode, getValue() (returns int) should be a generator
        boolean hasGetValueGenerator = generators.stream()
                .anyMatch(g -> g.getName().equals("getValue"));
        assertTrue("TARGET mode should add primitive-returning methods "
                + "as generators", hasGetValueGenerator);
    }

    @Test
    public void testDependencyModeFiltersPrimitiveReturnGenerators() {
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);
        memberAnalyzer.analyze(
                clazz, MemberAnalyzer.AnalysisMode.DEPENDENCY, 1);

        Set<GenericAccessibleObject<?>> generators = cluster.getGenerators();

        // In DEPENDENCY mode, getValue() (returns int=primitive) should NOT
        // be a generator because dependency mode skips primitive returns
        boolean hasGetValueGenerator = generators.stream()
                .anyMatch(g -> g.getName().equals("getValue"));
        assertFalse("DEPENDENCY mode should NOT add primitive-returning "
                + "methods as generators", hasGetValueGenerator);
    }
}
