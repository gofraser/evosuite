/*
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
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.coverage;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.branch.BranchCoverageFactory;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.coverage.branch.OnlyBranchCoverageFactory;
import org.evosuite.coverage.exception.TryCatchCoverageFactory;
import org.evosuite.coverage.io.input.InputCoverageFactory;
import org.evosuite.coverage.io.output.OutputCoverageFactory;
import org.evosuite.coverage.line.LineCoverageFactory;
import org.evosuite.coverage.statement.StatementCoverageFactory;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.instrumentation.BytecodeInstrumentation;
import org.evosuite.instrumentation.LinePool;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The goal factories, except the reflective method factories, count the target class and its
 * nested classes and nothing else, not even a class whose name merely ends with the target's.
 */
public class CutRuleGoalFactoriesTest {

    private static final String CUT = cutrule_tests.Fixture.class.getName();
    private static final String INNER = cutrule_tests.Fixture.Inner.class.getName();
    private static final String LOOKALIKE_PACKAGE = "lookalike.cutrule_tests";
    private static final String LOOKALIKE = lookalike.cutrule_tests.Fixture.class.getName();

    private String originalTargetClass;
    private String originalTargetClassPrefix;
    private boolean originalExceptionBranches;

    @BeforeEach
    public void setUp() throws Exception {
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
        originalTargetClass = Properties.TARGET_CLASS;
        originalExceptionBranches = Properties.EXCEPTION_BRANCHES;
        originalTargetClassPrefix = Properties.TARGET_CLASS_PREFIX;
        Properties.TARGET_CLASS = CUT;
        Properties.EXCEPTION_BRANCHES = true;
        ClassLoader loader = TestGenerationContext.getInstance().getClassLoaderForSUT();
        // the prefix scope check loads the class, so load the look-alike before enabling the prefix
        loader.loadClass(LOOKALIKE);
        // puts the look-alike into the instrumentation scope, as a project prefix would
        Properties.TARGET_CLASS_PREFIX = LOOKALIKE_PACKAGE;
        for (String name : new String[]{CUT, INNER, LOOKALIKE}) {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream in = CutRuleGoalFactoriesTest.class.getClassLoader().getResourceAsStream(resource)) {
                new BytecodeInstrumentation().transformBytes(loader, name.replace('.', '/'),
                        new ClassReader(in));
            }
        }
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.TARGET_CLASS_PREFIX = originalTargetClassPrefix;
        Properties.EXCEPTION_BRANCHES = originalExceptionBranches;
        BytecodeInstructionPool.clearAll();
        BranchPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).clear();
        LinePool.reset();
        ClassPathHandler.resetSingleton();
    }

    private static Set<String> targets(List<? extends TestFitnessFunction> goals) {
        Set<String> classes = new TreeSet<>();
        for (TestFitnessFunction goal : goals) {
            classes.add(goal.getTargetClass());
        }
        return classes;
    }

    private static void assertCutOnly(String factory, TestFitnessFactory<?> goalFactory) {
        Set<String> classes = targets(goalFactory.getCoverageGoals());
        assertFalse(classes.contains(LOOKALIKE), factory + " counts the look-alike class: " + classes);
        assertTrue(classes.contains(CUT), factory + " misses the target class: " + classes);
        assertTrue(classes.contains(INNER), factory + " misses the nested class: " + classes);
        assertEquals(2, classes.size(), factory + ": " + classes);
    }

    @Test
    public void branchFactoryCountsNestedClasses() {
        assertCutOnly("branch", new BranchCoverageFactory());
    }

    @Test
    public void onlyBranchFactoryCountsNestedClasses() {
        assertCutOnly("onlybranch", new OnlyBranchCoverageFactory());
    }

    @Test
    public void lineFactoryCountsNestedClasses() {
        assertCutOnly("line", new LineCoverageFactory());
    }

    @Test
    public void statementFactoryCountsNestedClasses() {
        assertCutOnly("statement", new StatementCoverageFactory());
    }

    @Test
    public void inputFactoryCountsNestedClasses() {
        assertCutOnly("input", new InputCoverageFactory());
    }

    @Test
    public void outputFactoryCountsNestedClasses() {
        assertCutOnly("output", new OutputCoverageFactory());
    }

    @Test
    public void tryCatchFactoryIgnoresClassesOutsideTheCut() {
        Set<String> classes = targets(new TryCatchCoverageFactory().getCoverageGoals());
        assertTrue(classes.contains(CUT), classes.toString());
        assertFalse(classes.contains(LOOKALIKE), classes.toString());
    }
}
