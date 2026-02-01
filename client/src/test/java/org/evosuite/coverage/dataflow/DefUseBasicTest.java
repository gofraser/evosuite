package org.evosuite.coverage.dataflow;

import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.dataflow.DefUseCoverageTestFitness.DefUsePairType;
import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.BasicBlock;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.graphs.cfg.RawControlFlowGraph;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DefUseBasicTest {

    private ClassLoader classLoader;

    @Before
    public void setUp() {
        classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
        resetStaticState();
    }

    @After
    public void tearDown() {
        resetStaticState();
    }

    private void resetStaticState() {
        // Reset via reflection to avoid NPEs if state is inconsistent
        try {
            java.lang.reflect.Field calledField = DefUseCoverageFactory.class.getDeclaredField("called");
            calledField.setAccessible(true);
            calledField.set(null, false);

            java.lang.reflect.Field duGoalsField = DefUseCoverageFactory.class.getDeclaredField("duGoals");
            duGoalsField.setAccessible(true);
            duGoalsField.set(null, null);

            java.lang.reflect.Field goalsField = DefUseCoverageFactory.class.getDeclaredField("goals");
            goalsField.setAccessible(true);
            goalsField.set(null, null);

        } catch (Exception e) {
            e.printStackTrace();
        }

        DefUsePool.clear();
        DefUseCoverageFactory.clear();
        GraphPool.getInstance(classLoader).clear();
        BytecodeInstructionPool.getInstance(classLoader).clear();
    }

    private void registerMockCFG(String className, String methodName) {
        RawControlFlowGraph cfg = mock(RawControlFlowGraph.class);
        when(cfg.getClassName()).thenReturn(className);
        when(cfg.getMethodName()).thenReturn(methodName);
        when(cfg.isStaticMethod()).thenReturn(false);
        when(cfg.vertexSet()).thenReturn(new HashSet<>());
        GraphPool.getInstance(classLoader).registerRawCFG(cfg);
    }

    private BasicBlock createMockBasicBlock(String className, String methodName) {
        BasicBlock block = mock(BasicBlock.class);
        when(block.getClassName()).thenReturn(className);
        when(block.getMethodName()).thenReturn(methodName);
        when(block.getControlDependencies()).thenReturn(Collections.emptySet());
        when(block.getControlDependentBranchIds()).thenReturn(Collections.emptySet());
        return block;
    }

    private void registerInstruction(BytecodeInstruction instruction) {
        BytecodeInstructionPool.getInstance(classLoader).registerInstruction(instruction);
    }

    @Test
    public void testDefUseToString() {
        String className = "Class1";
        String methodName = "method1";
        registerMockCFG(className, methodName);
        BasicBlock block = createMockBasicBlock(className, methodName);

        AbstractInsnNode asmNode = new VarInsnNode(Opcodes.ISTORE, 1);
        BytecodeInstruction instruction = new BytecodeInstruction(classLoader, className, methodName, 10, 0, asmNode, -1, block);
        registerInstruction(instruction);

        DefUsePool.addAsDefinition(instruction);
        Definition def = DefUsePool.getDefinitionByInstruction(instruction);

        assertNotNull(def);
        String str = def.toString();
        assertNotNull(str);
        assertTrue(str.contains("Definition"));
    }

    @Test
    public void testDefUseEquality() {
        String className = "Class1";
        String methodName = "method1";
        registerMockCFG(className, methodName);
        BasicBlock block = createMockBasicBlock(className, methodName);

        BytecodeInstruction i1 = createRealInstruction(className, methodName, "var1", 1, true, block);
        registerInstruction(i1);
        DefUsePool.addAsDefinition(i1);
        Definition d1 = DefUsePool.getDefinitionByInstruction(i1);

        Definition d1_again = DefUsePool.getDefinitionByInstruction(i1);
        assertEquals(d1, d1_again);

        BytecodeInstruction i2 = createRealInstruction(className, methodName, "var1", 2, true, block);
        registerInstruction(i2);
        DefUsePool.addAsDefinition(i2);
        Definition d2 = DefUsePool.getDefinitionByInstruction(i2);

        assertNotEquals(d1, d2);
    }

    private BytecodeInstruction createRealInstruction(String className, String methodName, String varName, int id, boolean isDef, BasicBlock block) {
        AbstractInsnNode asmNode = new VarInsnNode(isDef ? Opcodes.ISTORE : Opcodes.ILOAD, 1);
        return new BytecodeInstruction(classLoader, className, methodName, id, 0, asmNode, -1, block) {
            @Override
            public String getVariableName() {
                return varName;
            }
        };
    }


    @Test
    public void testFitnessSerialization() throws IOException, ClassNotFoundException {
        String className = "Class1";
        String methodName = "method1";
        registerMockCFG(className, methodName);
        BasicBlock block = createMockBasicBlock(className, methodName);

        BytecodeInstruction defInst = createRealInstruction(className, methodName, "var1", 1, true, block);
        registerInstruction(defInst);
        DefUsePool.addAsDefinition(defInst);
        Definition def = DefUsePool.getDefinitionByInstruction(defInst);

        BytecodeInstruction useInst = createRealInstruction(className, methodName, "var1", 20, false, block);
        registerInstruction(useInst);
        DefUsePool.addAsUse(useInst);
        Use use = DefUsePool.getUseByInstruction(useInst);

        DefUseCoverageTestFitness fitness = new DefUseCoverageTestFitness(def, use, DefUsePairType.INTRA_METHOD);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(fitness);
        oos.close();

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ObjectInputStream ois = new ObjectInputStream(bais);
        DefUseCoverageTestFitness deserialized = (DefUseCoverageTestFitness) ois.readObject();

        assertNotNull(deserialized);
        assertEquals(fitness.getType(), deserialized.getType());
        assertEquals(fitness.getGoalVariable(), deserialized.getGoalVariable());
        assertEquals(fitness.getGoalDefinition().getDefId(), deserialized.getGoalDefinition().getDefId());
    }

    @Test
    public void testDetectAliasingGoals() {
        boolean originalProp = org.evosuite.Properties.DEFUSE_ALIASES;
        org.evosuite.Properties.DEFUSE_ALIASES = true;
        try {
            String className = "Class1";
            String methodName = "method1";
            registerMockCFG(className, methodName);
            BasicBlock block = createMockBasicBlock(className, methodName);

            // Setup Definition (Active Def) for variable "varA"
            BytecodeInstruction defInst = createRealInstruction(className, methodName, "varA", 1, true, block);
            registerInstruction(defInst);
            DefUsePool.addAsDefinition(defInst);
            Definition def = DefUsePool.getDefinitionByInstruction(defInst);

            // Setup Use for variable "varB"
            BytecodeInstruction useInst = createRealInstruction(className, methodName, "varB", 2, false, block);
            registerInstruction(useInst);
            DefUsePool.addAsUse(useInst);
            Use use = DefUsePool.getUseByInstruction(useInst);

            BytecodeInstruction defBInst = createRealInstruction(className, methodName, "varB", 3, true, block);
            registerInstruction(defBInst);
            DefUsePool.addAsDefinition(defBInst);
            Definition defB = DefUsePool.getDefinitionByInstruction(defBInst);

            // Create goal (DefB, UseB)
            DefUseCoverageTestFitness goalB = DefUseCoverageFactory.createGoal(defB, use, DefUsePairType.INTRA_METHOD);

            java.lang.reflect.Field duGoalsField = DefUseCoverageFactory.class.getDeclaredField("duGoals");
            duGoalsField.setAccessible(true);
            List<DefUseCoverageTestFitness> duGoals = new ArrayList<>();
            duGoals.add(goalB);
            duGoalsField.set(null, duGoals);

            java.lang.reflect.Field goalsField = DefUseCoverageFactory.class.getDeclaredField("goals");
            goalsField.setAccessible(true);
            List<DefUseCoverageTestFitness> goals = new ArrayList<>();
            goals.add(goalB);
            goalsField.set(null, goals);

            java.lang.reflect.Field calledField = DefUseCoverageFactory.class.getDeclaredField("called");
            calledField.setAccessible(true);
            calledField.set(null, true);

            // Now setup ExecutionResult/Trace
            ExecutionResult result = mock(ExecutionResult.class);
            ExecutionTrace trace = mock(ExecutionTrace.class);
            when(result.getTrace()).thenReturn(trace);

            Object sharedObject = new Object();
            int objectId = System.identityHashCode(sharedObject);

            Map<String, HashMap<Integer, HashMap<Integer, Object>>> passedUsesObject = new HashMap<>();
            HashMap<Integer, HashMap<Integer, Object>> usesVarB = new HashMap<>();
            HashMap<Integer, Object> usesVarBObj = new HashMap<>();
            usesVarBObj.put(100, sharedObject);
            usesVarB.put(objectId, usesVarBObj);
            passedUsesObject.put("varB", usesVarB);

            when(trace.getUseDataObjects()).thenReturn(passedUsesObject);

            Map<String, HashMap<Integer, HashMap<Integer, Object>>> passedDefsObject = new HashMap<>();
            HashMap<Integer, HashMap<Integer, Object>> defsVarA = new HashMap<>();
            HashMap<Integer, Object> defsVarAObj = new HashMap<>();
            defsVarAObj.put(50, sharedObject);
            defsVarA.put(objectId, defsVarAObj);
            passedDefsObject.put("varA", defsVarA);

            when(trace.getDefinitionDataObjects()).thenReturn(passedDefsObject);

            Map<String, HashMap<Integer, HashMap<Integer, Integer>>> passedUses = new HashMap<>();
            HashMap<Integer, HashMap<Integer, Integer>> usesVarBInt = new HashMap<>();
            HashMap<Integer, Integer> usesVarBObjInt = new HashMap<>();
            usesVarBObjInt.put(100, use.getUseId());
            usesVarBInt.put(objectId, usesVarBObjInt);
            passedUses.put("varB", usesVarBInt);

            when(trace.getUseData()).thenReturn(passedUses);

            Map<String, HashMap<Integer, HashMap<Integer, Integer>>> passedDefs = new HashMap<>();
            HashMap<Integer, HashMap<Integer, Integer>> defsVarAInt = new HashMap<>();
            HashMap<Integer, Integer> defsVarAObjInt = new HashMap<>();
            defsVarAObjInt.put(50, def.getDefId());
            defsVarAInt.put(objectId, defsVarAObjInt);
            passedDefs.put("varA", defsVarAInt);

            when(trace.getDefinitionData()).thenReturn(passedDefs);

            List<ExecutionResult> results = new ArrayList<>();
            results.add(result);

            boolean found = DefUseCoverageFactory.detectAliasingGoals(results);
            assertTrue("Should find aliasing goals", found);

            List<DefUseCoverageTestFitness> currentGoals = (List<DefUseCoverageTestFitness>) duGoalsField.get(null);
            boolean newGoalFound = false;
            for(DefUseCoverageTestFitness g : currentGoals) {
                if(g.getGoalDefinition().equals(def) && g.getGoalUse().equals(use)) {
                    newGoalFound = true;
                    break;
                }
            }
            assertTrue("New goal (DefA, UseB) should be created", newGoalFound);

        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        } finally {
            org.evosuite.Properties.DEFUSE_ALIASES = originalProp;
        }
    }

}
