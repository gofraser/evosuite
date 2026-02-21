package org.evosuite.coverage.statement;

import org.evosuite.Properties;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.ControlDependency;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StatementCoverageReproduceTest {

    @BeforeEach
    public void setUp() {
        Properties.TEST_ARCHIVE = false;
    }

    private static class TestableStatementCoverageTestFitness extends StatementCoverageTestFitness {
        private static final long serialVersionUID = 1L;

        public TestableStatementCoverageTestFitness(BytecodeInstruction instruction) {
            super(instruction);
        }

        @Override
        protected void setupDependencies(BytecodeInstruction instruction) {
            // Do nothing to avoid static calls to Factories
            this.goalInstruction = instruction;
        }

        public void addBranchFitness(BranchCoverageTestFitness fitness) {
            this.branchFitnesses.add(fitness);
        }
    }

    private static class TestBytecodeInstruction extends BytecodeInstruction {
        public TestBytecodeInstruction(ClassLoader cl, String className, String methodName, int id, int offset, AbstractInsnNode node) {
            super(cl, className, methodName, id, offset, node);
        }

        @Override
        public boolean isRootBranchDependent() {
            return true;
        }

        @Override
        public Set<ControlDependency> getControlDependencies() {
            return Collections.emptySet();
        }

        @Override
        public boolean isLineNumber() {
            return false;
        }

        @Override
        public boolean isLabel() {
            return false;
        }
    }

    @Test
    public void testFitnessPenaltyWhenLineNotCovered() {
        String className = "TestClass";
        String methodName = "testMethod";
        int instructionId = 5;
        int line = 10;

        AbstractInsnNode asmNode = new InsnNode(Opcodes.NOP);
        TestBytecodeInstruction instruction = new TestBytecodeInstruction(getClass().getClassLoader(), className, methodName, instructionId, 0, asmNode);
        instruction.setLineNumber(line);

        TestableStatementCoverageTestFitness fitness = new TestableStatementCoverageTestFitness(instruction);

        BranchCoverageTestFitness mockBranchFitness = mock(BranchCoverageTestFitness.class);
        when(mockBranchFitness.getFitness(Mockito.any(), Mockito.any())).thenReturn(0.0);
        fitness.addBranchFitness(mockBranchFitness);

        DefaultTestCase testCase = new DefaultTestCase();
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(testCase);

        ExecutionResult result = new ExecutionResult(testCase, null);
        ExecutionTrace trace = mock(ExecutionTrace.class);
        result.setTrace(trace);

        Set<Integer> coveredLines = new HashSet<>();
        when(trace.getCoveredLines(className)).thenReturn(coveredLines);

        double calculatedFitness = fitness.getFitness(chromosome, result);

        // Expect 1.0 penalty
        assertEquals(1.0, calculatedFitness, 0.001);
    }

    @Test
    public void testFitnessZeroWhenLineCovered() {
        String className = "TestClass";
        String methodName = "testMethod";
        int instructionId = 5;
        int line = 10;

        AbstractInsnNode asmNode = new InsnNode(Opcodes.NOP);
        TestBytecodeInstruction instruction = new TestBytecodeInstruction(getClass().getClassLoader(), className, methodName, instructionId, 0, asmNode);
        instruction.setLineNumber(line);

        TestableStatementCoverageTestFitness fitness = new TestableStatementCoverageTestFitness(instruction);

        BranchCoverageTestFitness mockBranchFitness = mock(BranchCoverageTestFitness.class);
        when(mockBranchFitness.getFitness(Mockito.any(), Mockito.any())).thenReturn(0.0);
        fitness.addBranchFitness(mockBranchFitness);

        DefaultTestCase testCase = new DefaultTestCase();
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(testCase);

        ExecutionResult result = new ExecutionResult(testCase, null);
        ExecutionTrace trace = mock(ExecutionTrace.class);
        result.setTrace(trace);

        // Line covered
        Set<Integer> coveredLines = new HashSet<>(Arrays.asList(line));
        when(trace.getCoveredLines(className)).thenReturn(coveredLines);

        double calculatedFitness = fitness.getFitness(chromosome, result);

        // Expect 0.0
        assertEquals(0.0, calculatedFitness, 0.001);
    }
}
