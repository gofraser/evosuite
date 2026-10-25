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
package com.examples.with.different.packagename.solver;

public class TestCaseStringAppendIndexOf {

    public static boolean builderAppend(String string0, int int0, long long0, char char0, boolean boolean0,
                                        float float0, double double0) {
        StringBuilder builder = new StringBuilder(string0);
        builder.append(int0).append(long0).append(char0).append(boolean0);
        builder.append(float0).append(double0).append(string0);
        if (builder.toString().equals("x")) {
            return true;
        }
        return false;
    }

    public static boolean builderAppendObject(String string0, Object object0) {
        StringBuilder builder = new StringBuilder(string0);
        builder.append(object0);
        if (builder.toString().equals("x")) {
            return true;
        }
        return false;
    }

    public static boolean bufferAppend(String string0, int int0, long long0, char char0, boolean boolean0,
                                       float float0, double double0) {
        StringBuffer buffer = new StringBuffer(string0);
        buffer.append(int0).append(long0).append(char0).append(boolean0);
        buffer.append(float0).append(double0).append(string0);
        if (buffer.toString().equals("x")) {
            return true;
        }
        return false;
    }

    public static boolean indexOfChar(String string0, int int0) {
        return string0.indexOf(int0) == 2;
    }

    public static boolean indexOfCharFrom(String string0, int int0, int int1) {
        return string0.indexOf(int0, int1) == 2;
    }

    public static boolean indexOfString(String string0, String string1) {
        return string0.indexOf(string1) == 2;
    }

    public static boolean indexOfStringFrom(String string0, String string1, int int0) {
        return string0.indexOf(string1, int0) == 2;
    }

    public static boolean lastIndexOfChar(String string0, int int0) {
        return string0.lastIndexOf(int0) == 2;
    }

    public static boolean lastIndexOfCharFrom(String string0, int int0, int int1) {
        return string0.lastIndexOf(int0, int1) == 2;
    }

    public static boolean lastIndexOfString(String string0, String string1) {
        return string0.lastIndexOf(string1) == 2;
    }

    public static boolean lastIndexOfStringFrom(String string0, String string1, int int0) {
        return string0.lastIndexOf(string1, int0) == 2;
    }
}
