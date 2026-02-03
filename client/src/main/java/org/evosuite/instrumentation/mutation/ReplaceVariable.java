/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */

package org.evosuite.instrumentation.mutation;

import org.apache.commons.lang3.ClassUtils;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.mutation.Mutation;
import org.evosuite.coverage.mutation.MutationPool;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.setup.TestClusterUtils;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.Map.Entry;

/**
 * <p>
 * ReplaceVariable class.
 * </p>
 *
 * @author Gordon Fraser
 */
public class ReplaceVariable implements MutationOperator {

    private static final Logger logger = LoggerFactory.getLogger(ReplaceVariable.class);

    public static final String NAME = "ReplaceVariable";

    /* (non-Javadoc)
     * @see org.evosuite.cfg.instrumentation.mutation.MutationOperator#apply(org.objectweb.asm.tree.MethodNode, java.lang.String, java.lang.String, org.evosuite.cfg.BytecodeInstruction)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Mutation> apply(MethodNode mn, String className, String methodName,
                                BytecodeInstruction instruction, Frame frame) {
        List<Mutation> mutations = new LinkedList<>();
        if (mn.localVariables.isEmpty()) {
            logger.debug("Have no information about local variables - recompile with full debug information");
            return mutations;
        }
        logger.debug("Starting variable replacement in " + methodName);

        try {
            String origName = MutationUtils.getName(mn, instruction.getASMNode());
            int numReplacements = 0;

            for (Entry<String, InsnList> mutation : getReplacements(
                    mn,
                    className,
                    instruction.getASMNode(),
                    frame).entrySet()) {

                if (numReplacements++ > Properties.MAX_REPLACE_MUTANTS) {
                    logger.info("Reached maximum number of variable replacements");
                    break;
                }

                // insert mutation into pool
                Mutation mutationObject = MutationPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).addMutation(className,
                        methodName,
                        NAME + " "
                                + origName
                                + " -> "
                                + mutation.getKey(),
                        instruction,
                        mutation.getValue(),
                        getInfectionDistance(getType(mn,
                                        instruction.getASMNode()),
                                instruction.getASMNode(),
                                mutation.getValue()));
                mutations.add(mutationObject);
            }
        } catch (VariableNotFoundException e) {
            logger.debug("Variable not found: " + instruction);
        }
        logger.debug("Finished variable replacement in " + methodName);
        return mutations;
    }

    private Type getType(MethodNode mn, AbstractInsnNode node)
            throws VariableNotFoundException {
        if (node instanceof VarInsnNode) {
            LocalVariableNode var = MutationUtils.getLocal(mn, node, ((VarInsnNode) node).var);
            return Type.getType(var.desc);
        } else if (node instanceof FieldInsnNode) {
            return Type.getType(((FieldInsnNode) node).desc);
        } else if (node instanceof IincInsnNode) {
            IincInsnNode incNode = (IincInsnNode) node;
            LocalVariableNode var = MutationUtils.getLocal(mn, node, incNode.var);

            return Type.getType(var.desc);

        } else {
            throw new RuntimeException("Unknown variable node: " + node);
        }
    }

    /**
     * <p>
     * getInfectionDistance
     * </p>
     *
     * @param type     a {@link org.objectweb.asm.Type} object.
     * @param original a {@link org.objectweb.asm.tree.AbstractInsnNode} object.
     * @param mutant   a {@link org.objectweb.asm.tree.InsnList} object.
     * @return a {@link org.objectweb.asm.tree.InsnList} object.
     */
    public InsnList getInfectionDistance(Type type, AbstractInsnNode original,
                                         InsnList mutant) {
        // TODO: Treat reference types different!

        InsnList distance = new InsnList();

        if (original instanceof VarInsnNode) {
            VarInsnNode node = (VarInsnNode) original;
            distance.add(new VarInsnNode(node.getOpcode(), node.var));
            if (type.getDescriptor().startsWith("L")
                    || type.getDescriptor().startsWith("["))
                MutationUtils.addReferenceDistanceCheck(distance, type, mutant);
            else
                MutationUtils.addPrimitiveDistanceCheck(distance, type, mutant);

        } else if (original instanceof FieldInsnNode) {
            if (original.getOpcode() == Opcodes.GETFIELD)
                distance.add(new InsnNode(Opcodes.DUP)); //make sure to re-load this for GETFIELD

            FieldInsnNode node = (FieldInsnNode) original;
            distance.add(new FieldInsnNode(node.getOpcode(), node.owner, node.name,
                    node.desc));
            if (type.getDescriptor().startsWith("L")
                    || type.getDescriptor().startsWith("["))
                MutationUtils.addReferenceDistanceCheck(distance, type, mutant);
            else
                MutationUtils.addPrimitiveDistanceCheck(distance, type, mutant);

        } else if (original instanceof IincInsnNode) {
            distance.add(Mutation.getDefaultInfectionDistance());
        }
        return distance;
    }

    /**
     * Retrieve the set of variables that have the same type and are in scope
     *
     * @param node
     * @return
     */
    private Map<String, InsnList> getReplacements(MethodNode mn, String className,
                                                  AbstractInsnNode node, Frame frame) {
        Map<String, InsnList> variables = new HashMap<>();

        if (node instanceof VarInsnNode) {
            VarInsnNode var = (VarInsnNode) node;

            try {
                LocalVariableNode origVar = MutationUtils.getLocal(mn, node, var.var);

                //LocalVariableNode origVar = (LocalVariableNode) mn.localVariables.get(var.var);
                logger.debug("Looking for replacements for " + origVar.name + " of type "
                        + origVar.desc + " at index " + origVar.index);

                // FIXXME: ASM gets scopes wrong, so we only use primitive vars?
                //if (!origVar.desc.startsWith("L"))
                variables.putAll(getLocalReplacements(mn, origVar.desc, node, frame));
                variables.putAll(getFieldReplacements(mn, className, origVar.desc, node));
            } catch (VariableNotFoundException e) {
                logger.debug("Could not find variable, not replacing it: " + var.var);
                for (final LocalVariableNode n : mn.localVariables) {
                    logger.debug(n.index + ": " + n.name);
                }
                logger.debug(e.toString());
            }
        } else if (node instanceof FieldInsnNode) {
            FieldInsnNode field = (FieldInsnNode) node;
            if (field.owner.replace('/', '.').equals(className)) {
                logger.debug("Looking for replacements for static field " + field.name
                        + " of type " + field.desc);
                variables.putAll(getLocalReplacements(mn, field.desc, node, frame));
                variables.putAll(getFieldReplacements(mn, className, field.desc, node));
            }
        } else if (node instanceof IincInsnNode) {
            IincInsnNode incNode = (IincInsnNode) node;
            try {
                LocalVariableNode origVar = MutationUtils.getLocal(mn, node, incNode.var);

                variables.putAll(getLocalReplacementsInc(mn, origVar.desc, incNode, frame));
            } catch (VariableNotFoundException e) {
                logger.debug("Could not find variable, not replacing it: " + incNode.var);
            }

        } else {
            //throw new RuntimeException("Unknown type: " + node);
        }

        return variables;
    }

    private Map<String, InsnList> getLocalReplacements(MethodNode mn, String desc,
                                                       AbstractInsnNode node, Frame frame) {
        Map<String, InsnList> replacements = new HashMap<>();

        //if (desc.equals("I"))
        //	return replacements;

        int otherNum = -1;
        if (node instanceof VarInsnNode) {
            VarInsnNode vNode = (VarInsnNode) node;
            otherNum = vNode.var;
        }
        if (otherNum == -1)
            return replacements;

        int currentId = mn.instructions.indexOf(node);
        logger.debug("Looking for replacements at position " + currentId + " of variable "
                + otherNum + " of type " + desc);

        //	return replacements;

        for (Object v : mn.localVariables) {
            LocalVariableNode localVar = (LocalVariableNode) v;
            int startId = mn.instructions.indexOf(localVar.start);
            int endId = mn.instructions.indexOf(localVar.end);
            logger.debug("Checking local variable " + localVar.name + " of type "
                    + localVar.desc + " at index " + localVar.index);
            if (!localVar.desc.equals(desc))
                logger.debug("- Types do not match");
            if (localVar.index == otherNum)
                logger.debug("- Replacement = original");
            if (currentId < startId)
                logger.debug("- Out of scope (start)");
            if (currentId > endId)
                logger.debug("- Out of scope (end)");
            BasicValue newValue = (BasicValue) frame.getLocal(localVar.index);
            if (newValue == BasicValue.UNINITIALIZED_VALUE)
                logger.debug("- Not initialized");

            if (localVar.desc.equals(desc) && localVar.index != otherNum
                    && currentId >= startId && currentId <= endId
                    && newValue != BasicValue.UNINITIALIZED_VALUE) {

                logger.debug("Adding local variable " + localVar.name + " of type "
                        + localVar.desc + " at index " + localVar.index + ",  " + startId
                        + "-" + endId + ", " + currentId);
                InsnList list = new InsnList();
                if (node.getOpcode() == Opcodes.GETFIELD) {
                    list.add(new InsnNode(Opcodes.POP)); // Remove field owner from stack
                }

                list.add(new VarInsnNode(getLoadOpcode(localVar), localVar.index));
                replacements.put(localVar.name, list);
            }
        }
        return replacements;
    }

    private Map<String, InsnList> getLocalReplacementsInc(MethodNode mn, String desc,
                                                          IincInsnNode node, Frame frame) {
        Map<String, InsnList> replacements = new HashMap<>();

        int otherNum = -1;
        otherNum = node.var;
        int currentId = mn.instructions.indexOf(node);

        for (Object v : mn.localVariables) {
            LocalVariableNode localVar = (LocalVariableNode) v;
            int startId = mn.instructions.indexOf(localVar.start);
            int endId = mn.instructions.indexOf(localVar.end);
            logger.debug("Checking local variable " + localVar.name + " of type "
                    + localVar.desc + " at index " + localVar.index);
            if (!localVar.desc.equals(desc))
                logger.debug("- Types do not match: " + localVar.name);
            if (localVar.index == otherNum)
                logger.debug("- Replacement = original " + localVar.name);
            if (currentId < startId)
                logger.debug("- Out of scope (start) " + localVar.name);
            if (currentId > endId)
                logger.debug("- Out of scope (end) " + localVar.name);
            BasicValue newValue = (BasicValue) frame.getLocal(localVar.index);
            if (newValue == BasicValue.UNINITIALIZED_VALUE)
                logger.debug("- Not initialized");

            if (localVar.desc.equals(desc) && localVar.index != otherNum
                    && currentId >= startId && currentId <= endId
                    && newValue != BasicValue.UNINITIALIZED_VALUE) {

                logger.debug("Adding local variable " + localVar.name + " of type "
                        + localVar.desc + " at index " + localVar.index);
                InsnList list = new InsnList();
                list.add(new IincInsnNode(localVar.index, node.incr));
                replacements.put(localVar.name, list);
            }
        }
        return replacements;
    }

    private int getLoadOpcode(LocalVariableNode var) {
        Type type = Type.getType(var.desc);
        return type.getOpcode(Opcodes.ILOAD);
    }

    private Map<String, InsnList> getFieldReplacements(MethodNode mn, String className,
                                                       String desc, AbstractInsnNode node) {
        Map<String, InsnList> alternatives = new HashMap<>();

        boolean isStatic = (mn.access & Opcodes.ACC_STATIC) == Opcodes.ACC_STATIC;

        String otherName = "";
        if (node instanceof FieldInsnNode) {
            FieldInsnNode fNode = (FieldInsnNode) node;
            otherName = fNode.name;
        }
        try {
            logger.debug("Checking class " + className);
            Class<?> clazz = Class.forName(className, false,
                    TestGenerationContext.getInstance().getClassLoaderForSUT());

            for (Field field : TestClusterUtils.getFields(clazz)) {
                // We have to use a special version of canUse to avoid
                // that we access the CUT before it is fully initialised
                if (!canUse(field))
                    continue;

                Type type = Type.getType(field.getType());
                logger.debug("Checking replacement field variable " + field.getName());

                if (field.getName().equals(otherName))
                    continue;

                if (isStatic && !(Modifier.isStatic(field.getModifiers())))
                    continue;

                if (type.getDescriptor().equals(desc)) {
                    logger.debug("Adding replacement field variable " + field.getName());
                    InsnList list = new InsnList();
                    if (node.getOpcode() == Opcodes.GETFIELD) {
                        list.add(new InsnNode(Opcodes.POP)); // Remove field owner from stack
                    }

                    // new fieldinsnnode
                    if (Modifier.isStatic(field.getModifiers()))
                        list.add(new FieldInsnNode(Opcodes.GETSTATIC,
                                className.replace('.', '/'), field.getName(),
                                type.getDescriptor()));
                    else {
                        list.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
                        list.add(new FieldInsnNode(Opcodes.GETFIELD,
                                className.replace('.', '/'), field.getName(),
                                type.getDescriptor()));
                    }
                    alternatives.put(field.getName(), list);
                } else {
                    logger.debug("Descriptor does not match: " + field.getName() + " - "
                            + type.getDescriptor());
                }
            }
        } catch (Throwable t) {
            logger.debug("Class not found: " + className);
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
        return alternatives;
    }

    /**
     * This replicates TestUsageChecker.canUse but we need to avoid that
     * we try to access Properties.getTargetClassAndDontInitialise
     *
     * @param f
     * @return
     */
    public static boolean canUse(Field f) {

        if (f.getDeclaringClass().equals(java.lang.Object.class))
            return false;// handled here to avoid printing reasons

        if (f.getDeclaringClass().equals(java.lang.Thread.class))
            return false;// handled here to avoid printing reasons

        if (f.isSynthetic()) {
            logger.debug("Skipping synthetic field " + f.getName());
            return false;
        }

        if (f.getName().startsWith("ajc$")) {
            logger.debug("Skipping AspectJ field " + f.getName());
            return false;
        }

        // in, out, err
        if (f.getDeclaringClass().equals(FileDescriptor.class)) {
            return false;
        }

        if (Modifier.isPublic(f.getModifiers())) {
            // It may still be the case that the field is defined in a non-visible superclass of the class
            // we already know we can use. In that case, the compiler would be fine with accessing the
            // field, but reflection would start complaining about IllegalAccess!
            // Therefore, we set the field accessible to be on the safe side
            TestClusterUtils.makeAccessible(f);
            return true;
        }

        // If default access rights, then check if this class is in the same package as the target class
        if (!Modifier.isPrivate(f.getModifiers())) {
            String packageName = ClassUtils.getPackageName(f.getDeclaringClass());

            if (packageName.equals(Properties.CLASS_PREFIX)) {
                TestClusterUtils.makeAccessible(f);
                return true;
            }
        }

        return false;
    }
    /* (non-Javadoc)
     * @see org.evosuite.cfg.instrumentation.mutation.MutationOperator#isApplicable(org.evosuite.cfg.BytecodeInstruction)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isApplicable(BytecodeInstruction instruction) {
        return instruction.isLocalVariableUse()
                || instruction.getASMNode().getOpcode() == Opcodes.GETSTATIC
                || instruction.getASMNode().getOpcode() == Opcodes.GETFIELD;
    }

}
