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
}
