/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
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
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TypeBarrierObserver {

    private final GoalDescriptionMapper goalDescriptionMapper = new GoalDescriptionMapper();
    private final ExtractorTraceSink traceSink;

    TypeBarrierObserver(ExtractorTraceSink traceSink) {
        this.traceSink = traceSink == null ? ExtractorTraceSink.NOOP : traceSink;
    }

    Map<String, TypeBarrierSignal> observe(Map<String, List<TestFitnessFunction>> goalsByMethod,
                                           List<TestChromosome> snapshot,
                                           ExtractorTelemetry telemetry) {
        Map<String, TypeBarrierSignal> signals = new LinkedHashMap<>();
        if (goalsByMethod == null || goalsByMethod.isEmpty() || snapshot == null || snapshot.isEmpty()) {
            return signals;
        }
        Map<String, List<TestFitnessFunction>> goalsByType = groupGoalsByType(goalsByMethod);
        List<TestFitnessFunction> allGoals = flattenGoals(goalsByMethod);
        int chromosomeIndex = 0;
        for (TestChromosome chromosome : snapshot) {
            if (chromosome == null) {
                chromosomeIndex++;
                continue;
            }
            ExecutionResult result = chromosome.getLastExecutionResult();
            if (result == null) {
                chromosomeIndex++;
                continue;
            }
            TestCase test = chromosome.getTestCase() != null ? chromosome.getTestCase() : result.test;
            if (test == null) {
                chromosomeIndex++;
                continue;
            }

            int thrownPos = ExtractorObservationSupport.firstThrownPosition(result);
            traceSink.trace("type_barriers",
                    "test={} safe_size={} executed_statements={} thrown_pos={} has_exception={} timeout={}",
                    chromosomeIndex, ExtractorObservationSupport.safeTestSize(test),
                    result.getExecutedStatements(), thrownPos, result.hasTestException(), result.hasTimeout());
            recordSuccessfulTypeLifecycleSteps(signals, goalsByType, allGoals, test, result, thrownPos, telemetry,
                    chromosomeIndex);

            if (!result.hasTestException() || thrownPos < 0) {
                chromosomeIndex++;
                continue;
            }
            Statement thrownStatement = ExtractorObservationSupport.statementAt(test, thrownPos);
            if (thrownStatement == null) {
                traceSink.trace("type_barriers",
                        "test={} stmt={} action=skip_missing_thrown_statement",
                        chromosomeIndex, thrownPos);
                chromosomeIndex++;
                continue;
            }
            String exception = ExtractorObservationSupport.exceptionKey(result);
            if (thrownStatement instanceof ConstructorStatement) {
                ConstructorStatement constructorStatement = (ConstructorStatement) thrownStatement;
                String observedTypeName = ExtractorObservationSupport.constructorType(constructorStatement);
                if (observedTypeName.isEmpty()) {
                    traceSink.trace("type_barriers",
                            "test={} stmt={} action=skip_constructor_without_observed_type",
                            chromosomeIndex, thrownPos);
                    chromosomeIndex++;
                    continue;
                }
                String blockedTypeName = blockedTypeForStatement(
                        test, thrownPos, observedTypeName, goalsByType, telemetry,
                        "type_barriers", chromosomeIndex);
                if (blockedTypeName.isEmpty()) {
                    traceSink.trace("type_barriers",
                            "test={} stmt={} action=skip_unmapped_constructor_type_barrier observed_type={}",
                            chromosomeIndex, thrownPos, observedTypeName);
                    chromosomeIndex++;
                    continue;
                }
                TypeBarrierSignal signal = typeBarrierSignalFor(
                        signals, goalsByType, allGoals, test, blockedTypeName);
                signal.recordFailingConstructor(
                        goalDescriptionMapper.describeConstructorOperation(constructorStatement).getDisplayLabel());
                signal.exceptionTypeCounts.put(exception,
                        signal.exceptionTypeCounts.getOrDefault(exception, 0) + 1);
                traceSink.trace("type_barriers",
                        "test={} stmt={} action=failing_constructor observed_type={} blocked_type={} effective_type={} "
                                + "related_goals={} exception={}",
                        chromosomeIndex, thrownPos, observedTypeName, blockedTypeName, blockedTypeName,
                        signal.relatedGoals.size(), exception);
                chromosomeIndex++;
                continue;
            }
            if (thrownStatement instanceof MethodStatement) {
                MethodStatement methodStatement = (MethodStatement) thrownStatement;
                String observedTypeName = ExtractorObservationSupport.thrownTypeForMethod(methodStatement);
                if (observedTypeName.isEmpty()) {
                    traceSink.trace("type_barriers",
                            "test={} stmt={} action=skip_method_without_observed_type label={}",
                            chromosomeIndex, thrownPos,
                            ExtractorObservationSupport.statementDebugLabel(methodStatement));
                    chromosomeIndex++;
                    continue;
                }
                String blockedTypeName = blockedTypeForStatement(
                        test, thrownPos, observedTypeName, goalsByType, telemetry,
                        "type_barriers", chromosomeIndex);
                if (blockedTypeName.isEmpty()) {
                    traceSink.trace("type_barriers",
                            "test={} stmt={} action=skip_unmapped_method_type_barrier observed_type={} label={}",
                            chromosomeIndex, thrownPos, observedTypeName,
                            ExtractorObservationSupport.statementDebugLabel(methodStatement));
                    chromosomeIndex++;
                    continue;
                }
                TypeBarrierSignal signal = typeBarrierSignalFor(
                        signals, goalsByType, allGoals, test, blockedTypeName);
                if (ExtractorObservationSupport.isFactoryMethod(methodStatement)) {
                    signal.recordFailingFactory(
                            goalDescriptionMapper.describeMethodOperation(methodStatement).getDisplayLabel());
                    traceSink.trace("type_barriers",
                            "test={} stmt={} action=failing_factory label={} observed_type={} blocked_type={} "
                                    + "effective_type={} related_goals={} exception={}",
                            chromosomeIndex, thrownPos,
                            ExtractorObservationSupport.statementDebugLabel(methodStatement),
                            observedTypeName, blockedTypeName, blockedTypeName, signal.relatedGoals.size(), exception);
                } else {
                    signal.recordFailingSetupMethod(ExtractorObservationSupport.setupMethodKey(methodStatement),
                            goalDescriptionMapper.describeMethodOperation(methodStatement).getDisplayLabel());
                    traceSink.trace("type_barriers",
                            "test={} stmt={} action=failing_setup label={} observed_type={} blocked_type={} "
                                    + "effective_type={} related_goals={} exception={}",
                            chromosomeIndex, thrownPos,
                            ExtractorObservationSupport.statementDebugLabel(methodStatement),
                            observedTypeName, blockedTypeName, blockedTypeName, signal.relatedGoals.size(), exception);
                }
                signal.exceptionTypeCounts.put(exception,
                        signal.exceptionTypeCounts.getOrDefault(exception, 0) + 1);
            }
            chromosomeIndex++;
        }
        return signals;
    }

    private void recordSuccessfulTypeLifecycleSteps(Map<String, TypeBarrierSignal> signals,
                                                    Map<String, List<TestFitnessFunction>> goalsByType,
                                                    List<TestFitnessFunction> allGoals,
                                                    TestCase test,
                                                    ExecutionResult result,
                                                    int firstThrownPosition,
                                                    ExtractorTelemetry telemetry,
                                                    int chromosomeIndex) {
        int executedStatements = result == null ? 0 : result.getExecutedStatements();
        int safeSize = ExtractorObservationSupport.safeTestSize(test);
        if (safeSize <= 0) {
            return;
        }
        if (executedStatements <= 0 || executedStatements > safeSize) {
            executedStatements = safeSize;
        }
        int upperExclusive = firstThrownPosition >= 0
                ? Math.min(executedStatements, firstThrownPosition)
                : executedStatements;
        for (int i = 0; i < upperExclusive; i++) {
            Statement statement = ExtractorObservationSupport.statementAt(test, i);
            if (statement == null) {
                continue;
            }
            if (statement instanceof ConstructorStatement) {
                ConstructorStatement constructorStatement = (ConstructorStatement) statement;
                String observedTypeName = ExtractorObservationSupport.constructorType(constructorStatement);
                if (observedTypeName.isEmpty()) {
                    continue;
                }
                String blockedTypeName = blockedTypeForStatement(
                        test, i, observedTypeName, goalsByType, telemetry,
                        "type_barriers", chromosomeIndex);
                if (blockedTypeName.isEmpty()) {
                    traceSink.trace("type_barriers",
                            "test={} stmt={} action=skip_unmapped_successful_constructor observed_type={}",
                            chromosomeIndex, i, observedTypeName);
                    continue;
                }
                TypeBarrierSignal signal = typeBarrierSignalFor(
                        signals, goalsByType, allGoals, test, blockedTypeName);
                boolean meaningfulProgress = hasMeaningfulProgressAfterStatement(test, i, upperExclusive,
                        blockedTypeName, goalsByType);
                signal.recordSuccessfulConstructor(
                        goalDescriptionMapper.describeConstructorOperation(constructorStatement).getDisplayLabel(),
                        meaningfulProgress);
                traceSink.trace("type_barriers",
                        "test={} stmt={} action=successful_constructor label={} observed_type={} blocked_type={} "
                                + "effective_type={} related_goals={} meaningful_progress={}",
                        chromosomeIndex, i, ExtractorObservationSupport.statementDebugLabel(constructorStatement),
                        observedTypeName, blockedTypeName, blockedTypeName, signal.relatedGoals.size(),
                        meaningfulProgress);
                continue;
            }
            if (statement instanceof MethodStatement) {
                MethodStatement methodStatement = (MethodStatement) statement;
                String observedTypeName = ExtractorObservationSupport.thrownTypeForMethod(methodStatement);
                if (observedTypeName.isEmpty()) {
                    continue;
                }
                String blockedTypeName = blockedTypeForStatement(
                        test, i, observedTypeName, goalsByType, telemetry,
                        "type_barriers", chromosomeIndex);
                if (blockedTypeName.isEmpty()) {
                    traceSink.trace("type_barriers",
                            "test={} stmt={} action=skip_unmapped_successful_method observed_type={} label={}",
                            chromosomeIndex, i, observedTypeName,
                            ExtractorObservationSupport.statementDebugLabel(methodStatement));
                    continue;
                }
                TypeBarrierSignal signal = typeBarrierSignalFor(
                        signals, goalsByType, allGoals, test, blockedTypeName);
                if (ExtractorObservationSupport.isFactoryMethod(methodStatement)) {
                    boolean meaningfulProgress = hasMeaningfulProgressAfterStatement(test, i, upperExclusive,
                            blockedTypeName, goalsByType);
                    signal.recordSuccessfulFactory(
                            goalDescriptionMapper.describeMethodOperation(methodStatement).getDisplayLabel(),
                            meaningfulProgress);
                    traceSink.trace("type_barriers",
                            "test={} stmt={} action=successful_factory label={} observed_type={} blocked_type={} "
                                    + "effective_type={} related_goals={} meaningful_progress={}",
                            chromosomeIndex, i, ExtractorObservationSupport.statementDebugLabel(methodStatement),
                            observedTypeName, blockedTypeName, blockedTypeName, signal.relatedGoals.size(),
                            meaningfulProgress);
                } else {
                    GoalDescriptionMapper.OperationTarget setupOperation =
                            goalDescriptionMapper.describeMethodOperation(methodStatement);
                    boolean meaningfulSetup = isMeaningfulSuccessfulMethodForBlockedType(
                            methodStatement, blockedTypeName, goalsByType);
                    if (meaningfulSetup) {
                        signal.recordSuccessfulSetupMethod(ExtractorObservationSupport.setupMethodKey(methodStatement),
                                setupOperation.getDisplayLabel());
                    }
                    traceSink.trace("type_barriers",
                            "test={} stmt={} action=successful_setup label={} observed_type={} blocked_type={} "
                                    + "effective_type={} related_goals={} meaningful_setup={}",
                            chromosomeIndex, i, ExtractorObservationSupport.statementDebugLabel(methodStatement),
                            observedTypeName, blockedTypeName, blockedTypeName, signal.relatedGoals.size(),
                            meaningfulSetup);
                }
            }
        }
    }

    private TypeBarrierSignal typeBarrierSignalFor(Map<String, TypeBarrierSignal> signals,
                                                   Map<String, List<TestFitnessFunction>> goalsByType,
                                                   List<TestFitnessFunction> allGoals,
                                                   TestCase test,
                                                   String typeName) {
        TypeBarrierSignal signal = signals.computeIfAbsent(typeName, TypeBarrierSignal::new);
        signal.addRelatedGoals(goalsByType.get(typeName));
        signal.addRelatedGoals(goalsReferencedByTest(goalsByType, test));
        if (signal.relatedGoals.isEmpty()) {
            signal.addRelatedGoals(allGoals);
        }
        return signal;
    }

    private List<TestFitnessFunction> goalsReferencedByTest(Map<String, List<TestFitnessFunction>> goalsByType,
                                                            TestCase test) {
        List<TestFitnessFunction> referenced = new ArrayList<>();
        int safeSize = ExtractorObservationSupport.safeTestSize(test);
        for (int i = 0; i < safeSize; i++) {
            Statement statement = ExtractorObservationSupport.statementAt(test, i);
            if (statement == null) {
                continue;
            }
            if (statement instanceof ConstructorStatement) {
                mergeGoals(referenced, goalsByType.get(
                        ExtractorObservationSupport.constructorType((ConstructorStatement) statement)));
                continue;
            }
            if (statement instanceof MethodStatement) {
                MethodStatement methodStatement = (MethodStatement) statement;
                mergeGoals(referenced, goalsByType.get(
                        ExtractorObservationSupport.thrownTypeForMethod(methodStatement)));
                mergeGoals(referenced, goalsByType.get(
                        ExtractorObservationSupport.declaringTypeForMethod(methodStatement)));
            }
        }
        return referenced;
    }

    private String blockedTypeForStatement(TestCase test,
                                           int statementIndex,
                                           String observedTypeName,
                                           Map<String, List<TestFitnessFunction>> goalsByType,
                                           ExtractorTelemetry telemetry,
                                           String tracePhase,
                                           int chromosomeIndex) {
        if (hasGoalsForType(goalsByType, observedTypeName)) {
            traceSink.trace(tracePhase,
                    "test={} stmt={} action=map_blocked_type observed_type={} observed_has_goals=true mapped_type={}",
                    chromosomeIndex, statementIndex, observedTypeName, observedTypeName);
            return observedTypeName;
        }
        String downstreamType = firstGoalBearingTypeAfter(test, statementIndex, goalsByType);
        if (!downstreamType.isEmpty()) {
            traceSink.trace(tracePhase,
                    "test={} stmt={} action=map_blocked_type observed_type={} observed_has_goals=false "
                            + "downstream_type={} mapped_type={}",
                    chromosomeIndex, statementIndex, observedTypeName, downstreamType, downstreamType);
            return downstreamType;
        }
        String referencedType = firstGoalBearingTypeInTest(test, goalsByType);
        if (!referencedType.isEmpty()) {
            traceSink.trace(tracePhase,
                    "test={} stmt={} action=map_blocked_type observed_type={} observed_has_goals=false "
                            + "referenced_type={} mapped_type={}",
                    chromosomeIndex, statementIndex, observedTypeName, referencedType, referencedType);
            return referencedType;
        }
        if (telemetry != null && observedTypeName != null && !observedTypeName.isEmpty()) {
            telemetry.increment(ExtractorRejectReason.BLOCKED_TYPE_MAPPING_FAILURE);
        }
        traceSink.trace(tracePhase,
                "test={} stmt={} action=map_blocked_type_failure observed_type={} observed_has_goals=false mapped_type={}",
                chromosomeIndex, statementIndex, observedTypeName, "");
        return "";
    }

    private String firstGoalBearingTypeAfter(TestCase test,
                                             int statementIndex,
                                             Map<String, List<TestFitnessFunction>> goalsByType) {
        int safeSize = ExtractorObservationSupport.safeTestSize(test);
        for (int i = Math.max(0, statementIndex + 1); i < safeSize; i++) {
            String goalType = goalBearingTypeForStatement(
                    ExtractorObservationSupport.statementAt(test, i), goalsByType);
            if (!goalType.isEmpty()) {
                return goalType;
            }
        }
        return "";
    }

    private String firstGoalBearingTypeInTest(TestCase test,
                                              Map<String, List<TestFitnessFunction>> goalsByType) {
        int safeSize = ExtractorObservationSupport.safeTestSize(test);
        for (int i = 0; i < safeSize; i++) {
            String goalType = goalBearingTypeForStatement(
                    ExtractorObservationSupport.statementAt(test, i), goalsByType);
            if (!goalType.isEmpty()) {
                return goalType;
            }
        }
        return "";
    }

    private String goalBearingTypeForStatement(Statement statement,
                                               Map<String, List<TestFitnessFunction>> goalsByType) {
        if (statement instanceof ConstructorStatement) {
            String typeName = ExtractorObservationSupport.constructorType((ConstructorStatement) statement);
            return hasGoalsForType(goalsByType, typeName) ? typeName : "";
        }
        if (statement instanceof MethodStatement) {
            MethodStatement methodStatement = (MethodStatement) statement;
            String receiverType = ExtractorObservationSupport.thrownTypeForMethod(methodStatement);
            if (hasGoalsForType(goalsByType, receiverType)) {
                return receiverType;
            }
            String declaringType = ExtractorObservationSupport.declaringTypeForMethod(methodStatement);
            if (hasGoalsForType(goalsByType, declaringType)) {
                return declaringType;
            }
        }
        return "";
    }

    private boolean hasGoalsForType(Map<String, List<TestFitnessFunction>> goalsByType, String typeName) {
        return goalsByType != null
                && typeName != null
                && !typeName.isEmpty()
                && goalsByType.containsKey(typeName)
                && goalsByType.get(typeName) != null
                && !goalsByType.get(typeName).isEmpty();
    }

    private boolean hasMeaningfulProgressAfterStatement(TestCase test,
                                                        int statementIndex,
                                                        int upperExclusive,
                                                        String blockedTypeName,
                                                        Map<String, List<TestFitnessFunction>> goalsByType) {
        if (statementIndex < 0 || test == null || blockedTypeName == null || blockedTypeName.isEmpty()) {
            return false;
        }
        int safeUpper = Math.max(0, Math.min(upperExclusive, ExtractorObservationSupport.safeTestSize(test)));
        for (int i = statementIndex + 1; i < safeUpper; i++) {
            Statement next = ExtractorObservationSupport.statementAt(test, i);
            if (!(next instanceof MethodStatement)) {
                continue;
            }
            if (isMeaningfulSuccessfulMethodForBlockedType((MethodStatement) next, blockedTypeName, goalsByType)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMeaningfulSuccessfulMethodForBlockedType(MethodStatement methodStatement,
                                                               String blockedTypeName,
                                                               Map<String, List<TestFitnessFunction>> goalsByType) {
        if (methodStatement == null || blockedTypeName == null || blockedTypeName.isEmpty()) {
            return false;
        }
        String receiverType = ExtractorObservationSupport.thrownTypeForMethod(methodStatement);
        if (blockedTypeName.equals(receiverType)) {
            return true;
        }
        String declaringType = ExtractorObservationSupport.declaringTypeForMethod(methodStatement);
        if (blockedTypeName.equals(declaringType)) {
            return true;
        }
        String goalBearingType = goalBearingTypeForStatement(methodStatement, goalsByType);
        return blockedTypeName.equals(goalBearingType);
    }

    private Map<String, List<TestFitnessFunction>> groupGoalsByType(
            Map<String, List<TestFitnessFunction>> goalsByMethod) {
        Map<String, List<TestFitnessFunction>> goalsByType = new LinkedHashMap<>();
        if (goalsByMethod == null || goalsByMethod.isEmpty()) {
            return goalsByType;
        }
        for (Map.Entry<String, List<TestFitnessFunction>> entry : goalsByMethod.entrySet()) {
            String methodKey = entry.getKey();
            int idx = methodKey.lastIndexOf('.');
            if (idx <= 0) {
                continue;
            }
            String typeName = methodKey.substring(0, idx);
            List<TestFitnessFunction> bucket = goalsByType.computeIfAbsent(typeName,
                    ignored -> new ArrayList<>());
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                bucket.addAll(entry.getValue());
            }
        }
        return goalsByType;
    }

    private List<TestFitnessFunction> flattenGoals(Map<String, List<TestFitnessFunction>> goalsByMethod) {
        List<TestFitnessFunction> flattened = new ArrayList<>();
        if (goalsByMethod == null || goalsByMethod.isEmpty()) {
            return flattened;
        }
        for (List<TestFitnessFunction> goals : goalsByMethod.values()) {
            mergeGoals(flattened, goals);
        }
        return flattened;
    }

    private void mergeGoals(List<TestFitnessFunction> destination,
                            List<TestFitnessFunction> goals) {
        if (destination == null || goals == null || goals.isEmpty()) {
            return;
        }
        for (TestFitnessFunction goal : goals) {
            if (goal != null && !destination.contains(goal)) {
                destination.add(goal);
            }
        }
    }
}
