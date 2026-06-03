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
package org.evosuite.llm.prompt;

import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.coverage.exception.ExceptionCoverageTestFitness;
import org.evosuite.coverage.line.LineCoverageTestFitness;
import org.evosuite.coverage.method.MethodCoverageTestFitness;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoalDescriptionMapperTest {

    private final GoalDescriptionMapper mapper = new GoalDescriptionMapper();

    // ---- cleanMethodName ----

    @Test
    void cleanMethodName_stripsFqcnAndConvertsDescriptor() {
        assertEquals("bar(int)",
                GoalDescriptionMapper.cleanMethodName("com.example.Foo.bar(I)V"));
    }

    @Test
    void cleanMethodName_handlesMultipleArgs() {
        assertEquals("baz(String, boolean)",
                GoalDescriptionMapper.cleanMethodName("baz(Ljava/lang/String;Z)I"));
    }

    @Test
    void cleanMethodName_handlesInit() {
        assertEquals("<init>(int)",
                GoalDescriptionMapper.cleanMethodName("<init>(I)V"));
    }

    @Test
    void cleanMethodName_handlesNoDescriptor() {
        assertEquals("simple",
                GoalDescriptionMapper.cleanMethodName("simple"));
    }

    @Test
    void cleanMethodName_handlesNullAndEmpty() {
        assertEquals("", GoalDescriptionMapper.cleanMethodName(null));
        assertEquals("", GoalDescriptionMapper.cleanMethodName(""));
    }

    // ---- humanType ----

    @Test
    void humanType_primitives() {
        assertEquals("int", GoalDescriptionMapper.humanType(Type.INT_TYPE));
        assertEquals("boolean", GoalDescriptionMapper.humanType(Type.BOOLEAN_TYPE));
        assertEquals("double", GoalDescriptionMapper.humanType(Type.DOUBLE_TYPE));
        assertEquals("long", GoalDescriptionMapper.humanType(Type.LONG_TYPE));
        assertEquals("float", GoalDescriptionMapper.humanType(Type.FLOAT_TYPE));
        assertEquals("char", GoalDescriptionMapper.humanType(Type.CHAR_TYPE));
        assertEquals("byte", GoalDescriptionMapper.humanType(Type.BYTE_TYPE));
        assertEquals("short", GoalDescriptionMapper.humanType(Type.SHORT_TYPE));
        assertEquals("void", GoalDescriptionMapper.humanType(Type.VOID_TYPE));
    }

    @Test
    void humanType_array() {
        assertEquals("int[]", GoalDescriptionMapper.humanType(Type.getType("[I")));
    }

    @Test
    void humanType_object() {
        assertEquals("String",
                GoalDescriptionMapper.humanType(Type.getObjectType("java/lang/String")));
    }

    // ---- simpleName ----

    @Test
    void simpleName_stripsPackage() {
        assertEquals("String", GoalDescriptionMapper.simpleName("java.lang.String"));
    }

    @Test
    void simpleName_noPackage() {
        assertEquals("Foo", GoalDescriptionMapper.simpleName("Foo"));
    }

    // ---- humanizeValueDescriptor ----

    @Test
    void humanizeValueDescriptor_knownValues() {
        assertEquals("positive number",
                GoalDescriptionMapper.humanizeValueDescriptor("NUM_POSITIVE"));
        assertEquals("null",
                GoalDescriptionMapper.humanizeValueDescriptor("REF_NULL"));
        assertEquals("non-null",
                GoalDescriptionMapper.humanizeValueDescriptor("REF_NONNULL"));
        assertEquals("empty string",
                GoalDescriptionMapper.humanizeValueDescriptor("STRING_EMPTY"));
    }

    @Test
    void humanizeValueDescriptor_unknown() {
        assertEquals("some other",
                GoalDescriptionMapper.humanizeValueDescriptor("SOME_OTHER"));
    }

    // ---- humanizeMutationName ----

    @Test
    void humanizeMutationName_known() {
        assertEquals("arithmetic operator replacement",
                GoalDescriptionMapper.humanizeMutationName("AOR"));
    }

    @Test
    void humanizeMutationName_unknown() {
        assertEquals("XYZ", GoalDescriptionMapper.humanizeMutationName("XYZ"));
    }

    // ---- describe (dispatching) ----

    @Test
    void describe_lineCoverage() {
        LineCoverageTestFitness goal = mock(LineCoverageTestFitness.class);
        when(goal.getMethod()).thenReturn("foo(I)V");
        when(goal.getLine()).thenReturn(42);

        String desc = mapper.describe(goal);

        assertTrue(desc.contains("Line"), "should mention 'Line'");
        assertTrue(desc.contains("42"), "should contain line number");
    }

    @Test
    void describe_methodCoverage() {
        MethodCoverageTestFitness goal = mock(MethodCoverageTestFitness.class);
        when(goal.getMethod()).thenReturn("bar(Ljava/lang/String;)V");

        String desc = mapper.describe(goal);

        assertTrue(desc.startsWith("Method"), "should start with 'Method'");
    }

    @Test
    void describe_exceptionCoverage() {
        ExceptionCoverageTestFitness goal = mock(ExceptionCoverageTestFitness.class);
        when(goal.getMethod()).thenReturn("baz()V");
        when(goal.getExceptionClass()).thenReturn((Class) ArithmeticException.class);
        when(goal.getKey()).thenReturn("Foo_baz_ArithmeticException_EXPLICIT");

        String desc = mapper.describe(goal);

        assertTrue(desc.contains("ArithmeticException"), "should name exception");
        assertTrue(desc.contains("explicit"), "should describe exception type");
    }

    @Test
    void extractQualifiedMethodLabel_includesClassName() {
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.getTargetClass()).thenReturn("com.example.Foo");
        when(goal.getTargetMethod()).thenReturn("bar(I)V");

        assertEquals("com.example.Foo.bar(int)", mapper.extractQualifiedMethodLabel(goal));
    }

    @Test
    void describeMethodOperation_exposesDisplayLabelAndExecutionKey() {
        GoalDescriptionMapper.OperationTarget target =
                mapper.describeMethodOperation("com.example.Foo", "bar(I)V");

        assertEquals("com.example.Foo.bar(int)", target.getDisplayLabel());
        assertEquals("com.example.Foo.bar", target.getExecutionKey());
        assertEquals("bar", target.getBaseName());
    }

    @Test
    void describeMethodOperation_usesObservedReceiverTypeForInheritedInstanceCalls() throws Exception {
        MethodStatement statement = mock(MethodStatement.class);
        GenericMethod genericMethod = mock(GenericMethod.class);
        VariableReference callee = mock(VariableReference.class);
        Method inherited = InheritedBase.class.getDeclaredMethod("work", String.class);

        when(statement.getMethod()).thenReturn(genericMethod);
        when(genericMethod.getMethod()).thenReturn(inherited);
        when(genericMethod.isStatic()).thenReturn(false);
        when(genericMethod.getNameWithDescriptor()).thenReturn(null);
        when(statement.getCallee()).thenReturn(callee);
        doReturn(InheritedLeaf.class).when(callee).getVariableClass();

        GoalDescriptionMapper.OperationTarget target = mapper.describeMethodOperation(statement);

        assertEquals(InheritedLeaf.class.getName() + ".work(String)", target.getDisplayLabel());
        assertEquals(InheritedLeaf.class.getName() + ".work", target.getExecutionKey());
    }

    @Test
    void describeConstructorOperation_formatsConstructorDisplayLabel() {
        GoalDescriptionMapper.OperationTarget target =
                mapper.describeConstructorOperation("com.example.Foo", "<init>(Ljava/lang/String;I)V");

        assertEquals("com.example.Foo(String, int)", target.getDisplayLabel());
        assertEquals("com.example.Foo.<init>", target.getExecutionKey());
        assertEquals("<init>", target.getBaseName());
    }

    @Test
    void describeQualifiedMethodOperation_normalizesDescriptorFreeTraceLabels() {
        GoalDescriptionMapper.OperationTarget target =
                mapper.describeQualifiedMethodOperation("com.example.Foo.bar");

        assertEquals("com.example.Foo.bar", target.getDisplayLabel());
        assertEquals("com.example.Foo.bar", target.getExecutionKey());
        assertEquals("bar", target.getBaseName());
    }

    @Test
    void describeBranchTarget_includesLineAndOutcome() {
        BranchCoverageGoal goal = mock(BranchCoverageGoal.class);
        Branch branch = mock(Branch.class);
        when(goal.getBranch()).thenReturn(branch);
        when(goal.getMethodName()).thenReturn("bar(I)V");
        when(goal.getLineNumber()).thenReturn(42);
        when(goal.getValue()).thenReturn(true);
        when(branch.getClassName()).thenReturn("com.example.Foo");
        when(branch.getMethodName()).thenReturn("bar(I)V");

        GoalDescriptionMapper.BranchTarget target = mapper.describeBranchTarget(goal);

        assertEquals("com.example.Foo.bar(int) at line 42", target.getLocationLabel());
        assertEquals("TRUE path", target.getOutcomeLabel());
        assertEquals("Branch in bar(int) at line 42 — TRUE path", target.asGoalDescription());
    }

    @Test
    void describeBranchTarget_withoutLineFallsBackToQualifiedMethod() {
        Branch branch = mock(Branch.class);
        BytecodeInstruction instruction = mock(BytecodeInstruction.class);
        when(branch.getClassName()).thenReturn("com.example.Foo");
        when(branch.getMethodName()).thenReturn("baz()V");
        when(branch.getInstruction()).thenReturn(instruction);
        when(instruction.getLineNumber()).thenReturn(-1);

        GoalDescriptionMapper.BranchTarget target = mapper.describeBranchTarget(branch, false);

        assertEquals("com.example.Foo.baz()", target.getLocationLabel());
        assertEquals("FALSE path", target.getOutcomeLabel());
    }

    @Test
    void describe_unknownSubtype() {
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.toString()).thenReturn("unknown-goal-repr");

        String desc = mapper.describe(goal);

        assertEquals("unknown-goal-repr", desc);
    }

    private static class InheritedBase {
        void work(String input) {
            // no-op
        }
    }

    private static final class InheritedLeaf extends InheritedBase {
        // inherits work(String)
    }
}
