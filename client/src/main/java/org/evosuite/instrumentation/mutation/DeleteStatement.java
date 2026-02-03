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

import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.mutation.Mutation;
import org.evosuite.coverage.mutation.MutationPool;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;


/**
 * <p>DeleteStatement class.</p>
 *
 * @author Gordon Fraser
 */
public class DeleteStatement implements MutationOperator {

    private static final Logger logger = LoggerFactory.getLogger(DeleteStatement.class);

    public static final String NAME = "DeleteStatement";

    /* (non-Javadoc)
     * @see org.evosuite.cfg.instrumentation.MutationOperator#apply(org.objectweb.asm.tree.MethodNode, java.lang.String, java.lang.String, org.evosuite.cfg.BytecodeInstruction)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Mutation> apply(MethodNode mn, String className, String methodName,
                                BytecodeInstruction instruction, Frame frame) {

        List<Mutation> mutations = new LinkedList<>();

        MethodInsnNode node = (MethodInsnNode) instruction.getASMNode();
        Type returnType = Type.getReturnType(node.desc);

        // insert mutation into bytecode with conditional
        InsnList mutation = new InsnList();
        logger.debug("Mutation deletestatement for statement " + node.name + node.desc);
        for (Type argType : Type.getArgumentTypes(node.desc)) {
            if (argType.getSize() == 0)
                logger.debug("Ignoring parameter of type " + argType);
            else if (argType.getSize() == 2) {
                mutation.insert(new InsnNode(Opcodes.POP2));
                logger.debug("Deleting parameter of 2 type " + argType);
            } else {
                logger.debug("Deleting parameter of 1 type " + argType);
                mutation.insert(new InsnNode(Opcodes.POP));
            }
        }
        if (node.getOpcode() == Opcodes.INVOKEVIRTUAL) {
            logger.debug("Deleting callee of type " + node.owner);
            mutation.add(new InsnNode(Opcodes.POP));
        } else if (node.getOpcode() == Opcodes.INVOKEINTERFACE) {
            logger.debug("Deleting callee of type " + node.owner);
            mutation.add(new InsnNode(Opcodes.POP));
        }
        mutation.add(MutationUtils.getDefault(returnType));

        // insert mutation into pool
        Mutation mutationObject = MutationPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).addMutation(className,
                methodName,
                NAME + " "
                        + node.name
                        + node.desc,
                instruction,
                mutation,
                Mutation.getDefaultInfectionDistance());

        mutations.add(mutationObject);
        return mutations;
    }

    /* (non-Javadoc)
     * @see org.evosuite.cfg.instrumentation.mutation.MutationOperator#isApplicable(org.evosuite.cfg.BytecodeInstruction)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isApplicable(BytecodeInstruction instruction) {
        return instruction.isMethodCall()
                && instruction.getASMNode().getOpcode() != Opcodes.INVOKESPECIAL;
    }
}
