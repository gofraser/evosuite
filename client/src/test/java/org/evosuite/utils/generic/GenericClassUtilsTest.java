package org.evosuite.utils.generic;

import org.junit.Assert;
import org.junit.Test;

import javax.swing.JMenu;
import javax.swing.JSplitPane;
import java.lang.reflect.Type;
import java.util.Hashtable;

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

    @Test
    public void testAssignableToUninstantiatedTypeVariablesUsesRawCompatibility() throws Exception {
        Type lhsType = GenericHolder.class.getDeclaredField("table").getGenericType();
        Type rhsType = ConcreteHolder.class.getDeclaredField("table").getGenericType();

        Assert.assertTrue(GenericClassUtils.isAssignable(lhsType, rhsType));
    }

    @Test
    public void testConcreteParameterizedTypesRemainInvariant() throws Exception {
        Type lhsType = StringIntegerHolder.class.getDeclaredField("table").getGenericType();
        Type rhsType = ConcreteHolder.class.getDeclaredField("table").getGenericType();

        Assert.assertFalse(GenericClassUtils.isAssignable(lhsType, rhsType));
    }
}
