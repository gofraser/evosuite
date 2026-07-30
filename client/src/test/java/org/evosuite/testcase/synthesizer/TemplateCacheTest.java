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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.examples.with.different.packagename.objectconstruction.instancefield.PublicInstanceFieldExample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verification for the object-rule template cache: the best (lowest legitimacy distance)
 * template per branch is kept and handed out as a fresh clone, and entries for covered branches are
 * evicted.
 */
public class TemplateCacheTest {

    @BeforeEach
    public void setUp() {
        TemplateCache.getInstance().reset();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_METHOD = "";
        Properties.TARGET_CLASS = "";
        Properties.APPLY_OBJECT_RULE = false;
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

    /** Runs the analysis just to obtain a genuine {@link Branch} to use as a cache key. */
    private static Branch anyBranch() {
        Properties.CLIENT_ON_THREAD = true;
        Properties.CRITERION = new Criterion[] { Criterion.BRANCH };
        Properties.ASSERTIONS = false;
        Properties.RANDOM_SEED = 42L;
        Randomness.setSeed(42L);
        Properties.TARGET_CLASS = PublicInstanceFieldExample.class.getName();
        Properties.TARGET_METHOD = signatureOf(PublicInstanceFieldExample.class, "targetM", 1);
        Properties.APPLY_OBJECT_RULE = true;

        String cp = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(cp);
        List<String> classpath = new ArrayList<>();
        classpath.add(cp);
        try {
            DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, classpath);
        } catch (ClassNotFoundException | RuntimeException e) {
            throw new AssertionError("analyzeClass failed", e);
        }
        Map<Branch, Set<DepVariable>> map =
                ObjectConstructionContext.branchInterestedVarsMap.get(Properties.TARGET_METHOD);
        assertNotNull(map, "the target method should have an OCG entry");
        assertFalse(map.isEmpty(), "expected at least one analyzed branch");
        return map.keySet().iterator().next();
    }

    private static DefaultTestCase testOfSize(int size) {
        DefaultTestCase test = new DefaultTestCase();
        for (int i = 0; i < size; i++) {
            test.addStatement(new IntPrimitiveStatement(test, i));
        }
        return test;
    }

    @Test
    public void storesAndReturnsAClone() {
        Branch branch = anyBranch();
        TemplateCache cache = TemplateCache.getInstance();
        DefaultTestCase template = testOfSize(2);

        cache.store(branch, template, 3.0);

        assertTrue(cache.hasTemplate(branch));
        assertEquals(template.toCode(), cache.getClonedTemplate(branch).toCode(),
                "clone should be structurally equal to the stored template");
        assertNotSame(template, cache.getClonedTemplate(branch), "a fresh clone must be returned");
        assertNotSame(cache.getClonedTemplate(branch), cache.getClonedTemplate(branch),
                "each retrieval returns an independent clone");
    }

    @Test
    public void keepsTheTemplateWithLowestDistance() {
        Branch branch = anyBranch();
        TemplateCache cache = TemplateCache.getInstance();

        cache.store(branch, testOfSize(1), 5.0);
        cache.store(branch, testOfSize(2), 2.0);   // improves -> replaces
        cache.store(branch, testOfSize(3), 8.0);   // worse    -> ignored

        assertEquals(2, cache.getClonedTemplate(branch).size(),
                "the cached template should be the one with the lowest legitimacy distance");
    }

    @Test
    public void evictsCoveredBranches() {
        Branch branch = anyBranch();
        TemplateCache cache = TemplateCache.getInstance();
        cache.store(branch, testOfSize(1), 0.0);

        cache.retainUncovered(Collections.singletonList(branch));
        assertTrue(cache.hasTemplate(branch), "still uncovered -> retained");

        cache.retainUncovered(Collections.emptyList());
        assertFalse(cache.hasTemplate(branch), "covered -> evicted");
    }

    @Test
    public void resetAndNullSafety() {
        Branch branch = anyBranch();
        TemplateCache cache = TemplateCache.getInstance();

        cache.store(null, testOfSize(1), 0.0);
        cache.store(branch, null, 0.0);
        assertFalse(cache.hasTemplate(null));
        assertFalse(cache.hasTemplate(branch), "null template/branch stores are ignored");
        assertNull(cache.getClonedTemplate(null));

        cache.store(branch, testOfSize(1), 1.0);
        assertTrue(cache.hasTemplate(branch));
        cache.reset();
        assertFalse(cache.hasTemplate(branch), "reset clears the cache");
    }

}
