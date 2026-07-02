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
package org.evosuite.graphs.cfg;

import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.coverage.dataflow.DefUsePool;
import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cdg.ControlDependenceGraph;
import org.evosuite.graphs.interprocedural.DefUseAnalyzer;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.utils.MethodUtil;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.objectweb.asm.tree.analysis.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Internal representation of a BytecodeInstruction.
 *
 * <p>Extends ASMWrapper which serves as an interface to the ASM library.
 *
 * <p>Known super classes are DefUse and Branch which yield specific functionality
 * needed to achieve theirs respective coverage criteria.
 *
 * <p>Old: Node of the control flow graph.
 *
 * @author Gordon Fraser, Andre Mis
 */
public class BytecodeInstruction extends ASMWrapper implements Serializable,
        Comparable<BytecodeInstruction> {

    private static final long serialVersionUID = 3630449183355518857L;
    private static final Logger logger = LoggerFactory.getLogger(BytecodeInstruction.class);

    // identification of a byteCode instruction inside EvoSuite
    protected ClassLoader classLoader;
    protected String className;
    protected String methodName;
    protected int instructionId;
    protected int bytecodeOffset;

    // auxiliary information
    private int lineNumber = -1;

    // experiment: also searching through all CFG nodes in order to determine an
    // instruction BasicBlock might be a little to expensive too just to safe
    // space for one reference
    private BasicBlock basicBlock;

    /**
     * Generates a ByteCodeInstruction instance that represents a byteCode
     * instruction as indicated by the given ASMNode in the given method and
     * class.
     *
     * @param classLoader    class loader
     * @param className      a {@link java.lang.String} object.
     * @param methodName     a {@link java.lang.String} object.
     * @param instructionId  a int.
     * @param bytecodeOffset a int.
     * @param asmNode        a {@link org.objectweb.asm.tree.AbstractInsnNode} object.
     */
    public BytecodeInstruction(ClassLoader classLoader, String className,
                               String methodName, int instructionId, int bytecodeOffset, AbstractInsnNode asmNode) {

        if (className == null || methodName == null || asmNode == null) {
            throw new IllegalArgumentException("null given");
        }
        if (instructionId < 0) {
            throw new IllegalArgumentException(
                    "expect instructionId to be positive, not " + instructionId);
        }

        this.instructionId = instructionId;
        this.bytecodeOffset = bytecodeOffset;
        this.asmNode = asmNode;

        this.classLoader = classLoader;

        setClassName(className);
        setMethodName(methodName);
    }

    /**
     * Can represent any byteCode instruction.
     *
     * @param wrap a {@link org.evosuite.graphs.cfg.BytecodeInstruction} object.
     */
    public BytecodeInstruction(BytecodeInstruction wrap) {

        this(wrap.classLoader, wrap.className, wrap.methodName, wrap.instructionId,
                wrap.bytecodeOffset, wrap.asmNode, wrap.lineNumber, wrap.basicBlock);
        this.frame = wrap.frame;
    }

    /**
     * Constructor for BytecodeInstruction.
     *
     * @param classLoader    class loader
     * @param className      a {@link java.lang.String} object.
     * @param methodName     a {@link java.lang.String} object.
     * @param instructionId  a int.
     * @param bytecodeOffset a int.
     * @param asmNode        a {@link org.objectweb.asm.tree.AbstractInsnNode} object.
     * @param lineNumber     a int.
     * @param basicBlock     a {@link org.evosuite.graphs.cfg.BasicBlock} object.
     */
    public BytecodeInstruction(ClassLoader classLoader, String className,
                               String methodName, int instructionId, int bytecodeOffset, AbstractInsnNode asmNode,
                               int lineNumber, BasicBlock basicBlock) {

        this(classLoader, className, methodName, instructionId, bytecodeOffset, asmNode,
                lineNumber);

        this.basicBlock = basicBlock;
    }

    /**
     * Constructor for BytecodeInstruction.
     *
     * @param classLoader    class loader
     * @param className      a {@link java.lang.String} object.
     * @param methodName     a {@link java.lang.String} object.
     * @param instructionId  a int.
     * @param bytecodeOffset a int.
     * @param asmNode        a {@link org.objectweb.asm.tree.AbstractInsnNode} object.
     * @param lineNumber     a int.
     */
    public BytecodeInstruction(ClassLoader classLoader, String className,
                               String methodName, int instructionId, int bytecodeOffset, AbstractInsnNode asmNode,
                               int lineNumber) {

        this(classLoader, className, methodName, instructionId, bytecodeOffset, asmNode);

        if (lineNumber != -1) {
            setLineNumber(lineNumber);
        }
    }

    // getter + setter

    private void setMethodName(String methodName) {
        if (methodName == null) {
            throw new IllegalArgumentException("null given");
        }

        this.methodName = methodName;
    }

    private void setClassName(String className) {
        if (className == null) {
            throw new IllegalArgumentException("null given");
        }

        this.className = className;
    }

    /**
     * setCFGFrame.
     *
     * @param frame a {@link org.evosuite.graphs.cfg.CFGFrame} object.
     */
    public void setCFGFrame(CFGFrame frame) {
        this.frame = frame;
    }

    // --- Field Management ---

    /**
     * {@inheritDoc}
     */
    @Override
    public int getInstructionId() {
        return instructionId;
    }

    /**
     * getBytecodeOffset.
     *
     * @return a int.
     */
    public int getBytecodeOffset() {
        return bytecodeOffset;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getMethodName() {
        return methodName;
    }

    /**
     * Getter for the field <code>className</code>.
     *
     * @return a {@link java.lang.String} object.
     */
    @Override
    public String getClassName() {
        return className;
    }

    /**
     * getName.
     *
     * @return a {@link java.lang.String} object.
     */
    public String getName() {
        return "BytecodeInstruction " + instructionId + " in " + methodName;
    }

    /**
     * Return's the BasicBlock that contain's this instruction in it's CFG.
     *
     * <p>If no BasicBlock containing this instruction was created yet, null is
     * returned.
     *
     * @return a {@link org.evosuite.graphs.cfg.BasicBlock} object.
     */
    public BasicBlock getBasicBlock() {
        if (!hasBasicBlockSet()) {
            retrieveBasicBlock();
        }
        return basicBlock;
    }

    private void retrieveBasicBlock() {

        if (basicBlock == null) {
            basicBlock = getActualCFG().getBlockOf(this);
        }
    }

    /**
     * Once the CFG has been asked for this instruction's BasicBlock it sets
     * this instance's internal basicBlock field.
     *
     * @param block a {@link org.evosuite.graphs.cfg.BasicBlock} object.
     */
    public void setBasicBlock(BasicBlock block) {
        if (block == null) {
            throw new IllegalArgumentException("null given");
        }

        if (!block.getClassName().equals(getClassName())
                || !block.getMethodName().equals(getMethodName())) {
            throw new IllegalArgumentException(
                    "expect block to be for the same method and class as this instruction");
        }
        if (this.basicBlock != null) {
            throw new IllegalArgumentException(
                    "basicBlock already set! not allowed to overwrite");
        }

        this.basicBlock = block;
    }

    /**
     * Checks whether this instance's basicBlock has already been set by the CFG.
     *
     * @return a boolean.
     */
    public boolean hasBasicBlockSet() {
        return basicBlock != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getLineNumber() {

        if (lineNumber == -1 && isLineNumber()) {
            retrieveLineNumber();
        }

        return lineNumber;
    }

    /**
     * Setter for the field <code>lineNumber</code>.
     *
     * @param lineNumber a int.
     */
    public void setLineNumber(int lineNumber) {
        if (lineNumber <= 0) {
            throw new IllegalArgumentException(
                    "expect lineNumber value to be positive");
        }

        if (isLabel()) {
            return;
        }

        if (isLineNumber()) {
            int asmLine = super.getLineNumber();
            // sanity check
            if (lineNumber != -1 && asmLine != lineNumber) {
                throw new IllegalStateException(
                        "linenumber instruction has lineNumber field set to a value "
                                + "different from instruction linenumber");
            }
            this.lineNumber = asmLine;
        } else {
            this.lineNumber = lineNumber;
        }
    }

    /**
     * At first, if this instruction constitutes a line number instruction this
     * method tries to retrieve the lineNumber from the underlying asmNode and
     * set the lineNumber field to the value given by the asmNode.
     *
     * <p>This can lead to an IllegalStateException, should the lineNumber field
     * have been set to another value previously.
     *
     * <p>After that, if the lineNumber field is still not initialized, this method
     * returns false Otherwise it returns true.
     *
     * @return a boolean.
     */
    public boolean hasLineNumberSet() {
        retrieveLineNumber();
        return lineNumber != -1;
    }

    /**
     * If the underlying ASMNode is a LineNumberNode the lineNumber field of
     * this instance will be set to the lineNumber contained in that
     * LineNumberNode.
     *
     * <p>Should the lineNumber field have been set to a value different from that
     * contained in the asmNode, this method throws an IllegalStateExeption.
     */
    private void retrieveLineNumber() {
        if (isLineNumber()) {
            int asmLine = super.getLineNumber();
            // sanity check
            if (this.lineNumber != -1 && asmLine != this.lineNumber) {
                throw new IllegalStateException(
                        "lineNumber field was manually set to a value different from the actual lineNumber "
                                + "contained in LineNumberNode");
            }
            this.lineNumber = asmLine;
        }
    }

    // --- graph section ---

    /**
     * Returns the ActualControlFlowGraph of this instructions method.
     *
     * <p>Convenience method. Redirects the call to GraphPool.getActualCFG().
     *
     * @return a {@link org.evosuite.graphs.cfg.ActualControlFlowGraph} object.
     */
    public ActualControlFlowGraph getActualCFG() {

        ActualControlFlowGraph myCFG = GraphPool.getInstance(classLoader).getActualCFG(className,
                methodName);
        if (myCFG == null) {
            throw new IllegalStateException(
                    "expect GraphPool to know CFG for every method for which an instruction is known");
        }

        return myCFG;
    }

    /**
     * Returns the RawControlFlowGraph of this instructions method.
     *
     * <p>Convenience method. Redirects the call to GraphPool.getRawCFG().
     *
     * @return a {@link org.evosuite.graphs.cfg.RawControlFlowGraph} object.
     */
    public RawControlFlowGraph getRawCFG() {

        RawControlFlowGraph myCFG = GraphPool.getInstance(classLoader).getRawCFG(className,
                methodName);
        if (myCFG == null) {
            throw new IllegalStateException(
                    "expect GraphPool to know CFG for every method for which an instruction is known");
        }

        return myCFG;
    }

    /**
     * Returns the ControlDependenceGraph of this instructions method.
     *
     * <p>Convenience method. Redirects the call to GraphPool.getCDG().
     *
     * @return a {@link org.evosuite.graphs.cdg.ControlDependenceGraph} object.
     */
    public ControlDependenceGraph getCDG() {

        ControlDependenceGraph myCDG = GraphPool.getInstance(classLoader).getCDG(className,
                methodName);
        if (myCDG == null) {
            throw new IllegalStateException(
                    "expect GraphPool to know CDG for every method for which an instruction is known");
        }

        return myCDG;
    }

    // --- CDG-Section ---

    /**
     * Returns a cfg.Branch object for each branch this instruction is control
     * dependent on as determined by the ControlDependenceGraph.
     *
     * <p>If this instruction is only dependent on the root branch this method returns an
     * empty set.
     *
     * <p>If this instruction is a Branch and it is dependent on itself - which can
     * happen in loops for example - the returned set WILL contain this. If you
     * do not need the full set in order to avoid loops, call
     * getAllControlDependentBranches instead.
     *
     * @return a {@link java.util.Set} object.
     */
    public Set<ControlDependency> getControlDependencies() {

        BasicBlock myBlock = getBasicBlock();

        // return new
        // HashSet<ControlDependency>(myBlock.getControlDependencies());
        return myBlock.getControlDependencies();
    }

    /**
     * Returns a random Branch among all Branches this instruction
     * is control dependent on.
     *
     * <p>If this instruction is only dependent on the root branch, this method
     * returns null.
     *
     * <p>Since EvoSuite was previously unable to detect multiple control
     * dependencies for one instruction this method serves as a backwards
     * compatibility bridge.
     *
     * @return a {@link org.evosuite.coverage.branch.Branch} object.
     */
    public Branch getControlDependentBranch() {

        Set<ControlDependency> controlDependentBranches = getControlDependencies();

        for (ControlDependency cd : controlDependentBranches) {
            return cd.getBranch();
        }

        return null; // root branch
    }

    /**
     * Returns all branchIds of Branches this instruction is directly control
     * dependent on as determined by the ControlDependenceGraph for this
     * instruction's method.
     *
     * <p>If this instruction is control dependent on the root branch the id -1
     * will be contained in this set.
     *
     * @return a {@link java.util.Set} object.
     */
    public Set<Integer> getControlDependentBranchIds() {

        BasicBlock myBlock = getBasicBlock();

        return myBlock.getControlDependentBranchIds();
    }

    /**
     * Determines whether or not this instruction is control dependent on the
     * root branch of it's method by calling getControlDependentBranchIds() to
     * see if the return contains -1.
     *
     * @return a boolean.
     */
    public boolean isRootBranchDependent() {
        return getControlDependencies().isEmpty();
    }

    /**
     * Returns a random branchId among all branchIds this
     * instruction is control dependent on.
     *
     * <p>This method returns -1 if getControlDependentBranch() returns null,
     * otherwise that Branch's branchId is returned.
     *
     * <p>Note: The returned branchExpressionValue comes from the same Branch
     * getControlDependentBranch() and getControlDependentBranchId() return.
     *
     * <p>Since EvoSuite was previously unable to detect multiple control
     * dependencies for one instruction this method serves as a backwards
     * compatibility bridge.
     *
     * @return a int.
     */
    public int getControlDependentBranchId() {

        Branch b = getControlDependentBranch();
        if (b == null) {
            return -1;
        }

        return b.getActualBranchId();
    }

    /**
     * Returns the branchExpressionValue from a random Branch among
     * all Branches this instruction is control dependent on.
     *
     * <p>This method returns true if getControlDependentBranch() returns null,
     * otherwise that Branch's branchExpressionValue is returned.
     *
     * <p>Note: The returned branchExpressionValue comes from the same Branch
     * getControlDependentBranch() and getControlDependentBranchId() return.
     *
     * <p>Since EvoSuite was previously unable to detect multiple control
     * dependencies for one instruction this method serves as a backwards
     * compatibility bridge.
     *
     * @return a boolean.
     */
    public boolean getControlDependentBranchExpressionValue() {

        Branch b = getControlDependentBranch();
        return getBranchExpressionValue(b);
    }

    /**
     * getBranchExpressionValue.
     *
     * @param b a {@link org.evosuite.coverage.branch.Branch} object.
     * @return a boolean.
     */
    public boolean getBranchExpressionValue(Branch b) {
        if (!isDirectlyControlDependentOn(b)) {
            throw new IllegalArgumentException(
                    "this method can only be called for branches that "
                            + "this instruction is directly control dependent on.");
        }

        if (b == null) {
            return true; // root branch special case
        }

        return getControlDependency(b).getBranchExpressionValue();
    }

    /**
     * Determines whether this BytecodeInstruction is directly control dependent
     * on the given Branch. Meaning within this instruction CDG there is an
     * incoming ControlFlowEdge to this instructions BasicBlock holding the
     * given Branch as it's branchInstruction.
     *
     * <p>If the given Branch is null, this method checks whether the this
     * instruction is control dependent on the root branch of it's method.
     *
     * @param branch a {@link org.evosuite.coverage.branch.Branch} object.
     * @return a boolean.
     */
    public boolean isDirectlyControlDependentOn(Branch branch) {
        if (branch == null) {
            return getControlDependentBranchIds().contains(-1);
        }

        for (ControlDependency cd : getControlDependencies()) {
            if (cd.getBranch().equals(branch)) {
                return true;
            }
        }

        return false;
    }

    /**
     * getControlDependency.
     *
     * @param branch a {@link org.evosuite.coverage.branch.Branch} object.
     * @return a {@link org.evosuite.graphs.cfg.ControlDependency} object.
     */
    public ControlDependency getControlDependency(Branch branch) {
        if (!isDirectlyControlDependentOn(branch)) {
            throw new IllegalArgumentException(
                    "instruction not directly control dependent on given branch");
        }

        for (ControlDependency cd : getControlDependencies()) {
            if (cd.getBranch().equals(branch)) {
                return cd;
            }
        }

        throw new IllegalStateException(
                "expect getControlDependencies() to contain a CD for each branch that "
                        + "isDirectlyControlDependentOn() returns true on");
    }

    /**
     * getCDGDepth.
     *
     * @return a int.
     */
    public int getCDGDepth() {
        int min = Integer.MAX_VALUE;
        Set<ControlDependency> dependencies = getControlDependencies();
        if (dependencies.isEmpty()) {
            min = 1;
        }
        for (ControlDependency dependency : dependencies) {
            int depth = getCDG().getControlDependenceDepth(dependency);
            if (depth < min) {
                min = depth;
            }
        }
        return min;
    }

    // String methods

    /**
     * explain.
     *
     * @return a {@link java.lang.String} object.
     */
    public String explain() {
        if (isBranch()) {
            if (BranchPool.getInstance(classLoader).isKnownAsBranch(this)) {
                Branch b = BranchPool.getInstance(classLoader).getBranchForInstruction(this);
                if (b == null) {
                    throw new IllegalStateException(
                            "expect BranchPool to be able to return Branches for instructions fullfilling "
                                    + "BranchPool.isKnownAsBranch()");
                }

                return "Branch " + b.getActualBranchId() + " - "
                        + getInstructionType();
            }
            return "UNKNOWN Branch I" + instructionId + " "
                    + getInstructionType() + ", jump to " + ((JumpInsnNode) asmNode).label.getLabel();
        }

        return getASMNodeString();
    }

    /**
     * getASMNodeString.
     *
     * @return a {@link java.lang.String} object.
     */
    public String getASMNodeString() {
        String type = getType();
        String opcode = getInstructionType();

        String stack = "";
        if (frame == null) {
            stack = "null";
        } else {
            for (int i = 0; i < frame.getStackSize(); i++) {
                stack += frame.getStack(i) + ",";
            }
        }

        if (asmNode instanceof LabelNode) {
            return "LABEL " + ((LabelNode) asmNode).getLabel().toString();
        } else if (asmNode instanceof FieldInsnNode) {
            return "Field" + " " + ((FieldInsnNode) asmNode).owner + "."
                    + ((FieldInsnNode) asmNode).name + " Type=" + type
                    + ", Opcode=" + opcode;
        } else if (asmNode instanceof FrameNode) {
            return "Frame" + " " + asmNode.getOpcode() + " Type=" + type
                    + ", Opcode=" + opcode;
        } else if (asmNode instanceof IincInsnNode) {
            return "IINC " + ((IincInsnNode) asmNode).var + " Type=" + type
                    + ", Opcode=" + opcode;
        } else if (asmNode instanceof InsnNode) {
            return "" + opcode;
        } else if (asmNode instanceof IntInsnNode) {
            return "INT " + ((IntInsnNode) asmNode).operand + " Type=" + type
                    + ", Opcode=" + opcode;
        } else if (asmNode instanceof MethodInsnNode) {
            return opcode + " " + ((MethodInsnNode) asmNode).owner + "." + ((MethodInsnNode) asmNode).name
                    + ((MethodInsnNode) asmNode).desc;
        } else if (asmNode instanceof JumpInsnNode) {
            return "JUMP " + ((JumpInsnNode) asmNode).label.getLabel()
                    + " Type=" + type + ", Opcode=" + opcode + ", Stack: "
                    + stack + " - Line: " + lineNumber;
        } else if (asmNode instanceof LdcInsnNode) {
            return "LDC " + ((LdcInsnNode) asmNode).cst + " Type=" + type; // +
            // ", Opcode=";
            // + opcode; // cst starts with mutationid if
            // this is location of mutation
        } else if (asmNode instanceof LineNumberNode) {
            return "LINE " + " " + ((LineNumberNode) asmNode).line;
        } else if (asmNode instanceof LookupSwitchInsnNode) {
            return "LookupSwitchInsnNode" + " " + asmNode.getOpcode()
                    + " Type=" + type + ", Opcode=" + opcode;
        } else if (asmNode instanceof MultiANewArrayInsnNode) {
            return "MULTIANEWARRAY " + " " + asmNode.getOpcode() + " Type="
                    + type + ", Opcode=" + opcode;
        } else if (asmNode instanceof TableSwitchInsnNode) {
            return "TableSwitchInsnNode" + " " + asmNode.getOpcode() + " Type="
                    + type + ", Opcode=" + opcode;
        } else if (asmNode instanceof TypeInsnNode) {
            switch (asmNode.getOpcode()) {
                case Opcodes.NEW:
                    return "NEW " + ((TypeInsnNode) asmNode).desc;
                case Opcodes.ANEWARRAY:
                    return "ANEWARRAY " + ((TypeInsnNode) asmNode).desc;
                case Opcodes.CHECKCAST:
                    return "CHECKCAST " + ((TypeInsnNode) asmNode).desc;
                case Opcodes.INSTANCEOF:
                    return "INSTANCEOF " + ((TypeInsnNode) asmNode).desc;
                default:
                    return "Unknown node" + " Type=" + type + ", Opcode=" + opcode;
            }
        } else if (asmNode instanceof VarInsnNode) {
            return opcode + " " + ((VarInsnNode) asmNode).var;
        } else {
            return "Unknown node" + " Type=" + type + ", Opcode=" + opcode;
        }
    }

    /**
     * printFrameInformation.
     */
    public void printFrameInformation() {
        logger.info("Frame STACK:");
        for (int i = 0; i < frame.getStackSize(); i++) {
            SourceValue v = (SourceValue) frame.getStack(i);
            StringBuilder sb = new StringBuilder();
            sb.append(" ").append(i).append("(").append(v.insns.size()).append("): ");
            for (Object n : v.insns) {
                AbstractInsnNode node = (AbstractInsnNode) n;
                BytecodeInstruction ins = BytecodeInstructionPool.getInstance(classLoader).getInstruction(className,
                        methodName,
                        node);
                sb.append(ins.toString()).append(", ");
            }
            logger.info(sb.toString());
        }

        logger.info("Frame LOCALS:");
        for (int i = 1; i < frame.getLocals(); i++) {
            SourceValue v = (SourceValue) frame.getLocal(i);
            StringBuilder sb = new StringBuilder();
            sb.append(" ").append(i).append("(").append(v.insns.size()).append("): ");
            for (Object n : v.insns) {
                AbstractInsnNode node = (AbstractInsnNode) n;
                BytecodeInstruction ins = BytecodeInstructionPool.getInstance(classLoader).getInstruction(className,
                        methodName,
                        node);
                sb.append(ins.toString()).append(", ");
            }
            logger.info(sb.toString());
        }
    }

    // --- Inherited from Object ---

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {

        String r = "I" + instructionId;

        r += " (" + +bytecodeOffset + ")";
        r += " " + explain();

        if (hasLineNumberSet() && !isLineNumber()) {
            r += " l" + getLineNumber();
        }

        return r;
    }

    /**
     * Retrieves the corresponding Branch object from the BranchPool.
     *
     * <p>If this instruction is known by the BranchPool to be a Branch, you can
     * call this method in order to retrieve the corresponding Branch object
     * registered within the BranchPool.
     *
     * <p>Otherwise this method will return null.
     *
     * @return a {@link org.evosuite.coverage.branch.Branch} object.
     */
    public Branch toBranch() {

        try {
            return BranchPool.getInstance(classLoader).getBranchForInstruction(this);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * proceedsOwnConstructorInvocation.
     *
     * @return a boolean.
     */
    public boolean proceedsOwnConstructorInvocation() {

        RawControlFlowGraph cfg = getRawCFG();
        for (BytecodeInstruction other : cfg.vertexSet()) {
            if (other.isConstructorInvocation()
                    && other.isMethodCallOnSameObject()) {
                if (getInstructionId() < other.getInstructionId()) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * isWithinConstructor.
     *
     * @return a boolean.
     */
    public boolean isWithinConstructor() {
        return getMethodName().startsWith("<init>");
    }

    /**
     * isLastInstructionInMethod.
     *
     * @return a boolean.
     */
    public boolean isLastInstructionInMethod() {
        return equals(getRawCFG().getInstructionWithBiggestId());
    }

    /**
     * canBeExitPoint.
     *
     * @return a boolean.
     */
    public boolean canBeExitPoint() {
        return canReturnFromMethod() || isLastInstructionInMethod();
    }

    /**
     * Returns the RawCFG of the method called by this instruction.
     *
     * @return a {@link org.evosuite.graphs.cfg.RawControlFlowGraph} object.
     */
    public RawControlFlowGraph getCalledCFG() {
        if (!isMethodCall()) {
            return null;
        }

        return GraphPool.getInstance(classLoader).getRawCFG(getCalledMethodsClass(),
                getCalledMethod());
    }

    /**
     * Determines whether this instruction calls a method on its own Object
     * ('this').
     *
     * <p>This is done using the getSourceOfMethodInvocationInstruction() method
     * and checking if the return of that method loads this using loadsReferenceToThis().
     *
     * @return a boolean.
     */
    public boolean isMethodCallOnSameObject() {
        BytecodeInstruction srcInstruction = getSourceOfMethodInvocationInstruction();
        if (srcInstruction == null) {
            return false;
        }
        return srcInstruction.loadsReferenceToThis();
    }

    /**
     * Determines whether this instruction calls a method on a field variable.
     *
     * <p>This is done using the getSourceOfMethodInvocationInstruction() method
     * and checking if the return of that method is a field use instruction.
     *
     * @return a boolean.
     */
    public boolean isMethodCallOfField() {
        if (!this.isMethodCall()) {
            return false;
        }
        if (this.isInvokeStatic()) {
            return false;
        }
        // If the instruction belongs to static initialization block of the
        // class, then the method call cannot be done on a fields.
        if (this.methodName.contains("<clinit>")) {
            return false;
        }
        BytecodeInstruction srcInstruction = getSourceOfMethodInvocationInstruction();
        if (srcInstruction == null) {
            return false;
        }

        //is a field use? But field uses are also "GETSTATIC"
        if (srcInstruction.isFieldNodeUse()) {

            //is static? if not, return yes. This control is not necessary in theory, but you never know...
            if (srcInstruction.isStaticDefUse()) {
                //is static, check if the name of the class that contain the static field is equals
                // to the current class name
                //if is equals, return true, otherwise we are in a case where we are calling a field
                // over an external static class
                //e.g. System.out
                if (srcInstruction.asmNode instanceof FieldInsnNode) {
                    String classNameField = ((FieldInsnNode) srcInstruction.asmNode).owner;
                    classNameField = classNameField.replace('/', '.');
                    return classNameField.equals(className);
                }
            } else {
                return true;
            }
        }
        return false;

    }

    /**
     * Determines the name of the field variable this method call is invoked on.
     *
     * <p>This is done using the getSourceOfMethodInvocationInstruction() method
     * and returning its variable name.
     *
     * @return a {@link java.lang.String} object.
     */
    @Override
    public String getFieldMethodCallName() {
        BytecodeInstruction srcInstruction = getSourceOfMethodInvocationInstruction();
        if (srcInstruction == null) {
            return null;
        }
        return srcInstruction.getVariableName();
    }

    /**
     * If this is a method call instruction this method will return the
     * instruction that loaded the reference of the Object the method is invoked
     * onto the stack.
     *
     * <p>This is done using getSourceOfStackInstruction().
     *
     * <p>The reference is found on top of the stack minus the number of the called
     * methods argument.
     *
     * @return the source of method invocation instruction
     */
    public BytecodeInstruction getSourceOfMethodInvocationInstruction() {
        if (!isMethodCall()) {
            return null;
        }

        // the object on which this method is called is on top of the stack
        // minus the number of arguments the called method has
        return getSourceOfStackInstruction(getCalledMethodsArgumentCount());
    }

    /**
     * If this instruction is an array instruction this method will return the
     * BytecodeInstruction that loaded the reference of the array onto the
     * stack.
     *
     * <p>This is done using getSourceOfStackMethod().
     *
     * <p>The reference is found on top of the stack minus two.
     *
     * @return the source of array reference
     */
    public BytecodeInstruction getSourceOfArrayReference() {
        if (isArrayStoreInstruction()) {
            // when reaching an array store instruction the stack should end in
            // <arrayref>,<index>,<value>. so the array reference is on top of the
            // stack minus two
            return getSourceOfStackInstruction(2);

        } else if (isArrayLoadInstruction()) {
            // when reaching an array store instruction the stack should end in
            // <arrayref>,<index>. so the array reference is on top of the
            // stack minus one
            return getSourceOfStackInstruction(1);

        } else {
            return null;
        }

    }

    /**
     * Returns the BytecodeInstruction that loaded the reference
     * which is located on top of the stack minus positionFromTop when this
     * instruction is executed.
     *
     * <p>This is done using the CFGFrame created by the SourceInterpreter() of the
     * BytecodeAnalyzer via the CFGGenerator.
     *
     * <p>Note that this method may return null. This can happen when aliasing is
     * involved. For example for method invocations on objects this can happen
     * when you first store the object in a local variable and then call a
     * method on that variable.
     *
     * <p>see PairTestClass.sourceCallerTest() for an even worse example.
     *
     * <p>TODO: this could be done better by following the SourceValues even
     * further.
     *
     * @param positionFromTop position from top of stack
     * @return the source of stack instruction
     */
    public BytecodeInstruction getSourceOfStackInstruction(int positionFromTop) {
        if (frame == null) {
            throw new IllegalStateException(
                    "expect each BytecodeInstruction to have its CFGFrame set");
        }

        int stackPos = frame.getStackSize() - (1 + positionFromTop);
        if (stackPos < 0) {
            logger.warn("Stack trace: ", new Throwable());
            return null;
        }
        SourceValue source = (SourceValue) frame.getStack(stackPos);
        if (source.insns.size() != 1) {
            // we don't know for sure, let's be conservative
            return null;
        }
        Object sourceIns = source.insns.iterator().next();
        AbstractInsnNode sourceInstruction = (AbstractInsnNode) sourceIns;
        BytecodeInstruction src = BytecodeInstructionPool.getInstance(classLoader).getInstruction(className,
                methodName,
                sourceInstruction);
        return src;
    }

    /**
     * Checks if this is a field method call definition.
     *
     * @return true if it is a field method call definition
     */
    public boolean isFieldMethodCallDefinition() {
        if (!isMethodCallOfField()) {
            return false;
        }
        // before this instruction is categorized in the DefUsePool we do not
        // know if
        // this instruction calls a pure or impure method, so we just label it
        // as both a Use and Definition for now
        if (!(DefUsePool.isKnownAsUse(this) && DefUsePool
                .isKnownAsFieldMethodCall(this))) {
            return true;
        }
        // once the DefUsePool knows about this instruction we only return true
        // if it was
        // categorized as a Use
        return DefUsePool.isKnownAsDefinition(this);
    }

    /**
     * Checks if this is a field method call use.
     *
     * @return true if it is a field method call use
     */
    public boolean isFieldMethodCallUse() {
        if (!isMethodCallOfField()) {
            return false;
        }
        // before this instruction is categorized in the DefUsePool we do not
        // know if
        // this instruction calls a pure or impure method, so we just label it
        // as both a Use and Definition for now
        if ((DefUsePool.isKnownAsFieldMethodCall(this) && !DefUsePool.isKnownAsDefinition(this))) {
            return true;
        }
        // once the DefUsePool knows about this instruction we only return true
        // if it was
        // categorized as a Use
        return DefUsePool.isKnownAsUse(this);
    }

    /**
     * isCallToPublicMethod.
     *
     * @return a boolean.
     */
    public boolean isCallToPublicMethod() {
        if (!isMethodCall()) {
            return false;
        }

        if (getCalledCFG() == null) {
            // TODO not sure if I am supposed to throw an Exception at this
            // point
            return false;
        }

        return getCalledCFG().isPublicMethod();
    }

    /**
     * isCallToStaticMethod.
     *
     * @return a boolean.
     */
    public boolean isCallToStaticMethod() {
        if (!isMethodCall()) {
            return false;
        }

        if (getCalledCFG() == null) {
            // TODO not sure if I am supposed to throw an Exception at this
            // point
            return false;
        }

        return getCalledCFG().isStaticMethod();
    }

    /**
     * canBeInstrumented.
     *
     * @return a boolean.
     */
    public boolean canBeInstrumented() {
        return !isWithinConstructor() || !proceedsOwnConstructorInvocation();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result
                + ((className == null) ? 0 : className.hashCode());
        result = prime * result + instructionId;
        result = prime * result
                + ((methodName == null) ? 0 : methodName.hashCode());
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        BytecodeInstruction other = (BytecodeInstruction) obj;
        if (className == null) {
            if (other.className != null) {
                return false;
            }
        } else if (!className.equals(other.className)) {
            return false;
        }
        if (instructionId != other.instructionId) {
            return false;
        }
        if (methodName == null) {
            return other.methodName == null;
        } else {
            return methodName.equals(other.methodName);
        }
    }

    // inherited from Object

    @Override
    public int compareTo(BytecodeInstruction o) {
        int diff = getLineNumber() - o.getLineNumber();
        if (diff != 0) {
            return diff;
        }
        if (this.className != null) {
            if (o.className == null) {
                return 1;
            }
            diff = this.className.compareTo(o.className);
            if (diff != 0) {
                return diff;
            }
        } else if (o.className != null) {
            return -1;
        }

        if (this.methodName != null) {
            if (o.methodName == null) {
                return 1;
            }
            diff = this.methodName.compareTo(o.methodName);
            if (diff != 0) {
                return diff;
            }
        } else if (o.methodName != null) {
            return -1;
        }
        return Integer.compare(this.instructionId, o.instructionId);
    }

    // --- EvoObj: operand-stack / def-use accessors used by the OCG slicer ---
    // (see the evoobj re-implementation plan, Appendix A)

    /**
     * The {@link CFGFrame} attached to this instruction, or null if none has been set.
     *
     * @return the CFG frame of this instruction
     */
    public CFGFrame getFrame() {
        return frame;
    }

    /**
     * Generalization of {@link #getSourceOfStackInstruction(int)}: returns <em>all</em>
     * instructions that may have produced the operand at the given position from the top of
     * the operand stack (a {@link SourceValue} can carry several producing instructions, e.g.
     * across control-flow joins). Returns null on stack underflow.
     *
     * @param positionFromTop position from the top of the operand stack (0 = topmost)
     * @return the producing instructions, or null if the stack position is invalid
     */
    public List<BytecodeInstruction> getSourceOfStackInstructionList(int positionFromTop) {
        if (frame == null) {
            throw new IllegalStateException(
                    "expect each BytecodeInstruction to have its CFGFrame set");
        }

        int stackPos = frame.getStackSize() - (1 + positionFromTop);
        if (stackPos < 0) {
            return null;
        }

        List<BytecodeInstruction> list = new ArrayList<>();
        SourceValue source = (SourceValue) frame.getStack(stackPos);
        for (Object obj : source.insns) {
            if (obj instanceof AbstractInsnNode) {
                AbstractInsnNode sourceInstruction = (AbstractInsnNode) obj;
                BytecodeInstruction src = BytecodeInstructionPool.getInstance(classLoader)
                        .getInstruction(className, methodName, sourceInstruction);
                list.add(src);
            }
        }
        return list;
    }

    /**
     * Near-duplicate name of {@link #getSourceOfStackInstructionList(int)} present in the
     * reference implementation; kept as an alias so ported call sites resolve.
     *
     * @param positionFromTop position from the top of the operand stack (0 = topmost)
     * @return the producing instructions, or null if the stack position is invalid
     */
    public List<BytecodeInstruction> getSourceListOfStackInstruction(int positionFromTop) {
        return getSourceOfStackInstructionList(positionFromTop);
    }

    /**
     * All producing instructions of all operands this instruction pops from the stack, i.e.
     * for each of the {@link #getOperandNum()} operand slots, the instructions referenced by
     * that slot's {@link SourceValue}. Empty when no frame is set.
     *
     * @return the producing instructions of all operands
     */
    public List<BytecodeInstruction> getOperands() {
        List<BytecodeInstruction> operands = new ArrayList<>();
        CFGFrame frame = this.getFrame();

        if (frame == null) {
            return operands;
        }

        InstrumentingClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
        ActualControlFlowGraph cfg = GraphPool.getInstance(classLoader).getActualCFG(this.getClassName(),
                this.getMethodName());
        String className = cfg.getClassName();
        String methodName = cfg.getMethodName();
        MethodNode node = DefUseAnalyzer.getMethodNode(classLoader, className, methodName);

        for (int i = 0; i < this.getOperandNum(); i++) {
            int index = frame.getStackSize() - i - 1;
            Value val = frame.getStack(index);

            if (val instanceof SourceValue) {
                SourceValue srcValue = (SourceValue) val;
                for (AbstractInsnNode insNode : srcValue.insns) {
                    BytecodeInstruction defIns = DefUseAnalyzer.convert2BytecodeInstruction(cfg, node, insNode);
                    if (defIns != null) {
                        operands.add(defIns);
                    }
                }
            }
        }
        return operands;
    }

    /**
     * The neighbouring instruction with the preceding instructionId in this method's
     * {@link ActualControlFlowGraph}, or null at the method entry.
     *
     * @return the previous instruction, or null
     */
    public BytecodeInstruction getPreviousInstruction() {
        ActualControlFlowGraph graph = this.getActualCFG();
        if (this.instructionId == 0) {
            return null;
        }
        return graph.getInstruction(this.instructionId - 1);
    }

    /**
     * The neighbouring instruction with the following instructionId in this method's
     * {@link ActualControlFlowGraph}, or null at the method boundary.
     *
     * @return the next instruction, or null
     */
    public BytecodeInstruction getNextInstruction() {
        ActualControlFlowGraph graph = this.getActualCFG();
        return graph.getInstruction(this.instructionId + 1);
    }

    /**
     * For a local-variable-use instruction (an xLOAD), the 0-based position of the loaded slot
     * among the declared parameters of the method, or -1 if the slot is not a parameter (a
     * genuine local, or {@code this}). Wide types (long/double) occupy two slots.
     *
     * @return the 0-based parameter position, or -1
     */
    public int getParameterPosition() {
        if (isLocalVariableUse()) {
            int slot = getLocalVariableSlot();

            String[] parameters = MethodUtil.parseSignature(methodName);

            int paramNum = parameters.length - 1;
            if (!this.getActualCFG().isStaticMethod()) {
                slot--;
            }

            int cursor = 0;
            int position = 0;
            for (int i = 0; i < paramNum; i++) {
                String param = parameters[i];
                if (cursor == slot) {
                    return position;
                } else {
                    position++;
                    if (param.equals("double") || param.equals("long")) {
                        cursor += 2;
                    } else {
                        cursor++;
                    }
                }
            }

            if (slot == -1) {
                return -1;
            }
            if (slot > paramNum - 1) {
                return -1;
            }

            return slot;
        }

        return -1;
    }

    /**
     * Whether this instruction loads a method parameter.
     *
     * @return true if this is a local-variable use that resolves to a parameter
     */
    public boolean isParameter() {
        if (isLocalVariableUse()) {
            return this.getParameterPosition() != -1;
        }

        return false;
    }

    /**
     * The number of values this instruction pops from the operand stack, derived from its
     * opcode.
     *
     * @return the operand count
     */
    public int getOperandNum() {
        if (this.asmNode.getType() == AbstractInsnNode.FIELD_INSN) {
            if (this.asmNode.getOpcode() == Opcodes.GETSTATIC) {
                return 0;
            } else if (this.asmNode.getOpcode() == Opcodes.GETFIELD || this.asmNode.getOpcode() == Opcodes.PUTSTATIC) {
                return 1;
            } else if (this.asmNode.getOpcode() == Opcodes.PUTFIELD) {
                return 2;
            }
        } else if (this.asmNode.getType() == AbstractInsnNode.JUMP_INSN) {
            int op = this.asmNode.getOpcode();
            if (op == Opcodes.IFEQ || op == Opcodes.IFNE || op == Opcodes.IFGE || op == Opcodes.IFGT
                    || op == Opcodes.IFLE || op == Opcodes.IFLT || op == Opcodes.IFNULL || op == Opcodes.IFNONNULL) {
                return 1;
            } else if (op == Opcodes.IF_ACMPEQ || op == Opcodes.IF_ACMPNE || op == Opcodes.IF_ICMPEQ
                    || op == Opcodes.IF_ICMPGE || op == Opcodes.IF_ICMPGT || op == Opcodes.IF_ICMPLE
                    || op == Opcodes.IF_ICMPLT || op == Opcodes.IF_ICMPNE) {
                return 2;
            }
        } else if (this.asmNode.getType() == AbstractInsnNode.METHOD_INSN) {
            if (this.asmNode.getOpcode() == Opcodes.INVOKESTATIC) {
                return this.getCalledMethodsArgumentCount();
            }
            return this.getCalledMethodsArgumentCount() + 1;
        } else if (this.asmNode.getType() == AbstractInsnNode.INVOKE_DYNAMIC_INSN) {
            // Modern javac emits invokedynamic for lambdas / string concatenation (plan E6):
            // treat the call site as opaque, popping only its declared arguments.
            return org.objectweb.asm.Type.getArgumentTypes(((InvokeDynamicInsnNode) this.asmNode).desc).length;
        } else if (this.asmNode.getOpcode() == Opcodes.NEW) {
            return 0;
        } else if (this.asmNode.getType() == AbstractInsnNode.TYPE_INSN) {
            return 1;
        } else if (this.isLocalVariableUse()) {
            return 0;
        } else if (this.isLocalVariableDefinition()) {
            return 1;
        } else if (this.isReturn()) {
            return 1;
        } else if (this.isArrayLoadInstruction()) {
            return 2;
        } else if (this.isArrayStoreInstruction()) {
            return 3;
        } else if (this.isConstant()) {
            return 0;
        } else if (this.isConvertInstruction()) {
            return 1;
        } else if (this.isCheckAlgorithmic()) {
            return 2;
        } else if (this.asmNode.getOpcode() == Opcodes.ACONST_NULL) {
            return 0;
        } else if (this.asmNode.getOpcode() == Opcodes.DCMPG || this.asmNode.getOpcode() == Opcodes.DCMPL
                || this.asmNode.getOpcode() == Opcodes.FCMPG || this.asmNode.getOpcode() == Opcodes.FCMPL
                || this.asmNode.getOpcode() == Opcodes.LCMP) {
            return 2;
        } else if (this.asmNode.getOpcode() == Opcodes.MULTIANEWARRAY) {
            int count = 0;
            while (this.getSourceOfStackInstruction(count) != null) {
                count++;
            }
            return count;
        } else if (this.asmNode.getOpcode() == Opcodes.ARRAYLENGTH) {
            return 1;
        } else if (this.asmNode.getOpcode() == Opcodes.ATHROW) {
            return 1;
        } else if (this.asmNode.getOpcode() == Opcodes.IADD || this.asmNode.getOpcode() == Opcodes.FADD
                || this.asmNode.getOpcode() == Opcodes.DADD || this.asmNode.getOpcode() == Opcodes.LADD
                || this.asmNode.getOpcode() == Opcodes.IDIV || this.asmNode.getOpcode() == Opcodes.DDIV
                || this.asmNode.getOpcode() == Opcodes.FDIV || this.asmNode.getOpcode() == Opcodes.LDIV
                || this.asmNode.getOpcode() == Opcodes.IMUL || this.asmNode.getOpcode() == Opcodes.DMUL
                || this.asmNode.getOpcode() == Opcodes.FMUL || this.asmNode.getOpcode() == Opcodes.LMUL
                || this.asmNode.getOpcode() == Opcodes.IREM || this.asmNode.getOpcode() == Opcodes.DREM
                || this.asmNode.getOpcode() == Opcodes.FREM || this.asmNode.getOpcode() == Opcodes.LREM
                || this.asmNode.getOpcode() == Opcodes.ISUB || this.asmNode.getOpcode() == Opcodes.DSUB
                || this.asmNode.getOpcode() == Opcodes.FSUB || this.asmNode.getOpcode() == Opcodes.LSUB) {
            return 2;
        } else if (this.asmNode.getOpcode() == Opcodes.INEG || this.asmNode.getOpcode() == Opcodes.DNEG
                || this.asmNode.getOpcode() == Opcodes.FNEG || this.asmNode.getOpcode() == Opcodes.LNEG) {
            return 1;
        } else if (this.asmNode.getOpcode() == Opcodes.DUP) {
            return 1;
        } else if (this.asmNode.getOpcode() == Opcodes.DUP2) {
            return 2;
        } else if (this.asmNode.getOpcode() == Opcodes.DUP_X1) {
            return 2;
        } else if (this.asmNode.getOpcode() == Opcodes.DUP_X2) {
            return 3;
        } else if (this.asmNode.getOpcode() == Opcodes.DUP2_X1) {
            return 3;
        } else if (this.asmNode.getOpcode() == Opcodes.DUP2_X2) {
            return 4;
        } else if (this.asmNode.getOpcode() == Opcodes.BALOAD || this.asmNode.getOpcode() == Opcodes.BASTORE
                || this.asmNode.getOpcode() == Opcodes.CALOAD || this.asmNode.getOpcode() == Opcodes.CASTORE
                || this.asmNode.getOpcode() == Opcodes.SALOAD || this.asmNode.getOpcode() == Opcodes.SASTORE) {
            return 2;
        } else if (this.asmNode.getOpcode() == Opcodes.LOOKUPSWITCH || this.asmNode.getOpcode() == Opcodes.TABLESWITCH) {
            return 1;
        } else if (this.asmNode.getOpcode() == Opcodes.MONITORENTER || this.asmNode.getOpcode() == Opcodes.MONITOREXIT) {
            return 1;
        } else if (this.asmNode.getOpcode() == Opcodes.POP) {
            return 1;
        } else if (this.asmNode.getOpcode() == Opcodes.POP2) {
            return 2;
        } else if (this.asmNode.getOpcode() == Opcodes.SWAP) {
            return 2;
        }
        return 0;
    }

    /**
     * Binary bit/shift arithmetic instructions (pop two operands).
     */
    private boolean isCheckAlgorithmic() {
        switch (this.asmNode.getOpcode()) {
            case Opcodes.ISHL:
            case Opcodes.ISHR:
            case Opcodes.IAND:
            case Opcodes.IUSHR:
            case Opcodes.LSHL:
            case Opcodes.LSHR:
            case Opcodes.LUSHR:
            case Opcodes.IOR:
            case Opcodes.IXOR:
            case Opcodes.LAND:
            case Opcodes.LOR:
            case Opcodes.LXOR:
                return true;
            default:
                return false;
        }
    }

    /**
     * Numeric conversion instructions (x2y; pop one operand).
     */
    private boolean isConvertInstruction() {
        switch (this.asmNode.getOpcode()) {
            case Opcodes.D2F:
            case Opcodes.D2L:
            case Opcodes.D2I:
            case Opcodes.F2D:
            case Opcodes.F2I:
            case Opcodes.F2L:
            case Opcodes.I2B:
            case Opcodes.I2C:
            case Opcodes.I2D:
            case Opcodes.I2F:
            case Opcodes.I2L:
            case Opcodes.I2S:
            case Opcodes.L2D:
            case Opcodes.L2F:
            case Opcodes.L2I:
                return true;
            default:
                return false;
        }
    }

    /**
     * Whether this instruction is an INSTANCEOF check.
     *
     * @return true if the opcode is INSTANCEOF
     */
    public boolean checkInstanceOf() {
        return this.asmNode.getOpcode() == Opcodes.INSTANCEOF;
    }

    /**
     * For a type instruction (e.g. INSTANCEOF/CHECKCAST), the dot-separated name of the class
     * it references, or null.
     *
     * @return the referenced class name, or null
     */
    public String getInstanceOfCheckingType() {
        if (this.asmNode instanceof TypeInsnNode) {
            TypeInsnNode node = (TypeInsnNode) this.asmNode;
            return node.desc.replace("/", ".");
        }
        return null;
    }

}
