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
 */
package org.evosuite.testcase.synthesizer.var;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OtherVariableWrapperTest {

    @Test
    public void acceptsConcreteReceiverForInterfaceMethod() {
        assertTrue(OtherVariableWrapper.isCompatibleInstanceReceiver(
                CharSequence.class, String.class));
        assertFalse(OtherVariableWrapper.isCompatibleInstanceReceiver(
                String.class, CharSequence.class));
    }

    @Test
    public void recognizesIndexedReadOnConcreteListSubtype() throws Exception {
        Method get = List.class.getMethod("get", int.class);

        assertTrue(OtherVariableWrapper.isIndexedListRead(get, TypedList.class));
        assertFalse(OtherVariableWrapper.isIndexedListRead(
                String.class.getMethod("charAt", int.class), String.class));
    }

    @Test
    public void selectsTypedInsertionInsteadOfErasedBridge() {
        Method insertion = OtherVariableWrapper.findCompatibleListInsertionMethod(
                TypedList.class, Element.class);

        assertNotNull(insertion);
        assertEquals("add", insertion.getName());
        assertEquals(Element.class, insertion.getParameterTypes()[0]);
        assertFalse(insertion.isBridge());
    }

    @Test
    public void translatesHeaderLikeContainsToStateMutation() throws Exception {
        Method contains = HeaderBag.class.getMethod("contains", CharSequence.class);

        assertTrue(OtherVariableWrapper.isMembershipObservation(contains));
        Method mutation = OtherVariableWrapper.findMembershipMutation(
                contains, HeaderBag.class, String.class);

        assertNotNull(mutation);
        assertEquals("add", mutation.getName());
        assertEquals(2, mutation.getParameterCount());
        assertEquals(Object.class, mutation.getParameterTypes()[1]);
    }

    @Test
    public void doesNotTreatStringContainsAsMutableMembership() throws Exception {
        Method contains = String.class.getMethod("contains", CharSequence.class);

        assertTrue(OtherVariableWrapper.isMembershipObservation(contains));
        assertNull(OtherVariableWrapper.findMembershipMutation(
                contains, String.class, String.class));
    }

    @Test
    public void placesObservationAfterArgumentsResolvedLaterThanReceiver() {
        TestCase test = new DefaultTestCase();
        VariableReference receiver = test.addStatement(
                new StringPrimitiveStatement(test, "receiver"));
        VariableReference laterArgument = test.addStatement(
                new StringPrimitiveStatement(test, "argument"));

        int insertionPosition = OtherVariableWrapper.methodCallInsertionPosition(
                test, receiver, Collections.singletonList(laterArgument));

        assertEquals(2, insertionPosition,
                "the observation must not precede a mapped argument");
    }

    @Test
    public void distinguishesQueriesFromStateChangingCalls() throws Exception {
        assertTrue(OtherVariableWrapper.isPureObservation(
                HeaderBag.class.getMethod("getValue")));
        assertTrue(OtherVariableWrapper.isPureObservation(
                Lookahead.class.getMethod("LA", int.class)));
        assertFalse(OtherVariableWrapper.isPureObservation(
                HeaderBag.class.getMethod("add", CharSequence.class, Object.class)));
    }

    @Test
    public void doesNotTreatStaticValueHelperAsSetupAction() throws Exception {
        Method helper = java.net.FileNameMap.class.getMethod(
                "getContentTypeFor", String.class);

        assertFalse(OtherVariableWrapper.canMaterializeObservation(
                helper, java.net.FileNameMap.class));
    }

    @Test
    public void reusesPairedAddNewResultForEquivalentGetterPath() throws Exception {
        TestCase test = new DefaultTestCase();
        GenericConstructor constructor = new GenericConstructor(
                BuilderDocument.class.getConstructor(), BuilderDocument.class);
        VariableReference receiver = test.addStatement(new ConstructorStatement(
                test, constructor, Collections.emptyList()));
        Method getter = BuilderDocument.class.getMethod("getChild");

        VariableReference first = OtherVariableWrapper.materializeAddNewObservation(
                test, getter, receiver);
        VariableReference second = OtherVariableWrapper.materializeAddNewObservation(
                test, getter, receiver);

        assertNotNull(first);
        assertEquals(first.getStPosition(), second.getStPosition(),
                "the second graph occurrence must reuse the same materialized child");
        int builderCalls = 0;
        for (int i = 0; i < test.size(); i++) {
            if (test.getStatement(i) instanceof MethodStatement
                    && "addNewChild".equals(((MethodStatement) test.getStatement(i))
                            .getMethod().getName())) {
                builderCalls++;
            }
        }
        assertEquals(1, builderCalls);
    }

    public static final class Element {
    }

    public static final class TypedList extends ArrayList<Element> {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean add(Element element) {
            return super.add(element);
        }
    }

    public static final class HeaderBag {
        public Object getValue() {
            return null;
        }

        public boolean contains(CharSequence name) {
            return false;
        }

        public HeaderBag add(CharSequence name, Object value) {
            return this;
        }

        public HeaderBag add(CharSequence name, Iterable<?> values) {
            return this;
        }
    }

    public interface Lookahead {
        int LA(int offset);
    }

    public static final class BuilderDocument {
        private Child child;

        public Child getChild() {
            return child;
        }

        public Child addNewChild() {
            child = new Child();
            return child;
        }
    }

    public static final class Child {
    }
}
