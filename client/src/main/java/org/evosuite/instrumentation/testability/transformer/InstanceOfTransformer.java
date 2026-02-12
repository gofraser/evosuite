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
package org.evosuite.instrumentation.testability.transformer;

import org.evosuite.instrumentation.TransformationStatistics;
import org.evosuite.instrumentation.testability.BooleanHelper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Replace instanceof operation with helper that puts int on the stack.
 */
public class InstanceOfTransformer extends MethodNodeTransformer {
    /* (non-Javadoc)
     * @see org.evosuite.instrumentation.MethodNodeTransformer#transformTypeInsnNode(org.objectweb.asm.tree.MethodNode,
     * org.objectweb.asm.tree.TypeInsnNode)
     */
    @Override
    protected AbstractInsnNode transformTypeInsnNode(MethodNode mn,
                                                     TypeInsnNode typeNode) {
        if (typeNode.getOpcode() == Opcodes.INSTANCEOF) {
            TransformationStatistics.transformInstanceOf();

            // Depending on the class version we need a String or a Class
            // For now, we assume the older/safer method using Class.forName
            // as we don't have easy access to the class version here.

            LdcInsnNode lin = new LdcInsnNode(typeNode.desc.replace('/', '.'));
            mn.instructions.insertBefore(typeNode, lin);
            MethodInsnNode n = new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    Type.getInternalName(Class.class),
                    "forName",
                    Type.getMethodDescriptor(Type.getType(Class.class),
                            Type.getType(String.class)));
            mn.instructions.insertBefore(typeNode, n);

            MethodInsnNode helperInvoke = new MethodInsnNode(Opcodes.INVOKESTATIC,
                    Type.getInternalName(BooleanHelper.class), "instanceOf",
                    Type.getMethodDescriptor(Type.INT_TYPE,
                            Type.getType(Object.class),
                            Type.getType(Class.class)));
            mn.instructions.insertBefore(typeNode, helperInvoke);
            mn.instructions.remove(typeNode);
            return helperInvoke;
        }
        return typeNode;
    }
}
