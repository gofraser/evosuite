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

package org.evosuite.assertion;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.TimeController;
import org.evosuite.coverage.mutation.MutationPool;
import org.evosuite.ga.stoppingconditions.MaxStatementsStoppingCondition;
import org.evosuite.rmi.ClientServices;
import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.FieldStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;

/**
 * <p>
 * Abstract AssertionGenerator class.
 * </p>
 *
 * @author Gordon Fraser
 */
public abstract class AssertionGenerator {

    protected static final Logger logger = LoggerFactory.getLogger(AssertionGenerator.class);

    protected static final PrimitiveTraceObserver primitiveObserver = new PrimitiveTraceObserver();

    protected static final ComparisonTraceObserver comparisonObserver = new ComparisonTraceObserver();

    protected static final SameTraceObserver sameObserver = new SameTraceObserver();

    protected static final InspectorTraceObserver inspectorObserver = new InspectorTraceObserver();

    protected static final PrimitiveFieldTraceObserver fieldObserver = new PrimitiveFieldTraceObserver();

    protected static final NullTraceObserver nullObserver = new NullTraceObserver();

    protected static final ArrayTraceObserver arrayObserver = new ArrayTraceObserver();

    protected static final ArrayLengthObserver arrayLengthObserver = new ArrayLengthObserver();

    protected static final ContainsTraceObserver containsTraceObserver = new ContainsTraceObserver();

    protected static final Class<?>[] observerClasses = {PrimitiveTraceEntry.class, ComparisonTraceEntry.class,
            SameTraceEntry.class, InspectorTraceEntry.class, PrimitiveFieldTraceEntry.class, NullTraceEntry.class,
            ArrayTraceEntry.class, ArrayLengthTraceEntry.class, ContainsTraceEntry.class};

    /**
     * <p>
     * Constructor for AssertionGenerator.
     * </p>
     */
    public AssertionGenerator() {
        TestCaseExecutor.getInstance().addObserver(primitiveObserver);
        TestCaseExecutor.getInstance().addObserver(comparisonObserver);
        TestCaseExecutor.getInstance().addObserver(inspectorObserver);
        TestCaseExecutor.getInstance().addObserver(fieldObserver);
        TestCaseExecutor.getInstance().addObserver(nullObserver);
        TestCaseExecutor.getInstance().addObserver(sameObserver);
        TestCaseExecutor.getInstance().addObserver(arrayObserver);
        TestCaseExecutor.getInstance().addObserver(arrayLengthObserver);
        TestCaseExecutor.getInstance().addObserver(containsTraceObserver);
    }

    /**
     * <p>
     * addAssertions.
     * </p>
     *
     * @param test a {@link org.evosuite.testcase.TestCase} object.
     */
    public abstract void addAssertions(TestCase test);

    /**
     * Add assertions to all tests in a test suite.
     *
     * @param suite the test suite
     */
    public void addAssertions(TestSuiteChromosome suite) {

        setupClassLoader(suite);

        for (TestChromosome test : suite.getTestChromosomes()) {
            if (!TimeController.getInstance().hasTimeToExecuteATestCase()) {
                break;
            }

            addAssertions(test.getTestCase());
        }
    }

    /**
     * Execute a test case on the original unit.
     *
     * @param test The test case that should be executed
     * @return a {@link org.evosuite.testcase.execution.ExecutionResult} object.
     */
    protected ExecutionResult runTest(TestCase test) {
        ExecutionResult result = new ExecutionResult(test);
        try {
            logger.debug("Executing test");
            result = TestCaseExecutor.getInstance().execute(test);
            int num = test.size();
            MaxStatementsStoppingCondition.statementsExecuted(num);
            result.setTrace(comparisonObserver.getTrace(), ComparisonTraceEntry.class);
            result.setTrace(primitiveObserver.getTrace(), PrimitiveTraceEntry.class);
            result.setTrace(inspectorObserver.getTrace(), InspectorTraceEntry.class);
            result.setTrace(fieldObserver.getTrace(), PrimitiveFieldTraceEntry.class);
            result.setTrace(nullObserver.getTrace(), NullTraceEntry.class);
            result.setTrace(sameObserver.getTrace(), SameTraceEntry.class);
            result.setTrace(arrayObserver.getTrace(), ArrayTraceEntry.class);
            result.setTrace(arrayLengthObserver.getTrace(), ArrayLengthTraceEntry.class);
            result.setTrace(containsTraceObserver.getTrace(), ContainsTraceEntry.class);
        } catch (Exception e) {
            throw new Error(e);
        }

        return result;
    }

    protected void filterFailingAssertions(TestCase test) {

        // Make sure we are not keeping assertions influenced by static state
        // TODO: Need to handle statically initialized classes
        ExecutionResult result = runTest(test);
        Set<Assertion> invalidAssertions = new HashSet<>();
        for (Assertion assertion : test.getAssertions()) {
            for (OutputTrace<?> outputTrace : result.getTraces()) {
                if (outputTrace.isDetectedBy(assertion)) {
                    invalidAssertions.add(assertion);
                    break;
                }
            }
        }
        logger.info("Removing {} nondeterministic assertions", invalidAssertions.size());
        for (Assertion assertion : invalidAssertions) {
            test.removeAssertion(assertion);
        }
    }

    /**
     * Filters out failing assertions from the list of test cases.
     *
     * @param testCases the list of test cases to filter
     */
    public void filterFailingAssertions(List<TestCase> testCases) {
        List<TestCase> tests = new ArrayList<>(testCases);
        for (TestCase test : tests) {
            filterFailingAssertions(test);
        }

        // Execute again in different order
        Randomness.shuffle(tests);
        for (TestCase test : tests) {
            filterFailingAssertions(test);
        }
    }

    /**
     * Filters out failing assertions from the test suite chromosome.
     *
     * @param testSuite the test suite chromosome to filter
     */
    public void filterFailingAssertions(TestSuiteChromosome testSuite) {
        List<TestChromosome> tests = testSuite.getTestChromosomes();
        for (TestChromosome test : tests) {
            filterFailingAssertions(test.getTestCase());
        }

        // Execute again in different order
        Randomness.shuffle(tests);
        for (TestChromosome test : tests) {
            filterFailingAssertions(test.getTestCase());
        }
    }

    /**
     * Reinstrument to make sure final fields are removed.
     *
     * @param suite the test suite
     */
    public void setupClassLoader(TestSuiteChromosome suite) {
        if (!Properties.RESET_STATIC_FIELDS) {
            return;
        }
        final boolean resetAllClasses = Properties.RESET_ALL_CLASSES_DURING_ASSERTION_GENERATION;
        ClassReInitializer.getInstance().setReInitializeAllClasses(resetAllClasses);
        changeClassLoader(suite);
    }

    protected void changeClassLoader(TestSuiteChromosome suite) {
        Sandbox.goingToExecuteSUTCode();
        TestGenerationContext.getInstance().goingToExecuteSUTCode();
        Sandbox.goingToExecuteUnsafeCodeOnSameThread();
        try {


            TestGenerationContext.getInstance().resetContext();
            TestGenerationContext.getInstance().goingToExecuteSUTCode();
            // We need to reset the target Class since it requires a different instrumentation
            // for handling assertion generation.
            Properties.resetTargetClass();
            Properties.getInitializedTargetClass();

            ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Mutants,
                    MutationPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                            .getMutantCounter());

            for (TestChromosome test : suite.getTestChromosomes()) {
                DefaultTestCase dtest = (DefaultTestCase) test.getTestCase();
                dtest.changeClassLoader(TestGenerationContext.getInstance().getClassLoaderForSUT());
                test.setChanged(true); // clears cached results
                test.clearCachedMutationResults();
            }
        } catch (Throwable e) {
            LoggingUtils.getEvoLogger().error("* Error while initializing target class: "
                    + (e.getMessage() != null ? e.getMessage()
                    : e.toString()));
            logger.error("Problem for " + Properties.TARGET_CLASS + ". Full stack:", e);
        } finally {
            TestGenerationContext.getInstance().doneWithExecutingSUTCode();
            Sandbox.doneWithExecutingUnsafeCodeOnSameThread();
            Sandbox.doneWithExecutingSUTCode();
            TestGenerationContext.getInstance().doneWithExecutingSUTCode();
        }
    }

    /**
     * Returns true if the variable var is used as callee later on in the test.
     *
     * @param test the test case
     * @param var  the variable
     * @return true if the variable is used as callee
     */
    protected boolean isUsedAsCallee(TestCase test, VariableReference var) {
        for (int pos = var.getStPosition() + 1; pos < test.size(); pos++) {
            Statement statement = test.getStatement(pos);
            if (statement instanceof MethodStatement) {
                if (((MethodStatement) statement).getCallee() == var) {
                    return true;
                }
            } else if (statement instanceof FieldStatement) {
                if (((FieldStatement) statement).getSource() == var) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Remove NullAssertions that are redundant because more specific assertions
     * exist on the same return value, or the object is used as a callee later.
     *
     * @param test the test case
     */
    protected void filterRedundantNonnullAssertions(TestCase test) {
        Set<Assertion> redundantAssertions = new HashSet<>();
        for (Statement statement : test) {
            if (statement instanceof ConstructorStatement || statement instanceof MethodStatement) {
                VariableReference returnValue = statement.getReturnValue();
                Set<Assertion> assertions = statement.getAssertions();
                for (Assertion a : assertions) {
                    if (a instanceof NullAssertion) {
                        if (assertions.size() > 1) {
                            for (Assertion a2 : assertions) {
                                // Only a non-null assertion on the return value
                                // makes the NullAssertion redundant (e.g., assertEquals
                                // implies non-null). Inspector assertions on other
                                // variables (dependencies) don't count.
                                if (!(a2 instanceof NullAssertion)
                                        && a2.getSource() == returnValue) {
                                    redundantAssertions.add(a);
                                    break;
                                }
                            }
                        } else if (isUsedAsCallee(test, returnValue)) {
                            redundantAssertions.add(a);
                        }
                    }
                }
            }
        }

        for (Assertion a : redundantAssertions) {
            test.removeAssertion(a);
        }
    }

    /**
     * Remove chained inspector assertions that are redundant because the outer
     * method is explicitly called on the same variable later in the test.
     *
     * @param test the test case
     */
    protected void filterRedundantChainedInspectorAssertions(TestCase test) {
        Set<Assertion> toRemove = new HashSet<>();
        for (Statement statement : test) {
            for (Assertion assertion : statement.getAssertions()) {
                if (!(assertion instanceof InspectorAssertion)) {
                    continue;
                }
                InspectorAssertion ia = (InspectorAssertion) assertion;
                if (!(ia.getInspector() instanceof ChainedInspector)) {
                    continue;
                }
                ChainedInspector ci = (ChainedInspector) ia.getInspector();
                VariableReference sourceVar = ia.getSource();
                Method outerMethod = ci.getOuterMethod();

                // Check if any later MethodStatement calls the same outer method on the same variable
                for (int pos = statement.getPosition() + 1; pos < test.size(); pos++) {
                    Statement later = test.getStatement(pos);
                    if (later instanceof MethodStatement) {
                        MethodStatement ms = (MethodStatement) later;
                        if (ms.getCallee() == sourceVar
                                && ms.getMethod().getMethod().equals(outerMethod)) {
                            toRemove.add(assertion);
                            break;
                        }
                    }
                }
            }
        }
        for (Assertion a : toRemove) {
            test.removeAssertion(a);
        }
    }

    /**
     * Remove redundant isEmpty/size assertion pairs on the same source variable.
     * If size == 0, keep only isEmpty(). If size > 0, keep only size().
     *
     * @param test the test case
     */
    protected void filterRedundantIsEmptySizeAssertions(TestCase test) {
        for (Statement statement : test) {
            Set<Assertion> assertions = statement.getAssertions();
            if (assertions.size() < 2) {
                continue;
            }

            // Group InspectorAssertions by source variable
            Map<VariableReference, List<InspectorAssertion>> bySource = new HashMap<>();
            for (Assertion a : assertions) {
                if (a instanceof InspectorAssertion) {
                    InspectorAssertion ia = (InspectorAssertion) a;
                    bySource.computeIfAbsent(ia.getSource(), k -> new ArrayList<>()).add(ia);
                }
            }

            Set<Assertion> toRemove = new HashSet<>();
            for (List<InspectorAssertion> group : bySource.values()) {
                InspectorAssertion isEmptyAssertion = null;
                InspectorAssertion sizeAssertion = null;

                for (InspectorAssertion ia : group) {
                    Inspector inspector = ia.getInspector();
                    // Use the inspector's own method (for ChainedInspector this is the inner method)
                    String methodName = inspector.getMethod().getName();
                    if ("isEmpty".equals(methodName)) {
                        isEmptyAssertion = ia;
                    } else if ("size".equals(methodName)) {
                        sizeAssertion = ia;
                    }
                }

                if (isEmptyAssertion != null && sizeAssertion != null) {
                    Object sizeValue = sizeAssertion.value;
                    if (sizeValue instanceof Number && ((Number) sizeValue).intValue() == 0) {
                        // size == 0: keep isEmpty, remove size
                        toRemove.add(sizeAssertion);
                    } else {
                        // size > 0: keep size, remove isEmpty
                        toRemove.add(isEmptyAssertion);
                    }
                }
            }

            for (Assertion a : toRemove) {
                statement.removeAssertion(a);
            }
        }
    }

    /**
     * Remove inspector assertions that duplicate a primitive assertion for the
     * same method's return value.
     *
     * @param statement the statement
     */
    protected void filterInspectorPrimitiveDuplication(Statement statement) {
        Set<Assertion> assertions = new HashSet<>(statement.getAssertions());
        if (assertions.size() < 2) {
            return;
        }

        if (!(statement instanceof MethodStatement)) {
            return;
        }

        MethodStatement methodStatement = (MethodStatement) statement;

        boolean hasPrimitive = false;
        for (Assertion assertion : assertions) {
            if (assertion instanceof PrimitiveAssertion) {
                if (assertion.getStatement().equals(statement)) {
                    hasPrimitive = true;
                }
            }
        }

        if (hasPrimitive) {
            for (Assertion assertion : assertions) {
                if (assertion instanceof InspectorAssertion) {
                    InspectorAssertion ia = (InspectorAssertion) assertion;
                    if (ia.getInspector().getMethod().equals(methodStatement.getMethod().getMethod())) {
                        statement.removeAssertion(assertion);
                        return;
                    }
                }
            }
        }
    }

}
