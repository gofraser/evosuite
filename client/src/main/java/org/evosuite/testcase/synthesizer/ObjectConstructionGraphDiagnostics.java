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
import org.evosuite.Properties;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.ConstructionPath;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.EntityWithParametersStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.synthesizer.var.DepVariableWrapper;
import org.evosuite.testcase.synthesizer.var.VarRelevance;
import org.evosuite.testcase.variable.VariableReference;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Diagnostic-only JSONL export for EvoObj graph analysis and synthesis.
 *
 * <p>The exporter is deliberately outside the statistics channel: graph nodes and edges are
 * structured, variable-sized data. It is disabled by default and every operation is best-effort,
 * so a permissions, serialization, or I/O failure can never change a generated test.</p>
 */
public final class ObjectConstructionGraphDiagnostics {

    private static final Logger logger = LoggerFactory.getLogger(ObjectConstructionGraphDiagnostics.class);
    private static final Gson GSON = new Gson();
    private static final Set<Path> initializedFiles = new LinkedHashSet<>();
    private static boolean warned;

    private ObjectConstructionGraphDiagnostics() {
        // utility class
    }

    /** Clear per-run file state. The next record truncates its target sidecar. */
    public static synchronized void reset() {
        initializedFiles.clear();
        warned = false;
    }

    /** Record the canonical, valid-root-filtered graph for one analyzed branch. */
    public static void recordAnalysis(Branch branch, Set<DepVariable> dependentVariables) {
        if (!Properties.OBJECT_RULE_GRAPH_DIAGNOSTICS) {
            return;
        }
        TargetContext previous = TargetContext.getActive();
        try {
            if ((Properties.TARGET_METHOD == null || Properties.TARGET_METHOD.isEmpty())
                    && previous == null && branch != null) {
                TargetContext diagnosticContext =
                        TargetContext.forBranchWithoutStatistics(branch);
                if (diagnosticContext != null) {
                    TargetContext.setActive(diagnosticContext);
                }
            }
            // Keep raw-path health counters, but export the same filtered PartialGraph that
            // graph-to-code synthesis consumes. This prevents invalid callee-local roots from
            // being presented as if they were part of the EvoObj OCG.
            GraphSnapshot raw = snapshotFromDependentVariables(branch, dependentVariables);
            PartialGraph canonical = new ConstructionPathSynthesizer()
                    .constructPartialComputationGraph(branch, dependentVariables);
            GraphSnapshot graph = snapshotFromPartialGraph(canonical, null, false);
            Map<String, Object> record = baseRecord("analysis", branch);
            record.put("graph_form", "canonical_partial_graph");
            record.putAll(graph.asMap());
            // These describe paths discarded before the canonical graph was built. Keep them
            // explicitly separate from the canonical graph's own health counters.
            record.put("raw_invalid_root_path_count", raw.invalidRootPathCount);
            record.put("raw_unrooted_dependent_variable_count",
                    raw.unrootedDependentVariableCount);
            append(record);
        } catch (Throwable failure) {
            warnOnce(failure);
        } finally {
            if (previous == null) {
                TargetContext.clearActive();
            } else {
                TargetContext.setActive(previous);
            }
        }
    }

    /**
     * Record the cached eligibility verdict which controls whether a branch may enter synthesis.
     * Unlike an analysis record, this record always has a stable terminal reason, including when
     * no graph could be formed because the containing method is not a callable entry.
     */
    public static void recordEligibilityDecision(Branch branch, TargetContext context,
            PartialGraph graph, boolean eligible, String reason) {
        if (!Properties.OBJECT_RULE_GRAPH_DIAGNOSTICS) {
            return;
        }
        try {
            Map<String, Object> record = baseRecord("eligibility_decision", branch);
            record.put("eligible", eligible);
            record.put("reason", reason == null ? "unknown" : reason);
            record.put("entry_class", context == null ? null : context.getClassName());
            record.put("entry_method", context == null ? null : context.getMethodSignature());
            record.put("constructor_entry", context != null && context.isConstructorEntry());
            record.put("graph_form", "canonical_partial_graph");
            record.putAll(snapshotFromPartialGraph(graph, null, false).asMap());
            append(record);
        } catch (Throwable failure) {
            warnOnce(failure);
        }
    }

    /** Record completion of the pre-search eligibility pass, including the graphless case. */
    public static void recordPreparationSummary(
            int branchCount, int eligibleCount, boolean inert, long elapsedMillis) {
        if (!Properties.OBJECT_RULE_GRAPH_DIAGNOSTICS) {
            return;
        }
        try {
            Map<String, Object> record = baseRecord("preparation_summary", null);
            record.put("branch_count", Math.max(0, branchCount));
            record.put("eligible_count", Math.max(0, eligibleCount));
            record.put("inert", inert);
            record.put("elapsed_millis", Math.max(0L, elapsedMillis));
            record.put("phase", "initialization_before_search_budget");
            record.put("reason", branchCount == 0
                    ? "no_branch_search_goals"
                    : (inert ? "no_eligible_graphs" : "eligible_graphs_available"));
            append(record);
        } catch (Throwable failure) {
            warnOnce(failure);
        }
    }

    /** Record construction and attachment of the callable entry immediately preceding synthesis. */
    public static void recordEntryDecision(TargetContext context, String outcome,
            int cleanConstructionAttempts, boolean attachedToExistingReceiver) {
        if (!Properties.OBJECT_RULE_GRAPH_DIAGNOSTICS) {
            return;
        }
        try {
            Branch branch = context == null ? null : context.getBranch();
            Map<String, Object> record = baseRecord("entry_decision", branch);
            record.put("entry_class", context == null ? null : context.getClassName());
            record.put("entry_method", context == null ? null : context.getMethodSignature());
            record.put("constructor_entry", context != null && context.isConstructorEntry());
            record.put("outcome", outcome == null ? "unknown" : outcome);
            record.put("clean_construction_attempts", Math.max(0, cleanConstructionAttempts));
            record.put("attached_to_existing_receiver", attachedToExistingReceiver);
            append(record);
        } catch (Throwable failure) {
            warnOnce(failure);
        }
    }

    /**
     * Record one graph-to-code synthesis attempt. Public so the factory can remain in its existing
     * package and a deterministic schema test can exercise the exporter without a full search.
     */
    public static void recordSynthesisAttempt(Branch branch, PartialGraph graph,
            Map<DepVariableWrapper, VarRelevance> graph2CodeMap, TestCase test,
            ExecutionResult result, int originalStatementCount, Integer generatedStatementCount,
            Boolean consistent, boolean targetCallReached, Double workingGoalFitness,
            Collection<Boolean> goalDirections,
            String outcome, Throwable failure) {
        if (!Properties.OBJECT_RULE_GRAPH_DIAGNOSTICS) {
            return;
        }
        try {
            GraphSnapshot snapshot = snapshotFromPartialGraph(graph, graph2CodeMap, true);
            Map<String, Object> record = baseRecord("synthesis_attempt", branch);
            record.put("graph_form", "canonical_partial_graph");
            record.putAll(snapshot.asMap());
            int finalSize = test == null ? 0 : test.size();
            record.put("original_statement_count", originalStatementCount);
            record.put("final_statement_count", finalSize);
            record.put("generated_statement_count", generatedStatementCount == null
                    ? Math.max(0, finalSize - originalStatementCount) : generatedStatementCount);
            record.put("net_statement_delta", finalSize - originalStatementCount);
            record.put("consistent", consistent);
            record.put("target_call_present", hasTargetCall(test));
            record.put("target_call_reached", targetCallReached);
            record.put("exception_free", result == null ? null : result.noThrownExceptions());
            record.put("timed_out", result == null ? null : result.hasTimeout());
            record.put("executed_statements", result == null ? null : result.getExecutedStatements());
            Integer exceptionPosition = result == null
                    ? null : result.getFirstPositionOfThrownException();
            Throwable executionFailure = exceptionPosition == null || result == null
                    ? null : result.getExceptionThrownAtPosition(exceptionPosition);
            record.put("exception_position", exceptionPosition);
            record.put("exception_type", executionFailure == null
                    ? null : executionFailure.getClass().getName());
            record.put("exception_message", executionFailure == null
                    ? null : executionFailure.getMessage());
            record.put("virtual_setup_present", hasVirtualSetup(test));
            record.put("functional_mock_setups", functionalMockSetups(test));
            record.put("working_goal_fitness", finiteOrNull(workingGoalFitness));
            record.put("working_goal_directions", goalDirections == null
                    ? Collections.emptyList() : new ArrayList<>(goalDirections));
            record.put("outcome", outcome == null ? "unknown" : outcome);
            record.put("failure_type", failure == null ? null : failure.getClass().getName());
            record.put("failure_message", failure == null ? null : failure.getMessage());
            record.put("test_template", safeTestCode(test));
            record.put("statements", statementRecords(test));
            record.put("node_statement_mappings",
                    nodeStatementMappings(snapshot, graph2CodeMap, test));
            record.put("candidate_actions", ObjectConstructionActionTrace.candidateActions());
            record.put("validation_issues", ObjectConstructionActionTrace.validationIssues());
            record.put("validation_issue_count",
                    ObjectConstructionActionTrace.validationIssues().size());
            append(record);
        } catch (Throwable diagnosticFailure) {
            warnOnce(diagnosticFailure);
        }
    }

    private static boolean hasVirtualSetup(TestCase test) {
        if (test == null) {
            return false;
        }
        for (int position = 0; position < test.size(); position++) {
            Statement statement = test.getStatement(position);
            if (statement instanceof MethodStatement) {
                java.lang.reflect.Method method =
                        ((MethodStatement) statement).getMethod().getMethod();
                if ("org.evosuite.runtime.testdata.FileSystemHandling".equals(
                        method.getDeclaringClass().getName())
                        && "appendStringToFile".equals(method.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasTargetCall(TestCase test) {
        if (test == null) {
            return false;
        }
        String targetClass = TargetContext.activeClassName();
        String targetMethod = TargetContext.activeMethodSignature();
        if (targetMethod == null || targetMethod.isEmpty()) {
            return false;
        }
        if (TargetContext.activeConstructorEntry()) {
            for (int position = 0; position < test.size(); position++) {
                Statement statement = test.getStatement(position);
                if (statement instanceof ConstructorStatement) {
                    ConstructorStatement constructor = (ConstructorStatement) statement;
                    if (targetMethod.equals(constructor.getConstructor().getNameWithDescriptor())
                            && (targetClass == null || targetClass.equals(
                            constructor.getConstructor().getDeclaringClass().getName()))) {
                        return true;
                    }
                }
            }
            return false;
        }
        return test.findMethodCallStatement(targetClass, targetMethod) != null;
    }

    private static List<Map<String, Object>> functionalMockSetups(TestCase test) {
        List<Map<String, Object>> setups = new ArrayList<>();
        if (test == null) {
            return setups;
        }
        for (int position = 0; position < test.size(); position++) {
            Statement statement = test.getStatement(position);
            if (!(statement instanceof FunctionalMockStatement)) {
                continue;
            }
            FunctionalMockStatement mock = (FunctionalMockStatement) statement;
            Map<String, Object> setup = new LinkedHashMap<>();
            setup.put("position", position);
            setup.put("mock_type", mock.getTargetClass().getName());
            List<Map<String, Object>> stubs = new ArrayList<>();
            for (org.evosuite.testcase.fm.MethodDescriptor descriptor : mock.getMockedMethods()) {
                Map<String, Object> stub = new LinkedHashMap<>();
                stub.put("method_id", descriptor.getID());
                stub.put("method", descriptor.getMethodName());
                stub.put("return_type", descriptor.getMethod().getReturnType().getTypeName());
                List<VariableReference> values = mock.getParameters(descriptor.getID());
                stub.put("return_value_count", values == null ? 0 : values.size());
                List<Map<String, Object>> returnValues = new ArrayList<>();
                if (values != null) {
                    for (VariableReference value : values) {
                        Map<String, Object> returnValue = new LinkedHashMap<>();
                        returnValue.put("definition_position",
                                ObjectConstructionActionTrace.definitionPosition(test, value));
                        returnValue.put("type", value == null ? null : value.getType().getTypeName());
                        returnValue.put("assignable", value != null
                                && value.isAssignableTo(descriptor.getMethod().getGenericReturnType()));
                        returnValues.add(returnValue);
                    }
                }
                stub.put("return_values", returnValues);
                stubs.add(stub);
            }
            setup.put("stubs", stubs);
            setup.put("stub_count", stubs.size());
            setups.add(setup);
        }
        return setups;
    }

    private static Map<String, Object> baseRecord(String event, Branch branch) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("event", event);
        record.put("target_class", Properties.TARGET_CLASS);
        String targetMethod = TargetContext.activeMethodSignature();
        record.put("target_method", (targetMethod == null || targetMethod.isEmpty()) && branch != null
                ? branch.getMethodName() : targetMethod);
        record.put("branch_id", branch == null ? null : branch.getActualBranchId());
        record.put("branch_class", branch == null ? null : branch.getClassName());
        record.put("branch_method", branch == null ? null : branch.getMethodName());
        record.put("branch_line", branch == null || branch.getInstruction() == null
                ? null : branch.getInstruction().getLineNumber());
        record.put("switch_branch", branch != null && branch.isSwitchCaseBranch());
        record.put("switch_case_value", branch == null || !branch.isSwitchCaseBranch()
                ? null : branch.getTargetCaseValue());
        record.put("target_inputs", targetInputs((targetMethod == null || targetMethod.isEmpty())
                && branch != null ? branch.getMethodName() : targetMethod));
        return record;
    }

    private static List<Map<String, Object>> targetInputs(String methodSignature) {
        List<Map<String, Object>> inputs = new ArrayList<>();
        if (methodSignature == null) {
            return inputs;
        }
        int descriptorStart = methodSignature.indexOf('(');
        if (descriptorStart < 0) {
            return inputs;
        }
        try {
            Type[] argumentTypes = Type.getArgumentTypes(methodSignature.substring(descriptorStart));
            for (int index = 0; index < argumentTypes.length; index++) {
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("index", index);
                input.put("name", "arg" + index);
                input.put("descriptor", argumentTypes[index].getDescriptor());
                input.put("type", argumentTypes[index].getClassName());
                inputs.add(input);
            }
        } catch (IllegalArgumentException ignored) {
            // A malformed/absent descriptor must never affect generation; the empty input list
            // still makes the schema deterministic.
        }
        return inputs;
    }

    private static GraphSnapshot snapshotFromDependentVariables(Branch branch, Set<DepVariable> leaves) {
        GraphSnapshot snapshot = new GraphSnapshot();
        if (leaves == null) {
            return snapshot;
        }
        for (DepVariable leaf : leaves) {
            if (leaf == null) {
                continue;
            }
            snapshot.dependentVariableIds.add(snapshot.nodeId(leaf));
            Map<DepVariable, ArrayList<ConstructionPath>> roots = leaf.getRootVars();
            boolean hasValidRoot = false;
            for (Map.Entry<DepVariable, ArrayList<ConstructionPath>> rootEntry : roots.entrySet()) {
                DepVariable root = rootEntry.getKey();
                List<ConstructionPath> paths = rootEntry.getValue();
                if (isValidRoot(root, branch == null ? Properties.TARGET_CLASS : branch.getClassName())
                        && paths != null && !paths.isEmpty()) {
                    hasValidRoot = true;
                } else {
                    snapshot.invalidRootPathCount += paths == null ? 1 : Math.max(1, paths.size());
                }
                if (paths == null) {
                    continue;
                }
                for (ConstructionPath path : paths) {
                    List<DepVariable> variables = path.getPath(); // leaf -> root in this analysis API
                    for (DepVariable variable : variables) {
                        snapshot.nodeId(variable);
                    }
                    for (int i = 0; i + 1 < variables.size(); i++) {
                        int child = snapshot.nodeId(variables.get(i));
                        int parent = snapshot.nodeId(variables.get(i + 1));
                        Integer operand = i < path.getPosition().size() ? path.getPosition().get(i) : null;
                        snapshot.addEdge(parent, child, operand);
                    }
                }
            }
            if (!hasValidRoot) {
                snapshot.unrootedDependentVariableCount++;
            }
        }
        snapshot.finish();
        return snapshot;
    }

    private static GraphSnapshot snapshotFromPartialGraph(PartialGraph graph,
            Map<DepVariableWrapper, VarRelevance> graph2CodeMap, boolean resolutionEvaluated) {
        GraphSnapshot snapshot = new GraphSnapshot();
        snapshot.resolutionEvaluated = resolutionEvaluated;
        if (graph == null) {
            return snapshot;
        }
        for (DepVariableWrapper wrapper : graph.getNodes()) {
            snapshot.nodeId(wrapper.var);
        }
        for (DepVariableWrapper parent : graph.getNodes()) {
            int parentId = snapshot.nodeId(parent.var);
            for (DepVariableWrapper child : parent.children) {
                snapshot.addEdge(parentId, snapshot.nodeId(child.var), parent.findRelationPosition(child));
            }
        }
        for (DepVariableWrapper wrapper : graph.getNodes()) {
            int id = snapshot.nodeId(wrapper.var);
            if (wrapper.children.isEmpty()) {
                snapshot.dependentVariableIds.add(id);
            }
            if (resolutionEvaluated) {
                VarRelevance relevance = graph2CodeMap == null ? null : graph2CodeMap.get(wrapper);
                if (!hasVariables(relevance)) {
                    snapshot.unresolvedNodeIds.add(id);
                }
            }
        }
        snapshot.finish();
        return snapshot;
    }

    private static String safeTestCode(TestCase test) {
        if (test == null) {
            return null;
        }
        try {
            // Functional-mock rendering calls updateMockedMethods()/fillWithNullRefs() to make
            // stale tests printable. That operation is intentionally mutating. Diagnostics must
            // never add placeholder stubs to the live template that will subsequently enter the
            // search, so render an isolated copy.
            return test.clone().toCode();
        } catch (Throwable failure) {
            return "<unable to render test template: " + failure.getClass().getName() + ">";
        }
    }

    private static List<Map<String, Object>> statementRecords(TestCase test) {
        List<Map<String, Object>> records = new ArrayList<>();
        if (test == null) {
            return records;
        }
        List<String> renderedCodes = renderedStatementCodes(test);
        for (int position = 0; position < test.size(); position++) {
            Statement statement = test.getStatement(position);
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("position", position);
            record.put("statement_type", statement.getClass().getSimpleName());
            record.put("code", renderedCodes.get(position));
            record.put("return_definition_position", position);
            if (statement instanceof MethodStatement) {
                record.put("receiver_definition_position",
                        ObjectConstructionActionTrace.definitionPosition(test,
                                ((MethodStatement) statement).getCallee()));
            }
            if (statement instanceof EntityWithParametersStatement) {
                List<Integer> argumentPositions = new ArrayList<>();
                for (VariableReference parameter :
                        ((EntityWithParametersStatement) statement).getParameterReferences()) {
                    argumentPositions.add(ObjectConstructionActionTrace.definitionPosition(test, parameter));
                }
                record.put("argument_definition_positions", argumentPositions);
            }
            record.put("object_rule_slot", statement instanceof PrimitiveStatement
                    && ((PrimitiveStatement<?>) statement).isObjectRuleSlot());
            records.add(record);
        }
        return records;
    }

    private static List<Map<String, Object>> nodeStatementMappings(GraphSnapshot snapshot,
            Map<DepVariableWrapper, VarRelevance> graph2CodeMap, TestCase test) {
        List<Map<String, Object>> mappings = new ArrayList<>();
        if (graph2CodeMap == null || graph2CodeMap.isEmpty()) {
            return mappings;
        }
        for (Map.Entry<DepVariableWrapper, VarRelevance> entry : graph2CodeMap.entrySet()) {
            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("node_id", snapshot.nodeId(entry.getKey().var));
            mapping.put("matched", variableMappings(entry.getValue() == null
                    ? null : entry.getValue().matchedVars, test));
            mapping.put("influential", variableMappings(entry.getValue() == null
                    ? null : entry.getValue().influentialVars, test));
            mappings.add(mapping);
        }
        return mappings;
    }

    private static List<Map<String, Object>> variableMappings(List<VariableReference> variables,
            TestCase test) {
        List<Map<String, Object>> mappings = new ArrayList<>();
        if (variables == null) {
            return mappings;
        }
        List<String> renderedCodes = renderedStatementCodes(test);
        Set<Integer> positions = new LinkedHashSet<>();
        for (VariableReference variable : variables) {
            int position = safeStatementPosition(variable, test);
            if (!positions.add(position)) {
                continue;
            }
            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("position", position);
            mapping.put("variable_type", variable == null ? null : variable.getType().getTypeName());
            mapping.put("code", position >= 0 && test != null && position < test.size()
                    ? renderedCodes.get(position) : null);
            mappings.add(mapping);
        }
        return mappings;
    }

    /** Render statement text in the TestCase context so variable suffixes match test_template. */
    private static List<String> renderedStatementCodes(TestCase test) {
        List<String> fallback = new ArrayList<>();
        if (test == null) {
            return fallback;
        }
        try {
            TestCase renderingCopy = test.clone();
            for (int position = 0; position < renderingCopy.size(); position++) {
                fallback.add(safeStatementCode(renderingCopy.getStatement(position)));
            }
            List<String> lines = new ArrayList<>();
            for (String line : renderingCopy.toCode().split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    lines.add(trimmed);
                }
            }
            return lines.size() == test.size() ? lines : fallback;
        } catch (Throwable ignored) {
            // Even statement.getCode() can update functional-mock metadata, so do not construct
            // a fallback from the live test when cloning itself fails.
            return fallback;
        }
    }

    private static int safeStatementPosition(VariableReference variable, TestCase test) {
        if (variable == null || test == null) {
            return -1;
        }
        for (int position = 0; position < test.size(); position++) {
            if (test.getStatement(position).getReturnValue() == variable) {
                return position;
            }
        }
        for (int position = 0; position < test.size(); position++) {
            VariableReference candidate = test.getStatement(position).getReturnValue();
            if (candidate != null && candidate.equals(variable)) {
                return position;
            }
        }
        return -1;
    }

    private static String safeStatementCode(Statement statement) {
        try {
            return statement == null ? null : statement.getCode();
        } catch (Throwable failure) {
            return "<unable to render statement: " + failure.getClass().getName() + ">";
        }
    }

    private static boolean hasVariables(VarRelevance relevance) {
        return relevance != null
                && (notEmpty(relevance.influentialVars) || notEmpty(relevance.matchedVars));
    }

    private static boolean notEmpty(List<VariableReference> variables) {
        return variables != null && !variables.isEmpty();
    }

    private static boolean isValidRoot(DepVariable root, String targetClass) {
        if (root == null || root.getInstruction() == null) {
            return false;
        }
        if (root.isStaticField()) {
            return true;
        }
        if (root.getType() != DepVariable.THIS && root.getType() != DepVariable.PARAMETER) {
            return false;
        }
        return targetClass != null && targetClass.equals(root.getInstruction().getClassName());
    }

    private static Double finiteOrNull(Double value) {
        return value != null && !value.isNaN() && !value.isInfinite() ? value : null;
    }

    private static synchronized void append(Map<String, Object> record) throws IOException {
        Path file = outputFile();
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        boolean first = initializedFiles.add(file.toAbsolutePath().normalize());
        if (first) {
            Files.write(file, (GSON.toJson(record) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } else {
            Files.write(file, (GSON.toJson(record) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    static Path outputFile() {
        String target = Properties.TARGET_CLASS == null || Properties.TARGET_CLASS.isEmpty()
                ? "unknown" : Properties.TARGET_CLASS;
        return Paths.get(Properties.REPORT_DIR,
                "object_graph_diagnostics_" + target.replaceAll("[^A-Za-z0-9._-]", "_") + ".jsonl");
    }

    private static synchronized void warnOnce(Throwable failure) {
        if (!warned) {
            warned = true;
            logger.warn("Unable to write object-construction graph diagnostics; generation continues", failure);
        } else {
            logger.debug("Unable to write object-construction graph diagnostics", failure);
        }
    }

    /** Mutable graph snapshot used only while constructing a JSON record. */
    private static final class GraphSnapshot {
        private final Map<DepVariable, Integer> ids = new LinkedHashMap<>();
        private final List<Map<String, Object>> nodes = new ArrayList<>();
        private final List<Map<String, Object>> edges = new ArrayList<>();
        private final Set<String> edgeKeys = new LinkedHashSet<>();
        private final List<Integer> dependentVariableIds = new ArrayList<>();
        private final List<Integer> unresolvedNodeIds = new ArrayList<>();
        private int invalidRootPathCount;
        private int unrootedDependentVariableCount;
        private boolean cyclic;
        private int maxDepth;
        private int rootCount;
        private int leafCount;
        private boolean resolutionEvaluated;

        private int nodeId(DepVariable variable) {
            Integer existing = ids.get(variable);
            if (existing != null) {
                return existing;
            }
            int id = ids.size();
            ids.put(variable, id);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", id);
            node.put("kind", variable == null ? "UNKNOWN" : variable.getTypeString());
            node.put("class", variable == null ? null : variable.getClassName());
            node.put("method", variable == null || variable.getInstruction() == null
                    ? null : variable.getInstruction().getMethodName());
            node.put("instruction_id", variable == null || variable.getInstruction() == null
                    ? null : variable.getInstruction().getInstructionId());
            node.put("line", variable == null || variable.getInstruction() == null
                    ? null : variable.getInstruction().getLineNumber());
            node.put("name", variable == null ? null : variable.getName());
            node.put("parameter_index", variable != null && variable.isParameter()
                    ? variable.getParamOrder() - 1 : null);
            if (variable != null && variable.getInstruction() != null
                    && variable.getInstruction().getASMNode() instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) variable.getInstruction().getASMNode();
                node.put("field_owner", field.owner.replace('/', '.'));
                node.put("field_name", field.name);
                node.put("field_descriptor", field.desc);
            }
            if (variable != null && variable.getInstruction() != null
                    && variable.getInstruction().getASMNode() instanceof MethodInsnNode) {
                MethodInsnNode call =
                        (MethodInsnNode) variable.getInstruction().getASMNode();
                node.put("call_owner", call.owner.replace('/', '.'));
                node.put("call_method", call.name);
                node.put("call_descriptor", call.desc);
                node.put("constant_arguments", constantArguments(variable));
                if (variable.getName() == null || "$unknown".equals(variable.getName())) {
                    node.put("name", call.owner.replace('/', '.') + "." + call.name + call.desc);
                }
            }
            nodes.add(node);
            return id;
        }

        private static Map<Integer, Object> constantArguments(DepVariable variable) {
            Map<Integer, Object> constants = new LinkedHashMap<>();
            try {
                int count = variable.getInstruction().getCalledMethodsArgumentCount();
                for (int argument = 0; argument < count; argument++) {
                    org.evosuite.graphs.cfg.BytecodeInstruction source =
                            variable.getInstruction().getSourceOfStackInstruction(
                                    count - argument - 1);
                    if (source != null && source.getASMNode() instanceof LdcInsnNode) {
                        constants.put(argument, ((LdcInsnNode) source.getASMNode()).cst);
                    }
                }
            } catch (RuntimeException ignored) {
                // Diagnostics must remain observational.
            }
            return constants;
        }

        private void addEdge(int from, int to, Integer operand) {
            String key = from + ":" + to + ":" + operand;
            if (!edgeKeys.add(key)) {
                return;
            }
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("from", from);
            edge.put("to", to);
            edge.put("operand", operand);
            edges.add(edge);
        }

        private void finish() {
            Map<Integer, List<Integer>> outgoing = new LinkedHashMap<>();
            int[] indegree = new int[nodes.size()];
            for (int i = 0; i < nodes.size(); i++) {
                outgoing.put(i, new ArrayList<>());
            }
            for (Map<String, Object> edge : edges) {
                int from = (Integer) edge.get("from");
                int to = (Integer) edge.get("to");
                outgoing.get(from).add(to);
                indegree[to]++;
            }
            for (int i = 0; i < nodes.size(); i++) {
                if (indegree[i] == 0) {
                    rootCount++;
                }
                if (outgoing.get(i).isEmpty()) {
                    leafCount++;
                }
            }
            cyclic = hasCycle(outgoing);
            maxDepth = maxDepth(outgoing, indegree);
        }

        private Map<String, Object> asMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("node_count", nodes.size());
            values.put("edge_count", edges.size());
            values.put("root_count", rootCount);
            values.put("leaf_count", leafCount);
            values.put("max_depth", maxDepth);
            values.put("cyclic", cyclic);
            values.put("invalid_root_path_count", invalidRootPathCount);
            values.put("unrooted_dependent_variable_count", unrootedDependentVariableCount);
            values.put("dependent_variable_ids", dependentVariableIds);
            values.put("resolution_evaluated", resolutionEvaluated);
            values.put("unresolved_node_ids", unresolvedNodeIds);
            values.put("resolved_node_count",
                    resolutionEvaluated ? nodes.size() - unresolvedNodeIds.size() : null);
            values.put("nodes", nodes);
            values.put("edges", edges);
            return values;
        }

        private static boolean hasCycle(Map<Integer, List<Integer>> outgoing) {
            int[] colors = new int[outgoing.size()];
            for (int node = 0; node < colors.length; node++) {
                if (cycleDfs(node, outgoing, colors)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean cycleDfs(int node, Map<Integer, List<Integer>> outgoing, int[] colors) {
            if (colors[node] == 1) {
                return true;
            }
            if (colors[node] == 2) {
                return false;
            }
            colors[node] = 1;
            for (int child : outgoing.get(node)) {
                if (cycleDfs(child, outgoing, colors)) {
                    return true;
                }
            }
            colors[node] = 2;
            return false;
        }

        private static int maxDepth(Map<Integer, List<Integer>> outgoing, int[] indegree) {
            if (outgoing.isEmpty()) {
                return 0;
            }
            int[] depth = new int[outgoing.size()];
            Deque<Integer> queue = new ArrayDeque<>();
            for (int i = 0; i < indegree.length; i++) {
                if (indegree[i] == 0) {
                    depth[i] = 1;
                    queue.add(i);
                }
            }
            int max = 0;
            int visited = 0;
            while (!queue.isEmpty()) {
                int node = queue.remove();
                visited++;
                max = Math.max(max, depth[node]);
                for (int child : outgoing.get(node)) {
                    depth[child] = Math.max(depth[child], depth[node] + 1);
                    if (--indegree[child] == 0) {
                        queue.add(child);
                    }
                }
            }
            return visited == outgoing.size() ? max : 0;
        }
    }
}
