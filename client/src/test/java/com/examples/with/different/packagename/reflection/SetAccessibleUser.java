package com.examples.with.different.packagename.reflection;

import java.lang.reflect.Field;

/**
 * Example SUT modeling the com.fasterxml.jackson.databind.util.ClassUtil bug: reaches
 * deep into a JDK class with {@code Field#setAccessible(true)} and does not catch
 * {@link java.lang.reflect.InaccessibleObjectException}. During the EvoSuite search
 * this succeeds because the client JVM is started with {@code --add-opens
 * java.base/java.net=ALL-UNNAMED} (see ExecutionModeUtils); a Defects4J-style stock
 * test JVM has no such flag and throws.
 */
public class SetAccessibleUser {

    public Object makeUrlHostAddressAccessible() throws Exception {
        Field field = java.net.URL.class.getDeclaredField("hostAddress");
        field.setAccessible(true);
        return field;
    }
}
