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
package org.evosuite.symbolic.vm;

import org.evosuite.symbolic.expr.Expression;
import org.evosuite.symbolic.expr.bv.IntegerConstant;
import org.evosuite.symbolic.expr.bv.IntegerValue;
import org.evosuite.symbolic.expr.bv.IntegerVariable;
import org.evosuite.symbolic.expr.fp.RealConstant;
import org.evosuite.symbolic.expr.fp.RealValue;
import org.evosuite.symbolic.expr.fp.RealVariable;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the symbolic result of every modelled {@code java.lang.Math} method and
 * of the boxing wrappers by driving the registered models directly and
 * comparing the resulting expressions with a recorded transcript.
 */
public class SymbolicMathWrapperModelsTest {

    private static final String TRANSCRIPT = "math_wrapper_models.txt";

    /** Which argument is symbolic: none, the first, the last. */
    private static final int NONE = -1;
    private static final int FIRST = 0;
    private static final int LAST = 1;

    private static final Class<?>[] WRAPPERS = {
        Integer.class, Long.class, Float.class, Double.class,
        Short.class, Byte.class, Character.class, Boolean.class};

    private static final Class<?>[] PRIMITIVES = {
        int.class, long.class, float.class, double.class,
        short.class, byte.class, char.class, boolean.class};

    private static final String[] ACCESSORS = {
        "intValue", "longValue", "floatValue", "doubleValue",
        "shortValue", "byteValue", "charValue", "booleanValue"};

    private final SymbolicEnvironment env = new SymbolicEnvironment(null);

    @Test
    public void mathAndWrapperModelsKeepTheirExpressions() throws Exception {
        Map<?, SymbolicFunction> table = functionsTable();
        List<SymbolicFunction> math = table.values().stream()
                .filter(f -> f.getOwner().equals("java/lang/Math"))
                .sorted((a, b) -> key(a).compareTo(key(b)))
                .collect(Collectors.toList());

        List<String> lines = new ArrayList<>();
        for (SymbolicFunction f : math) {
            lines.addAll(driveMath(f));
        }
        lines.addAll(driveWrappers(table));

        assertEquals(readTranscript(), String.join("\n", lines) + "\n");
    }

    @SuppressWarnings("unchecked")
    private Map<?, SymbolicFunction> functionsTable() throws Exception {
        SymbolicFunctionVM vm = new SymbolicFunctionVM(env, new PathConditionCollector());
        Field table = SymbolicFunctionVM.class.getDeclaredField("functionsTable");
        table.setAccessible(true);
        return (Map<?, SymbolicFunction>) table.get(vm);
    }

    private static String key(SymbolicFunction f) {
        return f.getOwner() + "." + f.getName() + f.getDesc();
    }

    private String readTranscript() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(TRANSCRIPT)) {
            assertNotNull(in, "missing transcript " + TRANSCRIPT);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return r.lines().collect(Collectors.joining("\n")) + "\n";
        }
    }

    // ---- java.lang.Math ----

    private List<String> driveMath(SymbolicFunction f) throws Exception {
        Type[] argTypes = Type.getArgumentTypes(f.getDesc());
        Class<?>[] classes = new Class<?>[argTypes.length];
        Object[] concrete = new Object[argTypes.length];
        for (int i = 0; i < argTypes.length; i++) {
            classes[i] = primitiveClass(argTypes[i]);
            concrete[i] = sample(argTypes[i], i);
        }
        Method m = Math.class.getMethod(f.getName(), classes);
        Object ret = m.invoke(null, concrete);

        List<String> out = new ArrayList<>();
        for (int scenario : new int[]{NONE, FIRST, LAST}) {
            if (scenario == LAST && argTypes.length == 1) {
                continue;
            }
            int symbolic = scenario == LAST ? argTypes.length - 1 : scenario;
            for (int i = 0; i < argTypes.length; i++) {
                setArg(f, i, argTypes[i], concrete[i], i == symbolic);
            }
            Expression<?> symbRet = setReturn(f, Type.getReturnType(f.getDesc()), ret);
            Object result = f.executeFunction();
            out.add(key(f) + " symbolic=" + symbolic + " -> " + result
                    + (result == symbRet ? " [concrete]" : ""));
        }
        return out;
    }

    private static Class<?> primitiveClass(Type t) {
        switch (t.getSort()) {
            case Type.INT:
                return int.class;
            case Type.LONG:
                return long.class;
            case Type.FLOAT:
                return float.class;
            default:
                return double.class;
        }
    }

    private static Object sample(Type t, int index) {
        switch (t.getSort()) {
            case Type.INT:
                return index == 0 ? -7 : 3;
            case Type.LONG:
                return index == 0 ? -70L : 30L;
            case Type.FLOAT:
                return index == 0 ? 0.75f : -2.5f;
            default:
                return index == 0 ? 0.75 : -2.5;
        }
    }

    private static void setArg(SymbolicFunction f, int i, Type t, Object conc, boolean symbolic) {
        String name = "v" + i;
        switch (t.getSort()) {
            case Type.INT: {
                int v = (Integer) conc;
                f.setParam(i, v, symbolic ? new IntegerVariable(name, v, -100, 100) : new IntegerConstant(v));
                break;
            }
            case Type.LONG: {
                long v = (Long) conc;
                f.setParam(i, v, symbolic ? new IntegerVariable(name, v, -100, 100) : new IntegerConstant(v));
                break;
            }
            case Type.FLOAT: {
                float v = (Float) conc;
                f.setParam(i, v, symbolic ? new RealVariable(name, v, -100, 100) : new RealConstant(v));
                break;
            }
            default: {
                double v = (Double) conc;
                f.setParam(i, v, symbolic ? new RealVariable(name, v, -100, 100) : new RealConstant(v));
                break;
            }
        }
    }

    private static Expression<?> setReturn(SymbolicFunction f, Type t, Object ret) {
        switch (t.getSort()) {
            case Type.INT: {
                IntegerConstant s = new IntegerConstant((Integer) ret);
                f.setReturnValue(((Integer) ret).intValue(), s);
                return s;
            }
            case Type.LONG: {
                IntegerConstant s = new IntegerConstant((Long) ret);
                f.setReturnValue(((Long) ret).longValue(), s);
                return s;
            }
            case Type.FLOAT: {
                RealConstant s = new RealConstant((Float) ret);
                f.setReturnValue(((Float) ret).floatValue(), s);
                return s;
            }
            default: {
                RealConstant s = new RealConstant((Double) ret);
                f.setReturnValue(((Double) ret).doubleValue(), s);
                return s;
            }
        }
    }

    // ---- boxing wrappers ----

    private List<String> driveWrappers(Map<?, SymbolicFunction> table) {
        List<String> out = new ArrayList<>();

        for (int w = 0; w < WRAPPERS.length; w++) {
            Class<?> wrapper = WRAPPERS[w];
            Type prim = Type.getType(PRIMITIVES[w]);
            Type boxed = Type.getType(wrapper);
            String owner = Type.getInternalName(wrapper);
            SymbolicFunction init = find(table, owner, "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, prim));
            SymbolicFunction valueOf = find(table, owner, "valueOf", Type.getMethodDescriptor(boxed, prim));
            SymbolicFunction value = find(table, owner, ACCESSORS[w], Type.getMethodDescriptor(prim));

            Object conc = wrapperSample(wrapper);

            // the constructor stores the symbolic argument, the accessor reads it back
            ReferenceConstant viaInit = newReference();
            init.setReceiver(null, viaInit);
            setWrapperArg(init, wrapper, conc);
            out.add(key(init) + " -> " + init.executeFunction());
            out.add(key(value) + " after init -> " + readBack(value, viaInit, wrapper, conc));
            out.add(key(value) + " after init, other value -> " + readBack(value, viaInit, wrapper, other(wrapper)));

            // valueOf stores the symbolic argument on the returned reference
            ReferenceConstant viaValueOf = newReference();
            setWrapperArg(valueOf, wrapper, conc);
            valueOf.setReturnValue(conc, viaValueOf);
            out.add(key(valueOf) + " -> " + (valueOf.executeFunction() == viaValueOf));
            out.add(key(value) + " after valueOf -> " + readBack(value, viaValueOf, wrapper, conc));

            // an untouched receiver yields a constant
            ReferenceConstant fresh = newReference();
            out.add(key(value) + " untouched -> " + readBack(value, fresh, wrapper, conc));
        }
        return out;
    }

    private ReferenceConstant newReference() {
        return (ReferenceConstant) env.heap.getReference(new Object());
    }

    private static SymbolicFunction find(Map<?, SymbolicFunction> table, String owner, String name, String desc) {
        for (SymbolicFunction f : table.values()) {
            if (f.getOwner().equals(owner) && f.getName().equals(name) && f.getDesc().equals(desc)) {
                return f;
            }
        }
        throw new AssertionError("no model for " + owner + "." + name + desc);
    }

    private static Object wrapperSample(Class<?> wrapper) {
        if (wrapper == Integer.class) {
            return 41;
        } else if (wrapper == Long.class) {
            return 42L;
        } else if (wrapper == Float.class) {
            return 1.5f;
        } else if (wrapper == Double.class) {
            return 2.5;
        } else if (wrapper == Short.class) {
            return (short) 43;
        } else if (wrapper == Byte.class) {
            return (byte) 44;
        } else if (wrapper == Character.class) {
            return 'x';
        }
        return Boolean.TRUE;
    }

    private static Object other(Class<?> wrapper) {
        if (wrapper == Boolean.class) {
            return Boolean.FALSE;
        } else if (wrapper == Character.class) {
            return 'y';
        } else if (wrapper == Float.class) {
            return 9.5f;
        } else if (wrapper == Double.class) {
            return 9.5;
        } else if (wrapper == Long.class) {
            return 9L;
        } else if (wrapper == Short.class) {
            return (short) 9;
        } else if (wrapper == Byte.class) {
            return (byte) 9;
        }
        return 9;
    }

    private static void setWrapperArg(SymbolicFunction f, Class<?> wrapper, Object conc) {
        if (wrapper == Float.class) {
            float v = (Float) conc;
            f.setParam(0, v, new RealVariable("w", v, -100, 100));
        } else if (wrapper == Double.class) {
            double v = (Double) conc;
            f.setParam(0, v, new RealVariable("w", v, -100, 100));
        } else if (wrapper == Boolean.class) {
            f.setParam(0, true, new IntegerVariable("w", 1, 0, 1));
        } else if (wrapper == Character.class) {
            char v = (Character) conc;
            f.setParam(0, v, new IntegerVariable("w", v, 0, 255));
        } else if (wrapper == Long.class) {
            long v = (Long) conc;
            f.setParam(0, v, new IntegerVariable("w", v, -100, 100));
        } else if (wrapper == Short.class) {
            short v = (Short) conc;
            f.setParam(0, v, new IntegerVariable("w", v, -100, 100));
        } else if (wrapper == Byte.class) {
            byte v = (Byte) conc;
            f.setParam(0, v, new IntegerVariable("w", v, -100, 100));
        } else {
            int v = (Integer) conc;
            f.setParam(0, v, new IntegerVariable("w", v, -100, 100));
        }
    }

    private static String readBack(SymbolicFunction value, ReferenceConstant receiver, Class<?> wrapper,
                                   Object returned) {
        value.setReceiver(returned, receiver);
        if (wrapper == Boolean.class) {
            value.setReturnValue(((Boolean) returned).booleanValue(), (IntegerValue) null);
        } else if (wrapper == Long.class) {
            value.setReturnValue(((Long) returned).longValue(), (IntegerValue) null);
        } else if (wrapper == Float.class) {
            value.setReturnValue(((Float) returned).floatValue(), (RealValue) null);
        } else if (wrapper == Double.class) {
            value.setReturnValue(((Double) returned).doubleValue(), (RealValue) null);
        } else if (wrapper == Character.class) {
            value.setReturnValue((int) (Character) returned, (IntegerValue) null);
        } else {
            value.setReturnValue(((Number) returned).intValue(), (IntegerValue) null);
        }
        return String.valueOf(value.executeFunction());
    }
}
