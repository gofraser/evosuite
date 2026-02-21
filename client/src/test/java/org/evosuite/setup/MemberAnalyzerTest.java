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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

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

    @BeforeEach
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

    @AfterEach
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

        assertTrue(result, "analyze should return true for a usable class");

        List<GenericAccessibleObject<?>> testCalls = cluster.getTestCalls();
        assertFalse(testCalls.isEmpty(),
                "Test calls should not be empty in TARGET mode");

        // Should include constructors and methods declared in the fixture
        Set<String> callNames = testCalls.stream()
                .map(GenericAccessibleObject::getName)
                .collect(Collectors.toSet());

        // Constructor.getName() returns the FQCN
        assertTrue(callNames.contains(
                        MemberAnalyzerFixture.class.getName()),
                "Should include a constructor");
        assertTrue(callNames.contains("getValue"),
                "Should include getValue method");
        assertTrue(callNames.contains("setValue"),
                "Should include setValue method");
    }

    @Test
    public void testAnalyzeTargetAddsGenerators() {
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);
        memberAnalyzer.analyze(
                clazz, MemberAnalyzer.AnalysisMode.TARGET, 0);

        Set<GenericAccessibleObject<?>> generators = cluster.getGenerators();
        assertFalse(generators.isEmpty(), "Generators should not be empty");

        // Constructors should be generators for the fixture class
        boolean hasConstructorGenerator = generators.stream()
                .anyMatch(g -> g.isConstructor()
                        && g.getDeclaringClass()
                                .equals(MemberAnalyzerFixture.class));
        assertTrue(hasConstructorGenerator,
                "Should have a constructor generator");
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
        assertTrue(hasSetValueModifier, "Should have setValue as modifier");
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
        assertTrue(hasFieldGenerator, "Should have publicField as generator");
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

        assertTrue(result, "analyze should return true for a usable class");

        List<GenericAccessibleObject<?>> testCalls = cluster.getTestCalls();
        assertTrue(testCalls.isEmpty(),
                "Test calls should be empty in DEPENDENCY mode");
    }

    @Test
    public void testAnalyzeDependencyAddsGenerators() {
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);
        memberAnalyzer.analyze(
                clazz, MemberAnalyzer.AnalysisMode.DEPENDENCY, 1);

        Set<GenericAccessibleObject<?>> generators = cluster.getGenerators();
        assertFalse(generators.isEmpty(),
                "Generators should not be empty in DEPENDENCY mode");

        boolean hasConstructorGenerator = generators.stream()
                .anyMatch(g -> g.isConstructor()
                        && g.getDeclaringClass()
                                .equals(MemberAnalyzerFixture.class));
        assertTrue(hasConstructorGenerator,
                "Should have a constructor generator in DEPENDENCY mode");
    }

    @Test
    public void testAnalyzeDependencyAddsModifiers() {
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);
        memberAnalyzer.analyze(
                clazz, MemberAnalyzer.AnalysisMode.DEPENDENCY, 1);

        Set<GenericAccessibleObject<?>> modifiers = cluster.getModifiers();
        assertFalse(modifiers.isEmpty(),
                "Modifiers should not be empty in DEPENDENCY mode");
    }

    @Test
    public void testAnalyzeDependencyMarksClassAnalyzed() {
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);
        memberAnalyzer.analyze(
                clazz, MemberAnalyzer.AnalysisMode.DEPENDENCY, 1);

        assertTrue(cluster.getAnalyzedClasses()
                        .contains(MemberAnalyzerFixture.class),
                "Class should be marked as analyzed");
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

        assertTrue(dependencies.isEmpty(),
                "Primitives should not be added as dependencies");
    }

    @Test
    public void testAddDependencySkipsStrings() {
        GenericClass<?> stringClass = GenericClassFactory.get(String.class);
        memberAnalyzer.addDependency(stringClass, 0);

        assertTrue(dependencies.isEmpty(),
                "Strings should not be added as dependencies");
    }

    @Test
    public void testAddDependencySkipsEnum() {
        GenericClass<?> enumClass = GenericClassFactory.get(Enum.class);
        memberAnalyzer.addDependency(enumClass, 0);

        assertTrue(dependencies.isEmpty(),
                "Enum base class should not be added as dependencies");
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
        assertTrue(wasProcessed,
                "Array component type should be processed");
    }

    @Test
    public void testAddDependencySkipsDuplicates() {
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);

        memberAnalyzer.addDependency(clazz, 0);
        int sizeAfterFirst = dependencies.size();

        memberAnalyzer.addDependency(clazz, 0);
        int sizeAfterSecond = dependencies.size();

        assertEquals(sizeAfterFirst,
                sizeAfterSecond, "Duplicate dependency should not be added");
    }

    @Test
    public void testAddDependencySkipsAlreadyAnalyzedClasses() {
        analyzedClasses.add(MemberAnalyzerFixture.class);
        GenericClass<?> clazz = GenericClassFactory.get(
                MemberAnalyzerFixture.class);

        memberAnalyzer.addDependency(clazz, 0);

        assertTrue(dependencies.isEmpty(),
                "Already-analyzed classes should not be re-added");
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
        assertTrue(cacheSize > 0,
                "Dependency cache should be populated after analysis");

        // Re-analyzing the same constructors/methods should not grow cache
        memberAnalyzer.analyze(
                clazz, MemberAnalyzer.AnalysisMode.TARGET, 0);
        assertEquals(cacheSize,
                dependencyCache.size(), "Cache should not grow on re-analysis");
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
        assertTrue(hasGetValueGenerator, "TARGET mode should add primitive-returning methods "
                + "as generators");
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
        assertFalse(hasGetValueGenerator, "DEPENDENCY mode should NOT add primitive-returning "
                + "methods as generators");
    }
}
