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
 */
package org.evosuite.testcase.synthesizer;

import com.google.gson.Gson;
import com.examples.with.different.packagename.objectconstruction.instancefield.PublicInstanceFieldExample;
import com.examples.with.different.packagename.objectconstruction.wholeclass.LocalDerivedArrayWholeClassExample;
import org.evosuite.Properties;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.graphs.interprocedural.InterproceduralGraphAnalysis;
import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.synthesizer.var.DepVariableWrapper;
import org.evosuite.testcase.synthesizer.var.VarRelevance;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Executable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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

/** Verifies the opt-in graph audit sidecar without executing a synthesized test. */
public class ObjectConstructionGraphDiagnosticsTest {

    @TempDir
    Path reportDirectory;

    private final String originalReportDirectory = Properties.REPORT_DIR;
    private final boolean originalDiagnostics = Properties.OBJECT_RULE_GRAPH_DIAGNOSTICS;

    @AfterEach
    public void tearDown() {
        Properties.REPORT_DIR = originalReportDirectory;
        Properties.OBJECT_RULE_GRAPH_DIAGNOSTICS = originalDiagnostics;
        Properties.TARGET_METHOD = "";
        Properties.TARGET_CLASS = "";
        Properties.APPLY_OBJECT_RULE = false;
        Properties.OBJECT_RULE_WHOLE_CLASS = false;
        InterproceduralGraphAnalysis.clear();
        ObjectConstructionGraphDiagnostics.reset();
        ObjectConstructionActionTrace.clear();
    }

    @Test
    public void exportsRealAnalysisGraphAndSynthesisOutcome() throws Exception {
        Properties.REPORT_DIR = reportDirectory.toString();
        Properties.OBJECT_RULE_GRAPH_DIAGNOSTICS = true;
        analyze(PublicInstanceFieldExample.class, "targetM", 1);

        Map<Branch, Set<DepVariable>> branchGraphs =
                ObjectConstructionContext.branchInterestedVarsMap.get(Properties.TARGET_METHOD);
        assertNotNull(branchGraphs);
        Branch branch = branchGraphs.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a state-bearing branch"));

        PartialGraph partialGraph = new ConstructionPathSynthesizer()
                .constructPartialComputationGraph(branch);
        assertTrue(partialGraph.getGraphSize() > 0);

        TestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        ExecutionResult result = new ExecutionResult(test, null);
        result.setExecutedStatements(1);
        Map<DepVariableWrapper, VarRelevance> mapping = new LinkedHashMap<>();
        DepVariableWrapper mappedNode = partialGraph.getNodes().get(0);
        VariableReference mappedVariable = test.getStatement(0).getReturnValue();
        mapping.put(mappedNode, new VarRelevance(
                Collections.singletonList(mappedVariable), Collections.singletonList(mappedVariable)));
        ObjectConstructionActionTrace.begin();
        Field candidateField = PublicInstanceFieldExample.class.getField("student1");
        Executable candidateConstructor = PublicInstanceFieldExample.class.getConstructor();
        Map<Executable, List<ValueSettings>> candidates = new LinkedHashMap<>();
        candidates.put(candidateConstructor, Collections.emptyList());
        ObjectConstructionActionTrace.recordCandidates(candidateField,
                PublicInstanceFieldExample.class.getName(), candidates,
                new double[] { Double.NaN }, new double[] { Double.NaN }, 0,
                Collections.emptyMap());
        ObjectConstructionActionTrace.recordSelectedOutcome(candidateField, candidateConstructor,
                "generated_constructor", test, mappedVariable, mappedVariable);
        Method arraySetter = PublicInstanceFieldExample.class.getMethod("targetM", int.class);
        Map<Method, Integer> arrayCandidates = new LinkedHashMap<>();
        arrayCandidates.put(arraySetter, 0);
        ObjectConstructionActionTrace.recordArrayElementCandidates(
                PublicInstanceFieldExample.class.getName(), "students",
                PublicInstanceFieldExample.class.getName(), arrayCandidates, arraySetter);
        ObjectConstructionActionTrace.recordArrayElementOutcome(
                PublicInstanceFieldExample.class.getName(), "students", arraySetter,
                "generated_statement", test, mappedVariable, mappedVariable);
        ObjectConstructionActionTrace.issue("unit_test_invariant", 0,
                "student1", "diagnostic fixture");
        ObjectConstructionGraphDiagnostics.recordSynthesisAttempt(
                branch, partialGraph, mapping, test, result, 0,
                1, true, true, 0.25, Arrays.asList(false, true), "unit_test", null);

        Path sidecar = ObjectConstructionGraphDiagnostics.outputFile();
        assertTrue(Files.isRegularFile(sidecar));
        List<Map<String, Object>> records = readRecords(sidecar);
        Map<String, Object> analysis = records.stream()
                .filter(record -> "analysis".equals(record.get("event")))
                .filter(record -> number(record, "node_count") > 1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a non-empty analysis record"));

        assertTrue(number(analysis, "edge_count") > 0);
        assertTrue(number(analysis, "root_count") > 0);
        assertTrue(number(analysis, "max_depth") >= 2);
        assertTrue(number(analysis, "invalid_root_path_count") >= 0);
        assertEquals(0, number(analysis, "unrooted_dependent_variable_count"));
        assertEquals(Boolean.FALSE, analysis.get("cyclic"));
        assertEquals(Boolean.FALSE, analysis.get("resolution_evaluated"));
        assertEquals("canonical_partial_graph", analysis.get("graph_form"));
        assertNull(analysis.get("resolved_node_count"));
        assertFalse(list(analysis, "nodes").isEmpty());
        assertFalse(list(analysis, "edges").isEmpty());
        assertEquals(1, list(analysis, "target_inputs").size());

        Map<String, Object> synthesis = records.stream()
                .filter(record -> "synthesis_attempt".equals(record.get("event")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a synthesis-attempt record"));
        assertEquals(1, number(synthesis, "generated_statement_count"));
        assertEquals(1, number(synthesis, "net_statement_delta"));
        assertEquals(Boolean.TRUE, synthesis.get("consistent"));
        assertEquals(Boolean.FALSE, synthesis.get("target_call_present"));
        assertEquals(Boolean.TRUE, synthesis.get("target_call_reached"));
        assertEquals(Boolean.TRUE, synthesis.get("exception_free"));
        assertEquals(Boolean.TRUE, synthesis.get("resolution_evaluated"));
        assertEquals(1, number(synthesis, "resolved_node_count"));
        assertEquals(number(synthesis, "node_count") - 1,
                list(synthesis, "unresolved_node_ids").size());
        assertEquals(0.25, ((Number) synthesis.get("working_goal_fitness")).doubleValue(), 0.0);
        assertEquals(Arrays.asList(false, true), synthesis.get("working_goal_directions"));
        assertEquals("unit_test", synthesis.get("outcome"));
        assertTrue(((String) synthesis.get("test_template")).contains("int"));
        assertEquals(1, list(synthesis, "statements").size());
        assertEquals(1, list(synthesis, "node_statement_mappings").size());
        assertEquals(2, list(synthesis, "candidate_actions").size());
        Map<String, Object> arrayAction = (Map<String, Object>)
                list(synthesis, "candidate_actions").get(1);
        assertEquals("students", arrayAction.get("field_name"));
        assertEquals(Collections.singletonList(0.0),
                arrayAction.get("relevant_parameter_indices"));
        assertEquals("generated_statement", arrayAction.get("outcome"));
        assertEquals(1, list(synthesis, "validation_issues").size());
        assertEquals(1, number(synthesis, "validation_issue_count"));
    }

    @Test
    public void exportsStableEligibilityAndEntryDecisions() throws Exception {
        Properties.REPORT_DIR = reportDirectory.toString();
        Properties.OBJECT_RULE_GRAPH_DIAGNOSTICS = true;
        analyze(PublicInstanceFieldExample.class, "targetM", 1);
        Map<Branch, Set<DepVariable>> branchGraphs =
                ObjectConstructionContext.branchInterestedVarsMap.get(Properties.TARGET_METHOD);
        Branch branch = branchGraphs.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a state-bearing branch"));
        PartialGraph graph = new ConstructionPathSynthesizer()
                .constructPartialComputationGraph(branch);
        TargetContext context = new TargetContext(
                PublicInstanceFieldExample.class.getName(), Properties.TARGET_METHOD, branch);

        ObjectConstructionGraphDiagnostics.recordEligibilityDecision(
                branch, context, graph, true, "eligible");
        ObjectConstructionGraphDiagnostics.recordEntryDecision(
                context, "constructed_entry", 1, false);

        List<Map<String, Object>> records =
                readRecords(ObjectConstructionGraphDiagnostics.outputFile());
        Map<String, Object> eligibility = records.stream()
                .filter(record -> "eligibility_decision".equals(record.get("event")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected eligibility decision"));
        assertEquals(Boolean.TRUE, eligibility.get("eligible"));
        assertEquals("eligible", eligibility.get("reason"));
        assertEquals(Properties.TARGET_METHOD, eligibility.get("entry_method"));
        assertTrue(number(eligibility, "node_count") > 0);

        Map<String, Object> entry = records.stream()
                .filter(record -> "entry_decision".equals(record.get("event")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected entry decision"));
        assertEquals("constructed_entry", entry.get("outcome"));
        assertEquals(1, number(entry, "clean_construction_attempts"));
        assertEquals(Boolean.FALSE, entry.get("attached_to_existing_receiver"));
    }

    @Test
    public void remainsSilentWhenDisabled() {
        Properties.REPORT_DIR = reportDirectory.toString();
        Properties.TARGET_CLASS = PublicInstanceFieldExample.class.getName();
        Properties.OBJECT_RULE_GRAPH_DIAGNOSTICS = false;

        ObjectConstructionGraphDiagnostics.recordAnalysis(null, Collections.emptySet());

        assertFalse(Files.exists(ObjectConstructionGraphDiagnostics.outputFile()));
    }

    @Test
    public void wholeClassAnalysisUsesBranchTargetContext() throws Exception {
        Properties.REPORT_DIR = reportDirectory.toString();
        Properties.OBJECT_RULE_GRAPH_DIAGNOSTICS = true;
        Properties.TARGET_CLASS = LocalDerivedArrayWholeClassExample.class.getName();
        Properties.TARGET_METHOD = "";
        Properties.APPLY_OBJECT_RULE = true;
        Properties.OBJECT_RULE_WHOLE_CLASS = true;
        Properties.CRITERION =
                new Properties.Criterion[] { Properties.Criterion.BRANCH };

        String classPath = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(classPath);
        DependencyAnalysis.analyzeClass(
                Properties.TARGET_CLASS, Collections.singletonList(classPath));
        String signature = signatureOf(
                LocalDerivedArrayWholeClassExample.class, "parse", 1);
        List<Branch> branches = BranchPool.getInstance(
                org.evosuite.TestGenerationContext.getInstance()
                        .getClassLoaderForSUT())
                .retrieveBranchesInMethod(
                        LocalDerivedArrayWholeClassExample.class.getName(), signature);

        for (Branch branch : branches) {
            assertFalse(ObjectRuleBranchSelector.getInstance().isEligible(branch));
        }

        List<Map<String, Object>> records =
                readRecords(ObjectConstructionGraphDiagnostics.outputFile());
        Map<String, Object> analysis = records.stream()
                .filter(record -> "analysis".equals(record.get("event")))
                .filter(record -> list(record, "nodes").stream()
                        .map(node -> (Map<?, ?>) node)
                        .anyMatch(node -> "PARAMETER".equals(node.get("kind"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "analysis export must retain the branch method's parameter root"));
        assertEquals(signature, analysis.get("target_method"));
    }

    @Test
    public void renderingObservedFunctionalMockDoesNotMutateLiveTemplate() throws Exception {
        Properties.REPORT_DIR = reportDirectory.toString();
        Properties.OBJECT_RULE_GRAPH_DIAGNOSTICS = true;
        TestCase test = new DefaultTestCase();
        FunctionalMockStatement mockStatement = new FunctionalMockStatement(
                test, DiagnosticService.class, GenericClassFactory.get(DiagnosticService.class));
        VariableReference mock = test.addStatement(mockStatement);
        Method consume = DiagnosticConsumer.class.getMethod("consume", DiagnosticService.class);
        test.addStatement(new MethodStatement(test,
                new GenericMethod(consume, DiagnosticConsumer.class), null,
                Collections.singletonList(mock)));
        ExecutionResult result = TestCaseExecutor.getInstance().execute(test);
        assertFalse(mockStatement.getInvocationObservationsNeedingStubs().isEmpty());
        int originalSize = test.size();

        ObjectConstructionGraphDiagnostics.recordSynthesisAttempt(null, new PartialGraph(),
                Collections.emptyMap(), test, result, 0, originalSize,
                true, true, null, Collections.emptyList(), "render_only", null);

        assertEquals(originalSize, test.size());
        assertTrue(mockStatement.getMockedMethods().isEmpty());
        assertTrue(mockStatement.getParameterReferences().isEmpty());
    }

    public interface DiagnosticService {
        String value();
    }

    public static class DiagnosticConsumer {
        public static int consume(DiagnosticService service) {
            return service.value().length();
        }
    }

    private static void analyze(Class<?> clazz, String methodName, int parameterCount) throws Exception {
        Properties.TARGET_CLASS = clazz.getName();
        Properties.TARGET_METHOD = signatureOf(clazz, methodName, parameterCount);
        Properties.APPLY_OBJECT_RULE = true;
        Properties.CRITERION = new Properties.Criterion[] { Properties.Criterion.BRANCH };

        String classPath = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(classPath);
        List<String> entries = new ArrayList<>();
        entries.add(classPath);
        DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, entries);
    }

    private static String signatureOf(Class<?> clazz, String methodName, int parameterCount) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName)
                    && method.getParameterCount() == parameterCount) {
                return method.getName() + MethodUtil.getSignature(method);
            }
        }
        throw new IllegalStateException("no matching method");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readRecords(Path sidecar) throws Exception {
        Gson gson = new Gson();
        List<Map<String, Object>> records = new ArrayList<>();
        for (String line : Files.readAllLines(sidecar, StandardCharsets.UTF_8)) {
            records.add(gson.fromJson(line, Map.class));
        }
        return records;
    }

    private static int number(Map<String, Object> record, String key) {
        return ((Number) record.get(key)).intValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> record, String key) {
        return (List<Object>) record.get(key);
    }
}
