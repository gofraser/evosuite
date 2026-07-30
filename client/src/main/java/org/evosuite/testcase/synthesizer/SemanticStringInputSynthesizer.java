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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.EntityWithParametersStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.synthesizer.var.DepVariableWrapper;
import org.evosuite.testcase.variable.VariableReference;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * Inverts selected semantic String pipelines into useful target inputs.
 *
 * <p>A graph rooted in a String often describes observations made after the target method starts:
 * parsing JSON, splitting a CIDR, matching an address, and so on. Replaying those calls in the test
 * creates disconnected values and cannot change the target execution. For supported, deterministic
 * formats we instead materialize a representative source String and pass it directly to the target
 * entry. The derived parser/array graph is then considered satisfied and must not be replayed.</p>
 */
final class SemanticStringInputSynthesizer {

    enum Kind {
        JSON,
        CIDR,
        IP_ADDRESS
    }

    private SemanticStringInputSynthesizer() {
        // utility class
    }

    static boolean supports(PartialGraph graph) {
        return plan(graph, graph == null ? null : graph.getBranch()) != null;
    }

    /**
     * Apply the semantic input plan. A successful result means the complete observation graph was
     * handled and generic graph-to-code replay should be skipped.
     */
    static boolean apply(TestCase test, PartialGraph graph, Branch branch) {
        Plan plan = plan(graph, branch);
        if (plan == null || test == null) {
            return false;
        }
        EntityWithParametersStatement target = test.findTargetExecutableStatement();
        int targetPosition = statementPosition(test, target);
        if (target == null || targetPosition < 0) {
            return false;
        }

        boolean changed = false;
        for (DepVariableWrapper root : plan.stringRoots) {
            int argument = root.var.getParamOrder() - 1;
            if (argument < 0 || argument >= target.getParameterReferences().size()) {
                continue;
            }
            String value = exemplar(plan.kind, plan.fieldNames);
            StringPrimitiveStatement input = new StringPrimitiveStatement(test, value);
            VariableReference reference = test.addStatement(input, targetPosition++);
            target.replaceParameterReference(reference, argument);
            int nodeId = root.var.getInstruction() == null ? -1
                    : root.var.getInstruction().getInstructionId();
            ObjectConstructionActionTrace.setupAction("semantic_string_input", nodeId,
                    plan.kind.name().toLowerCase() + ":" + value,
                    ObjectConstructionActionTrace.definitionPosition(test, reference));
            changed = true;
        }
        return changed;
    }

    static Kind classify(Set<String> calls, Set<String> fields, String targetMethod) {
        if (containsOwner(calls, "com/fasterxml/jackson/")
                && (containsCall(calls, ".createParser")
                        || containsCall(calls, ".readTree")
                        || containsCall(calls, ".nextToken"))) {
            return Kind.JSON;
        }

        boolean addressPipeline = containsCall(calls, "java/net/InetAddress.")
                || containsCall(calls, "MockInetAddress.")
                || fields.contains("IPv4_REGEX") || fields.contains("IPv6_REGEX");
        if (targetMethod != null && targetMethod.startsWith("cidr(")
                && containsCall(calls, "java/lang/String.split")) {
            return Kind.CIDR;
        }
        if (addressPipeline && targetMethod != null
                && (targetMethod.startsWith("ipTo")
                        || targetMethod.startsWith("isIPv4OrIPv6("))) {
            return Kind.IP_ADDRESS;
        }
        return null;
    }

    private static Plan plan(PartialGraph graph, Branch branch) {
        if (graph == null) {
            return null;
        }
        List<DepVariableWrapper> roots = new ArrayList<>();
        String targetMethod = null;
        for (DepVariableWrapper root : graph.getTopLayer()) {
            if (isTargetStringParameter(root)) {
                roots.add(root);
                targetMethod = root.var.getInstruction().getMethodName();
            }
        }
        if (roots.isEmpty()) {
            return null;
        }

        Set<String> calls = new LinkedHashSet<>();
        Set<String> fields = new LinkedHashSet<>();
        for (DepVariableWrapper node : graph.getNodes()) {
            if (node == null || node.var == null || node.var.getInstruction() == null) {
                continue;
            }
            if (node.var.getInstruction().getASMNode() instanceof MethodInsnNode) {
                MethodInsnNode call =
                        (MethodInsnNode) node.var.getInstruction().getASMNode();
                calls.add(call.owner + "." + call.name + call.desc);
            } else if (node.var.getInstruction().getASMNode() instanceof FieldInsnNode) {
                fields.add(((FieldInsnNode) node.var.getInstruction().getASMNode()).name);
            }
        }
        Kind kind = classify(calls, fields, targetMethod);
        return kind == null ? null : new Plan(kind, roots, fields);
    }

    private static boolean isTargetStringParameter(DepVariableWrapper root) {
        if (root == null || root.var == null || !root.var.isParameter()
                || root.var.getInstruction() == null
                || !TargetContext.activeClassName().equals(
                        root.var.getInstruction().getClassName())
                || !TargetContext.activeMethodSignature().equals(
                        root.var.getInstruction().getMethodName())) {
            return false;
        }
        try {
            String method = root.var.getInstruction().getMethodName();
            Type[] arguments = Type.getArgumentTypes(method.substring(method.indexOf('(')));
            int index = root.var.getParamOrder() - 1;
            return index >= 0 && index < arguments.length
                    && Type.getType(String.class).equals(arguments[index]);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String exemplar(Kind kind, Set<String> fields) {
        switch (kind) {
        case JSON:
            if (fields.contains("VALUE_STRING")) {
                return "{\"value\":\"text\"}";
            }
            if (fields.contains("START_ARRAY") || fields.contains("END_ARRAY")) {
                return "{\"value\":[]}";
            }
            if (fields.contains("START_OBJECT")) {
                return "{\"value\":{}}";
            }
            if (fields.contains("END_OBJECT")) {
                return "{}";
            }
            return "{\"value\":0}";
        case CIDR:
            // Start with a fully valid value. Its String slot remains mutable, so ordinary search
            // can subsequently explore malformed masks without templates failing at the parser.
            return "10.0.0.1/24";
        case IP_ADDRESS:
        default:
            return "10.0.0.1";
        }
    }

    private static boolean containsOwner(Set<String> calls, String owner) {
        for (String call : calls) {
            if (call.startsWith(owner)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsCall(Set<String> calls, String fragment) {
        for (String call : calls) {
            if (call.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static int statementPosition(TestCase test, Object statement) {
        for (int i = 0; i < test.size(); i++) {
            if (test.getStatement(i) == statement) {
                return i;
            }
        }
        return -1;
    }

    private static final class Plan {
        private final Kind kind;
        private final List<DepVariableWrapper> stringRoots;
        private final Set<String> fieldNames;

        private Plan(Kind kind, List<DepVariableWrapper> stringRoots, Set<String> fieldNames) {
            this.kind = kind;
            this.stringRoots = stringRoots;
            this.fieldNames = fieldNames;
        }
    }
}
