/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.testcase.synthesizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.synthesizer.SemanticStringInputSynthesizer.Kind;
import org.evosuite.testcase.synthesizer.var.DepVariableWrapper;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodInsnNode;

class SemanticStringInputSynthesizerTest {

    @AfterEach
    void clearContext() {
        TargetContext.clearActive();
    }

    @Test
    void recognizesOnlySupportedReversibleStringPipelines() {
        assertEquals(Kind.JSON, SemanticStringInputSynthesizer.classify(
                calls("com/fasterxml/jackson/core/JsonFactory.createParser"
                        + "(Ljava/lang/String;)Lcom/fasterxml/jackson/core/JsonParser;"),
                Collections.emptySet(), "parse(Ljava/lang/String;)V"));

        assertEquals(Kind.CIDR, SemanticStringInputSynthesizer.classify(
                calls("java/lang/String.split(Ljava/lang/String;)[Ljava/lang/String;"),
                Collections.emptySet(), "cidr(Ljava/lang/String;)Ljava/lang/Object;"));

        assertEquals(Kind.IP_ADDRESS, SemanticStringInputSynthesizer.classify(
                calls("java/util/regex/Pattern.matcher"
                        + "(Ljava/lang/CharSequence;)Ljava/util/regex/Matcher;"),
                new LinkedHashSet<>(Arrays.asList("IPv4_REGEX")),
                "ipToRangeIfPossible(Ljava/lang/String;)Ljava/lang/Object;"));

        assertEquals(null, SemanticStringInputSynthesizer.classify(
                calls("java/lang/String.substring(I)Ljava/lang/String;"),
                Collections.emptySet(), "consume(Ljava/lang/String;)V"));
    }

    @Test
    void jsonPlanReplacesTheExactTargetArgumentAndSuppressesDisconnectedState() throws Exception {
        Method method = Fixture.class.getMethod("consumeJson", String.class);
        String signature = method.getName() + MethodUtil.getSignature(method);
        TargetContext.setActive(new TargetContext(Fixture.class.getName(), signature, null));

        TestCase test = new DefaultTestCase();
        VariableReference oldInput =
                test.addStatement(new StringPrimitiveStatement(test, "not json"));
        MethodStatement target = new MethodStatement(test,
                new GenericMethod(method, Fixture.class), null,
                Collections.singletonList(oldInput));
        test.addStatement(target);

        PartialGraph graph = jsonGraph(signature);
        assertTrue(graph.hasExternallyConstructibleState());
        assertTrue(SemanticStringInputSynthesizer.apply(test, graph, null));

        assertEquals(3, test.size());
        assertSame(oldInput, test.getStatement(0).getReturnValue(),
                "the old value may have other users and must not be rewritten globally");
        VariableReference replacement = target.getParameterReferences().get(0);
        assertSame(test.getStatement(1).getReturnValue(), replacement);
        assertEquals("{\"value\":0}",
                ((StringPrimitiveStatement) test.getStatement(1)).getValue());
    }

    @Test
    void unsupportedStringObservationDoesNotMakeAnExpandedGraphEligible() throws Exception {
        Method method = Fixture.class.getMethod("consumeJson", String.class);
        String signature = method.getName() + MethodUtil.getSignature(method);
        TargetContext.setActive(new TargetContext(Fixture.class.getName(), signature, null));

        PartialGraph graph = graph(signature,
                new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring",
                        "(I)Ljava/lang/String;", false));
        assertFalse(graph.hasExternallyConstructibleState());
    }

    private static PartialGraph jsonGraph(String signature) {
        return graph(signature, new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "com/fasterxml/jackson/core/JsonFactory", "createParser",
                "(Ljava/lang/String;)Lcom/fasterxml/jackson/core/JsonParser;", false));
    }

    private static PartialGraph graph(String signature, MethodInsnNode call) {
        DepVariableWrapper root = wrapper(parameter(signature));
        DepVariableWrapper observation = wrapper(methodCall(signature, call));
        root.children.add(observation);
        observation.parents.add(root);

        PartialGraph graph = new PartialGraph();
        graph.allRelevantNodes.put(root, root);
        graph.allRelevantNodes.put(observation, observation);
        return graph;
    }

    private static DepVariable parameter(String signature) {
        DepVariable variable = mock(DepVariable.class);
        BytecodeInstruction instruction = mock(BytecodeInstruction.class);
        when(variable.isParameter()).thenReturn(true);
        when(variable.getParamOrder()).thenReturn(1);
        when(variable.getInstruction()).thenReturn(instruction);
        when(instruction.getClassName()).thenReturn(Fixture.class.getName());
        when(instruction.getMethodName()).thenReturn(signature);
        when(instruction.getInstructionId()).thenReturn(1);
        return variable;
    }

    private static DepVariable methodCall(String signature, MethodInsnNode call) {
        DepVariable variable = mock(DepVariable.class);
        BytecodeInstruction instruction = mock(BytecodeInstruction.class);
        when(variable.isMethodCall()).thenReturn(true);
        when(variable.getInstruction()).thenReturn(instruction);
        when(instruction.getClassName()).thenReturn(Fixture.class.getName());
        when(instruction.getMethodName()).thenReturn(signature);
        when(instruction.getASMNode()).thenReturn(call);
        return variable;
    }

    private static DepVariableWrapper wrapper(DepVariable variable) {
        DepVariableWrapper wrapper = mock(DepVariableWrapper.class);
        wrapper.var = variable;
        wrapper.children = new ArrayList<>();
        wrapper.parents = new ArrayList<>();
        return wrapper;
    }

    private static Set<String> calls(String... calls) {
        return new LinkedHashSet<>(Arrays.asList(calls));
    }

    public static class Fixture {
        public static void consumeJson(String value) {
            // shape-only target
        }
    }
}
