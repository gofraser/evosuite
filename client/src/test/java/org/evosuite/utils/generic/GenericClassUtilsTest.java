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
package org.evosuite.utils.generic;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.JSplitPane;
import java.lang.reflect.Type;
import java.util.Hashtable;
import java.util.Map;
import java.util.Properties;

public class GenericClassUtilsTest {

    private static class GenericHolder<K, V> {
        @SuppressWarnings("unused")
        Hashtable<K, V> table;
    }

    private static class ConcreteHolder {
        @SuppressWarnings("unused")
        Hashtable<JSplitPane, JMenu> table;
    }

    private static class StringIntegerHolder {
        @SuppressWarnings("unused")
        Hashtable<String, Integer> table;
    }

    private static class GenericMapHolder<K, V> {
        @SuppressWarnings("unused")
        Map<K, V> values;
    }

    // Mirrors the shape that triggers the gentyref bug: a *static* nested class inside
    // a generic outer class does not capture the outer class's type parameter, but
    // gentyref 1.2.0's own isMissingTypeParameters(Class) walks getEnclosingClass()
    // without checking Modifier.isStatic, so it wrongly treats the nested class as raw
    // and erases the whole supertype chain (see GenericClassUtils.isAssignableViaExactSuperType).
    private static class GenericOuter<T> {
        static class NestedLeaf extends GenericOuter<String> {
        }
    }

    // A multi-level chain, like Jackson's DOMDeserializer -> FromStringDeserializer ->
    // ... -> JsonDeserializer, where the static nested class is several hops away from
    // the generic interface being checked against.
    private interface GenericBase<T> {
    }

    private static abstract class GenericMid<T> implements GenericBase<T> {
    }

    private static class GenericOuter2<T> extends GenericMid<T> {
        static class NestedLeaf2 extends GenericOuter2<String> {
        }
    }

    @Test
    public void testAssignableToUninstantiatedTypeVariablesUsesRawCompatibility() throws Exception {
        Type lhsType = GenericHolder.class.getDeclaredField("table").getGenericType();
        Type rhsType = ConcreteHolder.class.getDeclaredField("table").getGenericType();

        Assertions.assertTrue(GenericClassUtils.isAssignable(lhsType, rhsType));
    }

    @Test
    public void testConcreteParameterizedTypesRemainInvariant() throws Exception {
        Type lhsType = StringIntegerHolder.class.getDeclaredField("table").getGenericType();
        Type rhsType = ConcreteHolder.class.getDeclaredField("table").getGenericType();

        Assertions.assertFalse(GenericClassUtils.isAssignable(lhsType, rhsType));
    }

    @Test
    public void testRawClassAssignableToUninstantiatedTypeVariablesUsesRawCompatibility() throws Exception {
        Type lhsType = GenericMapHolder.class.getDeclaredField("values").getGenericType();

        Assertions.assertTrue(GenericClassUtils.isAssignable(lhsType, Properties.class));
    }

    @Test
    public void testClassStrictness() {
        // Class<Integer> should NOT be assignable to Class<T> (where T is from Class itself)
        // because Class is special and we want to maintain strictness there.
        Type typeVarClass = GenericClassFactory.get(Class.class).getType();
        Type concreteClass = new org.evosuite.utils.ParameterizedTypeImpl(Class.class, new Type[]{Integer.class}, null);

        Assertions.assertFalse(GenericClassUtils.isAssignable(typeVarClass, concreteClass));
    }

    @Test
    public void testMapUninstantiatedCompatibility() {
        // Map<K, V> should be assignable from HashMap<String, String> if we consider it uninstantiated
        Type mapTypeVar = GenericClassFactory.get(Map.class).getType();
        Type hashMapConcrete = new org.evosuite.utils.ParameterizedTypeImpl(java.util.HashMap.class, new Type[]{String.class, String.class}, null);

        // This is true because of the isUninstantiatedClassTypeParameters rule
        Assertions.assertTrue(GenericClassUtils.isAssignable(mapTypeVar, hashMapConcrete));
    }

    @Test
    public void testStaticNestedClassInGenericOuterIsNotAssignableToWrongTypeArgument() {
        // NestedLeaf is-a GenericOuter<String>, not GenericOuter<Object>: it must not be
        // considered assignable to GenericOuter<Object>, even though NestedLeaf's enclosing
        // class GenericOuter<T> declares a type parameter.
        Type wrongTypeArgument = new org.evosuite.utils.ParameterizedTypeImpl(
                GenericOuter.class, new Type[]{Object.class}, null);

        Assertions.assertFalse(GenericClassUtils.isAssignable(wrongTypeArgument, GenericOuter.NestedLeaf.class));
    }

    @Test
    public void testStaticNestedClassInGenericOuterIsAssignableToItsActualTypeArgument() {
        Type actualTypeArgument = new org.evosuite.utils.ParameterizedTypeImpl(
                GenericOuter.class, new Type[]{String.class}, null);

        Assertions.assertTrue(GenericClassUtils.isAssignable(actualTypeArgument, GenericOuter.NestedLeaf.class));
    }

    @Test
    public void testStaticNestedClassMultiLevelChainIsNotAssignableToWrongTypeArgument() {
        // Same as above, but with a multi-hop hierarchy between the static nested class and
        // the generic interface being checked, as in Jackson's
        // DOMDeserializer<T>.DocumentDeserializer -> ... -> JsonDeserializer<T> chain.
        Type wrongTypeArgument = new org.evosuite.utils.ParameterizedTypeImpl(
                GenericBase.class, new Type[]{Object.class}, null);

        Assertions.assertFalse(GenericClassUtils.isAssignable(wrongTypeArgument, GenericOuter2.NestedLeaf2.class));
    }

    @Test
    public void testStaticNestedClassMultiLevelChainIsAssignableToItsActualTypeArgument() {
        Type actualTypeArgument = new org.evosuite.utils.ParameterizedTypeImpl(
                GenericBase.class, new Type[]{String.class}, null);

        Assertions.assertTrue(GenericClassUtils.isAssignable(actualTypeArgument, GenericOuter2.NestedLeaf2.class));
    }
}
