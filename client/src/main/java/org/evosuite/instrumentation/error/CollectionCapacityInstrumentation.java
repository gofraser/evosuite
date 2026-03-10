/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.instrumentation.error;

import org.evosuite.PackageInfo;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Instruments constructor calls of common JDK collections/maps to guard against
 * very large initial capacities that may otherwise lead to OOM during execution.
 */
public class CollectionCapacityInstrumentation extends ErrorBranchInstrumenter {

    private static final String PROPERTIES_INTERNAL_NAME =
            PackageInfo.getNameWithSlash(org.evosuite.Properties.class);

    private static final String TIMEOUT_EXCEEDED_INTERNAL_NAME =
            PackageInfo.getNameWithSlash(org.evosuite.testcase.execution.TestCaseExecutor.TimeoutExceeded.class);

    private static final Set<String> COLLECTION_OWNERS = new HashSet<>(Arrays.asList(
            "java/util/ArrayList",
            "java/util/ArrayDeque",
            "java/util/HashSet",
            "java/util/LinkedHashSet",
            "java/util/PriorityQueue",
            "java/util/Vector"
    ));

    private static final Set<String> MAP_OWNERS = new HashSet<>(Arrays.asList(
            "java/util/HashMap",
            "java/util/LinkedHashMap",
            "java/util/Hashtable",
            "java/util/IdentityHashMap",
            "java/util/WeakHashMap",
            "java/util/concurrent/ConcurrentHashMap"
    ));

    public CollectionCapacityInstrumentation(ErrorConditionMethodAdapter mv) {
        super(mv);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        // TODO: Optional phase for OOM control:
        // add mutator-growth guards here (or in a dedicated instrumenter) for
        // Collection/Map mutators such as add/addAll/put/putAll by checking
        // receiver.size() against capacity limits before executing the call.
        // Keep it behind a separate opt-in property to avoid over-constraining
        // valid executions.
        if (opcode != Opcodes.INVOKESPECIAL || !"<init>".equals(name)) {
            return;
        }

        boolean isCollectionCtor = COLLECTION_OWNERS.contains(owner);
        boolean isMapCtor = MAP_OWNERS.contains(owner);
        if (!isCollectionCtor && !isMapCtor) {
            return;
        }

        Type[] args = Type.getArgumentTypes(desc);
        if (args.length == 0 || !Type.INT_TYPE.equals(args[0])) {
            return;
        }

        int[] locals = new int[args.length];
        for (int i = args.length - 1; i >= 0; i--) {
            locals[i] = mv.newLocal(args[i]);
            mv.storeLocal(locals[i]);
        }

        tagBranchStart();
        mv.loadLocal(locals[0]);
        mv.visitFieldInsn(Opcodes.GETSTATIC, PROPERTIES_INTERNAL_NAME,
                isMapCtor ? "MAP_CAPACITY_LIMIT" : "COLLECTION_CAPACITY_LIMIT", "I");

        Label ok = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, ok);
        mv.visitTypeInsn(Opcodes.NEW, TIMEOUT_EXCEEDED_INTERNAL_NAME);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, TIMEOUT_EXCEEDED_INTERNAL_NAME,
                "<init>", "()V", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(ok);
        tagBranchEnd();

        for (int i = 0; i < args.length; i++) {
            mv.loadLocal(locals[i]);
        }
    }
}
