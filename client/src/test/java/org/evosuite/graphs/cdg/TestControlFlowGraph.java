package org.evosuite.graphs.cdg;

import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.ControlFlowGraph;
import org.evosuite.graphs.cfg.ControlFlowEdge;

public class TestControlFlowGraph extends ControlFlowGraph<Integer> {

    public TestControlFlowGraph(String className, String methodName) {
        super(className, methodName, 0);
    }

    @Override
    public BytecodeInstruction getInstruction(int instructionId) {
        return null;
    }

    @Override
    public boolean containsInstruction(BytecodeInstruction instruction) {
        return false;
    }

    @Override
    public String getCFGType() {
        return "TestCFG";
    }

    public void addNode(Integer i) {
        addVertex(i);
    }

    public void addTestEdge(Integer src, Integer target) {
        addEdge(src, target, new ControlFlowEdge());
    }
}
