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
package org.evosuite.instrumentation.error;

import org.evosuite.PackageInfo;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Instruments constructor and method calls on common JDK collections/maps/models
 * to guard against very large capacities that may lead to OOM during execution.
 *
 * <p>Guards two categories:</p>
 * <ul>
 *   <li><b>Constructors</b> with int capacity parameters (ArrayList, HashMap, etc.)</li>
 *   <li><b>Resize methods</b> that take an int size and allocate proportionally
 *       (DefaultTableModel.setRowCount, Vector.setSize, etc.)</li>
 * </ul>
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

    /**
     * Methods on JDK classes that allocate memory proportional to an int argument.
     * Keyed by owner → set of method names. All must have descriptor "(I)V".
     */
    private static final Map<String, Set<String>> RESIZE_METHODS = new HashMap<>();

    static {
        RESIZE_METHODS.put("javax/swing/table/DefaultTableModel",
                new HashSet<>(Arrays.asList("setRowCount", "setColumnCount")));
        RESIZE_METHODS.put("java/util/Vector",
                new HashSet<>(Arrays.asList("setSize")));
    }

    public CollectionCapacityInstrumentation(ErrorConditionMethodAdapter mv) {
        super(mv);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name)) {
            instrumentConstructor(opcode, owner, name, desc, itf);
        } else if (opcode == Opcodes.INVOKEVIRTUAL) {
            instrumentResizeMethod(opcode, owner, name, desc, itf);
        }
    }

    /**
     * Guards constructors of Collection/Map classes with int capacity parameters.
     */
    private void instrumentConstructor(int opcode, String owner, String name, String desc, boolean itf) {
        boolean isCollectionCtor = COLLECTION_OWNERS.contains(owner);
        boolean isMapCtor = MAP_OWNERS.contains(owner);
        if (!isCollectionCtor && !isMapCtor) {
            return;
        }

        Type[] args = Type.getArgumentTypes(desc);
        if (args.length == 0 || !Type.INT_TYPE.equals(args[0])) {
            return;
        }

        String limitField = isMapCtor ? "MAP_CAPACITY_LIMIT" : "COLLECTION_CAPACITY_LIMIT";
        guardIntArg(args, limitField);
    }

    /**
     * Guards method calls that resize JDK data structures proportionally to an int arg.
     * These are INVOKEVIRTUAL calls in SUT code to methods like
     * DefaultTableModel.setColumnCount(int) — the JDK class itself is not instrumented,
     * but the SUT call site is.
     */
    private void instrumentResizeMethod(int opcode, String owner, String name, String desc, boolean itf) {
        Set<String> methods = RESIZE_METHODS.get(owner);
        if (methods == null || !methods.contains(name)) {
            return;
        }
        Type[] args = Type.getArgumentTypes(desc);
        if (args.length != 1 || !Type.INT_TYPE.equals(args[0])) {
            return;
        }

        // Stack: ..., objectref, int_arg
        // Save the int arg, check it, then restore objectref + int_arg
        int intLocal = mv.newLocal(Type.INT_TYPE);
        mv.storeLocal(intLocal);                         // save int arg
        // Stack: ..., objectref

        tagBranchStart();
        mv.loadLocal(intLocal);
        mv.visitFieldInsn(Opcodes.GETSTATIC, PROPERTIES_INTERNAL_NAME,
                "COLLECTION_CAPACITY_LIMIT", "I");

        Label ok = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, ok);
        mv.visitTypeInsn(Opcodes.NEW, TIMEOUT_EXCEEDED_INTERNAL_NAME);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, TIMEOUT_EXCEEDED_INTERNAL_NAME,
                "<init>", "()V", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(ok);
        tagBranchEnd();

        mv.loadLocal(intLocal);                          // restore int arg
        // Stack: ..., objectref, int_arg  (original state)
    }

    /**
     * Emits a guard that throws TimeoutExceeded if the first int arg exceeds the limit.
     */
    private void guardIntArg(Type[] args, String limitField) {
        int[] locals = new int[args.length];
        for (int i = args.length - 1; i >= 0; i--) {
            locals[i] = mv.newLocal(args[i]);
            mv.storeLocal(locals[i]);
        }

        tagBranchStart();
        mv.loadLocal(locals[0]);
        mv.visitFieldInsn(Opcodes.GETSTATIC, PROPERTIES_INTERNAL_NAME,
                limitField, "I");

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
