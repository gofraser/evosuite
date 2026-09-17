package com.examples;

/**
 * Calls hashCode()/toString() through an Object-typed receiver, the shape the
 * instrumentation replaces; see ObjectMethodReplacementTest.
 */
public class ObjectMethodCaller {

    public static int hash(Object o) {
        return o.hashCode();
    }

    public static String string(Object o) {
        return o.toString();
    }

    /** Has no hashCode/toString of its own: the mocked identity forms apply. */
    public static class Plain {
    }
}
