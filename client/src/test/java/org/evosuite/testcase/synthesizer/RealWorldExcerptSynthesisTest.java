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
package org.evosuite.testcase.synthesizer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.ExceptionMapGenerator;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.factories.RandomLengthTestFactory;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.examples.with.different.packagename.objectconstruction.arjarchiveentry.ArjArchiveEntry;
import com.examples.with.different.packagename.objectconstruction.jnfe.Province;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Robustness coverage for the real-world decompiled excerpts ported from the fork's EvoObj suite
 *: commons-compress's {@code ArjArchiveEntry} (a final nested header object) and the
 * "JNFE" nested address model ({@code Province}/{@code City}/{@code Country} with interprocedural
 * getter chains, {@code instanceof} checks, an enum and inheritance). These guard that analysis and
 * synthesis handle messy, realistic bytecode without failing.
 */
public class RealWorldExcerptSynthesisTest {

    @AfterEach
    public void tearDown() {
        Properties.TARGET_METHOD = "";
        Properties.TARGET_CLASS = "";
        Properties.APPLY_OBJECT_RULE = false;
        org.evosuite.TestGenerationContext.getInstance().getObjectRuleSession().clearWorkingTarget();
        org.evosuite.TestGenerationContext.getInstance().resetContext();
    }

    private static String signatureOf(Class<?> clazz, String methodName, int paramNum) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == paramNum) {
                return m.getName() + MethodUtil.getSignature(m);
            }
        }
        throw new IllegalStateException("no method " + methodName + "/" + paramNum + " in " + clazz);
    }

    private static void analyze(Class<?> clazz, String methodName, int paramNum) {
        Properties.CLIENT_ON_THREAD = true;
        Properties.CRITERION = new Criterion[] { Criterion.BRANCH };
        Properties.ASSERTIONS = false;
        Properties.RANDOM_SEED = 42L;
        Randomness.setSeed(42L);
        Properties.CHROMOSOME_LENGTH = 40;
        Properties.INDIVIDUAL_LEGITIMIZATION_BUDGET = 3;
        Properties.TARGET_CLASS = clazz.getName();
        Properties.TARGET_METHOD = signatureOf(clazz, methodName, paramNum);
        Properties.APPLY_OBJECT_RULE = true;
        Properties.P_OBJECT_RULE_APPLICATION = 1.0;

        String cp = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(cp);
        List<String> classpath = new ArrayList<>();
        classpath.add(cp);
        try {
            DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, classpath);
        } catch (ClassNotFoundException | RuntimeException e) {
            throw new AssertionError("analyzeClass failed for " + clazz, e);
        }

        LoopCounter.getInstance().setActive(false);
        ExceptionMapGenerator.initializeExceptionMap(Properties.TARGET_CLASS);
        TestCaseExecutor.initExecutor();
    }

    private static Branch stateBearingBranch() {
        Map<Branch, Set<DepVariable>> map =
                ObjectConstructionContext.branchInterestedVarsMap.get(Properties.TARGET_METHOD);
        assertNotNull(map, "the target method should have an OCG entry");
        Branch best = null;
        int bestSize = 0;
        for (Map.Entry<Branch, Set<DepVariable>> e : map.entrySet()) {
            if (e.getValue() != null && e.getValue().size() > bestSize) {
                best = e.getKey();
                bestSize = e.getValue().size();
            }
        }
        return best;
    }

    /**
     * {@code ArjArchiveEntry.isDirectory()} reads {@code this.localFileHeader.fileType} — a field of
     * a private final nested header. The template must construct that state and reach the call.
     */
    @Test
    public void arjArchiveEntryNestedFinalHeaderField() {
        analyze(ArjArchiveEntry.class, "isDirectory", 0);
        Branch stateBranch = stateBearingBranch();
        assertNotNull(stateBranch, "isDirectory should depend on nested header state");

        RandomLengthTestFactory factory = new RandomLengthTestFactory();
        boolean produced = false;
        for (int attempt = 0; attempt < 60 && !produced; attempt++) {
            org.evosuite.TestGenerationContext.getInstance().getObjectRuleSession().setWorkingTarget(0, stateBranch, java.util.Collections.emptyList());
            TestCase test = factory.getChromosome().getTestCase();
            if (test.findTargetMethodCallStatement() != null) {
                produced = true;
                assertNotNull(TestCaseExecutor.getInstance().execute(test, Integer.MAX_VALUE),
                        "the ArjArchiveEntry template should execute");
            }
        }
        assertTrue(produced, "the factory should build a template calling isDirectory");
    }

    /**
     * The JNFE address model is the paper's messy {@code equals()} shape (getter chains, enum,
     * inheritance). This is a robustness guard: analysis produces a graph and synthesis runs without
     * throwing.
     */
    @Test
    public void jnfeAddressModelIsRobust() {
        analyze(Province.class, "equals", 1);
        Branch stateBranch = stateBearingBranch();

        RandomLengthTestFactory factory = new RandomLengthTestFactory();
        for (int attempt = 0; attempt < 10; attempt++) {
            org.evosuite.TestGenerationContext.getInstance().getObjectRuleSession().setWorkingTarget(0, stateBranch, java.util.Collections.emptyList());
            TestChromosome chromosome = factory.getChromosome();
            assertNotNull(chromosome.getTestCase(), "synthesis on the JNFE model must not fail");
        }
    }
}
