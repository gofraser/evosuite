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
package org.evosuite.llm.search;

import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.NullReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExceptionBarrierTrackerTest {

    @Test
    void aggregatesAcrossObservations() {
        ExceptionBarrierTracker tracker = new ExceptionBarrierTracker(4, 64);
        String method = ExampleExceptionTarget.class.getName() + ".doWork";

        tracker.observe(Collections.singletonList(throwingChromosome(method,
                new NullPointerException("npe"))), Collections.singleton(method));
        tracker.observe(Collections.singletonList(throwingChromosome(method,
                new IllegalStateException("ise"))), Collections.singleton(method));

        Map<String, ExceptionBarrierTracker.AggregatedStats> snapshot =
                tracker.snapshot(Collections.singleton(method));
        ExceptionBarrierTracker.AggregatedStats stats = snapshot.get(method);

        assertTrue(stats != null, "Expected aggregated stats for tracked method");
        assertEquals(2, stats.getAttempts());
        assertEquals(0, stats.getSuccesses());
        assertEquals(2, stats.getExceptions());
    }

    @Test
    void successfulCompletionIsTracked() {
        ExceptionBarrierTracker tracker = new ExceptionBarrierTracker(4, 64);
        String method = ExampleExceptionTarget.class.getName() + ".doWork";

        tracker.observe(Collections.singletonList(successChromosome(method)),
                Collections.singleton(method));

        Map<String, ExceptionBarrierTracker.AggregatedStats> snapshot =
                tracker.snapshot(Collections.singleton(method));
        ExceptionBarrierTracker.AggregatedStats stats = snapshot.get(method);

        assertTrue(stats != null, "Expected stats after one observation");
        assertEquals(1, stats.getAttempts());
        assertEquals(1, stats.getSuccesses());
        assertEquals(0, stats.getExceptions());
    }

    @Test
    void failingCoverageWithoutDirectInvocationIsTrackedSeparately() {
        ExceptionBarrierTracker tracker = new ExceptionBarrierTracker(4, 64);
        String method = ExampleExceptionTarget.class.getName() + ".doWork";

        tracker.observe(Collections.singletonList(throwingChromosomeWithCoveredMethodOnly(method,
                        new IllegalStateException("ise"))),
                Collections.singleton(method));

        Map<String, ExceptionBarrierTracker.AggregatedStats> snapshot =
                tracker.snapshot(Collections.singleton(method));
        ExceptionBarrierTracker.AggregatedStats stats = snapshot.get(method);

        assertTrue(stats != null, "Expected stats after covered failing observation");
        assertEquals(0, stats.getAttempts());
        assertEquals(0, stats.getSuccesses());
        assertEquals(0, stats.getExceptions());
        assertEquals(1, stats.getCoveredInFailingTests());
    }

    @Test
    void countsThrowingInvocationWhenExecutionStopsAtThrowIndex() {
        ExceptionBarrierTracker tracker = new ExceptionBarrierTracker(4, 64);
        String method = ExampleExceptionTarget.class.getName() + ".doWork";

        Statement helperCall = methodStatementFor(declaredMethod(ExampleExceptionTarget.class, "helper"));
        Statement targetCall = methodStatementFor(declaredMethod(ExampleExceptionTarget.class, "doWork"));
        TestChromosome chromosome = chromosomeWithStatements(
                java.util.Arrays.asList(helperCall, targetCall),
                1,
                1,
                new IllegalStateException("ise"),
                Collections.singleton(method));

        tracker.observe(Collections.singletonList(chromosome), Collections.singleton(method));
        Map<String, ExceptionBarrierTracker.AggregatedStats> snapshot =
                tracker.snapshot(Collections.singleton(method));
        ExceptionBarrierTracker.AggregatedStats stats = snapshot.get(method);

        assertTrue(stats != null, "Expected stats after one throwing observation");
        assertEquals(1, stats.getAttempts());
        assertEquals(0, stats.getSuccesses());
        assertEquals(1, stats.getExceptions());
    }

    @Test
    void tracksContextSpecificExceptionHistoryAcrossObservations() {
        ExceptionBarrierTracker tracker = new ExceptionBarrierTracker(4, 64);
        String method = ExampleExceptionTarget.class.getName() + ".withDependency";

        tracker.observe(Collections.singletonList(throwingChromosome(method,
                        methodStatementFor(declaredMethod(ExampleExceptionTarget.class, "withDependency", Object.class),
                                Collections.singletonList(nonNullParameter(0, Object.class))),
                        new NullPointerException("npe"))),
                Collections.singleton(method));
        tracker.observe(Collections.singletonList(successChromosome(method,
                        methodStatementFor(declaredMethod(ExampleExceptionTarget.class, "withDependency", Object.class),
                                Collections.singletonList(nullParameter(Object.class))))),
                Collections.singleton(method));

        Map<String, ExceptionBarrierTracker.AggregatedStats> snapshot =
                tracker.snapshot(Collections.singleton(method));
        ExceptionBarrierTracker.AggregatedStats stats = snapshot.get(method);
        assertNotNull(stats, "Expected aggregated stats for tracked method");
        ExceptionBarrierTracker.ContextStats nonNullContext = stats.getContextStats().get("arg0=nonnull");
        ExceptionBarrierTracker.ContextStats nullContext = stats.getContextStats().get("arg0=null");
        assertNotNull(nonNullContext, "Expected non-null argument context to be tracked");
        assertNotNull(nullContext, "Expected null argument context to be tracked");
        assertEquals(1, nonNullContext.getAttempts());
        assertEquals(1, nonNullContext.getExceptions());
        assertEquals(0, nullContext.getExceptions());
        assertEquals(1, nullContext.getSuccesses());
        assertEquals("arg0 (Object)=nonnull", nonNullContext.getContextLabel());
    }

    @Test
    void tracksUpstreamExceptionHistoryAcrossObservations() {
        ExceptionBarrierTracker tracker = new ExceptionBarrierTracker(4, 64);
        String goalMethod = ExampleExceptionTarget.class.getName() + ".doWork";

        Statement helperCall = methodStatementFor(declaredMethod(ExampleExceptionTarget.class, "helper"));
        Statement targetCall = methodStatementFor(declaredMethod(ExampleExceptionTarget.class, "doWork"));
        tracker.observe(Collections.singletonList(chromosomeWithStatements(
                        java.util.Arrays.asList(helperCall, targetCall),
                        0,
                        new IllegalStateException("helper fail"),
                        Collections.singleton(goalMethod))),
                Collections.singleton(goalMethod));
        tracker.observe(Collections.singletonList(chromosomeWithStatements(
                        java.util.Arrays.asList(helperCall, targetCall),
                        0,
                        new NullPointerException("helper fail"),
                        Collections.singleton(goalMethod))),
                Collections.singleton(goalMethod));

        Map<String, ExceptionBarrierTracker.AggregatedStats> snapshot =
                tracker.snapshot(Collections.singleton(goalMethod));
        ExceptionBarrierTracker.AggregatedStats stats = snapshot.get(goalMethod);
        assertNotNull(stats, "Expected aggregated stats for tracked method");
        ExceptionBarrierTracker.UpstreamStats upstream = stats.getUpstreamStats()
                .get(ExampleExceptionTarget.class.getName() + ".helper");
        assertNotNull(upstream, "Expected upstream helper failures to be tracked for the blocked target method");
        assertEquals(2, upstream.getAttempts());
        assertEquals(2, upstream.getExceptions());
        assertEquals(0, upstream.getSuccesses());
    }

    @Test
    void barrierCardsAreStableAcrossHistoryWindows() throws Exception {
        String dependency = ExampleExceptionTarget.class.getName() + ".withDependency";
        Statement nonNullCall = methodStatementFor(declaredMethod(ExampleExceptionTarget.class,
                "withDependency", Object.class), Collections.singletonList(nonNullParameter(0, Object.class)));
        Statement nullCall = methodStatementFor(declaredMethod(ExampleExceptionTarget.class,
                "withDependency", Object.class), Collections.singletonList(nullParameter(Object.class)));
        TestChromosome nonNullIse = throwingChromosome(dependency, nonNullCall, new IllegalStateException("a"));
        TestChromosome nonNullNpe = throwingChromosome(dependency, nonNullCall, new NullPointerException("b"));
        TestChromosome nonNullOk = successChromosome(dependency, nonNullCall);
        TestChromosome nullOk = successChromosome(dependency, nullCall);

        List<List<TestChromosome>> contextRounds = Arrays.asList(
                Arrays.asList(nonNullIse, nonNullIse, nonNullNpe, nullOk, nullOk, nullOk, nullOk),
                Arrays.asList(nonNullNpe, nonNullNpe, nonNullNpe, nullOk, nullOk, nullOk, nullOk),
                Arrays.asList(nonNullOk, nullOk, nullOk, nullOk, nullOk),
                Arrays.asList(nonNullIse, nonNullNpe, nullOk, nullOk, nullOk, nullOk),
                Arrays.asList(nonNullIse, nonNullIse, nullOk, nullOk));

        String doWork = ExampleExceptionTarget.class.getName() + ".doWork";
        Statement helperCall = methodStatementFor(declaredMethod(ExampleExceptionTarget.class, "helper"));
        Statement targetCall = methodStatementFor(declaredMethod(ExampleExceptionTarget.class, "doWork"));
        TestChromosome helperIse = chromosomeWithStatements(Arrays.asList(helperCall, targetCall), 0,
                new IllegalStateException("h"), Collections.singleton(doWork));
        TestChromosome helperNpe = chromosomeWithStatements(Arrays.asList(helperCall, targetCall), 0,
                new NullPointerException("h"), Collections.singleton(doWork));
        TestChromosome targetOk = successChromosome(doWork);
        TestChromosome targetIse = throwingChromosome(doWork, new IllegalStateException("t"));
        List<List<TestChromosome>> upstreamRounds = Arrays.asList(
                Arrays.asList(helperIse, helperIse, targetOk),
                Arrays.asList(helperNpe, targetOk, targetOk),
                Arrays.asList(helperNpe, helperIse),
                Arrays.asList(targetIse, targetOk, targetOk, targetOk));

        String withInput = ExampleExceptionTarget.class.getName() + ".withInput";
        Statement stringCall = methodStatementFor(declaredMethod(ExampleExceptionTarget.class,
                "withInput", String.class));
        Statement intCall = methodStatementFor(declaredMethod(ExampleExceptionTarget.class,
                "withInput", int.class));
        TestChromosome stringNpe = throwingChromosome(withInput, stringCall, new NullPointerException("s"));
        TestChromosome stringIse = throwingChromosome(withInput, stringCall, new IllegalStateException("s"));
        TestChromosome intOk = successChromosome(withInput, intCall);
        List<List<TestChromosome>> signatureRounds = Arrays.asList(
                Arrays.asList(stringNpe, stringNpe, stringIse, intOk, intOk, intOk, intOk, intOk),
                Arrays.asList(stringNpe, stringIse, intOk, intOk),
                Arrays.asList(stringNpe, intOk, intOk, intOk));

        String rendered = renderBarrierCards(contextRounds, "withDependency(Ljava/lang/Object;)V", dependency)
                + renderBarrierCards(upstreamRounds, "doWork()V", doWork)
                + renderBarrierCards(signatureRounds, "withInput(Ljava/lang/String;)V", withInput);
        assertEquals(expectedBarrierCards(), rendered);
    }

    private static String renderBarrierCards(List<List<TestChromosome>> rounds, String goalMethod, String methodKey) {
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.getTargetClass()).thenReturn(ExampleExceptionTarget.class.getName());
        when(goal.getTargetMethod()).thenReturn(goalMethod);
        when(goal.toString()).thenReturn(ExampleExceptionTarget.class.getName() + "." + goalMethod);
        ExceptionBarrierTracker tracker = new ExceptionBarrierTracker(3, 64);
        ProblemCardExtractor extractor = new ProblemCardExtractor();
        StringBuilder out = new StringBuilder();
        int round = 0;
        for (List<TestChromosome> population : rounds) {
            Map<String, ExceptionBarrierTracker.AggregatedStats> historical =
                    tracker.snapshot(Collections.singleton(methodKey));
            out.append("== ").append(methodKey).append(" round ").append(round++)
                    .append(" historical=").append(historical.containsKey(methodKey)).append('\n');
            List<ProblemCard> cards = new ArrayList<>(extractor.extract(Collections.singleton(goal),
                    population, Integer.MAX_VALUE, historical));
            for (ProblemCard card : cards) {
                if (card.getType() != ProblemCardType.EXCEPTION_BARRIER) {
                    continue;
                }
                out.append(card.getTitle()).append('\n');
                for (String line : card.getEvidence()) {
                    out.append("  ").append(line).append('\n');
                }
                out.append("  blockage=").append(card.getBlockage())
                        .append(" confidence=").append(card.getConfidence())
                        .append(" impact=").append(card.getImpact())
                        .append(" priority=").append(card.getPriority()).append('\n')
                        .append("  root=").append(card.getRootCauseKey())
                        .append(" scope=").append(card.getScopeKey()).append('\n')
                        .append("  fingerprint=").append(card.getSelectionFingerprint()).append('\n');
            }
            tracker.observe(population, Collections.singleton(methodKey));
        }
        return out.toString();
    }

    private static String expectedBarrierCards() throws Exception {
        try (java.io.InputStream in = ExceptionBarrierTrackerTest.class
                .getResourceAsStream("exception-barrier-cards.txt")) {
            assertNotNull(in, "missing exception-barrier-cards.txt");
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                bytes.write(buffer, 0, read);
            }
            return new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static TestChromosome throwingChromosome(String method, Throwable throwable) {
        Statement targetCall = methodStatementFor(declaredMethod(ExampleExceptionTarget.class, "doWork"));
        return chromosomeWithStatements(Collections.singletonList(targetCall), 0, throwable,
                Collections.singleton(method));
    }

    private static TestChromosome throwingChromosome(String method,
                                                     Statement targetCall,
                                                     Throwable throwable) {
        return chromosomeWithStatements(Collections.singletonList(targetCall), 0, throwable,
                Collections.singleton(method));
    }

    private static TestChromosome successChromosome(String method) {
        Statement targetCall = methodStatementFor(declaredMethod(ExampleExceptionTarget.class, "doWork"));
        return successChromosome(method, targetCall);
    }

    private static TestChromosome successChromosome(String method, Statement targetCall) {
        return chromosomeWithSuccessfulStatements(Collections.singletonList(targetCall),
                Collections.singleton(method));
    }

    private static TestChromosome throwingChromosomeWithCoveredMethodOnly(String method, Throwable throwable) {
        Statement helperCall = methodStatementFor(declaredMethod(ExampleExceptionTarget.class, "helper"));
        return chromosomeWithStatements(Collections.singletonList(helperCall), 0, throwable,
                Collections.singleton(method));
    }

    private static TestChromosome chromosomeWithStatements(List<Statement> statements,
                                                           int thrownPosition,
                                                           Throwable throwable,
                                                           java.util.Set<String> coveredMethods) {
        return chromosomeWithStatements(statements, thrownPosition, thrownPosition + 1,
                throwable, coveredMethods);
    }

    private static TestChromosome chromosomeWithStatements(List<Statement> statements,
                                                           int thrownPosition,
                                                           int executedStatements,
                                                           Throwable throwable,
                                                           java.util.Set<String> coveredMethods) {
        TestChromosome chromosome = mock(TestChromosome.class);
        TestCase testCase = mock(TestCase.class);
        ExecutionResult result = mock(ExecutionResult.class);
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getCoveredMethods()).thenReturn(coveredMethods);
        when(result.getTrace()).thenReturn(trace);
        when(result.hasTimeout()).thenReturn(false);
        when(result.hasTestException()).thenReturn(true);
        when(result.getFirstPositionOfThrownException()).thenReturn(thrownPosition);
        when(result.getExceptionThrownAtPosition(thrownPosition)).thenReturn(throwable);
        when(result.getExecutedStatements()).thenReturn(executedStatements);
        when(testCase.size()).thenReturn(statements.size());
        for (int i = 0; i < statements.size(); i++) {
            when(testCase.hasStatement(i)).thenReturn(true);
            when(testCase.getStatement(i)).thenReturn(statements.get(i));
        }
        when(chromosome.getLastExecutionResult()).thenReturn(result);
        when(chromosome.getTestCase()).thenReturn(testCase);
        return chromosome;
    }

    private static TestChromosome chromosomeWithSuccessfulStatements(List<Statement> statements,
                                                                     java.util.Set<String> coveredMethods) {
        TestChromosome chromosome = mock(TestChromosome.class);
        TestCase testCase = mock(TestCase.class);
        ExecutionResult result = mock(ExecutionResult.class);
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getCoveredMethods()).thenReturn(coveredMethods);
        when(result.getTrace()).thenReturn(trace);
        when(result.hasTimeout()).thenReturn(false);
        when(result.hasTestException()).thenReturn(false);
        when(result.getExecutedStatements()).thenReturn(statements.size());
        when(testCase.size()).thenReturn(statements.size());
        for (int i = 0; i < statements.size(); i++) {
            when(testCase.hasStatement(i)).thenReturn(true);
            when(testCase.getStatement(i)).thenReturn(statements.get(i));
        }
        when(chromosome.getLastExecutionResult()).thenReturn(result);
        when(chromosome.getTestCase()).thenReturn(testCase);
        return chromosome;
    }

    private static Statement methodStatementFor(Method reflectedMethod) {
        return methodStatementFor(reflectedMethod, Collections.emptyList());
    }

    private static Statement methodStatementFor(Method reflectedMethod, List<VariableReference> parameters) {
        MethodStatement statement = mock(MethodStatement.class);
        GenericMethod method = mock(GenericMethod.class);
        VariableReference callee = mock(VariableReference.class);
        when(method.getMethod()).thenReturn(reflectedMethod);
        when(method.isStatic()).thenReturn(false);
        when(statement.getMethod()).thenReturn(method);
        when(statement.getParameterReferences()).thenReturn(parameters);
        doReturn(reflectedMethod.getDeclaringClass()).when(callee).getVariableClass();
        when(statement.getCallee()).thenReturn(callee);
        return statement;
    }

    private static VariableReference nonNullParameter(int position, Class<?> parameterType) {
        VariableReference parameter = mock(VariableReference.class);
        TestCase testCase = mock(TestCase.class);
        when(parameter.getTestCase()).thenReturn(testCase);
        when(parameter.getStPosition()).thenReturn(position);
        when(parameter.isPrimitive()).thenReturn(false);
        when(parameter.getSimpleClassName()).thenReturn(parameterType.getSimpleName());
        when(testCase.getStatement(position)).thenReturn(mock(Statement.class));
        return parameter;
    }

    private static VariableReference nullParameter(Class<?> parameterType) {
        return new NullReference(mock(TestCase.class), parameterType);
    }

    private static Method declaredMethod(Class<?> type, String methodName) {
        try {
            return type.getDeclaredMethod(methodName);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static Method declaredMethod(Class<?> type, String methodName, Class<?>... parameterTypes) {
        try {
            return type.getDeclaredMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static final class ExampleExceptionTarget {
        void doWork() {
            // no-op
        }

        void withDependency(Object dependency) {
            // no-op
        }

        void withInput(String input) {
            // no-op
        }

        @SuppressWarnings("unused")
        void withInput(int input) {
            // overload used by the signature bucketing case
        }

        void helper() {
            // no-op
        }
    }
}
