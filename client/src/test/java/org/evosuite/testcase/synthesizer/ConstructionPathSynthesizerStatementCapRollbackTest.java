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

import com.examples.with.different.packagename.objectconstruction.instancefield.PublicInstanceFieldExample;
import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.ExceptionMapGenerator;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthesis inserts construction statements before the target call. When an expansion crosses the
 * statement cap it must be rolled back by removing what it inserted, not by truncating the test
 * from the end, which deletes the target call and keeps the partial expansion.
 */
public class ConstructionPathSynthesizerStatementCapRollbackTest {

    @AfterEach
    public void tearDown() {
        Properties.TARGET_METHOD = "";
        Properties.TARGET_CLASS = "";
        Properties.APPLY_OBJECT_RULE = false;
        TestGenerationContext.getInstance().resetContext();
    }

    @Test
    public void statementCapRollbackKeepsTheTargetCallAndOriginalStatements() throws Exception {
        Class<?> clazz = PublicInstanceFieldExample.class;
        analyze(clazz, "targetM", 1);
        Branch branch = pickStateBearingBranch();

        TestCase uncapped = initializeTestWithTargetCall(clazz, "targetM", 1);
        int sizeBefore = uncapped.size();
        new ConstructionPathSynthesizer().buildNodeStatementCorrespondence(uncapped, branch, false);
        assertTrue(uncapped.size() > sizeBefore,
                "precondition: uncapped synthesis adds statements before the target call");

        Randomness.setSeed(42L);
        TestCase test = initializeTestWithTargetCall(clazz, "targetM", 1);
        List<Statement> original = new ArrayList<>();
        for (int i = 0; i < test.size(); i++) {
            original.add(test.getStatement(i));
        }
        MethodStatement target = test.findTargetMethodCallStatement();

        // Any expansion that adds a statement crosses this cap and must be undone.
        new ConstructionPathSynthesizer().buildNodeStatementCorrespondence(
                test, branch, false, null, sizeBefore + 1);

        assertSame(target, test.findTargetMethodCallStatement(),
                "the target call must survive the statement-cap rollback; test is now:\n"
                        + test.toCode());
        Map<Statement, Boolean> remaining = new IdentityHashMap<>();
        for (int i = 0; i < test.size(); i++) {
            remaining.put(test.getStatement(i), Boolean.TRUE);
        }
        for (Statement statement : original) {
            assertTrue(remaining.containsKey(statement),
                    "an original statement was removed by the rollback: " + statement);
        }
    }

    private static void analyze(Class<?> clazz, String methodName, int paramNum) {
        Properties.CLIENT_ON_THREAD = true;
        Properties.CRITERION = new Criterion[]{Criterion.BRANCH};
        Properties.ASSERTIONS = false;
        Properties.RANDOM_SEED = 42L;
        Randomness.setSeed(42L);

        Properties.TARGET_CLASS = clazz.getName();
        Properties.TARGET_METHOD = signatureOf(clazz, methodName, paramNum);
        Properties.APPLY_OBJECT_RULE = true;

        String cp = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(cp);
        try {
            DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, Collections.singletonList(cp));
        } catch (ClassNotFoundException | RuntimeException e) {
            throw new AssertionError("analyzeClass failed for " + clazz, e);
        }
        LoopCounter.getInstance().setActive(false);
        ExceptionMapGenerator.initializeExceptionMap(Properties.TARGET_CLASS);
        TestCaseExecutor.initExecutor();
    }

    private static Branch pickStateBearingBranch() {
        Map<Branch, Set<DepVariable>> graph = TestGenerationContext.getInstance()
                .getObjectRuleRuntime().getBranchInterestedVarsMap().get(Properties.TARGET_METHOD);
        assertNotNull(graph, "the target method should have an OCG entry");
        Branch best = null;
        int bestSize = 0;
        for (Map.Entry<Branch, Set<DepVariable>> e : graph.entrySet()) {
            if (e.getValue() != null && e.getValue().size() > bestSize) {
                best = e.getKey();
                bestSize = e.getValue().size();
            }
        }
        assertNotNull(best, "expected at least one branch depending on object state");
        return best;
    }

    private static String signatureOf(Class<?> clazz, String methodName, int paramNum) {
        return methodName + MethodUtil.getSignature(reflectMethod(clazz, methodName, paramNum));
    }

    private static Method reflectMethod(Class<?> clazz, String methodName, int paramNum) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == paramNum) {
                return m;
            }
        }
        throw new IllegalStateException("no method " + methodName + "/" + paramNum + " in " + clazz);
    }

    private static TestCase initializeTestWithTargetCall(Class<?> clazz, String methodName,
                                                         int paramNum) throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        TestFactory factory = TestFactory.getInstance();
        Class<?> sutClass = TestGenerationContext.getInstance().getClassLoaderForSUT()
                .loadClass(clazz.getName());
        Method m = reflectMethod(sutClass, methodName, paramNum);
        VariableReference callee = factory.addConstructor(test,
                new GenericConstructor(sutClass.getDeclaredConstructor(), sutClass), test.size(), 0);
        List<VariableReference> params = new ArrayList<>();
        for (Class<?> type : m.getParameterTypes()) {
            if (type == int.class) {
                params.add(test.addStatement(new IntPrimitiveStatement(test), test.size()));
            } else {
                params.add(factory.addConstructor(test,
                        new GenericConstructor(type.getDeclaredConstructor(), type), test.size(), 0));
            }
        }
        test.addStatement(new MethodStatement(test, new GenericMethod(m, sutClass), callee, params),
                test.size());
        assertNotNull(test.findTargetMethodCallStatement(),
                "initial test must contain the target-method call");
        return test;
    }
}
