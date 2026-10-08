package org.evosuite.testcase.synthesizer.var;

import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.synthesizer.VariableInTest;
import org.evosuite.testcase.synthesizer.improvedsynth.Operation;
import org.evosuite.testcase.variable.VariableReference;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wraps a dependency variable together with its position in the dependency tree used for test synthesis.
 */
public abstract class DepVariableWrapper {
    public DepVariable var;
    public List<DepVariableWrapper> children = new ArrayList<>();
    public List<DepVariableWrapper> parents = new ArrayList<>();

    /**
     * It means the variable has been accessed by the code of the enriching test code.
     */
    public boolean processed = false;

    /**
     * Wraps the given dependency variable.
     *
     * @param var the dependency variable to wrap
     */
    protected DepVariableWrapper(DepVariable var) {
        this.var = var;
    }

    /**
     * Finds or generates the statements that provide the value of this variable in the test.
     *
     * @param test the test to search and extend
     * @param isLeaf whether this wrapper is a leaf of the dependency tree
     * @param variable the variable in the test the dependency is accessed on
     * @param map the relevance information of the wrappers already processed
     * @param b the targeted branch
     * @param allowNullValue whether a null value may be used
     * @param recommendation the recommended operation for the synthesis
     * @return the matched and relevant variables
     */
    public abstract VarRelevance generateOrFindStatement(TestCase test, boolean isLeaf, VariableInTest variable,
            Map<DepVariableWrapper, VarRelevance> map, Branch b, boolean allowNullValue, Operation recommendation);

    /**
     * Finds the variables in the test that correspond to this dependency variable.
     *
     * @param test the test to search
     * @param isLeaf whether this wrapper is a leaf of the dependency tree
     * @param callerObject the object on which the dependency is accessed
     * @param map the relevance information of the wrappers already processed
     * @return the corresponding variables, empty if none is found
     */
    public List<VariableReference> findCorrespondingVariables(TestCase test, boolean isLeaf,
            VariableReference callerObject, Map<DepVariableWrapper, VarRelevance> map) {
        List<VariableReference> vars = new ArrayList<>();
        VariableReference var = find(test, isLeaf, callerObject, map);
        if (var != null) {
            vars.add(var);
        }
        return vars;
    }

    /**
     * Finds the variable in the test that corresponds to this dependency variable.
     *
     * @param test the test to search
     * @param isLeaf whether this wrapper is a leaf of the dependency tree
     * @param callerObject the object on which the dependency is accessed
     * @param map the relevance information of the wrappers already processed
     * @return the corresponding variable, or null if none is found
     */
    public abstract VariableReference find(TestCase test, boolean isLeaf, VariableReference callerObject,
            Map<DepVariableWrapper, VarRelevance> map);

    /** Whether synthesis should continue through this node when it produced no corresponding value. */
    public boolean traverseChildrenAfterFailedGeneration() {
        return true;
    }

    /** Whether synthesis should continue through this node after the current generation attempt. */
    public boolean traverseChildrenAfterGeneration(boolean generated) {
        return generated || traverseChildrenAfterFailedGeneration();
    }

    @Override
    public int hashCode() {
        return this.var.getType();
    }

    @Override
    public boolean equals(Object obj) {

        if (obj instanceof DepVariableWrapper) {
            DepVariableWrapper varWrapper = (DepVariableWrapper) obj;
            if (var.equals(varWrapper.var)) {
                return true;
            } else {
                DepVariable other = varWrapper.var;
                if (var.getType() == other.getType()) {
                    if (var.getType() == DepVariable.INSTANCE_FIELD
                            || var.getType() == DepVariable.STATIC_FIELD) {
                        FieldInsnNode thatField = (FieldInsnNode) (var.getInstruction().getASMNode());
                        FieldInsnNode thisField = (FieldInsnNode) (other.getInstruction().getASMNode());
                        return thisField.desc.equals(thatField.desc)
                                && thisField.owner.equals(thatField.owner)
                                && thisField.name.equals(thatField.name);
                    } else if (var.getType() == DepVariable.PARAMETER) {
                        return var.getInstruction().getClassName().equals(other.getInstruction().getClassName())
                                && var.getInstruction().getMethodName().equals(other.getInstruction().getMethodName())
                                && var.getParamOrder() == other.getParamOrder();
                    } else if (var.getType() == DepVariable.THIS) {
                        return var.getInstruction().getClassName().equals(other.getInstruction().getClassName());
                    } else if (var.getType() == DepVariable.OTHER) {
                        // Two loads of the same local variable slot in the same method are the same
                        // value. Equivalence of other instructions, such as method calls, is not
                        // defined yet. (ARRAY_ELEMENT has no equivalence either.)
                        AbstractInsnNode node1 = var.getInstruction().getASMNode();
                        AbstractInsnNode node2 = other.getInstruction().getASMNode();
                        return node1.getOpcode() == Opcodes.ALOAD
                                && node2.getOpcode() == Opcodes.ALOAD
                                && var.getInstruction().getClassName().equals(other.getInstruction().getClassName())
                                && var.getInstruction().getMethodName()
                                        .equals(other.getInstruction().getMethodName())
                                && ((VarInsnNode) node1).var == ((VarInsnNode) node2).var;
                    }
                }

                return false;
            }
        }

        return false;
    }

    /**
     * Adds a parent of this node unless it is already present.
     *
     * @param parent the parent node
     */
    public void addParent(DepVariableWrapper parent) {
        if (!this.parents.contains(parent)) {
            this.parents.add(parent);
        }
    }

    /**
     * Adds a child of this node unless it is already present.
     *
     * @param child the child node
     */
    public void addChild(DepVariableWrapper child) {
        if (!this.children.contains(child)) {
            this.children.add(child);
        }
    }

    /**
     * Finds the operand position at which the given node is related to this node.
     *
     * @param node the related node
     * @return the position of the relation, or -1 if the node is not related
     */
    public int findRelationPosition(DepVariableWrapper node) {
        for (int i = 0; i < var.getRelations().length; i++) {
            if (var.getRelations()[i] == null || var.getRelations()[i].isEmpty()) {
                continue;
            }

            for (DepVariable child : var.getRelations()[i]) {
                DepVariableWrapper childWrapper = DepVariableWrapperFactory.createWrapperInstance(child);
                if (childWrapper.equals(node)) {
                    return i;
                }
            }

        }

        return -1;
    }

    public String toString() {
        return var.toString();
    }

    /**
     * Returns the parent nodes which can directly "call" this node.
     *
     * @return the caller nodes, or null if there is none
     */
    public List<DepVariableWrapper> getCallerNode() {
        if (this.parents.isEmpty()) {
            return null;
        }

        if (this.var.isMethodCall()) {
            for (DepVariableWrapper par : this.parents) {
                if (par.isSupportOperandFor(this.var, 0)) {
                    List<DepVariableWrapper> list = new ArrayList<>();
                    list.add(par);
                    return list;
                }
            }
        } else {
            return this.parents;
        }

        return null;
    }

    private boolean isSupportOperandFor(DepVariable var2, int index) {
        return this.var.isSupportOperandFor(var2, index);
    }

    /**
     * Returns a short name made of the method name and instruction id of this variable.
     *
     * @return the short name
     */
    public String getShortName() {
        StringBuffer buffer = new StringBuffer();
        buffer.append(var.getShortMethodName() + "#" + var.getInstruction().getInstructionId());
        return buffer.toString();
    }

    /**
     * Returns the parent that provides the first operand of this variable.
     *
     * @return the first parent, or null if there is none
     */
    public DepVariableWrapper getFirstParent() {
        for (DepVariableWrapper par : this.parents) {
            int order = this.var.getInputOrder(par.var);
            if (order == 0) {
                return par;
            }
        }
        return null;
    }

    /**
     * Follows the first parents upwards until a node that has already been processed is found.
     *
     * @return the closest processed ancestor, or null if there is none
     */
    public DepVariableWrapper findTaintedParent() {

        DepVariableWrapper firstPar = this.getFirstParent();
        if (firstPar == null) {
            return null;
        }

        while (!firstPar.processed) {
            firstPar = firstPar.getFirstParent();

            if (firstPar == null) {
                return null;
            }
        }

        return firstPar;
    }

}
