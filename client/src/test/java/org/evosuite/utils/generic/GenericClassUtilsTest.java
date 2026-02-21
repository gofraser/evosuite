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
}
