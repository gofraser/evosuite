/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package com.examples;

/** Small allocation target used by the runtime instrumentation regression test. */
public class ArrayAllocationFixture {

    public static int primitive(int size) {
        return new int[size].length;
    }

    public static int reference(int size) {
        return new Object[size].length;
    }

    public static int multi(int rows, int columns) {
        return new byte[rows][columns].length;
    }

    public static int multi3(int a, int b, int c) {
        return new byte[a][b][c].length;
    }
}
