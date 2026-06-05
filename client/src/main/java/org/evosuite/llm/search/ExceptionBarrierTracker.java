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

import org.evosuite.llm.prompt.GoalDescriptionMapper;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Tracks exception-barrier evidence across multiple stagnation snapshots.
 */
public class ExceptionBarrierTracker {

    private static final int DEFAULT_WINDOW_SIZE = 6;
    private static final int DEFAULT_MAX_METHODS = 512;

    private final int windowSize;
    private final int maxMethods;
    private final GoalDescriptionMapper goalDescriptionMapper = new GoalDescriptionMapper();
    private final Map<String, Deque<MethodObservation>> observationsByMethod =
            new LinkedHashMap<>();

    public ExceptionBarrierTracker() {
        this(DEFAULT_WINDOW_SIZE, DEFAULT_MAX_METHODS);
    }

    ExceptionBarrierTracker(int windowSize, int maxMethods) {
        this.windowSize = Math.max(1, windowSize);
        this.maxMethods = Math.max(16, maxMethods);
    }

    /**
     * Observes a population snapshot for the given goal methods.
     */
    public synchronized void observe(List<TestChromosome> population, Set<String> goalMethods) {
        if (goalMethods == null || goalMethods.isEmpty()) {
            return;
        }
        List<TestChromosome> snapshot = population == null ? Collections.emptyList() : population;
        Map<String, MethodObservation> current = new HashMap<>();
        for (String method : goalMethods) {
            current.put(method, new MethodObservation());
        }

        for (TestChromosome chromosome : snapshot) {
            if (chromosome == null) {
                continue;
            }
            ExecutionResult result = chromosome.getLastExecutionResult();
            if (result == null) {
                continue;
            }
            TestCase test = chromosome.getTestCase() != null ? chromosome.getTestCase() : result.test;
            if (test == null) {
                continue;
            }
            int safeSize = safeTestSize(test);
            if (safeSize <= 0) {
                continue;
            }
            int executedStatements = result.getExecutedStatements();
            int thrownPosition = firstThrownPosition(result);
            if (result.hasTestException() && thrownPosition >= 0) {
                // Include the throwing statement itself (ExecutionResult counts
                // only fully completed statements before the abrupt termination).
                executedStatements = Math.max(executedStatements, thrownPosition + 1);
            }
            if (executedStatements <= 0 || executedStatements > safeSize) {
                executedStatements = safeSize;
            }
            String exceptionType = result.hasTestException() ? exceptionType(result) : "";
            // Track goal methods directly invoked in this test's prefix so we don't double-credit
            // them as "covered in failing tests" — their direct outcome already shows up in
            // attempts/successes/exceptions.
            Set<String> directlyInvokedGoalMethods = new LinkedHashSet<>();
            for (int i = 0; i < executedStatements; i++) {
                Statement statement = statementAt(test, i);
                if (!(statement instanceof MethodStatement)) {
                    continue;
                }
                MethodObservation obs = current.get(directMethodKey((MethodStatement) statement));
                GoalDescriptionMapper.OperationTarget invocation =
                        goalDescriptionMapper.describeMethodOperation((MethodStatement) statement);
                boolean exceptionOutcome = result.hasTestException() && thrownPosition == i;
                if (obs != null) {
                    directlyInvokedGoalMethods.add(directMethodKey((MethodStatement) statement));
                    InvocationContextClassifier.InvocationContext context =
                            InvocationContextClassifier.classify((MethodStatement) statement);
                    ContextObservation contextObservation = obs.context(context);
                    obs.attempts++;
                    contextObservation.attempts++;
                    if (exceptionOutcome) {
                        obs.exceptions++;
                        contextObservation.exceptions++;
                        String invocationLabel = invocation.getDisplayLabel();
                        if (!invocationLabel.isEmpty()) {
                            obs.failingInvocationLabelCounts.put(invocationLabel,
                                    obs.failingInvocationLabelCounts.getOrDefault(invocationLabel, 0) + 1);
                        }
                        if (i > 0) {
                            obs.failingInvocationsWithSuccessfulPrefix++;
                        }
                        if (!exceptionType.isEmpty()) {
                            obs.exceptionCounts.put(exceptionType,
                                    obs.exceptionCounts.getOrDefault(exceptionType, 0) + 1);
                            contextObservation.exceptionCounts.put(exceptionType,
                                    contextObservation.exceptionCounts.getOrDefault(exceptionType, 0) + 1);
                        }
                        contextObservation.recordFailingInvocation(invocationLabel, i > 0);
                    } else if (!result.hasTimeout()) {
                        obs.successes++;
                        contextObservation.successes++;
                    }
                    continue;
                }
                Set<String> downstreamGoalMethods = downstreamGoalMethodsAfter(test, i, goalMethods);
                if (downstreamGoalMethods.isEmpty()) {
                    continue;
                }
                for (String blockedGoalMethod : downstreamGoalMethods) {
                    MethodObservation blockedObservation = current.get(blockedGoalMethod);
                    if (blockedObservation == null) {
                        continue;
                    }
                    UpstreamObservation upstream = blockedObservation.upstream(invocation.getExecutionKey(),
                            invocation.getDisplayLabel());
                    upstream.attempts++;
                    if (exceptionOutcome) {
                        upstream.exceptions++;
                        upstream.recordFailingInvocation(invocation.getDisplayLabel(), i > 0);
                        if (!exceptionType.isEmpty()) {
                            upstream.exceptionCounts.put(exceptionType,
                                    upstream.exceptionCounts.getOrDefault(exceptionType, 0) + 1);
                        }
                    } else if (!result.hasTimeout()) {
                        upstream.successes++;
                    }
                }
            }
            if (result.hasTestException()) {
                ExecutionTrace trace = result.getTrace();
                if (trace != null && trace.getCoveredMethods() != null) {
                    for (String coveredMethod : trace.getCoveredMethods()) {
                        String key = coveredMethodKey(coveredMethod);
                        if (directlyInvokedGoalMethods.contains(key)) {
                            continue;
                        }
                        MethodObservation obs = current.get(key);
                        if (obs != null) {
                            obs.coveredInFailingTests++;
                        }
                    }
                }
            }
        }

        for (Map.Entry<String, MethodObservation> entry : current.entrySet()) {
            String method = entry.getKey();
            MethodObservation obs = entry.getValue();
            Deque<MethodObservation> deque = observationsByMethod.computeIfAbsent(method,
                    ignored -> new ArrayDeque<>());
            deque.addLast(obs.copy());
            while (deque.size() > windowSize) {
                deque.removeFirst();
            }
        }
        pruneToMaxMethods(goalMethods);
    }

    /**
     * Returns aggregated stats for the requested methods.
     */
    public synchronized Map<String, AggregatedStats> snapshot(Set<String> methods) {
        if (methods == null || methods.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, AggregatedStats> result = new HashMap<>();
        for (String method : methods) {
            Deque<MethodObservation> deque = observationsByMethod.get(method);
            if (deque == null || deque.isEmpty()) {
                continue;
            }
            int attempts = 0;
            int successes = 0;
            int exceptions = 0;
            int coveredInFailingTests = 0;
            Map<String, Integer> exCounts = new HashMap<>();
            Map<String, ContextObservation> mergedContexts = new LinkedHashMap<>();
            Map<String, Integer> failingInvocationCounts = new HashMap<>();
            Map<String, UpstreamObservation> mergedUpstream = new LinkedHashMap<>();
            int failingInvocationsWithSuccessfulPrefix = 0;
            for (MethodObservation obs : deque) {
                attempts += obs.attempts;
                successes += obs.successes;
                exceptions += obs.exceptions;
                coveredInFailingTests += obs.coveredInFailingTests;
                for (Map.Entry<String, Integer> e : obs.exceptionCounts.entrySet()) {
                    exCounts.put(e.getKey(), exCounts.getOrDefault(e.getKey(), 0) + e.getValue());
                }
                mergeLabelCounts(failingInvocationCounts, obs.failingInvocationLabelCounts);
                failingInvocationsWithSuccessfulPrefix += obs.failingInvocationsWithSuccessfulPrefix;
                for (ContextObservation contextObservation : obs.contexts.values()) {
                    ContextObservation merged = mergedContexts.computeIfAbsent(contextObservation.contextKey,
                            ignored -> new ContextObservation(contextObservation.contextKey,
                                    contextObservation.contextLabel));
                    merged.add(contextObservation);
                }
                for (UpstreamObservation upstreamObservation : obs.upstreamObservations.values()) {
                    UpstreamObservation merged = mergedUpstream.computeIfAbsent(
                            upstreamObservation.blockerExecutionKey,
                            ignored -> new UpstreamObservation(upstreamObservation.blockerExecutionKey,
                                    upstreamObservation.blockerDisplayLabel));
                    merged.add(upstreamObservation);
                }
            }
            Map<String, ContextStats> contextStats = new LinkedHashMap<>();
            for (ContextObservation contextObservation : mergedContexts.values()) {
                contextStats.put(contextObservation.contextKey, new ContextStats(
                        contextObservation.contextKey,
                        contextObservation.contextLabel,
                        contextObservation.attempts,
                        contextObservation.successes,
                        contextObservation.exceptions,
                        dominantException(contextObservation.exceptionCounts),
                        dominantLabel(contextObservation.failingInvocationLabelCounts),
                        contextObservation.failingInvocationsWithSuccessfulPrefix));
            }
            Map<String, UpstreamStats> upstreamStats = new LinkedHashMap<>();
            for (UpstreamObservation upstreamObservation : mergedUpstream.values()) {
                upstreamStats.put(upstreamObservation.blockerExecutionKey, new UpstreamStats(
                        upstreamObservation.blockerExecutionKey,
                        upstreamObservation.blockerDisplayLabel,
                        upstreamObservation.attempts,
                        upstreamObservation.successes,
                        upstreamObservation.exceptions,
                        dominantException(upstreamObservation.exceptionCounts),
                        dominantLabel(upstreamObservation.failingInvocationLabelCounts),
                        upstreamObservation.failingInvocationsWithSuccessfulPrefix));
            }
            result.put(method, new AggregatedStats(
                    attempts, successes, exceptions, coveredInFailingTests, dominantException(exCounts),
                    dominantLabel(failingInvocationCounts), failingInvocationsWithSuccessfulPrefix,
                    contextStats, upstreamStats));
        }
        return result;
    }

    /**
     * Clears all accumulated observations.
     */
    public synchronized void reset() {
        observationsByMethod.clear();
    }

    private void pruneToMaxMethods(Set<String> activeMethods) {
        if (observationsByMethod.size() <= maxMethods) {
            return;
        }
        // Prefer retaining currently relevant methods.
        List<String> removable = new ArrayList<>();
        for (String key : observationsByMethod.keySet()) {
            if (activeMethods == null || !activeMethods.contains(key)) {
                removable.add(key);
            }
        }
        for (String key : removable) {
            observationsByMethod.remove(key);
            if (observationsByMethod.size() <= maxMethods) {
                return;
            }
        }
        while (observationsByMethod.size() > maxMethods) {
            String first = observationsByMethod.keySet().iterator().next();
            observationsByMethod.remove(first);
        }
    }

    private String exceptionType(ExecutionResult result) {
        try {
            Integer position = result.getFirstPositionOfThrownException();
            if (position == null) {
                return "";
            }
            Throwable throwable = result.getExceptionThrownAtPosition(position);
            Throwable root = rootCause(throwable);
            return root == null ? "" : root.getClass().getSimpleName();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private int firstThrownPosition(ExecutionResult result) {
        try {
            Integer position = result.getFirstPositionOfThrownException();
            return position == null ? -1 : position;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private int safeTestSize(TestCase test) {
        try {
            return test == null ? 0 : test.size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private Statement statementAt(TestCase test, int position) {
        if (test == null || position < 0) {
            return null;
        }
        try {
            if (!test.hasStatement(position)) {
                return null;
            }
            return test.getStatement(position);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String directMethodKey(MethodStatement statement) {
        return goalDescriptionMapper.describeMethodOperation(statement).getExecutionKey();
    }

    private String coveredMethodKey(String coveredMethod) {
        return goalDescriptionMapper.describeQualifiedMethodOperation(coveredMethod).getExecutionKey();
    }

    private Set<String> downstreamGoalMethodsAfter(TestCase test, int statementIndex, Set<String> goalMethods) {
        if (test == null || goalMethods == null || goalMethods.isEmpty()) {
            return Collections.emptySet();
        }
        int safeSize = safeTestSize(test);
        if (statementIndex < 0 || statementIndex >= safeSize - 1) {
            return Collections.emptySet();
        }
        Set<String> downstream = new LinkedHashSet<>();
        for (int i = statementIndex + 1; i < safeSize; i++) {
            Statement statement = statementAt(test, i);
            if (!(statement instanceof MethodStatement)) {
                continue;
            }
            String executionKey = directMethodKey((MethodStatement) statement);
            if (goalMethods.contains(executionKey)) {
                downstream.add(executionKey);
            }
        }
        return downstream;
    }

    private Throwable rootCause(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        int safety = 0;
        while (current.getCause() != null && current.getCause() != current && safety++ < 32) {
            current = current.getCause();
        }
        return current;
    }

    private String dominantException(Map<String, Integer> counts) {
        return dominantLabel(counts);
    }

    private String dominantLabel(Map<String, Integer> counts) {
        String winner = "";
        int best = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            Integer value = entry.getValue();
            if (value != null && value > best) {
                winner = entry.getKey();
                best = value;
            }
        }
        return winner;
    }

    private static void mergeLabelCounts(Map<String, Integer> destination, Map<String, Integer> source) {
        if (destination == null || source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty() || entry.getValue() == null) {
                continue;
            }
            destination.put(entry.getKey(),
                    destination.getOrDefault(entry.getKey(), 0) + entry.getValue());
        }
    }

    private static final class MethodObservation {
        int attempts;
        int successes;
        int exceptions;
        int coveredInFailingTests;
        final Map<String, Integer> exceptionCounts = new HashMap<>();
        final Map<String, Integer> failingInvocationLabelCounts = new LinkedHashMap<>();
        final Map<String, ContextObservation> contexts = new LinkedHashMap<>();
        final Map<String, UpstreamObservation> upstreamObservations = new LinkedHashMap<>();
        int failingInvocationsWithSuccessfulPrefix;

        MethodObservation copy() {
            MethodObservation clone = new MethodObservation();
            clone.attempts = attempts;
            clone.successes = successes;
            clone.exceptions = exceptions;
            clone.coveredInFailingTests = coveredInFailingTests;
            clone.exceptionCounts.putAll(exceptionCounts);
            clone.failingInvocationLabelCounts.putAll(failingInvocationLabelCounts);
            clone.failingInvocationsWithSuccessfulPrefix = failingInvocationsWithSuccessfulPrefix;
            for (ContextObservation context : contexts.values()) {
                clone.contexts.put(context.contextKey, context.copy());
            }
            for (UpstreamObservation upstream : upstreamObservations.values()) {
                clone.upstreamObservations.put(upstream.blockerExecutionKey, upstream.copy());
            }
            return clone;
        }

        ContextObservation context(InvocationContextClassifier.InvocationContext context) {
            InvocationContextClassifier.InvocationContext effective = context == null
                    ? InvocationContextClassifier.DEFAULT_CONTEXT
                    : context;
            return contexts.computeIfAbsent(effective.getKey(),
                    ignored -> new ContextObservation(effective.getKey(), effective.getLabel()));
        }

        UpstreamObservation upstream(String blockerExecutionKey, String blockerDisplayLabel) {
            if (blockerExecutionKey == null || blockerExecutionKey.isEmpty()) {
                return new UpstreamObservation("", blockerDisplayLabel);
            }
            return upstreamObservations.computeIfAbsent(blockerExecutionKey,
                    ignored -> new UpstreamObservation(blockerExecutionKey, blockerDisplayLabel));
        }
    }

    public static final class AggregatedStats {
        private final int attempts;
        private final int successes;
        private final int exceptions;
        private final int coveredInFailingTests;
        private final String dominantException;
        private final String dominantFailingInvocationLabel;
        private final int failingInvocationsWithSuccessfulPrefix;
        private final Map<String, ContextStats> contextStats;
        private final Map<String, UpstreamStats> upstreamStats;

        AggregatedStats(int attempts, int successes, int exceptions,
                        int coveredInFailingTests,
                        String dominantException,
                        String dominantFailingInvocationLabel,
                        int failingInvocationsWithSuccessfulPrefix,
                        Map<String, ContextStats> contextStats,
                        Map<String, UpstreamStats> upstreamStats) {
            this.attempts = Math.max(0, attempts);
            this.successes = Math.max(0, successes);
            this.exceptions = Math.max(0, exceptions);
            this.coveredInFailingTests = Math.max(0, coveredInFailingTests);
            this.dominantException = Objects.toString(dominantException, "");
            this.dominantFailingInvocationLabel = Objects.toString(dominantFailingInvocationLabel, "");
            this.failingInvocationsWithSuccessfulPrefix = Math.max(0, failingInvocationsWithSuccessfulPrefix);
            this.contextStats = contextStats == null ? Collections.emptyMap() : Collections.unmodifiableMap(contextStats);
            this.upstreamStats = upstreamStats == null ? Collections.emptyMap() : Collections.unmodifiableMap(upstreamStats);
        }

        public int getAttempts() {
            return attempts;
        }

        public int getSuccesses() {
            return successes;
        }

        public int getExceptions() {
            return exceptions;
        }

        public int getCoveredInFailingTests() {
            return coveredInFailingTests;
        }

        public String getDominantException() {
            return dominantException;
        }

        public String getDominantFailingInvocationLabel() {
            return dominantFailingInvocationLabel;
        }

        public int getFailingInvocationsWithSuccessfulPrefix() {
            return failingInvocationsWithSuccessfulPrefix;
        }

        public Map<String, ContextStats> getContextStats() {
            return contextStats;
        }

        public Map<String, UpstreamStats> getUpstreamStats() {
            return upstreamStats;
        }
    }

    private static final class ContextObservation {
        final String contextKey;
        final String contextLabel;
        int attempts;
        int successes;
        int exceptions;
        final Map<String, Integer> exceptionCounts = new HashMap<>();
        final Map<String, Integer> failingInvocationLabelCounts = new LinkedHashMap<>();
        int failingInvocationsWithSuccessfulPrefix;

        ContextObservation(String contextKey, String contextLabel) {
            this.contextKey = contextKey;
            this.contextLabel = contextLabel;
        }

        void recordFailingInvocation(String label, boolean hadSuccessfulPrefix) {
            if (label != null && !label.isEmpty()) {
                failingInvocationLabelCounts.put(label,
                        failingInvocationLabelCounts.getOrDefault(label, 0) + 1);
            }
            if (hadSuccessfulPrefix) {
                failingInvocationsWithSuccessfulPrefix++;
            }
        }

        void add(ContextObservation other) {
            if (other == null) {
                return;
            }
            attempts += other.attempts;
            successes += other.successes;
            exceptions += other.exceptions;
            failingInvocationsWithSuccessfulPrefix += other.failingInvocationsWithSuccessfulPrefix;
            mergeLabelCounts(exceptionCounts, other.exceptionCounts);
            mergeLabelCounts(failingInvocationLabelCounts, other.failingInvocationLabelCounts);
        }

        ContextObservation copy() {
            ContextObservation clone = new ContextObservation(contextKey, contextLabel);
            clone.attempts = attempts;
            clone.successes = successes;
            clone.exceptions = exceptions;
            clone.failingInvocationsWithSuccessfulPrefix = failingInvocationsWithSuccessfulPrefix;
            clone.exceptionCounts.putAll(exceptionCounts);
            clone.failingInvocationLabelCounts.putAll(failingInvocationLabelCounts);
            return clone;
        }
    }

    public static final class ContextStats {
        private final String contextKey;
        private final String contextLabel;
        private final int attempts;
        private final int successes;
        private final int exceptions;
        private final String dominantException;
        private final String dominantFailingInvocationLabel;
        private final int failingInvocationsWithSuccessfulPrefix;

        ContextStats(String contextKey,
                     String contextLabel,
                     int attempts,
                     int successes,
                     int exceptions,
                     String dominantException,
                     String dominantFailingInvocationLabel,
                     int failingInvocationsWithSuccessfulPrefix) {
            this.contextKey = Objects.toString(contextKey, "");
            this.contextLabel = Objects.toString(contextLabel, "");
            this.attempts = Math.max(0, attempts);
            this.successes = Math.max(0, successes);
            this.exceptions = Math.max(0, exceptions);
            this.dominantException = Objects.toString(dominantException, "");
            this.dominantFailingInvocationLabel = Objects.toString(dominantFailingInvocationLabel, "");
            this.failingInvocationsWithSuccessfulPrefix = Math.max(0, failingInvocationsWithSuccessfulPrefix);
        }

        public String getContextKey() {
            return contextKey;
        }

        public String getContextLabel() {
            return contextLabel;
        }

        public int getAttempts() {
            return attempts;
        }

        public int getSuccesses() {
            return successes;
        }

        public int getExceptions() {
            return exceptions;
        }

        public String getDominantException() {
            return dominantException;
        }

        public String getDominantFailingInvocationLabel() {
            return dominantFailingInvocationLabel;
        }

        public int getFailingInvocationsWithSuccessfulPrefix() {
            return failingInvocationsWithSuccessfulPrefix;
        }
    }

    private static final class UpstreamObservation {
        final String blockerExecutionKey;
        String blockerDisplayLabel;
        int attempts;
        int successes;
        int exceptions;
        final Map<String, Integer> exceptionCounts = new HashMap<>();
        final Map<String, Integer> failingInvocationLabelCounts = new LinkedHashMap<>();
        int failingInvocationsWithSuccessfulPrefix;

        UpstreamObservation(String blockerExecutionKey, String blockerDisplayLabel) {
            this.blockerExecutionKey = blockerExecutionKey;
            this.blockerDisplayLabel = blockerDisplayLabel == null ? "" : blockerDisplayLabel;
        }

        void recordFailingInvocation(String label, boolean hadSuccessfulPrefix) {
            if (label != null && !label.isEmpty()) {
                failingInvocationLabelCounts.put(label,
                        failingInvocationLabelCounts.getOrDefault(label, 0) + 1);
                if (blockerDisplayLabel.isEmpty()) {
                    blockerDisplayLabel = label;
                }
            }
            if (hadSuccessfulPrefix) {
                failingInvocationsWithSuccessfulPrefix++;
            }
        }

        void add(UpstreamObservation other) {
            if (other == null) {
                return;
            }
            attempts += other.attempts;
            successes += other.successes;
            exceptions += other.exceptions;
            failingInvocationsWithSuccessfulPrefix += other.failingInvocationsWithSuccessfulPrefix;
            mergeLabelCounts(exceptionCounts, other.exceptionCounts);
            mergeLabelCounts(failingInvocationLabelCounts, other.failingInvocationLabelCounts);
            if ((blockerDisplayLabel == null || blockerDisplayLabel.isEmpty())
                    && other.blockerDisplayLabel != null && !other.blockerDisplayLabel.isEmpty()) {
                blockerDisplayLabel = other.blockerDisplayLabel;
            }
        }

        UpstreamObservation copy() {
            UpstreamObservation clone = new UpstreamObservation(blockerExecutionKey, blockerDisplayLabel);
            clone.attempts = attempts;
            clone.successes = successes;
            clone.exceptions = exceptions;
            clone.failingInvocationsWithSuccessfulPrefix = failingInvocationsWithSuccessfulPrefix;
            clone.exceptionCounts.putAll(exceptionCounts);
            clone.failingInvocationLabelCounts.putAll(failingInvocationLabelCounts);
            return clone;
        }
    }

    public static final class UpstreamStats {
        private final String blockerExecutionKey;
        private final String blockerDisplayLabel;
        private final int attempts;
        private final int successes;
        private final int exceptions;
        private final String dominantException;
        private final String dominantFailingInvocationLabel;
        private final int failingInvocationsWithSuccessfulPrefix;

        UpstreamStats(String blockerExecutionKey,
                      String blockerDisplayLabel,
                      int attempts,
                      int successes,
                      int exceptions,
                      String dominantException,
                      String dominantFailingInvocationLabel,
                      int failingInvocationsWithSuccessfulPrefix) {
            this.blockerExecutionKey = Objects.toString(blockerExecutionKey, "");
            this.blockerDisplayLabel = Objects.toString(blockerDisplayLabel, "");
            this.attempts = Math.max(0, attempts);
            this.successes = Math.max(0, successes);
            this.exceptions = Math.max(0, exceptions);
            this.dominantException = Objects.toString(dominantException, "");
            this.dominantFailingInvocationLabel = Objects.toString(dominantFailingInvocationLabel, "");
            this.failingInvocationsWithSuccessfulPrefix = Math.max(0, failingInvocationsWithSuccessfulPrefix);
        }

        public String getBlockerExecutionKey() {
            return blockerExecutionKey;
        }

        public String getBlockerDisplayLabel() {
            return blockerDisplayLabel;
        }

        public int getAttempts() {
            return attempts;
        }

        public int getSuccesses() {
            return successes;
        }

        public int getExceptions() {
            return exceptions;
        }

        public String getDominantException() {
            return dominantException;
        }

        public String getDominantFailingInvocationLabel() {
            return dominantFailingInvocationLabel;
        }

        public int getFailingInvocationsWithSuccessfulPrefix() {
            return failingInvocationsWithSuccessfulPrefix;
        }
    }
}
