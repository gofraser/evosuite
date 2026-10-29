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
package org.evosuite.instrumentation.testability;

import org.evosuite.Properties;
import org.evosuite.seeding.ConstantPoolManager;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

/**
 * <p>
 * BooleanHelper class.
 * </p>
 *
 * @author Gordon Fraser
 */
public class BooleanHelper {

    /**
     * Constant <code>K=Integer.MAX_VALUE - 2</code>.
     */
    public static final int K = Integer.MAX_VALUE - 2;
    //public static final int K = 1000;

    public static final int TRUE = K;

    public static final int FALSE = -K;

    static Map<Integer, Integer> lastDistance = new HashMap<>();

    public static void clearStack() {
        lastDistance.clear();
    }


    /**
     * Keep track of the distance for this predicate.
     */
    public static void pushPredicate(int distance, int branchId) {
        lastDistance.put(branchId, Math.abs(distance));
    }

    /**
     * Retrieve the distance of a predicate with its given approximation level.
     */
    public static int getDistance(int branchId, int approximationLevel, int value) {
        int distance = Integer.MAX_VALUE;
        if (branchId > 0) {
            if (lastDistance.containsKey(branchId)) {
                distance = lastDistance.get(branchId);
            }
        }
        double val = (1.0 + normalize(distance)) / Math.pow(2.0, approximationLevel);

        int d = (int) Math.ceil(K * val);
        if (d == 0) {
            d = 1;
        }
        if (value <= 0) {
            d = -d;
        }

        return d;
    }

    /**
     * FIXME: the use of this function needs to be clarified.
     *
     * @param distance the distance to normalize.
     * @return the normalized distance.
     */
    public static double normalize(int distance) {
        // double k = K;
        double k = Properties.MAX_INT;
        double d = distance;
        return d / (d + 0.5 * k);
        //return distance / (distance + 1.0);
    }

    /**
     * Replacement function for double comparison.
     */
    public static int doubleSubG(double d1, double d2) {
        if (d1 == d2) {
            ConstantPoolManager.addDynamicConstantIfTracing(d1);
            return 0;
        } else {
            // Bytecode spec: If either number is NaN, the integer 1 is pushed onto the stack
            if (Double.isNaN(d1) || Double.isNaN(d2)) {
                return 1;
            }

            return doubleSubHelper(d1, d2);
        }
    }

    /**
     * Replacement function for double comparison.
     */
    public static int doubleSubL(double d1, double d2) {
        if (d1 == d2) {
            ConstantPoolManager.addDynamicConstantIfTracing(d1);
            return 0;
        } else {
            // Bytecode spec: If either number is NaN, the integer -1 is pushed onto the stack
            if (Double.isNaN(d1) || Double.isNaN(d2)) {
                return -1;
            }

            return doubleSubHelper(d1, d2);
        }
    }


    private static int doubleSubHelper(double d1, double d2) {
        if (Double.isInfinite(d1) || Double.isInfinite(d2)) {
            return Double.compare(d1, d2);
        }

        double diff = d1 - d2;
        double diff2 = diff / (1.0 + Math.abs(diff));
        // int d3 = (int) Math.round(Integer.MAX_VALUE * diff2);
        int d3 = (int) (diff2 < 0 ? Math.floor(Integer.MAX_VALUE * diff2)
                : Math.ceil(Integer.MAX_VALUE * diff2));
        if (d3 == 0) {
            d3 = (int) Math.signum(diff);
        }

        ConstantPoolManager.addDynamicConstantIfTracing(d1);
        ConstantPoolManager.addDynamicConstantIfTracing(d2);
        return d3;
    }

    /**
     * Replacement function for float comparison.
     */
    public static int floatSubG(float f1, float f2) {
        if (f1 == f2) {
            ConstantPoolManager.addDynamicConstantIfTracing(f1);
            return 0;
        } else {
            // Bytecode spec: If either number is NaN, the integer 1 is pushed onto the stack
            if (Float.isNaN(f1) || Float.isNaN(f2)) {
                return 1;
            }

            return floatSubHelper(f1, f2);
        }
    }

    /**
     * Replacement function for float comparison.
     */
    public static int floatSubL(float f1, float f2) {
        if (f1 == f2) {
            ConstantPoolManager.addDynamicConstantIfTracing(f1);
            return 0;
        } else {
            // Bytecode spec: If either number is NaN, the integer -1 is pushed onto the stack
            if (Float.isNaN(f1) || Float.isNaN(f2)) {
                return -1;
            }
            return floatSubHelper(f1, f2);
        }
    }

    private static int floatSubHelper(float f1, float f2) {

        if (Float.isInfinite(f1) || Float.isInfinite(f2)) {
            return Float.compare(f1, f2);
        }
        double diff = (double) f1 - (double) f2;
        double diff2 = Math.signum(diff) * Math.abs(diff) / (1.0F + Math.abs(diff));
        int d3 = (int) Math.ceil(Integer.MAX_VALUE * diff2);
        if (d3 == 0) {
            d3 = (int) Math.signum(diff);
        }
        ConstantPoolManager.addDynamicConstantIfTracing(f1);
        ConstantPoolManager.addDynamicConstantIfTracing(f2);
        return d3;
    }


    public static int intSub(int a, int b) {
        long sub = (long) a - (long) b;
        if (sub < -K) {
            return -K;
        } else if (sub > K) {
            return K;
        }
        return (int) sub;
    }

    /**
     * Replacement function for long comparison.
     */
    public static int longSub(long l1, long l2) {
        if (l1 == l2) {
            ConstantPoolManager.addDynamicConstantIfTracing(l1);
            return 0;
        } else {
            double diff = (double) l1 - (double) l2;
            double diff2 = Math.signum(diff) * Math.abs(diff) / (1.0 + Math.abs(diff));
            int d3 = (int) Math.ceil(Integer.MAX_VALUE * diff2);
            ConstantPoolManager.addDynamicConstantIfTracing(l1);
            ConstantPoolManager.addDynamicConstantIfTracing(l2);
            return d3;
        }
    }

    @Deprecated
    public static int fromDouble(double d) {
        //logger.info("Converting double " + d);
        /*
        if (d > Integer.MAX_VALUE)
            return Integer.MAX_VALUE;
        else if (d < Integer.MIN_VALUE)
            return Integer.MIN_VALUE;
        else
        */
        if (d == 0.0) {
            return 0;
        } else {
            double d2 = Math.signum(d) * Math.abs(d) / (1.0 + Math.abs(d));
            //logger.info(" -> " + d2);
            int d3 = (int) Math.round(Integer.MAX_VALUE * d2);
            //logger.info(" -> " + d3);
            return d3;
        }
    }

    @Deprecated
    public static int fromFloat(float d) {
        //logger.info("Converting float " + d);
        /*
        if (d > Integer.MAX_VALUE)
            return Integer.MAX_VALUE;
        else if (d < Integer.MIN_VALUE)
            return Integer.MIN_VALUE;
        else */
        if (d == 0.0f) {
            return 0;
        } else {
            float d2 = Math.signum(d) * Math.abs(d) / (1f + Math.abs(d));
            //logger.info(" ->" + d2);
            int d3 = Math.round(Integer.MAX_VALUE * d2);
            //logger.info(" -> " + d3);
            return d3;
        }
    }

    @Deprecated
    public static int fromLong(long d) {
        /*
        if (d > Integer.MAX_VALUE)
            return Integer.MAX_VALUE;
        else if (d < Integer.MIN_VALUE)
            return Integer.MIN_VALUE;
            */
        //else
        //  return (int) d;
        if (d == 0L) {
            return 0;
        }
        double d2 = Math.signum(d) * Math.abs(d) / (1L + Math.abs(d));
        int d3 = (int) Math.round(Integer.MAX_VALUE * d2);
        return d3;
    }

    public static int booleanToInt(boolean b) {
        if (b) {
            return TRUE;
        } else {
            return FALSE;
        }
    }

    public static boolean intToBoolean(int x) {
        return x > 0;
    }


    /**
     * Replacement function for the Java instanceof instruction, which returns a
     * distance integer.
     */
    public static int instanceOf(Object o, Class<?> c) {
        if (o == null) {
            return FALSE;
        }
        return c.isAssignableFrom(o.getClass()) ? TRUE : FALSE;
    }

    /**
     * Replacement function for the Java IFNULL instruction, returning a
     * distance integer.
     *
     * @param o      the object to check.
     * @param opcode the opcode of the instruction.
     */
    public static int isNull(Object o, int opcode) {
        if (opcode == Opcodes.IFNULL) {
            return o == null ? TRUE : FALSE;
        } else {
            return o != null ? TRUE : FALSE;
        }
    }

    @SuppressWarnings("checkstyle:methodname")
    public static int IOR(int a, int b) {
        int ret = 0;
        if (a > 0 || b > 0) {
            // True

            ret = a;
            if (b > 0 && b < a) {
                ret = b;
            }
        } else {
            // False

            ret = a;
            if (b > a) {
                ret = b;
            }
        }

        return ret;
    }

    @SuppressWarnings("checkstyle:methodname")
    public static int IAND(int a, int b) {
        return Math.min(a, b);
    }

    @SuppressWarnings("checkstyle:methodname")
    public static int IXOR(int a, int b) {
        int ret = 0;
        if (a > 0 && b <= 0) {
            // True
            ret = a;
        } else if (b > 0 && a <= 0) {
            ret = b;
        } else {
            // False
            ret = -Math.abs(a - b);
        }

        return ret;
    }

    public static int isEqual(Object o1, Object o2, int opcode) {
        if (opcode == Opcodes.IF_ACMPEQ) {
            return o1 == o2 ? K : -K;
        } else {
            return o1 != o2 ? K : -K;
        }
    }

    private static final Stack<Object> parametersObject = new Stack<>();
    private static final Stack<Boolean> parametersBoolean = new Stack<>();
    private static final Stack<Character> parametersChar = new Stack<>();
    private static final Stack<Byte> parametersByte = new Stack<>();
    private static final Stack<Short> parametersShort = new Stack<>();
    private static final Stack<Integer> parametersInteger = new Stack<>();
    private static final Stack<Float> parametersFloat = new Stack<>();
    private static final Stack<Long> parametersLong = new Stack<>();
    private static final Stack<Double> parametersDouble = new Stack<>();

    public static boolean popParameterBooleanFromInt() {
        int i = parametersInteger.pop();
        boolean result = i > 0;
        return result;
    }

    public static int popParameterIntFromBoolean() {
        boolean i = parametersBoolean.pop();
        if (i) {
            return K;
        } else {
            return -K;
        }
    }

    public static boolean popParameterBoolean() {
        return parametersBoolean.pop();
    }

    public static char popParameterChar() {
        return parametersChar.pop();
    }

    public static byte popParameterByte() {
        return parametersByte.pop();
    }

    public static short popParameterShort() {
        return parametersShort.pop();
    }

    public static int popParameterInt() {
        return parametersInteger.pop();
    }

    public static float popParameterFloat() {
        return parametersFloat.pop();
    }

    public static long popParameterLong() {
        return parametersLong.pop();
    }

    public static double popParameterDouble() {
        return parametersDouble.pop();
    }

    public static Object popParameterObject() {
        return parametersObject.pop();
    }

    public static Object popParameter(Object o) {
        return parametersObject.pop();
    }

    public static void pushParameter(boolean o) {
        parametersBoolean.push(o);
    }

    public static void pushParameter(char o) {
        parametersChar.push(o);
    }

    public static void pushParameter(byte o) {
        parametersByte.push(o);
    }

    public static void pushParameter(short o) {
        parametersShort.push(o);
    }

    public static void pushParameter(int o) {
        parametersInteger.push(o);
    }

    public static void pushParameter(float o) {
        parametersFloat.push(o);
    }

    public static void pushParameter(long o) {
        parametersLong.push(o);
    }

    public static void pushParameter(double o) {
        parametersDouble.push(o);
    }

    public static void pushParameter(Object o) {
        parametersObject.push(o);
    }
}
