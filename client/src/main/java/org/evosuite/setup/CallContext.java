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
package org.evosuite.setup;

import org.evosuite.PackageInfo;
import org.evosuite.Properties;
import org.evosuite.testcase.execution.MethodCall;

import java.io.Serializable;
import java.util.*;

/**
 * <p>
 * CallContext class.
 * </p>
 * TODO THIS IS APPROXIMATED call context computed at runtime DO NOT consider
 * the method signature, but only the method name. Currently, callcontext with
 * and without signature are considered equal
 *
 * @author Gordon Fraser
 * @author mattia
 */
public class CallContext implements Serializable {


    private static final long serialVersionUID = 8650619230188403356L;

    /**
     * The calls, root first. A context derived on method entry starts out as a {@link Node}
     * chain only and the list is built when it is first read: the tracer only hashes and
     * compares such contexts, and building the list on every entry costs time linear in the
     * depth of the stack.
     */
    private volatile List<Call> context;

    /**
     * The calls of a context derived on method entry, until {@link #context} is built.
     */
    private transient Node node;

    private final int hcode;

    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * <p>
     * Constructor for CallContext.
     * </p>
     *
     * @param stackTrace an array of {@link java.lang.StackTraceElement} objects.
     */
    public CallContext(StackTraceElement[] stackTrace) {
        int startPos = stackTrace.length - 1;
        int endPos = 0;
        List<Call> context = new ArrayList<>();

        // Stack traces may be empty, e.g. if an exception is thrown in a constructor call in a test
        while (startPos >= 0 && shouldSkipEntry(stackTrace[startPos].getClassName())) {
            startPos--;
        }
        while (endPos < stackTrace.length && shouldSkipEntry(stackTrace[endPos].getClassName())) {
            endPos++;
        }

        for (int i = startPos; i >= endPos; i--) {
            StackTraceElement element = stackTrace[i];
            Call newCall = new Call(element.getClassName(), element.getMethodName());
            boolean skip = false;
            if (context.size() >= 2) { // Need at least a sequence of three for this check to make sense
                Call previousCall1 = context.get(context.size() - 1);
                Call previousCall2 = context.get(context.size() - 2);
                if (previousCall1.equals(newCall) && previousCall2.equals(newCall)) {
                    skip = true;
                }
            }
            if (!skip) {
                context.add(newCall);
            }
            if (!skip) {
                context.add(newCall);
            }
        }
        this.context = context;
        hcode = this.context.hashCode();
    }

    /**
     * Constructor for CallContext.
     *
     * @param stackTrace the stack trace
     */
    public CallContext(LinkedList<MethodCall> stackTrace) {
        MethodCall top = stackTrace.peekFirst();
        if (top != null && top.contextState != null) {
            Node last = top.contextState.atLastKept;
            if (last == null) {
                this.context = new ArrayList<>();
            } else {
                this.node = last;
            }
            hcode = last == null ? EMPTY_HASH : last.hash;
            return;
        }

        // A LinkedList is walked by index in O(n) per access; every method entry builds a
        // context, so a method that recurses until the stack overflows took cubic time in the depth
        // and every test that called it ran into the timeout.
        MethodCall[] calls = stackTrace.toArray(new MethodCall[0]);
        int startPos = calls.length - 1;
        int endPos = 0;
        List<Call> context = new ArrayList<>();

        while (startPos >= 0 && shouldSkipEntry(calls[startPos].className)) {
            startPos--;
        }

        while (endPos < calls.length && shouldSkipEntry(calls[endPos].className)) {
            endPos++;
        }

        for (int i = startPos; i >= endPos; i--) {
            MethodCall element = calls[i];

            Call newCall = element.asContextCall();

            boolean skip = false;
            if (context.size() >= 2) { // Need at least a sequence of three for this check to make sense
                Call previousCall1 = context.get(context.size() - 1);
                Call previousCall2 = context.get(context.size() - 2);
                if (previousCall1.equals(newCall) && previousCall2.equals(newCall)) {
                    skip = true;
                }
            }
            if (!skip) {
                context.add(newCall);
            }
        }
        this.context = context;
        hcode = this.context.hashCode();
    }

    /**
     * Constructor for public methods.
     *
     * @param className the class name
     * @param methodName the method name
     */
    public CallContext(String className, String methodName) {
        List<Call> context = new ArrayList<>();
        context.add(new Call(className, methodName));
        this.context = context;
        hcode = this.context.hashCode();
    }

    /**
     * Default constructor for CallContext.
     */
    public CallContext() {
        this.context = new ArrayList<>();
        hcode = this.context.hashCode();
    }

    /**
     * Constructor for CallContext with a collection of calls.
     *
     * @param contextt the collection of calls
     */
    public CallContext(Collection<Call> contextt) {
        this.context = new ArrayList<>(contextt);
        hcode = this.context.hashCode();
    }

    public int size() {
        Node chain = node;
        return chain != null ? chain.size : calls().size();
    }

    /** The calls, built from the node chain the first time they are needed. */
    private List<Call> calls() {
        List<Call> calls = context;
        if (calls == null) {
            calls = toList(node);
            context = calls;
        }
        return calls;
    }

    private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
        calls();
        out.defaultWriteObject();
    }

    /**
     * A call of a context with the calls before it, so that the context of a stack
     * can be derived from the context of the stack without its top call.
     */
    private static final class Node {
        final Call call;
        final Node prev;
        final int size;
        /**
         * {@link List#hashCode()} of the calls up to this one, so a context is hashed once.
         */
        final int hash;

        Node(Call call, Node prev) {
            this.call = call;
            this.prev = prev;
            this.size = prev == null ? 1 : prev.size + 1;
            this.hash = 31 * (prev == null ? EMPTY_HASH : prev.hash) + call.hashCode();
        }
    }

    /**
     * {@link List#hashCode()} of an empty list.
     */
    private static final int EMPTY_HASH = 1;

    /**
     * The context of the stack that ends in a call: {@code deduped} holds the calls from the
     * first frame that is not skipped up to this one, with a third repeat of the same call
     * left out, and {@code atLastKept} the same up to the last frame that is not skipped,
     * which is the context itself.
     */
    public static final class State {
        private final Node deduped;
        private final Node atLastKept;

        private State(Node deduped, Node atLastKept) {
            this.deduped = deduped;
            this.atLastKept = atLastKept;
        }
    }

    /**
     * Derives the state of {@code child}, about to be pushed on top of {@code parent}.
     * Every method entry needs the context of the stack, and building it from the whole
     * stack costs time linear in the depth, so a method that recurses until the stack
     * overflows kept the test running into the timeout.
     *
     * @param parent         the call on top of the stack, null if the stack is empty
     * @param child          the call that is pushed
     * @param parentIsBottom whether the parent is the only call on the stack
     */
    public static void extend(MethodCall parent, MethodCall child, boolean parentIsBottom) {
        if (parent == null) {
            return;
        }
        State parentState = parent.contextState;
        if (parentState == null && parentIsBottom) {
            parentState = next(null, parent);
            parent.contextState = parentState;
        }
        if (parentState != null) {
            child.contextState = next(parentState, child);
        }
    }

    private static State next(State previous, MethodCall call) {
        boolean skip = shouldSkipEntry(call.className);
        Node previousDeduped = previous == null ? null : previous.deduped;
        Node deduped;
        if (previousDeduped == null) {
            deduped = skip ? null : new Node(call.asContextCall(), null);
        } else {
            Call newCall = call.asContextCall();
            if (previousDeduped.size >= 2 && previousDeduped.call.equals(newCall)
                    && previousDeduped.prev.call.equals(newCall)) {
                deduped = previousDeduped;
            } else {
                deduped = new Node(newCall, previousDeduped);
            }
        }
        Node atLastKept = skip ? (previous == null ? null : previous.atLastKept) : deduped;
        return new State(deduped, atLastKept);
    }

    private static List<Call> toList(Node node) {
        int size = node == null ? 0 : node.size;
        Call[] calls = new Call[size];
        for (Node n = node; n != null; n = n.prev) {
            calls[n.size - 1] = n.call;
        }
        return new ArrayList<>(Arrays.asList(calls));
    }

    /**
     * The excluded prefixes and the value of {@link Properties#JUNIT} they were derived from.
     */
    private static final class Excludes {
        final String junit;
        final String[] prefixes;

        Excludes(String junit) {
            this.junit = junit;
            List<String> values = new ArrayList<>(Arrays.asList("java", "sun", PackageInfo.getEvoSuitePackage()));
            // When measuring the coverage of existing JUnit tests, leave out the tests and JUnit
            if (!junit.isEmpty()) {
                values.add("org.junit");
                values.addAll(Arrays.asList(junit.split(":")));
            }
            this.prefixes = values.toArray(new String[0]);
        }
    }

    private static volatile Excludes excludes;

    /**
     * The class name prefixes left out of a context. Contexts are built on every method
     * entry, so the prefixes are derived from {@link Properties#JUNIT} only when it changes.
     */
    static String[] excludedPrefixes() {
        String junit = Properties.JUNIT;
        Excludes current = excludes;
        if (current == null || !current.junit.equals(junit)) {
            current = new Excludes(junit);
            excludes = current;
        }
        return current.prefixes;
    }

    private static boolean shouldSkipEntry(String entry) {
        if (entry.isEmpty()) {
            return true;
        }
        for (String excludedPrefix : excludedPrefixes()) {
            if (entry.startsWith(excludedPrefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * attach the className-methodname pair passed as parameter before the
     * current context.
     **/
    @Deprecated
    public CallContext getSuperContext(String className, String methodName) {
        throw new IllegalStateException("YET TO IMPLEMENT, DEPRECATED");
    }

    /**
     * <p>
     * getRootClassName.
     * </p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getRootClassName() {
        return calls().get(0).getClassName();
    }

    /**
     * <p>
     * getRootMethodName.
     * </p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getRootMethodName() {
        return calls().get(0).getMethodName();
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        for (Call call : calls()) {
            builder.append(call.toString());
            builder.append(" ");
        }
        String tmp = builder.toString();
        return tmp.trim();
    }

    @Override
    public int hashCode() {
        return hcode;
    }

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
        CallContext other = (CallContext) obj;
        return hcode == other.hcode;
    }

    /**
     * Determine if this context matches another one.
     *
     * @param other the other context
     * @return true if they match
     */
    public boolean oldMatches(CallContext other) {
        List<Call> calls = calls();
        List<Call> otherCalls = other.calls();
        if (calls.size() != otherCalls.size()) {
            return false;
        }
        if (other.hcode == hcode) {
            return true;
        }
        for (int i = 0; i < calls.size(); i++) {
            Call call1 = calls.get(i);
            Call call2 = otherCalls.get(i);
            if (!call1.matches(call2)) {
                return false;
            }
        }

        return false;
    }

    //A empty context matches with everything.
    public boolean matches(CallContext other) {
        return isEmpty() || other.isEmpty() || other.hcode == hcode;
    }


    public List<Call> getContext() {
        return calls();
    }
    // ----------------
    // CALL class

}
