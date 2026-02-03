package org.evosuite.coverage.dataflow.analysis;

import org.evosuite.coverage.dataflow.DefUseCoverageFactory;
import org.evosuite.coverage.dataflow.DefUseCoverageTestFitness;
import org.evosuite.graphs.ccfg.*;
import org.evosuite.graphs.ccg.ClassCallGraph;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class AllUsesAnalysisTest {

    @Test
    public void testSimpleIntraMethodPair() {
        ClassControlFlowGraph ccfg = mock(ClassControlFlowGraph.class);
        ClassCallGraph ccg = mock(ClassCallGraph.class);
        when(ccfg.getCcg()).thenReturn(ccg);
        when(ccg.vertexSet()).thenReturn(Collections.emptySet());

        String methodName = "testMethod";

        CCFGMethodEntryNode entryNode = mock(CCFGMethodEntryNode.class);
        when(entryNode.getMethod()).thenReturn(methodName);
        CCFGCodeNode entryCodeNode = mock(CCFGCodeNode.class);
        when(entryNode.getEntryInstruction()).thenReturn(entryCodeNode);
        BytecodeInstruction entryInst = mock(BytecodeInstruction.class);
        when(entryCodeNode.getCodeInstruction()).thenReturn(entryInst);
        when(entryInst.getMethodName()).thenReturn(methodName);
        // Important: checkCallStackSanity compares method names
        when(entryInst.getMethodName()).thenReturn(methodName);

        // Def
        CCFGCodeNode defNode = mock(CCFGCodeNode.class);
        BytecodeInstruction defInst = mock(BytecodeInstruction.class);
        when(defInst.isDefinition()).thenReturn(true);
        when(defInst.isDefUse()).thenReturn(true); // Needed because BytecodeInstruction is a mock
        when(defInst.isUse()).thenReturn(false); // Make sure it's not treated as use
        when(defInst.getVariableName()).thenReturn("var1");
        when(defInst.getMethodName()).thenReturn(methodName);
        when(defInst.isLocalDU()).thenReturn(true);
        when(defInst.canBeInstrumented()).thenReturn(true);
        when(defNode.getCodeInstruction()).thenReturn(defInst);

        // Use
        CCFGCodeNode useNode = mock(CCFGCodeNode.class);
        BytecodeInstruction useInst = mock(BytecodeInstruction.class);
        when(useInst.isDefinition()).thenReturn(false);
        when(useInst.isUse()).thenReturn(true);
        when(useInst.isDefUse()).thenReturn(true); // Needed because BytecodeInstruction is a mock
        when(useInst.getVariableName()).thenReturn("var1");
        when(useInst.getMethodName()).thenReturn(methodName);
        when(useInst.isFieldUse()).thenReturn(false); // It's local use
        when(useInst.canBeInstrumented()).thenReturn(true);
        when(useNode.getCodeInstruction()).thenReturn(useInst);

        CCFGMethodExitNode exitNode = mock(CCFGMethodExitNode.class);
        when(exitNode.getMethod()).thenReturn(methodName);
        when(exitNode.isExitOfMethodEntry(entryNode)).thenReturn(true);

        // CCFG
        ccfg.publicMethods = new HashSet<>();
        ccfg.publicMethods.add(entryNode);
        when(ccfg.isPublicMethod(methodName)).thenReturn(true);

        // AllUsesAnalysis starts traversal at entryCodeNode
        when(ccfg.getChildren(entryCodeNode)).thenReturn(Collections.singleton(defNode));
        when(ccfg.getChildren(defNode)).thenReturn(Collections.singleton(useNode));
        when(ccfg.getChildren(useNode)).thenReturn(Collections.singleton(exitNode));
        when(ccfg.getChildren(exitNode)).thenReturn(Collections.emptySet());

        when(ccfg.outDegreeOf(any())).thenReturn(1);
        when(ccfg.getSingleChild(entryCodeNode)).thenReturn(defNode);
        when(ccfg.getSingleChild(defNode)).thenReturn(useNode);
        when(ccfg.getSingleChild(useNode)).thenReturn(exitNode);

        try (MockedStatic<DefUseCoverageFactory> factory = mockStatic(DefUseCoverageFactory.class)) {
            // Mock createGoal to return a mock goal and avoid real logic
            factory.when(() -> DefUseCoverageFactory.createGoal(any(BytecodeInstruction.class), any(BytecodeInstruction.class), any()))
                   .thenAnswer(inv -> mock(DefUseCoverageTestFitness.class));

            AllUsesAnalysis analysis = new AllUsesAnalysis(ccfg);
            Set<DefUseCoverageTestFitness> goals = analysis.determineDefUsePairs();

            assertEquals(1, goals.size());
        }
    }
}
