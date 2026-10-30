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

import org.objectweb.asm.Opcodes;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.Vector;

/**
 * Guards calls that throw when the receiver collection is empty: the receiver is tested with a
 * no-argument boolean method, and a branch throwing the collection's exception is inserted in
 * front of the call. What differs between collections is only described by a {@link Rule}.
 */
public class EmptyCollectionInstrumentation extends ErrorBranchInstrumenter {

    /**
     * Which calls to guard and how to emit the guard.
     */
    private static final class Rule {
        private final List<String> owners;
        private final List<String> methods;
        private final String check;
        private final String exception;
        /** Whether the guard repeats the guarded call's invocation kind (interface or virtual). */
        private final boolean followsInvocationKind;
        /** Whether the check call is wrapped in its own branch tag. */
        private final boolean tagsCheck;
        /** Whether the throwing branch carries its own tag. */
        private final boolean tagsBranch;

        private Rule(List<String> owners, List<String> methods, String check, String exception,
                     boolean followsInvocationKind, boolean tagsCheck, boolean tagsBranch) {
            this.owners = owners;
            this.methods = methods;
            this.check = check;
            this.exception = exception;
            this.followsInvocationKind = followsInvocationKind;
            this.tagsCheck = tagsCheck;
            this.tagsBranch = tagsBranch;
        }
    }

    private static final String NO_SUCH_ELEMENT = "java/util/NoSuchElementException";

    private static final List<String> DEQUE_OWNERS = Arrays.asList("java/util/Deque",
            "java/util/concurrent/LinkedBlockingDeque", "java/util/concurrent/BlockingDeque",
            "java/util/ArrayDeque");

    private static final List<String> QUEUE_OWNERS = Arrays.asList("java/util/Queue",
            "java/util/PriorityQueue", "java/util/Deque", "java/util/concurrent/LinkedBlockingDeque",
            "java/util/concurrent/BlockingDeque", "java/util/ArrayDeque");

    private static final Rule QUEUE = new Rule(QUEUE_OWNERS, Arrays.asList("remove", "element"),
            "isEmpty", NO_SUCH_ELEMENT, true, true, true);

    private static final Rule DEQUE = new Rule(DEQUE_OWNERS,
            Arrays.asList("getFirst", "getLast", "removeFirst", "removeLast", "pop"),
            "isEmpty", NO_SUCH_ELEMENT, true, true, true);

    private static final Rule STACK = new Rule(
            Arrays.asList(Stack.class.getCanonicalName().replace('.', '/')),
            Arrays.asList("pop", "peek"), "empty", "java/util/EmptyStackException", false, true, true);

    private static final Rule VECTOR = new Rule(
            Arrays.asList(Vector.class.getCanonicalName().replace('.', '/')),
            Arrays.asList("firstElement", "lastElement"), "isEmpty", NO_SUCH_ELEMENT, false, true, true);

    private static final Rule LINKED_LIST = new Rule(
            Arrays.asList(LinkedList.class.getCanonicalName().replace('.', '/')),
            Arrays.asList("getFirst", "getLast", "removeFirst", "removeLast", "element", "pop"),
            "isEmpty", NO_SUCH_ELEMENT, false, false, true);

    private static final Rule LINKED_HASH_SET = new Rule(
            Arrays.asList(LinkedHashSet.class.getCanonicalName().replace('.', '/')),
            Arrays.asList("first", "last"), "isEmpty", NO_SUCH_ELEMENT, false, true, false);

    private final Rule rule;

    private EmptyCollectionInstrumentation(ErrorConditionMethodAdapter mv, Rule rule) {
        super(mv);
        this.rule = rule;
    }

    public static EmptyCollectionInstrumentation queue(ErrorConditionMethodAdapter mv) {
        return new EmptyCollectionInstrumentation(mv, QUEUE);
    }

    public static EmptyCollectionInstrumentation deque(ErrorConditionMethodAdapter mv) {
        return new EmptyCollectionInstrumentation(mv, DEQUE);
    }

    public static EmptyCollectionInstrumentation stack(ErrorConditionMethodAdapter mv) {
        return new EmptyCollectionInstrumentation(mv, STACK);
    }

    public static EmptyCollectionInstrumentation vector(ErrorConditionMethodAdapter mv) {
        return new EmptyCollectionInstrumentation(mv, VECTOR);
    }

    public static EmptyCollectionInstrumentation linkedList(ErrorConditionMethodAdapter mv) {
        return new EmptyCollectionInstrumentation(mv, LINKED_LIST);
    }

    public static EmptyCollectionInstrumentation linkedHashSet(ErrorConditionMethodAdapter mv) {
        return new EmptyCollectionInstrumentation(mv, LINKED_HASH_SET);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name,
                                String desc, boolean itf) {
        if (!rule.owners.contains(owner) || !rule.methods.contains(name)) {
            return;
        }
        Map<Integer, Integer> tempVariables = getMethodCallee(desc);

        if (rule.tagsCheck) {
            tagBranchStart();
        }
        boolean interfaceCall = rule.followsInvocationKind && itf;
        mv.visitMethodInsn(interfaceCall ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, owner,
                rule.check, "()Z", interfaceCall);
        if (rule.tagsBranch) {
            insertBranch(Opcodes.IFLE, rule.exception);
        } else {
            insertBranchWithoutTag(Opcodes.IFLE, rule.exception);
        }
        if (rule.tagsCheck) {
            tagBranchEnd();
        }
        restoreMethodParameters(tempVariables, desc);
    }
}
