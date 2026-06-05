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

import org.evosuite.Properties;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.ControlDependency;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.NullReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProblemCardExtractorTest {

    @Test
    void doesNotExtractUnreachedMethodCardWhenTypeHasNoReusableSuccessfulPrefix() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal("com.example.Foo", "doWork()V");
        TestChromosome test = chromosomeWithCoveredMethods(Collections.singleton("com.example.Foo.other"));

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                Collections.singletonList(test), 3);

        assertFalse(cards.stream().anyMatch(c -> c.getType() == ProblemCardType.UNREACHED_METHOD),
                "UNREACHED_METHOD should stay suppressed without any reusable successful prefix on the type");
    }

    @Test
    void extractsTypeNeverAttemptedCardWhenGoalTypeHasNoActivityAtAll() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal("com.example.Untouched", "doWork()V");

        // Empty population — no test ever touched the goal-bearing type.
        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                Collections.<TestChromosome>emptyList(), 3);

        ProblemCard card = findCard(cards, ProblemCardType.TYPE_NEVER_ATTEMPTED);
        assertNotNull(card,
                "Expected TYPE_NEVER_ATTEMPTED when the goal type is not touched by any test");
        assertTrue(card.getTitle().contains("com.example.Untouched"),
                "TYPE_NEVER_ATTEMPTED title should expose the never-attempted type");
        assertEquals(ProblemCardFamily.STRUCTURAL, card.getFamily(),
                "TYPE_NEVER_ATTEMPTED should sit in the STRUCTURAL family");
        assertFalse(cards.stream().anyMatch(c -> c.getType() == ProblemCardType.UNREACHED_METHOD),
                "UNREACHED_METHOD must not double-emit when TYPE_NEVER_ATTEMPTED already covers the type");
    }

    @Test
    void doesNotExtractTypeNeverAttemptedWhenSomeTestReferencedThatType() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal("com.example.Foo", "doWork()V");
        TestChromosome test = chromosomeWithCoveredMethods(Collections.singleton("com.example.Foo.other"));

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                Collections.singletonList(test), 3);

        assertFalse(cards.stream().anyMatch(c -> c.getType() == ProblemCardType.TYPE_NEVER_ATTEMPTED),
                "Covered method traces touching the type must suppress TYPE_NEVER_ATTEMPTED");
    }

    @Test
    void extractsIndirectReachabilityBarrierForSyntheticHelperMethods() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        String syntheticType = ExampleIndirectReachabilityTarget.class.getName() + "$1ZIPFileList";
        String inferredOuterType = ProblemCardExtractorTest.class.getName();
        TestFitnessFunction goal = goal(syntheticType, "deleteFiles()V");
        Statement outerEntrypoint = methodStatementFor(
                ExampleIndirectReachabilityTarget.class.getName(),
                declaredMethod(ExampleIndirectReachabilityTarget.class, "driveZipWorkflow"),
                false);

        TestChromosome failing = chromosomeWithThrownStatements(
                Collections.singletonList(outerEntrypoint),
                0,
                new IllegalStateException("zip setup failed"));
        TestChromosome successful = chromosomeWithSuccessfulStatements(
                Collections.singletonList(outerEntrypoint),
                Collections.emptySet());

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                java.util.Arrays.asList(failing, successful), 3);

        ProblemCard card = findCard(cards, ProblemCardType.INDIRECT_REACHABILITY_BARRIER);
        assertNotNull(card,
                "Expected INDIRECT_REACHABILITY_BARRIER when only synthetic/local helper methods stay unreached");
        assertTrue(card.getTitle().contains(syntheticType),
                "INDIRECT_REACHABILITY_BARRIER title should expose the blocked helper type");
        assertTrue(card.getEvidence().stream().anyMatch(line -> line.contains(
                        "Inferred outer entrypoint type: " + inferredOuterType)),
                "INDIRECT_REACHABILITY_BARRIER evidence should infer the outer type");
        assertTrue(card.getEvidence().stream().anyMatch(line -> line.contains("Most frequent successful outer entrypoint:")),
                "INDIRECT_REACHABILITY_BARRIER should expose a reusable successful outer entrypoint");
    }

    @Test
    void extractsIndirectReachabilityBarrierForNamedNestedHelperType() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        // Named nested helper — the old digit-prefix matcher missed this and would have routed
        // the case through UNREACHED_METHOD / TYPE_NEVER_ATTEMPTED instead of indirect-reachability.
        String namedHelperType = ExampleIndirectReachabilityTarget.class.getName() + "$ZipBuilder";
        TestFitnessFunction goal = goal(namedHelperType, "deleteFiles()V");
        Statement outerEntrypoint = methodStatementFor(
                ExampleIndirectReachabilityTarget.class.getName(),
                declaredMethod(ExampleIndirectReachabilityTarget.class, "driveZipWorkflow"),
                false);

        TestChromosome successful = chromosomeWithSuccessfulStatements(
                Collections.singletonList(outerEntrypoint),
                Collections.emptySet());

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                Collections.singletonList(successful), 3);

        ProblemCard card = findCard(cards, ProblemCardType.INDIRECT_REACHABILITY_BARRIER);
        assertNotNull(card,
                "Expected INDIRECT_REACHABILITY_BARRIER when the blocked goals live in a named Builder helper");
        assertTrue(card.getTitle().contains(namedHelperType),
                "INDIRECT_REACHABILITY_BARRIER title should expose the named helper type");
    }

    @Test
    void doesNotMarkMethodUnreachedWhenCoveredTraceUsesBaseMethodKey() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal(ExampleExceptionTarget.class.getName(), "withInput(Ljava/lang/String;)V");
        TestChromosome test = chromosomeWithCoveredMethods(
                Collections.singleton(ExampleExceptionTarget.class.getName() + ".withInput"));

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                Collections.singletonList(test), 3);

        assertFalse(cards.stream().anyMatch(c -> c.getType() == ProblemCardType.UNREACHED_METHOD),
                "Covered method traces without descriptors should still match descriptor-based goals");
    }

    @Test
    void extractsUnreachedMethodCardWithReachabilityContextWhenTypeWasReached() throws NoSuchMethodException {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        String typeName = ExampleStateType.class.getName();
        TestFitnessFunction goal = goal(typeName, "targetMethod()V");
        Statement constructor = constructorStatementFor(ExampleStateType.class);
        Statement helperCall = methodStatementFor(typeName, ExampleStateType.class.getDeclaredMethod("helper"), false);

        TestChromosome reachableTest = chromosomeWithSuccessfulStatements(
                java.util.Arrays.asList(constructor, helperCall),
                Collections.singleton(typeName + ".helper"));

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                Collections.singletonList(reachableTest), 3);

        ProblemCard card = findCard(cards, ProblemCardType.UNREACHED_METHOD);
        assertNotNull(card, "Expected UNREACHED_METHOD card when target call is never observed");
        assertTrue(card.getTitle().contains(typeName + ".targetMethod()"),
                "Method titles should use prompt-friendly qualified labels");
        assertTrue(card.getEvidence().stream().anyMatch(line -> line.contains("was reached successfully")),
                () -> "UNREACHED_METHOD evidence should distinguish reusable prefixes from unreachable types: "
                        + card.getEvidence());
        assertTrue(card.getEvidence().stream().anyMatch(line -> line.contains("Reusable successful prefix on this type:")),
                "UNREACHED_METHOD evidence should call out the reusable working prefix");
        assertTrue(card.getEvidence().stream().anyMatch(line -> line.contains(typeName + ".helper()")),
                "UNREACHED_METHOD evidence should mention successful prefix steps on the reached type");
    }

    @Test
    void extractsIndirectReachabilityBarrierWhenSetupSucceedsButDirectGoalMethodNeverExecutes()
            throws NoSuchMethodException {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        String typeName = ExampleStateType.class.getName();
        TestFitnessFunction goal = goal(typeName, "targetMethod()V");
        Statement constructor = constructorStatementFor(ExampleStateType.class);
        Statement helperCall = methodStatementFor(typeName, declaredMethod(ExampleStateType.class, "helper"), false);

        TestChromosome reachableTest = chromosomeWithSuccessfulStatements(
                java.util.Arrays.asList(constructor, helperCall),
                Collections.singleton(typeName + ".helper"));

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                Collections.singletonList(reachableTest), 4);

        ProblemCard card = findCard(cards, ProblemCardType.INDIRECT_REACHABILITY_BARRIER);
        assertNotNull(card,
                "Expected INDIRECT_REACHABILITY_BARRIER when setup succeeds but no direct goal method executes");
        assertTrue(card.getTitle().contains("direct target invocation"),
                "Invocation-gap indirect cards should explain that the missing step is the direct target call");
        assertTrue(card.getEvidence().stream().anyMatch(line -> line.contains("Observed direct goal-method executions: 0")),
                "Invocation-gap indirect cards should expose that no direct goal method was executed");
        assertTrue(card.getEvidence().stream().anyMatch(line -> line.contains(typeName + ".helper()")),
                "Invocation-gap indirect cards should cite the reusable setup step that already works");
    }

    @Test
    void doesNotExtractUnreachedMethodCardWhenAcquisitionNeverProgressesPastCreation() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        String typeName = ExampleStateType.class.getName();
        TestFitnessFunction goal = goal(typeName, "targetMethod()V");
        Statement constructor = constructorStatementFor(ExampleStateType.class);

        TestChromosome acquisitionOnly = chromosomeWithSuccessfulStatements(
                Collections.singletonList(constructor),
                Collections.emptySet());

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                Collections.singletonList(acquisitionOnly), 3);

        assertFalse(cards.stream().anyMatch(c -> c.getType() == ProblemCardType.UNREACHED_METHOD),
                "UNREACHED_METHOD should stay suppressed when only bare acquisition succeeded");
    }

    @Test
    void extractsStateDiversificationGapForRepeatedSuccessfulSingleContextExecutions() throws NoSuchMethodException {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        String typeName = ExampleStateType.class.getName();
        String coveredMethod = typeName + ".targetMethod";
        TestFitnessFunction goal = goal(typeName, "targetMethod()V");
        Statement targetCall = methodStatementFor(typeName, declaredMethod(ExampleStateType.class, "targetMethod"), false);

        List<TestChromosome> population = java.util.Arrays.asList(
                chromosomeWithSuccessfulStatements(Collections.singletonList(targetCall), Collections.singleton(coveredMethod)),
                chromosomeWithSuccessfulStatements(Collections.singletonList(targetCall), Collections.singleton(coveredMethod)),
                chromosomeWithSuccessfulStatements(Collections.singletonList(targetCall), Collections.singleton(coveredMethod)),
                chromosomeWithSuccessfulStatements(Collections.singletonList(targetCall), Collections.singleton(coveredMethod)));

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal), population, 4);

        ProblemCard card = findCard(cards, ProblemCardType.STATE_DIVERSIFICATION_GAP);
        assertNotNull(card,
                "Expected STATE_DIVERSIFICATION_GAP when direct executions repeatedly succeed in one regime");
        assertTrue(card.getEvidence().stream().anyMatch(line -> line.contains("Direct executions repeatedly reached")),
                "State-diversification evidence should describe repeated direct executions");
        assertTrue(card.getEvidence().stream().anyMatch(line -> line.contains("Dominant successful context:")),
                "State-diversification evidence should expose the dominant observed regime");
        assertEquals(ProblemCardFamily.LOCAL, card.getFamily(),
                "STATE_DIVERSIFICATION_GAP should behave as a local diversification card");
    }

    @Test
    void extractsStateDiversificationGapWhenSuccessesDominateDespiteRareFailure()
            throws NoSuchMethodException {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        String typeName = ExampleStateType.class.getName();
        String coveredMethod = typeName + ".targetMethod";
        TestFitnessFunction goal = goal(typeName, "targetMethod()V");
        Statement targetCall = methodStatementFor(typeName,
                declaredMethod(ExampleStateType.class, "targetMethod"), false);

        List<TestChromosome> population = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            population.add(chromosomeWithSuccessfulStatements(
                    Collections.singletonList(targetCall),
                    Collections.singleton(coveredMethod)));
        }
        // One rare exception (success share = 5/6 ≈ 0.83 > 0.8 → still emit the card).
        population.add(chromosomeWithThrownStatements(
                Collections.singletonList(targetCall), 0,
                new RuntimeException("flake"),
                Collections.singleton(coveredMethod)));

        ProblemCard card = findCard(
                extractor.extract(Collections.singleton(goal), population, 4),
                ProblemCardType.STATE_DIVERSIFICATION_GAP);
        assertNotNull(card,
                "STATE_DIVERSIFICATION_GAP must survive a rare exception when successes still dominate");
        assertTrue(card.getEvidence().stream()
                        .anyMatch(line -> line.contains("exceptions=1")),
                "Evidence should report the observed exception count rather than hard-coding zero");
    }

    @Test
    void extractsExceptionBarrierCardWhenAllObservedInvocationsThrow() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal(ExampleExceptionTarget.class.getName(), "withInput(Ljava/lang/String;)V");
        Statement helperCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "helper"),
                false);
        Statement targetCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "withInput", String.class),
                false);
        TestChromosome t1 = chromosomeWithThrownStatements(java.util.Arrays.asList(helperCall, targetCall), 1,
                new NullPointerException("boom"),
                Collections.singleton(ExampleExceptionTarget.class.getName() + ".withInput"));
        TestChromosome t2 = chromosomeWithThrownStatements(java.util.Arrays.asList(helperCall, targetCall), 1,
                new IllegalStateException("bad state"),
                Collections.singleton(ExampleExceptionTarget.class.getName() + ".withInput"));

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                java.util.Arrays.asList(t1, t2), 3);

        ProblemCard card = findCard(cards, ProblemCardType.EXCEPTION_BARRIER);
        assertNotNull(card,
                "Expected EXCEPTION_BARRIER card when all observed invocations end with exceptions");
        assertTrue(card.getTitle().contains(ExampleExceptionTarget.class.getName() + ".withInput(String)"),
                "Exception barrier titles should use prompt-friendly qualified labels");
        assertTrue(card.getEvidence().stream().anyMatch(line -> line.contains("Dominant failing invocation: "
                + ExampleExceptionTarget.class.getName() + ".withInput(String)")),
                "Exception barrier evidence should report the dominant failing direct invocation");
        assertTrue(card.getEvidence().stream().anyMatch(line -> line.contains("Earlier successful statements preceded the throw")),
                "Exception barrier evidence should summarize whether failing invocations had successful prefixes");
        assertEquals(0.5, card.getConfidence(), 0.0001,
                "Barrier confidence should ramp up quickly enough to surface repeated early failures");
    }

    @Test
    void extractsExceptionBarrierWhenExceptionsDominateDespiteSomeSuccesses() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal(ExampleExceptionTarget.class.getName(), "withInput(Ljava/lang/String;)V");
        Statement helperCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "helper"),
                false);
        Statement targetCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "withInput", String.class),
                false);
        TestChromosome failing1 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(helperCall, targetCall), 1,
                new NullPointerException("boom"),
                Collections.singleton(ExampleExceptionTarget.class.getName() + ".withInput"));
        TestChromosome failing2 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(helperCall, targetCall), 1,
                new IllegalStateException("bad state"),
                Collections.singleton(ExampleExceptionTarget.class.getName() + ".withInput"));
        TestChromosome success = chromosomeWithSuccessfulStatements(
                java.util.Arrays.asList(helperCall, targetCall),
                Collections.singleton(ExampleExceptionTarget.class.getName() + ".withInput"));

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                java.util.Arrays.asList(failing1, failing2, success), 3);

        ProblemCard card = findCard(cards, ProblemCardType.EXCEPTION_BARRIER);
        assertNotNull(card,
                "EXCEPTION_BARRIER should remain when direct invocations are still exception-dominated");
        assertTrue(card.getTitle().contains("exception-dominated"),
                "Exception barrier titles should no longer claim zero successes are required");
        assertTrue(card.getEvidence().stream().anyMatch(line -> line.contains("Direct exception dominance: 2/3.")),
                "Exception barrier evidence should report the dominance ratio explicitly");
    }

    @Test
    void extractsExceptionBarrierForDominantNonNullContextDespiteNullSuccesses() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal(ExampleExceptionTarget.class.getName(), "withDependency(Ljava/lang/Object;)V");
        Statement helperCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "helper"),
                false);
        Statement failingCall1 = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "withDependency", Object.class),
                false,
                Collections.singletonList(nonNullParameter(0, Object.class)));
        Statement failingCall2 = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "withDependency", Object.class),
                false,
                Collections.singletonList(nonNullParameter(0, Object.class)));
        Statement nullSuccessCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "withDependency", Object.class),
                false,
                Collections.singletonList(nullParameter(Object.class)));

        TestChromosome failing1 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(helperCall, failingCall1), 1,
                new NullPointerException("boom"),
                Collections.singleton(ExampleExceptionTarget.class.getName() + ".withDependency"));
        TestChromosome failing2 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(helperCall, failingCall2), 1,
                new IllegalStateException("bad state"),
                Collections.singleton(ExampleExceptionTarget.class.getName() + ".withDependency"));
        TestChromosome nullSuccess = chromosomeWithSuccessfulStatements(
                java.util.Arrays.asList(helperCall, nullSuccessCall),
                Collections.singleton(ExampleExceptionTarget.class.getName() + ".withDependency"));

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                java.util.Arrays.asList(failing1, failing2, nullSuccess), 3);

        ProblemCard card = findCard(cards, ProblemCardType.EXCEPTION_BARRIER);
        assertNotNull(card,
                "Expected EXCEPTION_BARRIER when one invocation context remains exception-dominated");
        assertTrue(card.getTitle().contains("in context"),
                "Context-sensitive exception barriers should surface the failing invocation regime");
        assertTrue(card.getTitle().contains("arg0 (Object)=nonnull"),
                "Exception barrier titles should expose the blocked argument context");
        assertTrue(card.getEvidence().stream().anyMatch(line ->
                        line.contains("Blocked invocation context: arg0 (Object)=nonnull.")),
                "Exception barrier evidence should name the masked failing context");
        assertTrue(card.getEvidence().stream().anyMatch(line ->
                        line.contains("Context-local exception dominance: 2/2.")),
                "Exception barrier evidence should use context-local dominance for masked cases");
        assertTrue(card.getEvidence().stream().anyMatch(line ->
                        line.contains("Method-wide non-exception completions: 1.")),
                "Method-wide evidence should still show the successes that masked the context before");
    }

    @Test
    void doesNotExtractExceptionBarrierWhenSuccessesBreakDominance() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal(ExampleExceptionTarget.class.getName(), "withInput(Ljava/lang/String;)V");
        Statement helperCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "helper"),
                false);
        Statement targetCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "withInput", String.class),
                false);
        TestChromosome failing = chromosomeWithThrownStatements(
                java.util.Arrays.asList(helperCall, targetCall), 1,
                new NullPointerException("boom"),
                Collections.singleton(ExampleExceptionTarget.class.getName() + ".withInput"));
        TestChromosome success1 = chromosomeWithSuccessfulStatements(
                java.util.Arrays.asList(helperCall, targetCall),
                Collections.singleton(ExampleExceptionTarget.class.getName() + ".withInput"));
        TestChromosome success2 = chromosomeWithSuccessfulStatements(
                java.util.Arrays.asList(helperCall, targetCall),
                Collections.singleton(ExampleExceptionTarget.class.getName() + ".withInput"));

        ProblemCardExtractor.ExtractionResult extraction = extractor.extractWithTelemetry(Collections.singleton(goal),
                java.util.Arrays.asList(failing, success1, success2), 3);
        List<ProblemCard> cards = extraction.getCards();

        assertFalse(cards.stream().anyMatch(c -> c.getType() == ProblemCardType.EXCEPTION_BARRIER),
                "EXCEPTION_BARRIER should stay suppressed once successes outnumber the throwing pattern");
        assertEquals(1, extraction.getCandidateCounts().getOrDefault(
                        ExtractorCandidateMetric.EXCEPTION_BARRIER_SUPPRESSED_INSUFFICIENT_ATTEMPTS, 0),
                "Candidate telemetry should expose direct exception barriers that stay below the minimum-repeat gate");
    }

    @Test
    void doesNotExtractExceptionBarrierWhenOnlyCoveredInFailingTests() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal(ExampleExceptionTarget.class.getName(), "doWork()V");
        Statement helperCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "helper"),
                false);
        TestChromosome t1 = chromosomeWithThrownStatements(Collections.singletonList(helperCall), 0,
                new NullPointerException("boom"),
                Collections.singleton(ExampleExceptionTarget.class.getName() + ".doWork"));
        TestChromosome t2 = chromosomeWithThrownStatements(Collections.singletonList(helperCall), 0,
                new IllegalStateException("bad state"),
                Collections.singleton(ExampleExceptionTarget.class.getName() + ".doWork"));

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                java.util.Arrays.asList(t1, t2), 3);

        assertFalse(cards.stream().anyMatch(c -> c.getType() == ProblemCardType.EXCEPTION_BARRIER),
                "Covered methods in failing tests should not count as direct exception-barrier evidence");
    }

    @Test
    void extractsExceptionBarrierForDominantOverloadWhenMethodWideRateIsDiluted() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal(ExampleExceptionTarget.class.getName(), "withInput(Ljava/lang/String;)V");
        Statement stringOverloadCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "withInput", String.class),
                false);
        Statement intOverloadCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "withInput", int.class),
                false);
        Set<String> traceCoveringInput = Collections.singleton(
                ExampleExceptionTarget.class.getName() + ".withInput");

        // 5 failing tests where the String overload is the throw site.
        List<TestChromosome> population = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            population.add(chromosomeWithThrownStatements(
                    Collections.singletonList(stringOverloadCall), 0,
                    new NullPointerException("boom"), traceCoveringInput));
        }
        // 5 successful tests on the int overload — these dilute method-wide failure rate (50%)
        // below the 2/3 threshold, so the old method-level check would suppress the card.
        for (int i = 0; i < 5; i++) {
            population.add(chromosomeWithSuccessfulStatements(
                    Collections.singletonList(intOverloadCall), traceCoveringInput));
        }

        ProblemCard card = findCard(
                extractor.extract(Collections.singleton(goal), population, 3),
                ProblemCardType.EXCEPTION_BARRIER);
        assertNotNull(card,
                "Per-signature evidence must rescue the EXCEPTION_BARRIER when one overload always throws "
                        + "and another always succeeds");
        assertTrue(card.getTitle().contains("Overload is exception-dominated"),
                "Title must call out the overload axis when signature evidence wins");
        assertTrue(card.getEvidence().stream().anyMatch(line -> line.startsWith("Blocked overload: ")),
                "Evidence should expose the dominant failing overload");
        assertTrue(card.getSelectionFingerprint().contains("|signature="),
                "Selection fingerprint should record the signature-level discrimination");
    }

    @Test
    void doesNotDoubleCreditDirectlySuccessfulGoalMethodAsCoveredInFailingTest() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal(ExampleExceptionTarget.class.getName(), "withInput(Ljava/lang/String;)V");
        Statement targetCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "withInput", String.class),
                false);
        Statement helperCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "helper"),
                false);
        Set<String> traceCoveringTarget = Collections.singleton(
                ExampleExceptionTarget.class.getName() + ".withInput");

        // 5 failing tests where the target is the throw site.
        TestChromosome directFail1 = chromosomeWithThrownStatements(
                Collections.singletonList(targetCall), 0,
                new NullPointerException("boom"), traceCoveringTarget);
        TestChromosome directFail2 = chromosomeWithThrownStatements(
                Collections.singletonList(targetCall), 0,
                new NullPointerException("boom"), traceCoveringTarget);
        TestChromosome directFail3 = chromosomeWithThrownStatements(
                Collections.singletonList(targetCall), 0,
                new NullPointerException("boom"), traceCoveringTarget);
        TestChromosome directFail4 = chromosomeWithThrownStatements(
                Collections.singletonList(targetCall), 0,
                new NullPointerException("boom"), traceCoveringTarget);
        TestChromosome directFail5 = chromosomeWithThrownStatements(
                Collections.singletonList(targetCall), 0,
                new NullPointerException("boom"), traceCoveringTarget);
        // 1 failing test where the target runs successfully at stmt 0 and the helper throws at stmt 1.
        // Under the old whole-trace credit, this would still bump coveredInFailingTests for the target.
        TestChromosome successThenHelperFail = chromosomeWithThrownStatements(
                java.util.Arrays.asList(targetCall, helperCall), 1,
                new IllegalStateException("helper boom"), traceCoveringTarget);

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                java.util.Arrays.asList(directFail1, directFail2, directFail3, directFail4, directFail5,
                        successThenHelperFail), 3);

        ProblemCard card = findCard(cards, ProblemCardType.EXCEPTION_BARRIER);
        assertNotNull(card, "Expected EXCEPTION_BARRIER from the directly throwing invocations");
        assertFalse(card.getEvidence().stream().anyMatch(line -> line.contains("Covered in failing tests")),
                "Methods directly invoked in a failing test should not also be counted as "
                        + "indirectly covered in that same failing test");
    }

    @Test
    void extractsExceptionBarrierWhenObservedReceiverTypeMatchesSubclassGoals() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal(ExampleInheritedExceptionTarget.class.getName(), "withInput(Ljava/lang/String;)V");
        Statement helperCall = methodStatementFor(
                ExampleInheritedExceptionTarget.class.getName(),
                declaredMethod(ExampleInheritedExceptionBase.class, "helper"),
                false);
        Statement targetCall = methodStatementFor(
                ExampleInheritedExceptionTarget.class.getName(),
                declaredMethod(ExampleInheritedExceptionBase.class, "withInput", String.class),
                false);

        TestChromosome failing1 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(helperCall, targetCall), 1,
                new NullPointerException("boom"),
                Collections.singleton(ExampleInheritedExceptionTarget.class.getName() + ".withInput"));
        TestChromosome failing2 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(helperCall, targetCall), 1,
                new IllegalStateException("bad state"),
                Collections.singleton(ExampleInheritedExceptionTarget.class.getName() + ".withInput"));

        ProblemCard card = findCard(extractor.extract(Collections.singleton(goal),
                java.util.Arrays.asList(failing1, failing2), 3), ProblemCardType.EXCEPTION_BARRIER);
        assertNotNull(card,
                "Observed inherited-method invocations should match subclass uncovered-goal buckets");
        assertTrue(card.getTitle().contains(ExampleInheritedExceptionTarget.class.getName() + ".withInput(String)"));
    }

    @Test
    void extractsUninstantiableTypeCardWhenConstructorsAlwaysThrow() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        String typeName = ExampleStateType.class.getName();
        TestFitnessFunction goal = goal(typeName, "targetMethod()V");
        Statement constructor = constructorStatementFor(ExampleStateType.class);

        TestChromosome t1 = chromosomeWithThrownStatement(constructor, 0, new IllegalArgumentException("bad ctor"));
        TestChromosome t2 = chromosomeWithThrownStatement(constructor, 0, new IllegalStateException("bad ctor"));

        List<ProblemCard> cards = extractor.extract(Collections.singletonList(goal),
                java.util.Arrays.asList(t1, t2), 3);

        ProblemCard card = findCard(cards, ProblemCardType.UNINSTANTIABLE_TYPE);
        assertNotNull(card, "Expected UNINSTANTIABLE_TYPE when constructor/factory acquisition always fails");
        assertEquals(0.5, card.getConfidence(), 0.0001,
                "Type-barrier confidence should ramp up quickly after two failed acquisitions");
    }

    @Test
    void uninstantiableTypeEvidenceShowsConstructorFactorySplitAndEntryPoint() throws NoSuchMethodException {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        String typeName = ExampleStateType.class.getName();
        TestFitnessFunction goal = goal(typeName, "targetMethod()V");
        Statement constructor = constructorStatementFor(ExampleStateType.class);
        Statement factory = methodStatementFor(typeName,
                ExampleStateType.class.getDeclaredMethod("build"),
                true);

        TestChromosome t1 = chromosomeWithThrownStatement(constructor, 0, new IllegalArgumentException("bad ctor"));
        TestChromosome t2 = chromosomeWithThrownStatement(factory, 0, new IllegalStateException("bad factory"));
        TestChromosome t3 = chromosomeWithThrownStatement(factory, 0, new NullPointerException("bad factory"));

        List<ProblemCard> cards = extractor.extract(Collections.singletonList(goal),
                java.util.Arrays.asList(t1, t2, t3), 3);

        ProblemCard card = findCard(cards, ProblemCardType.UNINSTANTIABLE_TYPE);
        assertNotNull(card, "Expected UNINSTANTIABLE_TYPE when all acquisition entry points fail");
        assertTrue(card.getEvidence().stream().anyMatch(line -> line.contains("Constructor failures: 1; factory failures: 2")),
                "UNINSTANTIABLE_TYPE evidence should split constructor and factory failures");
        assertTrue(card.getEvidence().stream().anyMatch(line -> line.contains("Dominant failing acquisition entry point: "
                + typeName + ".build()")),
                "UNINSTANTIABLE_TYPE evidence should mention the dominant failing acquisition entry point");
    }

    @Test
    void typeBarrierImpactGetsLeverageBoostFromDistinctBlockedMethods() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        String typeName = ExampleStateType.class.getName();
        Statement constructor = constructorStatementFor(ExampleStateType.class);
        TestChromosome t1 = chromosomeWithThrownStatement(constructor, 0, new IllegalArgumentException("bad ctor"));
        TestChromosome t2 = chromosomeWithThrownStatement(constructor, 0, new IllegalStateException("bad ctor"));

        List<ProblemCard> cards = extractor.extract(java.util.Arrays.asList(
                        goal(typeName, "targetMethod()V"),
                        goal(typeName, "helper()V"),
                        goal(typeName, "someStateSetter()V")),
                java.util.Arrays.asList(t1, t2), 3);

        ProblemCard card = findCard(cards, ProblemCardType.UNINSTANTIABLE_TYPE);
        assertNotNull(card, "Expected UNINSTANTIABLE_TYPE when all acquisition attempts fail");
        assertTrue(card.getImpact() > 0.6,
                "Type-barrier impact should increase when the same blocker holds back multiple methods");
    }

    @Test
    void extractsUninstantiableTypeCardForDependencyTypeReferencedByBlockedTest() throws NoSuchMethodException {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal(ExampleTargetType.class.getName(), "targetMethod()V");
        Statement dependencyConstructor = constructorStatementFor(ExampleStateType.class);
        Statement targetCall = methodStatementFor(
                ExampleTargetType.class.getName(),
                ExampleTargetType.class.getDeclaredMethod("targetMethod"),
                false);

        TestChromosome t1 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(dependencyConstructor, targetCall),
                0,
                new IllegalArgumentException("bad ctor"));
        TestChromosome t2 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(dependencyConstructor, targetCall),
                0,
                new IllegalStateException("bad ctor"));

        List<ProblemCard> cards = extractor.extract(Collections.singletonList(goal),
                java.util.Arrays.asList(t1, t2), 3);

        ProblemCard card = findCard(cards, ProblemCardType.UNINSTANTIABLE_TYPE);
        assertNotNull(card, "Expected UNINSTANTIABLE_TYPE when dependency bootstrap failures block the target type");
        assertTrue(card.getTitle().contains(ExampleTargetType.class.getName()),
                "Bootstrap acquisition failures should be attributed to the blocked downstream target type");
        assertTrue(card.getEvidence().stream().anyMatch(line ->
                        line.contains("Dominant failing acquisition entry point: " + ExampleStateType.class.getName())),
                "Bootstrap attribution should still expose the dependency entry point that actually failed");
    }

    @Test
    void ignoresHelperOnlyConstructorMappingFailuresWithoutGoalBearingType() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal(ExampleTargetType.class.getName(), "targetMethod()V");
        Statement helperConstructor = constructorStatementFor(ExampleStateType.class);

        TestChromosome failing1 = chromosomeWithThrownStatement(
                helperConstructor, 0, new IllegalArgumentException("bad ctor"));
        TestChromosome failing2 = chromosomeWithThrownStatement(
                helperConstructor, 0, new IllegalStateException("bad ctor"));
        TestChromosome helperOnlySuccess = chromosomeWithSuccessfulStatements(
                Collections.singletonList(helperConstructor), Collections.emptySet());

        ProblemCardExtractor.ExtractionResult extraction = extractor.extractWithTelemetry(Collections.singletonList(goal),
                java.util.Arrays.asList(failing1, failing2, helperOnlySuccess), 3);
        List<ProblemCard> cards = extraction.getCards();

        assertNull(findCard(cards, ProblemCardType.UNINSTANTIABLE_TYPE),
                "Helper-only constructor failures with no goal-bearing type in the test should not be attributed broadly");
        assertNull(findCard(cards, ProblemCardType.STATE_SETUP_BARRIER),
                "Orphan helper-only constructor activity should not create setup-barrier cards either");
        assertEquals(3, extraction.getRejectCounts().getOrDefault(
                        ExtractorRejectReason.BLOCKED_TYPE_MAPPING_FAILURE, 0),
                "Each unmapped helper-only constructor observation should be counted as a mapping failure");
    }

    @Test
    void ignoresHelperOnlyFactoryMappingFailuresWithoutGoalBearingType() throws NoSuchMethodException {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal(ExampleTargetType.class.getName(), "targetMethod()V");
        Statement helperFactory = methodStatementFor(
                ExampleStateType.class.getName(),
                ExampleStateType.class.getDeclaredMethod("build"),
                true);

        TestChromosome failing1 = chromosomeWithThrownStatement(
                helperFactory, 0, new IllegalArgumentException("bad factory"));
        TestChromosome failing2 = chromosomeWithThrownStatement(
                helperFactory, 0, new IllegalStateException("bad factory"));
        TestChromosome helperOnlySuccess = chromosomeWithSuccessfulStatements(
                Collections.singletonList(helperFactory), Collections.emptySet());

        ProblemCardExtractor.ExtractionResult extraction = extractor.extractWithTelemetry(Collections.singletonList(goal),
                java.util.Arrays.asList(failing1, failing2, helperOnlySuccess), 3);
        List<ProblemCard> cards = extraction.getCards();

        assertNull(findCard(cards, ProblemCardType.UNINSTANTIABLE_TYPE),
                "Helper-only factory failures with no goal-bearing type in the test should not be attributed broadly");
        assertNull(findCard(cards, ProblemCardType.STATE_SETUP_BARRIER),
                "Orphan helper-only factory activity should not create setup-barrier cards either");
        assertEquals(3, extraction.getRejectCounts().getOrDefault(
                        ExtractorRejectReason.BLOCKED_TYPE_MAPPING_FAILURE, 0),
                "Each unmapped helper-only factory observation should be counted as a mapping failure");
    }

    @Test
    void extractsUninstantiableTypeWhenRareAcquisitionNeverProgressesPastCreation() throws NoSuchMethodException {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        String typeName = ExampleStateType.class.getName();
        TestFitnessFunction goal = goal(typeName, "targetMethod()V");
        Statement constructor = constructorStatementFor(ExampleStateType.class);
        Statement factory = methodStatementFor(typeName,
                ExampleStateType.class.getDeclaredMethod("build"),
                true);

        TestChromosome failingConstructor = chromosomeWithThrownStatement(
                constructor, 0, new IllegalArgumentException("bad ctor"));
        TestChromosome failingFactory = chromosomeWithThrownStatement(
                factory, 0, new IllegalStateException("bad factory"));
        TestChromosome successfulAcquisition = chromosomeWithSuccessfulStatements(
                Collections.singletonList(constructor),
                Collections.emptySet());

        List<ProblemCard> cards = extractor.extract(Collections.singletonList(goal),
                java.util.Arrays.asList(failingConstructor, failingFactory, successfulAcquisition), 3);

        ProblemCard card = findCard(cards, ProblemCardType.UNINSTANTIABLE_TYPE);
        assertNotNull(card,
                "UNINSTANTIABLE_TYPE should remain when acquisition is failure-dominated and never progresses");
        assertTrue(card.getEvidence().stream()
                        .anyMatch(line -> line.contains("Acquisitions that progressed into same-type or goal-bearing execution: 0.")),
                "UNINSTANTIABLE_TYPE should report that bare acquisitions never advanced past creation");
        assertTrue(card.getEvidence().stream()
                        .anyMatch(line -> line.contains("Successful acquisitions without any later same-type or goal-bearing step: 1.")),
                "UNINSTANTIABLE_TYPE should report successful but unusable acquisitions");
        assertTrue(card.getEvidence().stream()
                        .anyMatch(line -> line.contains("Observed acquisitions never led to a usable post-construction step.")),
                "UNINSTANTIABLE_TYPE should explain why a few raw successes still count as a bootstrap barrier");
        assertTrue(card.getSelectionFingerprint().contains("|progressed=0"),
                "UNINSTANTIABLE_TYPE fingerprint should encode whether acquisition ever progressed");
    }

    @Test
    void recordsUninstantiableCandidateTelemetryWhenAttemptsStayBelowThreshold() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        String typeName = ExampleStateType.class.getName();
        TestFitnessFunction goal = goal(typeName, "targetMethod()V");
        Statement constructor = constructorStatementFor(ExampleStateType.class);

        ProblemCardExtractor.ExtractionResult extraction = extractor.extractWithTelemetry(Collections.singletonList(goal),
                Collections.singletonList(chromosomeWithThrownStatement(
                        constructor, 0, new IllegalArgumentException("bad ctor"))), 3);

        assertEquals(1, extraction.getCandidateCounts().getOrDefault(
                        ExtractorCandidateMetric.TYPE_BARRIER_SIGNALS_WITH_CONSTRUCTION_FAILURE, 0),
                "Candidate telemetry should record raw construction-failure type signals");
        assertEquals(1, extraction.getCandidateCounts().getOrDefault(
                        ExtractorCandidateMetric.UNINSTANTIABLE_TYPE_SUPPRESSED_INSUFFICIENT_ATTEMPTS, 0),
                "Candidate telemetry should expose UNINSTANTIABLE_TYPE cases filtered only by sparse attempts");
        assertEquals(0, extraction.getCandidateCounts().getOrDefault(
                        ExtractorCandidateMetric.UNINSTANTIABLE_TYPE_CANDIDATES, 0),
                "Sub-threshold construction failures should not count as full UNINSTANTIABLE_TYPE candidates");
    }

    @Test
    void doesNotExtractUninstantiableTypeWhenAcquisitionProgressesPastCreation() throws NoSuchMethodException {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        String typeName = ExampleStateType.class.getName();
        TestFitnessFunction goal = goal(typeName, "targetMethod()V");
        Statement constructor = constructorStatementFor(ExampleStateType.class);
        Statement factory = methodStatementFor(typeName,
                ExampleStateType.class.getDeclaredMethod("build"),
                true);
        Statement helper = methodStatementFor(typeName,
                ExampleStateType.class.getDeclaredMethod("helper"),
                false);

        TestChromosome failingConstructor = chromosomeWithThrownStatement(
                constructor, 0, new IllegalArgumentException("bad ctor"));
        TestChromosome failingFactory = chromosomeWithThrownStatement(
                factory, 0, new IllegalStateException("bad factory"));
        TestChromosome successfulAcquisition = chromosomeWithSuccessfulStatements(
                java.util.Arrays.asList(constructor, helper),
                Collections.emptySet());

        ProblemCardExtractor.ExtractionResult extraction = extractor.extractWithTelemetry(Collections.singletonList(goal),
                java.util.Arrays.asList(failingConstructor, failingFactory, successfulAcquisition), 3);
        List<ProblemCard> cards = extraction.getCards();

        assertFalse(cards.stream().anyMatch(c -> c.getType() == ProblemCardType.UNINSTANTIABLE_TYPE),
                "UNINSTANTIABLE_TYPE should stay suppressed once acquisition advances beyond object creation");
        assertEquals(1, extraction.getRejectCounts().getOrDefault(
                        ExtractorRejectReason.UNINSTANTIABLE_PROGRESS_BEYOND_CREATION, 0),
                "Extractor telemetry should record when meaningful same-type progress suppresses UNINSTANTIABLE_TYPE");
    }

    @Test
    void extractsUninstantiableTypeWhenOnlyUnrelatedHelperRunsAfterAcquisition() throws NoSuchMethodException {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        String typeName = ExampleStateType.class.getName();
        TestFitnessFunction goal = goal(typeName, "targetMethod()V");
        Statement constructor = constructorStatementFor(ExampleStateType.class);
        Statement unrelatedCall = methodStatementFor(
                ExampleTargetType.class.getName(),
                ExampleTargetType.class.getDeclaredMethod("targetMethod"),
                false);

        TestChromosome failingConstructor = chromosomeWithThrownStatement(
                constructor, 0, new IllegalArgumentException("bad ctor"));
        TestChromosome failingFactory = chromosomeWithThrownStatement(
                methodStatementFor(typeName, ExampleStateType.class.getDeclaredMethod("build"), true),
                0, new IllegalStateException("bad factory"));
        TestChromosome successfulAcquisition = chromosomeWithSuccessfulStatements(
                java.util.Arrays.asList(constructor, unrelatedCall),
                Collections.emptySet());

        List<ProblemCard> cards = extractor.extract(Collections.singletonList(goal),
                java.util.Arrays.asList(failingConstructor, failingFactory, successfulAcquisition), 3);

        ProblemCard card = findCard(cards, ProblemCardType.UNINSTANTIABLE_TYPE);
        assertNotNull(card,
                "UNINSTANTIABLE_TYPE should remain when later execution never advances on the blocked type");
        assertTrue(card.getEvidence().stream().anyMatch(line ->
                        line.contains("Acquisitions that progressed into same-type or goal-bearing execution: 0.")),
                "Unrelated helper activity should not count as meaningful acquisition progress");
    }

    @Test
    void extractsStateSetupBarrierWhenConstructionSucceedsButSetupAlwaysThrows() throws NoSuchMethodException {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        String typeName = ExampleStateType.class.getName();
        TestFitnessFunction goal = goal(typeName, "targetMethod()V");
        Statement constructor = constructorStatementFor(ExampleStateType.class);
        Method setupMethod = ExampleStateType.class.getDeclaredMethod("someStateSetter");
        Statement setupCall = methodStatementFor(typeName, setupMethod, false);

        TestChromosome t1 = chromosomeWithThrownStatement(
                constructor, setupCall, 1, new IllegalStateException("setup fail"));
        TestChromosome t2 = chromosomeWithThrownStatement(
                constructor, setupCall, 1, new NullPointerException("setup fail"));

        List<ProblemCard> cards = extractor.extract(Collections.singletonList(goal),
                java.util.Arrays.asList(t1, t2), 3);

        ProblemCard card = findCard(cards, ProblemCardType.STATE_SETUP_BARRIER);
        assertNotNull(card,
                "Expected STATE_SETUP_BARRIER when construction succeeds but lifecycle setup fails");
        assertNull(findCard(cards, ProblemCardType.UNINSTANTIABLE_TYPE),
                "UNINSTANTIABLE_TYPE should stay suppressed when the search already reaches a later setup step");
        assertTrue(card.getEvidence().stream().anyMatch(line -> line.contains("Dominant failing setup/lifecycle step: "
                + typeName + ".someStateSetter()")),
                "STATE_SETUP_BARRIER evidence should expose the failing setup step label");
    }

    @Test
    void extractsStateSetupBarrierDespiteUnrelatedSuccessfulSetupCalls() throws NoSuchMethodException {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal(ExampleTargetType.class.getName(), "targetMethod()V");
        Statement constructor = constructorStatementFor(ExampleStateType.class);
        Statement helperCall = methodStatementFor(
                ExampleStateType.class.getName(),
                ExampleStateType.class.getDeclaredMethod("helper"),
                false);
        Statement setupCall = methodStatementFor(
                ExampleStateType.class.getName(),
                ExampleStateType.class.getDeclaredMethod("someStateSetter"),
                false);
        Statement targetCall = methodStatementFor(
                ExampleTargetType.class.getName(),
                ExampleTargetType.class.getDeclaredMethod("targetMethod"),
                false);

        TestChromosome t1 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(constructor, helperCall, setupCall, targetCall),
                2,
                new IllegalStateException("setup fail"));
        TestChromosome t2 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(constructor, helperCall, setupCall, targetCall),
                2,
                new NullPointerException("setup fail"));

        List<ProblemCard> cards = extractor.extract(Collections.singletonList(goal),
                java.util.Arrays.asList(t1, t2), 3);

        ProblemCard card = findCard(cards, ProblemCardType.STATE_SETUP_BARRIER);
        assertNotNull(card,
                "Expected STATE_SETUP_BARRIER when dependency setup repeatedly blocks the downstream target type");
        assertTrue(card.getTitle().contains(ExampleTargetType.class.getName()),
                "Bootstrap setup failures should be attributed to the blocked downstream target type");
        assertTrue(card.getEvidence().stream().anyMatch(line ->
                        line.contains("Dominant failing setup/lifecycle step: "
                                + ExampleStateType.class.getName() + ".someStateSetter()")),
                "Bootstrap setup attribution should still expose the failing dependency setup step");
    }

    @Test
    void extractsStateSetupBarrierWhenBootstrapFailuresAreDistributedAcrossSteps() throws NoSuchMethodException {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal(ExampleTargetType.class.getName(), "targetMethod()V");
        Statement constructor = constructorStatementFor(ExampleStateType.class);
        Statement helperCall = methodStatementFor(
                ExampleStateType.class.getName(),
                ExampleStateType.class.getDeclaredMethod("helper"),
                false);
        Statement setupCall = methodStatementFor(
                ExampleStateType.class.getName(),
                ExampleStateType.class.getDeclaredMethod("someStateSetter"),
                false);
        Statement targetCall = methodStatementFor(
                ExampleTargetType.class.getName(),
                ExampleTargetType.class.getDeclaredMethod("targetMethod"),
                false);

        TestChromosome helperFailure = chromosomeWithThrownStatements(
                java.util.Arrays.asList(constructor, helperCall, targetCall),
                1,
                new IllegalStateException("helper fail"));
        TestChromosome setupFailure = chromosomeWithThrownStatements(
                java.util.Arrays.asList(constructor, setupCall, targetCall),
                1,
                new NullPointerException("setup fail"));

        ProblemCard card = findCard(extractor.extract(Collections.singletonList(goal),
                java.util.Arrays.asList(helperFailure, setupFailure), 3), ProblemCardType.STATE_SETUP_BARRIER);
        assertNotNull(card,
                "STATE_SETUP_BARRIER should surface when bootstrap failures fragment across multiple setup steps");
        assertTrue(card.getEvidence().stream().anyMatch(line ->
                        line.contains("distributed across multiple bootstrap operations")),
                "Distributed setup failures should be called out explicitly in the evidence");
    }

    @Test
    void extractsStateSetupBarrierWhenFailuresStillDominateRareSuccessfulSetup() throws NoSuchMethodException {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        String typeName = ExampleStateType.class.getName();
        TestFitnessFunction goal = goal(typeName, "targetMethod()V");
        Statement constructor = constructorStatementFor(ExampleStateType.class);
        Statement setupCall = methodStatementFor(
                typeName,
                ExampleStateType.class.getDeclaredMethod("someStateSetter"),
                false);
        Statement targetCall = methodStatementFor(
                typeName,
                ExampleStateType.class.getDeclaredMethod("targetMethod"),
                false);

        TestChromosome failing1 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(constructor, setupCall, targetCall),
                1,
                new IllegalStateException("setup fail"));
        TestChromosome failing2 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(constructor, setupCall, targetCall),
                1,
                new NullPointerException("setup fail"));
        TestChromosome successfulSetup = chromosomeWithSuccessfulStatements(
                java.util.Arrays.asList(constructor, setupCall),
                Collections.emptySet());

        List<ProblemCard> cards = extractor.extract(Collections.singletonList(goal),
                java.util.Arrays.asList(failing1, failing2, successfulSetup), 5);

        ProblemCard card = findCard(cards, ProblemCardType.STATE_SETUP_BARRIER);
        assertNotNull(card,
                "STATE_SETUP_BARRIER should remain when the same setup step still fails most of the time");
        assertTrue(card.getEvidence().stream().anyMatch(line -> line.contains("Dominant failing setup-step outcomes: 2/3.")),
                "STATE_SETUP_BARRIER should expose the dominant setup-step failure ratio");
    }

    @Test
    void doesNotExtractStateSetupBarrierWhenSuccessesBreakDominance() throws NoSuchMethodException {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        String typeName = ExampleStateType.class.getName();
        TestFitnessFunction goal = goal(typeName, "targetMethod()V");
        Statement constructor = constructorStatementFor(ExampleStateType.class);
        Statement setupCall = methodStatementFor(
                typeName,
                ExampleStateType.class.getDeclaredMethod("someStateSetter"),
                false);

        TestChromosome failing1 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(constructor, setupCall),
                1,
                new IllegalStateException("setup fail"));
        TestChromosome failing2 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(constructor, setupCall),
                1,
                new NullPointerException("setup fail"));
        TestChromosome success1 = chromosomeWithSuccessfulStatements(
                java.util.Arrays.asList(constructor, setupCall),
                Collections.emptySet());
        TestChromosome success2 = chromosomeWithSuccessfulStatements(
                java.util.Arrays.asList(constructor, setupCall),
                Collections.emptySet());

        ProblemCardExtractor.ExtractionResult extraction = extractor.extractWithTelemetry(Collections.singletonList(goal),
                java.util.Arrays.asList(failing1, failing2, success1, success2), 3);
        List<ProblemCard> cards = extraction.getCards();

        assertFalse(cards.stream().anyMatch(c -> c.getType() == ProblemCardType.STATE_SETUP_BARRIER),
                "STATE_SETUP_BARRIER should stay suppressed once the setup step no longer fails dominantly");
        assertEquals(1, extraction.getCandidateCounts().getOrDefault(
                        ExtractorCandidateMetric.STATE_SETUP_BARRIER_CANDIDATES, 0),
                "Candidate telemetry should record setup-barrier candidates before diluted successes suppress them");
        assertEquals(1, extraction.getRejectCounts().getOrDefault(
                        ExtractorRejectReason.STATE_SETUP_DILUTED_SUCCESS, 0),
                "Extractor telemetry should record when successful setup executions suppress STATE_SETUP_BARRIER");
    }

    @Test
    void persistentExceptionEvidenceCanTriggerBarrierCard() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        String methodKey = ExampleExceptionTarget.class.getName() + ".doWork";
        TestFitnessFunction goal = goal(ExampleExceptionTarget.class.getName(), "doWork()V");
        Statement targetCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "doWork"),
                false);
        TestChromosome oneThrowingAttempt = chromosomeWithThrownStatements(
                Collections.singletonList(targetCall), 0, new NullPointerException("boom"),
                Collections.singleton(methodKey));

        Map<String, ExceptionBarrierTracker.AggregatedStats> persistent = new LinkedHashMap<>();
        persistent.put(methodKey, new ExceptionBarrierTracker.AggregatedStats(
                2, 0, 2, 2, "NullPointerException", "", 0, Collections.emptyMap(), Collections.emptyMap()));

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                Collections.singletonList(oneThrowingAttempt), 3, persistent);

        assertTrue(cards.stream().anyMatch(c -> c.getType() == ProblemCardType.EXCEPTION_BARRIER),
                "Persistent exception evidence should contribute to EXCEPTION_BARRIER detection");
    }

    @Test
    void exceptionBarrierCountsThrowingStatementWhenExecutionStopsAtThrowIndex() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal(ExampleExceptionTarget.class.getName(), "withInput(Ljava/lang/String;)V");
        Statement helperCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "helper"),
                false);
        Statement targetCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "withInput", String.class),
                false);
        TestChromosome t1 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(helperCall, targetCall),
                1,
                1,
                new NullPointerException("boom"),
                Collections.singleton(ExampleExceptionTarget.class.getName() + ".withInput"));
        TestChromosome t2 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(helperCall, targetCall),
                1,
                1,
                new IllegalStateException("bad state"),
                Collections.singleton(ExampleExceptionTarget.class.getName() + ".withInput"));

        List<ProblemCard> cards = extractor.extract(Collections.singleton(goal),
                java.util.Arrays.asList(t1, t2), 3);
        assertTrue(cards.stream().anyMatch(c -> c.getType() == ProblemCardType.EXCEPTION_BARRIER),
                "Throwing invocation should still count when executedStatements equals throw index");
    }

    @Test
    void extractsUpstreamExceptionBarrierWhenHelperThrowsBeforeTarget() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal(ExampleExceptionTarget.class.getName(), "doWork()V");
        Statement helperCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "helper"),
                false);
        Statement targetCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "doWork"),
                false);
        TestChromosome t1 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(helperCall, targetCall),
                0,
                new IllegalStateException("helper fail"));
        TestChromosome t2 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(helperCall, targetCall),
                0,
                new NullPointerException("helper fail"));

        ProblemCard card = findCard(extractor.extract(Collections.singletonList(goal),
                java.util.Arrays.asList(t1, t2), 3), ProblemCardType.EXCEPTION_BARRIER);

        assertNotNull(card, "Expected EXCEPTION_BARRIER when the same helper throws before the target call is reached");
        assertTrue(card.getTitle().contains("Upstream invocation is exception-dominated"),
                "Upstream blocker evidence should produce an upstream-focused exception-barrier title");
        assertTrue(card.getEvidence().stream().anyMatch(line ->
                        line.contains("Repeated upstream invocation blocked the target before it was reached")),
                "Upstream exception barriers should explain that the target was blocked before direct invocation");
        assertTrue(card.getSelectionFingerprint().contains("|upstream="),
                "Upstream exception barriers should encode the blocker execution key in the fingerprint");
    }

    @Test
    void recordsUpstreamExceptionCandidateTelemetryWhenHelperThrowsBeforeTarget() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        TestFitnessFunction goal = goal(ExampleExceptionTarget.class.getName(), "doWork()V");
        Statement helperCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "helper"),
                false);
        Statement targetCall = methodStatementFor(
                ExampleExceptionTarget.class.getName(),
                declaredMethod(ExampleExceptionTarget.class, "doWork"),
                false);
        TestChromosome t1 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(helperCall, targetCall),
                0,
                new IllegalStateException("helper fail"));
        TestChromosome t2 = chromosomeWithThrownStatements(
                java.util.Arrays.asList(helperCall, targetCall),
                0,
                new NullPointerException("helper fail"));

        ProblemCardExtractor.ExtractionResult extraction = extractor.extractWithTelemetry(Collections.singletonList(goal),
                java.util.Arrays.asList(t1, t2), 3);

        assertEquals(1, extraction.getCandidateCounts().getOrDefault(
                        ExtractorCandidateMetric.UPSTREAM_EXCEPTION_REPEATED_SOURCES, 0),
                "Candidate telemetry should count repeated upstream throwing sources before blocked-goal attribution");
        assertEquals(1, extraction.getCandidateCounts().getOrDefault(
                        ExtractorCandidateMetric.UPSTREAM_EXCEPTION_BLOCKED_GOAL_METHODS, 0),
                "Candidate telemetry should count distinct downstream goal methods blocked by upstream throws");
        assertEquals(1, extraction.getCandidateCounts().getOrDefault(
                        ExtractorCandidateMetric.EXCEPTION_BARRIER_UPSTREAM_CANDIDATES, 0),
                "Candidate telemetry should record upstream exception barriers that clear extraction thresholds");
    }

    @Test
    void extractsBranchPolarityGapCardWhenOnlyOppositeBranchOutcomeIsObserved() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        BranchCoverageTestFitness branchGoal = mock(BranchCoverageTestFitness.class);
        BranchCoverageGoal branchCoverageGoal = mock(BranchCoverageGoal.class);
        Branch branch = mock(Branch.class);
        when(branchGoal.getTargetClass()).thenReturn("com.example.Foo");
        when(branchGoal.getTargetMethod()).thenReturn("doWork()V");
        when(branchGoal.getBranchGoal()).thenReturn(branchCoverageGoal);
        when(branchCoverageGoal.getBranch()).thenReturn(branch);
        when(branchCoverageGoal.getLineNumber()).thenReturn(42);
        when(branchCoverageGoal.getValue()).thenReturn(true);
        when(branch.getActualBranchId()).thenReturn(77);
        when(branch.getClassName()).thenReturn("com.example.Foo");
        when(branch.getMethodName()).thenReturn("doWork()V");

        TestChromosome t1 = chromosomeWithBranchOutcomes("com.example.Foo.doWork",
                Collections.emptySet(), Collections.singleton(77));
        TestChromosome t2 = chromosomeWithBranchOutcomes("com.example.Foo.doWork",
                Collections.emptySet(), Collections.singleton(77));

        List<ProblemCard> cards = extractor.extract(Collections.singletonList(branchGoal),
                java.util.Arrays.asList(t1, t2), 3);

        ProblemCard card = cards.stream()
                .filter(c -> c.getType() == ProblemCardType.BRANCH_POLARITY_GAP)
                .findFirst()
                .orElse(null);
        assertNotNull(card,
                "Expected BRANCH_POLARITY_GAP when target outcome is never seen but opposite is observed");
        assertTrue(card.getTitle().contains("com.example.Foo.doWork() at line 42"),
                "Branch polarity cards should expose method and line instead of only internal ids");
        assertFalse(card.getTitle().contains("branchId="),
                "Branch polarity cards should not expose raw branch ids in prompt text");
    }

    @Test
    void marksSaturatedMultiBranchGapsAsLowSteerability() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        BranchCoverageTestFitness firstGoal = mock(BranchCoverageTestFitness.class);
        BranchCoverageGoal firstBranchCoverageGoal = mock(BranchCoverageGoal.class);
        Branch firstBranch = mock(Branch.class);
        when(firstGoal.getTargetClass()).thenReturn("apbs_mem_gui.InFile");
        when(firstGoal.getTargetMethod()).thenReturn("writeGatingSection(II)V");
        when(firstGoal.getBranchGoal()).thenReturn(firstBranchCoverageGoal);
        when(firstBranchCoverageGoal.getBranch()).thenReturn(firstBranch);
        when(firstBranchCoverageGoal.getLineNumber()).thenReturn(396);
        when(firstBranchCoverageGoal.getValue()).thenReturn(true);
        when(firstBranch.getActualBranchId()).thenReturn(77);
        when(firstBranch.getClassName()).thenReturn("apbs_mem_gui.InFile");
        when(firstBranch.getMethodName()).thenReturn("writeGatingSection(II)V");

        BranchCoverageTestFitness secondGoal = mock(BranchCoverageTestFitness.class);
        BranchCoverageGoal secondBranchCoverageGoal = mock(BranchCoverageGoal.class);
        Branch secondBranch = mock(Branch.class);
        when(secondGoal.getTargetClass()).thenReturn("apbs_mem_gui.InFile");
        when(secondGoal.getTargetMethod()).thenReturn("writeGatingSection(II)V");
        when(secondGoal.getBranchGoal()).thenReturn(secondBranchCoverageGoal);
        when(secondBranchCoverageGoal.getBranch()).thenReturn(secondBranch);
        when(secondBranchCoverageGoal.getLineNumber()).thenReturn(410);
        when(secondBranchCoverageGoal.getValue()).thenReturn(true);
        when(secondBranch.getActualBranchId()).thenReturn(78);
        when(secondBranch.getClassName()).thenReturn("apbs_mem_gui.InFile");
        when(secondBranch.getMethodName()).thenReturn("writeGatingSection(II)V");

        TestChromosome t1 = chromosomeWithBranchOutcomes("apbs_mem_gui.InFile.writeGatingSection",
                Collections.emptySet(), setOf(77, 78));
        TestChromosome t2 = chromosomeWithBranchOutcomes("apbs_mem_gui.InFile.writeGatingSection",
                Collections.emptySet(), setOf(77, 78));
        TestChromosome t3 = chromosomeWithBranchOutcomes("apbs_mem_gui.InFile.writeGatingSection",
                Collections.emptySet(), setOf(77, 78));
        TestChromosome t4 = chromosomeWithBranchOutcomes("apbs_mem_gui.InFile.writeGatingSection",
                Collections.emptySet(), setOf(77, 78));

        List<ProblemCard> cards = extractor.extract(java.util.Arrays.asList(firstGoal, secondGoal),
                java.util.Arrays.asList(t1, t2, t3, t4), 5);
        List<ProblemCard> branchCards = cards.stream()
                .filter(card -> card.getType() == ProblemCardType.BRANCH_POLARITY_GAP)
                .collect(java.util.stream.Collectors.toList());

        assertEquals(2, branchCards.size(),
                "Expected one branch polarity card per saturated uncovered branch target");
        for (ProblemCard card : branchCards) {
            assertTrue(card.getSelectionFingerprint().contains("|steer=low"),
                    "Saturated multi-branch gaps in one helper method should be marked low-steerability");
            assertTrue(card.getEvidence().stream().anyMatch(line -> line.contains("likely caller-locked")),
                    "Low-steerability branch cards should explain why they are deprioritized");
            assertTrue(card.getPriority() < 0.12,
                    "Low-steerability branch cards should be significantly downweighted");
        }
    }

    @Test
    void marksHeavilyRepeatedSingleBranchSaturationAsLowSteerability() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        BranchCoverageTestFitness branchGoal = mock(BranchCoverageTestFitness.class);
        BranchCoverageGoal branchCoverageGoal = mock(BranchCoverageGoal.class);
        Branch branch = mock(Branch.class);
        when(branchGoal.getTargetClass()).thenReturn("com.example.Foo");
        when(branchGoal.getTargetMethod()).thenReturn("toCacheModel()V");
        when(branchGoal.getBranchGoal()).thenReturn(branchCoverageGoal);
        when(branchCoverageGoal.getBranch()).thenReturn(branch);
        when(branchCoverageGoal.getLineNumber()).thenReturn(42);
        when(branchCoverageGoal.getValue()).thenReturn(true);
        when(branch.getActualBranchId()).thenReturn(77);
        when(branch.getClassName()).thenReturn("com.example.Foo");
        when(branch.getMethodName()).thenReturn("toCacheModel()V");

        List<TestChromosome> executions = java.util.Arrays.asList(
                chromosomeWithBranchOutcomes("com.example.Foo.toCacheModel", Collections.emptySet(), Collections.singleton(77)),
                chromosomeWithBranchOutcomes("com.example.Foo.toCacheModel", Collections.emptySet(), Collections.singleton(77)),
                chromosomeWithBranchOutcomes("com.example.Foo.toCacheModel", Collections.emptySet(), Collections.singleton(77)),
                chromosomeWithBranchOutcomes("com.example.Foo.toCacheModel", Collections.emptySet(), Collections.singleton(77)),
                chromosomeWithBranchOutcomes("com.example.Foo.toCacheModel", Collections.emptySet(), Collections.singleton(77)),
                chromosomeWithBranchOutcomes("com.example.Foo.toCacheModel", Collections.emptySet(), Collections.singleton(77)),
                chromosomeWithBranchOutcomes("com.example.Foo.toCacheModel", Collections.emptySet(), Collections.singleton(77)),
                chromosomeWithBranchOutcomes("com.example.Foo.toCacheModel", Collections.emptySet(), Collections.singleton(77)));

        ProblemCard card = findCard(extractor.extract(Collections.singletonList(branchGoal), executions, 3),
                ProblemCardType.BRANCH_POLARITY_GAP);
        assertNotNull(card, "Expected BRANCH_POLARITY_GAP for a saturated single-branch polarity miss");
        assertTrue(card.getSelectionFingerprint().contains("|steer=low"),
                "Heavily repeated single-branch saturation should also be marked low-steerability");
    }

    @Test
    void ignoresSwitchDefaultBranchPolarityGaps() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        BranchCoverageTestFitness branchGoal = mock(BranchCoverageTestFitness.class);
        BranchCoverageGoal branchCoverageGoal = mock(BranchCoverageGoal.class);
        Branch branch = mock(Branch.class);
        when(branchGoal.getTargetClass()).thenReturn("com.example.Foo");
        when(branchGoal.getTargetMethod()).thenReturn("doWork()V");
        when(branchGoal.getBranchGoal()).thenReturn(branchCoverageGoal);
        when(branchCoverageGoal.getBranch()).thenReturn(branch);
        when(branchCoverageGoal.getLineNumber()).thenReturn(42);
        when(branchCoverageGoal.getValue()).thenReturn(true);
        when(branch.getActualBranchId()).thenReturn(77);
        when(branch.getClassName()).thenReturn("com.example.Foo");
        when(branch.getMethodName()).thenReturn("doWork()V");
        when(branch.isSwitchCaseBranch()).thenReturn(true);
        when(branch.isDefaultCase()).thenReturn(true);

        TestChromosome t1 = chromosomeWithBranchOutcomes("com.example.Foo.doWork",
                Collections.emptySet(), Collections.singleton(77));
        TestChromosome t2 = chromosomeWithBranchOutcomes("com.example.Foo.doWork",
                Collections.emptySet(), Collections.singleton(77));

        List<ProblemCard> cards = extractor.extract(Collections.singletonList(branchGoal),
                java.util.Arrays.asList(t1, t2), 3);

        assertFalse(cards.stream().anyMatch(c -> c.getType() == ProblemCardType.BRANCH_POLARITY_GAP),
                "Switch-default polarity gaps should be suppressed as likely infeasible noise");
    }

    @Test
    void extractsCdgBottleneckCardForUnmetControlDependency() {
        ProblemCardExtractor extractor = new ProblemCardExtractor();

        BranchCoverageTestFitness branchGoal = mock(BranchCoverageTestFitness.class);
        BranchCoverageGoal targetGoal = mock(BranchCoverageGoal.class);
        Branch targetBranch = mock(Branch.class);
        Branch dependencyBranch = mock(Branch.class);
        BytecodeInstruction dependencyInstruction = mock(BytecodeInstruction.class);
        BytecodeInstruction dependencyBranchInstruction = mock(BytecodeInstruction.class);

        when(branchGoal.getTargetClass()).thenReturn("com.example.Foo");
        when(branchGoal.getTargetMethod()).thenReturn("doWork()V");
        when(branchGoal.getBranchGoal()).thenReturn(targetGoal);
        when(targetGoal.getBranch()).thenReturn(targetBranch);
        when(targetGoal.getLineNumber()).thenReturn(99);
        when(targetGoal.getValue()).thenReturn(true);
        when(targetBranch.getActualBranchId()).thenReturn(77);
        when(targetBranch.getClassName()).thenReturn("com.example.Foo");
        when(targetBranch.getMethodName()).thenReturn("doWork()V");
        when(targetBranch.getInstruction()).thenReturn(dependencyInstruction);
        when(dependencyInstruction.getControlDependencies())
                .thenReturn(Collections.singleton(new ControlDependency(dependencyBranch, true)));

        when(dependencyBranch.getActualBranchId()).thenReturn(55);
        when(dependencyBranch.getClassName()).thenReturn("com.example.Foo");
        when(dependencyBranch.getMethodName()).thenReturn("gate()V");
        when(dependencyBranch.getInstruction()).thenReturn(dependencyBranchInstruction);
        when(dependencyBranchInstruction.getLineNumber()).thenReturn(21);

        TestChromosome t1 = chromosomeWithBranchOutcomes("com.example.Foo.doWork",
                Collections.emptySet(), Collections.singleton(55));
        TestChromosome t2 = chromosomeWithBranchOutcomes("com.example.Foo.doWork",
                Collections.emptySet(), Collections.singleton(55));

        List<ProblemCard> cards = extractor.extract(Collections.singletonList(branchGoal),
                java.util.Arrays.asList(t1, t2), 3);

        ProblemCard card = cards.stream()
                .filter(c -> c.getType() == ProblemCardType.CDG_BOTTLENECK)
                .findFirst()
                .orElse(null);
        assertNotNull(card,
                "Expected CDG_BOTTLENECK when required control dependency outcome is never seen");
        assertTrue(card.getTitle().contains("com.example.Foo.gate() at line 21"),
                "CDG cards should expose dependency method and line instead of only internal ids");
        assertFalse(card.getTitle().contains("branchId="),
                "CDG cards should not expose raw branch ids in prompt text");
    }

    @Test
    void formatterAddsSpecializedSuggestedActionsForTypeBarriers() {
        ProblemCard unreached = ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                .title("Method not reached")
                .evidence(Collections.singletonList("e0"))
                .relatedGoals(Collections.emptyList())
                .impact(0.8).blockage(0.9).confidence(0.9)
                .build();
        ProblemCard polarityGap = ProblemCard.builder(ProblemCardType.BRANCH_POLARITY_GAP)
                .title("Branch polarity gap")
                .evidence(Collections.singletonList("e-1"))
                .relatedGoals(Collections.emptyList())
                .impact(0.8).blockage(0.9).confidence(0.9)
                .build();
        ProblemCard uninstantiable = ProblemCard.builder(ProblemCardType.UNINSTANTIABLE_TYPE)
                .title("Cannot instantiate")
                .evidence(Collections.singletonList("e1"))
                .relatedGoals(Collections.emptyList())
                .impact(0.8).blockage(0.9).confidence(0.9)
                .build();
        ProblemCard setupBarrier = ProblemCard.builder(ProblemCardType.STATE_SETUP_BARRIER)
                .title("Setup fails")
                .evidence(Collections.singletonList("e2"))
                .relatedGoals(Collections.emptyList())
                .impact(0.8).blockage(0.9).confidence(0.9)
                .build();
        ProblemCard indirect = ProblemCard.builder(ProblemCardType.INDIRECT_REACHABILITY_BARRIER)
                .title("Indirect helper barrier")
                .evidence(Collections.singletonList("e3"))
                .relatedGoals(Collections.emptyList())
                .impact(0.8).blockage(0.9).confidence(0.9)
                .build();

        String text = ProblemCardFormatter.format(
                java.util.Arrays.asList(unreached, polarityGap, uninstantiable, setupBarrier, indirect));
        assertTrue(text.contains("Suggested action: Reuse a working acquisition/setup prefix"),
                "UNREACHED_METHOD cards should steer the LLM toward direct invocation without assertions");
        assertTrue(text.contains("Suggested action: Reuse a working prefix"),
                "BRANCH_POLARITY_GAP cards should include a predicate-flipping suggested action");
        assertTrue(text.contains("Suggested action: Try alternate object acquisition paths"),
                "UNINSTANTIABLE_TYPE cards should include an acquisition-focused suggested action");
        assertTrue(text.contains("usable instance"),
                "UNINSTANTIABLE_TYPE cards should ask for a usable instance, not just any constructor call");
        assertTrue(text.contains("Suggested action: Construct the object first"),
                "STATE_SETUP_BARRIER cards should include a setup-sequence suggested action");
        assertTrue(text.contains("Suggested action: Drive the outer entrypoint workflow"),
                "INDIRECT_REACHABILITY_BARRIER cards should include an outer-entrypoint setup action");
    }

    @Test
    void diagnosticModeBuildsPromptWithProblemCardsSection() throws NoSuchMethodException {
        Properties.LlmStagnationPromptMode oldMode = Properties.LLM_STAGNATION_PROMPT;
        String oldTargetClass = Properties.TARGET_CLASS;
        try {
            Properties.LLM_STAGNATION_PROMPT = Properties.LlmStagnationPromptMode.DIAGNOSTIC;
            String typeName = ExampleStateType.class.getName();
            Properties.TARGET_CLASS = typeName;
            TestFitnessFunction goal = goal(typeName, "targetMethod()V");
            Statement constructor = constructorStatementFor(ExampleStateType.class);
            Statement helperCall = methodStatementFor(typeName, ExampleStateType.class.getDeclaredMethod("helper"), false);
            TestChromosome test = chromosomeWithSuccessfulStatements(
                    java.util.Arrays.asList(constructor, helperCall),
                    Collections.singleton(typeName + ".helper"));
            Map<TestFitnessFunction, Double> best = new LinkedHashMap<>();
            best.put(goal, 1.0);

            LlmService service = mock(LlmService.class);
            StagnationDetector detector = new StagnationDetector(service, false, 10, 2, System::nanoTime);
            PromptResult prompt = detector.buildPrompt(Collections.singleton(goal),
                    Collections.singletonList(test), 10, 5, best);

            boolean hasCardsSection = prompt.getMessages().stream()
                    .filter(m -> m.getRole() == LlmMessage.Role.USER)
                    .map(LlmMessage::getContent)
                    .anyMatch(text -> text.contains("Priority problem cards:")
                            && (text.contains("[UNREACHED_METHOD]")
                            || text.contains("[INDIRECT_REACHABILITY_BARRIER]")));
            assertTrue(hasCardsSection, "Diagnostic prompt should include ranked problem cards");
            boolean encouragesFeasibleCoverage = prompt.getMessages().stream()
                    .filter(m -> m.getRole() == LlmMessage.Role.USER)
                    .map(LlmMessage::getContent)
                    .anyMatch(text -> text.contains("address as many of these cards as feasible")
                            && text.contains("A single test may address multiple cards.")
                            && !text.contains("generate up to"));
            assertTrue(encouragesFeasibleCoverage,
                    "Diagnostic prompt should remove the numeric test cap and encourage feasible multi-card coverage");
            boolean forbidsAssertions = prompt.getMessages().stream()
                    .filter(m -> m.getRole() == LlmMessage.Role.USER)
                    .map(LlmMessage::getContent)
                    .anyMatch(text -> text.contains("assertions are unnecessary")
                            && text.contains("Do NOT include assertions"));
            assertTrue(forbidsAssertions,
                    "Diagnostic prompt should reinforce that injected coverage tests do not need assertions");
            boolean systemPromptDefersToNoAssertions = prompt.getMessages().stream()
                    .filter(m -> m.getRole() == LlmMessage.Role.SYSTEM)
                    .map(LlmMessage::getContent)
                    .anyMatch(text -> text.contains("omit assertions entirely"));
            assertTrue(systemPromptDefersToNoAssertions,
                    "System prompt should explicitly defer to coverage-guidance prompts that forbid assertions");
            assertTrue(prompt.getDiagnosticCardTypes().contains(ProblemCardType.UNREACHED_METHOD)
                            || prompt.getDiagnosticCardTypes().contains(ProblemCardType.INDIRECT_REACHABILITY_BARRIER),
                    "Diagnostic prompt metadata should include selected card types");
        } finally {
            Properties.LLM_STAGNATION_PROMPT = oldMode;
            Properties.TARGET_CLASS = oldTargetClass;
        }
    }

    private static TestFitnessFunction goal(String className, String methodName) {
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.getTargetClass()).thenReturn(className);
        when(goal.getTargetMethod()).thenReturn(methodName);
        when(goal.toString()).thenReturn(className + "." + methodName);
        return goal;
    }

    private static TestChromosome chromosomeWithCoveredMethods(Set<String> coveredMethods) {
        TestChromosome chromosome = mock(TestChromosome.class);
        ExecutionResult result = mock(ExecutionResult.class);
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getCoveredMethods()).thenReturn(coveredMethods);
        when(result.getTrace()).thenReturn(trace);
        when(result.hasTimeout()).thenReturn(false);
        when(result.hasTestException()).thenReturn(false);
        when(chromosome.getLastExecutionResult()).thenReturn(result);
        return chromosome;
    }

    private static TestChromosome chromosomeWithBranchOutcomes(String coveredMethod,
                                                               Set<Integer> trueBranches,
                                                               Set<Integer> falseBranches) {
        TestChromosome chromosome = mock(TestChromosome.class);
        ExecutionResult result = mock(ExecutionResult.class);
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getCoveredMethods()).thenReturn(Collections.singleton(coveredMethod));
        when(trace.getCoveredTrueBranches()).thenReturn(trueBranches);
        when(trace.getCoveredFalseBranches()).thenReturn(falseBranches);
        when(result.getTrace()).thenReturn(trace);
        when(result.hasTimeout()).thenReturn(false);
        when(result.hasTestException()).thenReturn(false);
        when(chromosome.getLastExecutionResult()).thenReturn(result);
        return chromosome;
    }

    private static Set<Integer> setOf(Integer... values) {
        return new java.util.LinkedHashSet<>(java.util.Arrays.asList(values));
    }

    private static TestChromosome chromosomeWithThrownStatement(Statement thrownStatement,
                                                                   int thrownPosition,
                                                                   Throwable throwable) {
        TestChromosome chromosome = mock(TestChromosome.class);
        TestCase testCase = mock(TestCase.class);
        ExecutionResult result = mock(ExecutionResult.class);
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getCoveredMethods()).thenReturn(Collections.emptySet());
        when(result.getTrace()).thenReturn(trace);
        when(result.hasTimeout()).thenReturn(false);
        when(result.hasTestException()).thenReturn(true);
        when(result.getFirstPositionOfThrownException()).thenReturn(thrownPosition);
        when(result.getExceptionThrownAtPosition(thrownPosition)).thenReturn(throwable);
        when(result.getExecutedStatements()).thenReturn(thrownPosition + 1);
        when(testCase.size()).thenReturn(thrownPosition + 1);
        when(testCase.toCode()).thenReturn("// throwing test");
        when(testCase.hasStatement(thrownPosition)).thenReturn(true);
        when(testCase.getStatement(thrownPosition)).thenReturn(thrownStatement);
        when(chromosome.getLastExecutionResult()).thenReturn(result);
        when(chromosome.getTestCase()).thenReturn(testCase);
        return chromosome;
    }

    private static TestChromosome chromosomeWithSuccessfulStatements(List<Statement> statements,
                                                                     Set<String> coveredMethods) {
        TestChromosome chromosome = mock(TestChromosome.class);
        TestCase testCase = mock(TestCase.class);
        ExecutionResult result = mock(ExecutionResult.class);
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getCoveredMethods()).thenReturn(coveredMethods);
        when(result.getTrace()).thenReturn(trace);
        when(result.hasTimeout()).thenReturn(false);
        when(result.hasTestException()).thenReturn(false);
        when(result.getFirstPositionOfThrownException()).thenReturn(null);
        when(result.getExecutedStatements()).thenReturn(statements.size());
        when(testCase.size()).thenReturn(statements.size());
        when(testCase.toCode()).thenReturn("// successful test");
        for (int i = 0; i < statements.size(); i++) {
            when(testCase.hasStatement(i)).thenReturn(true);
            when(testCase.getStatement(i)).thenReturn(statements.get(i));
        }
        when(chromosome.getLastExecutionResult()).thenReturn(result);
        when(chromosome.getTestCase()).thenReturn(testCase);
        return chromosome;
    }

    private static TestChromosome chromosomeWithThrownStatements(List<Statement> statements,
                                                                 int thrownPosition,
                                                                 Throwable throwable) {
        return chromosomeWithThrownStatements(statements, thrownPosition, throwable, Collections.emptySet());
    }

    private static TestChromosome chromosomeWithThrownStatements(List<Statement> statements,
                                                                 int thrownPosition,
                                                                 Throwable throwable,
                                                                 Set<String> coveredMethods) {
        return chromosomeWithThrownStatements(statements, thrownPosition, thrownPosition + 1,
                throwable, coveredMethods);
    }

    private static TestChromosome chromosomeWithThrownStatements(List<Statement> statements,
                                                                 int thrownPosition,
                                                                 int executedStatements,
                                                                 Throwable throwable,
                                                                 Set<String> coveredMethods) {
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
        when(testCase.toCode()).thenReturn("// throwing test");
        for (int i = 0; i < statements.size(); i++) {
            when(testCase.hasStatement(i)).thenReturn(true);
            when(testCase.getStatement(i)).thenReturn(statements.get(i));
        }
        when(chromosome.getLastExecutionResult()).thenReturn(result);
        when(chromosome.getTestCase()).thenReturn(testCase);
        return chromosome;
    }

    private static TestChromosome chromosomeWithThrownStatement(Statement firstStatement,
                                                                Statement thrownStatement,
                                                                int thrownPosition,
                                                                Throwable throwable) {
        TestChromosome chromosome = mock(TestChromosome.class);
        TestCase testCase = mock(TestCase.class);
        ExecutionResult result = mock(ExecutionResult.class);
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getCoveredMethods()).thenReturn(Collections.emptySet());
        when(result.getTrace()).thenReturn(trace);
        when(result.hasTimeout()).thenReturn(false);
        when(result.hasTestException()).thenReturn(true);
        when(result.getFirstPositionOfThrownException()).thenReturn(thrownPosition);
        when(result.getExceptionThrownAtPosition(thrownPosition)).thenReturn(throwable);
        when(result.getExecutedStatements()).thenReturn(thrownPosition + 1);
        when(testCase.size()).thenReturn(thrownPosition + 1);
        when(testCase.toCode()).thenReturn("// throwing test");
        when(testCase.hasStatement(0)).thenReturn(true);
        when(testCase.hasStatement(thrownPosition)).thenReturn(true);
        when(testCase.getStatement(0)).thenReturn(firstStatement);
        when(testCase.getStatement(thrownPosition)).thenReturn(thrownStatement);
        when(chromosome.getLastExecutionResult()).thenReturn(result);
        when(chromosome.getTestCase()).thenReturn(testCase);
        return chromosome;
    }

    private static Statement constructorStatementFor(Class<?> declaringType) {
        ConstructorStatement statement = mock(ConstructorStatement.class);
        GenericConstructor constructor = mock(GenericConstructor.class);
        doReturn(declaringType).when(constructor).getDeclaringClass();
        when(statement.getConstructor()).thenReturn(constructor);
        return statement;
    }

    private static Statement methodStatementFor(String receiverTypeName,
                                                Method reflectedMethod,
                                                boolean asFactory) {
        return methodStatementFor(receiverTypeName, reflectedMethod, asFactory, Collections.emptyList());
    }

    private static Statement methodStatementFor(String receiverTypeName,
                                                Method reflectedMethod,
                                                boolean asFactory,
                                                List<VariableReference> parameters) {
        MethodStatement statement = mock(MethodStatement.class);
        GenericMethod method = mock(GenericMethod.class);
        VariableReference callee = mock(VariableReference.class);
        when(method.getMethod()).thenReturn(reflectedMethod);
        when(method.isStatic()).thenReturn(asFactory);
        when(statement.getMethod()).thenReturn(method);
        when(statement.getParameterReferences()).thenReturn(parameters);
        if (asFactory) {
            when(statement.getCallee()).thenReturn(null);
        } else {
            try {
                doReturn(Class.forName(receiverTypeName)).when(callee).getVariableClass();
            } catch (ClassNotFoundException e) {
                doReturn(Object.class).when(callee).getVariableClass();
            }
            when(statement.getCallee()).thenReturn(callee);
        }
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
            throw new AssertionError("Missing test helper method: " + type.getName() + "." + methodName, e);
        }
    }

    private static Method declaredMethod(Class<?> type, String methodName, Class<?>... parameterTypes) {
        try {
            return type.getDeclaredMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Missing test helper method: " + type.getName() + "." + methodName, e);
        }
    }

    private static ProblemCard findCard(List<ProblemCard> cards, ProblemCardType type) {
        return cards.stream()
                .filter(c -> c.getType() == type)
                .findFirst()
                .orElse(null);
    }

    private static final class ExampleStateType {
        static ExampleStateType build() {
            return new ExampleStateType();
        }

        void helper() {
            // no-op
        }

        void someStateSetter() {
            // no-op
        }

        void targetMethod() {
            // no-op
        }
    }

    private static final class ExampleExceptionTarget {
        void doWork() {
            // no-op
        }

        void withInput(String input) {
            // no-op
        }

        @SuppressWarnings("unused")
        void withInput(int input) {
            // overload used by overload-bucketing tests
        }

        void withDependency(Object dependency) {
            // no-op
        }

        void helper() {
            // no-op
        }
    }

    private static class ExampleInheritedExceptionBase {
        void withInput(String input) {
            // no-op
        }

        void helper() {
            // no-op
        }
    }

    private static final class ExampleInheritedExceptionTarget extends ExampleInheritedExceptionBase {
        // inherits helper/withInput
    }

    private static final class ExampleTargetType {
        void targetMethod() {
            // no-op
        }
    }

    private static final class ExampleIndirectReachabilityTarget {
        void driveZipWorkflow() {
            // no-op
        }
    }
}
