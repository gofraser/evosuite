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
package org.evosuite.runtime;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Stops a class under test from corrupting the JVM that runs the search through
 * {@code sun.misc.Unsafe}.
 *
 * <p>A class such as a hand-rolled deserialiser writes object fields with
 * {@code Unsafe.putObject(dest, offset, value)} and trusts its caller to pass a base object,
 * an offset and a value that belong together. A generated test does not: it can assign
 * {@code theValue = new Object()} to a field of type {@code byte[]}, or map a class to the
 * fields of another one, and the write then lands in the wrong place or stores a value of
 * the wrong type. The JVM does not check any of this, so the damage surfaces later as a
 * crash in the garbage collector, or as a search that no longer stops because one of its own
 * objects has been overwritten.
 *
 * <p>Instrumented code has its {@code Unsafe} writes redirected to the methods of this
 * class. Each one checks, before writing, that the target is something the JVM would also
 * accept from well-behaved code and throws {@link IllegalArgumentException} otherwise:
 * <ul>
 * <li>a null base (an absolute address) only addresses memory the class itself allocated with
 * {@code allocateMemory} and has not freed;</li>
 * <li>for an array base, that the offset is the start of an element and that the value
 * fits the element type;</li>
 * <li>for any other base, that the offset is the offset of a declared instance field of
 * the base's class or one of its superclasses and that the value fits that field's type
 * (a {@code putInt} needs an {@code int} field, a {@code putObject} a reference field the
 * value is assignable to);</li>
 * <li>for a {@code Class} base, that the offset is that of a static field of the class it
 * represents;</li>
 * <li>that the base is not one of the JVM's own control objects (classes, class loaders,
 * threads, reflection objects, method handles) or a value from a shared box cache such as
 * {@code Boolean.TRUE} or {@code Integer.valueOf(1)}, which every other piece of code in the
 * JVM, EvoSuite included, relies on being immutable.</li>
 * </ul>
 * The receiver of the original call is passed as an {@code Object} so that this class does
 * not name {@code sun.misc.Unsafe}, which the Java 8 API signature does not expose; the
 * write is delegated to it reflectively once the check has passed.
 *
 * <p>Only the writes are guarded. Reads cannot corrupt anything, and the allocation and
 * raw-memory calls are not meant to be reached by test input.
 */
public final class UnsafeGuard {

    /** Offsets of the writable instance fields of a class, with the type each one holds. */
    private static final class FieldTable {
        final Map<Long, Class<?>> instance = new HashMap<>();
        final Map<Long, Class<?>> statics = new HashMap<>();
    }

    private static final Map<Class<?>, FieldTable> TABLES =
            Collections.synchronizedMap(new WeakHashMap<Class<?>, FieldTable>());

    private static final Map<String, Method> METHODS =
            Collections.synchronizedMap(new HashMap<String, Method>());

    private UnsafeGuard() {
    }

    // ---- object writes -------------------------------------------------------------------

    public static void putObject(Object unsafe, Object base, long offset, Object value) {
        checkField(unsafe, base, offset, Object.class, value);
        invoke(unsafe, "putObject", new Class<?>[]{Object.class, long.class, Object.class},
                base, offset, value);
    }

    public static void putObjectVolatile(Object unsafe, Object base, long offset, Object value) {
        checkField(unsafe, base, offset, Object.class, value);
        invoke(unsafe, "putObjectVolatile", new Class<?>[]{Object.class, long.class, Object.class},
                base, offset, value);
    }

    public static void putOrderedObject(Object unsafe, Object base, long offset, Object value) {
        checkField(unsafe, base, offset, Object.class, value);
        invoke(unsafe, "putOrderedObject", new Class<?>[]{Object.class, long.class, Object.class},
                base, offset, value);
    }

    public static boolean compareAndSwapObject(Object unsafe, Object base, long offset,
                                               Object expected, Object value) {
        checkField(unsafe, base, offset, Object.class, value);
        return (Boolean) invoke(unsafe, "compareAndSwapObject",
                new Class<?>[]{Object.class, long.class, Object.class, Object.class},
                base, offset, expected, value);
    }

    // ---- primitive writes ----------------------------------------------------------------

    public static void putBoolean(Object unsafe, Object base, long offset, boolean value) {
        checkField(unsafe, base, offset, boolean.class, null);
        invoke(unsafe, "putBoolean", new Class<?>[]{Object.class, long.class, boolean.class},
                base, offset, value);
    }

    public static void putByte(Object unsafe, Object base, long offset, byte value) {
        checkField(unsafe, base, offset, byte.class, null);
        invoke(unsafe, "putByte", new Class<?>[]{Object.class, long.class, byte.class},
                base, offset, value);
    }

    public static void putShort(Object unsafe, Object base, long offset, short value) {
        checkField(unsafe, base, offset, short.class, null);
        invoke(unsafe, "putShort", new Class<?>[]{Object.class, long.class, short.class},
                base, offset, value);
    }

    public static void putChar(Object unsafe, Object base, long offset, char value) {
        checkField(unsafe, base, offset, char.class, null);
        invoke(unsafe, "putChar", new Class<?>[]{Object.class, long.class, char.class},
                base, offset, value);
    }

    public static void putInt(Object unsafe, Object base, long offset, int value) {
        checkField(unsafe, base, offset, int.class, null);
        invoke(unsafe, "putInt", new Class<?>[]{Object.class, long.class, int.class},
                base, offset, value);
    }

    public static void putIntVolatile(Object unsafe, Object base, long offset, int value) {
        checkField(unsafe, base, offset, int.class, null);
        invoke(unsafe, "putIntVolatile", new Class<?>[]{Object.class, long.class, int.class},
                base, offset, value);
    }

    public static void putOrderedInt(Object unsafe, Object base, long offset, int value) {
        checkField(unsafe, base, offset, int.class, null);
        invoke(unsafe, "putOrderedInt", new Class<?>[]{Object.class, long.class, int.class},
                base, offset, value);
    }

    public static boolean compareAndSwapInt(Object unsafe, Object base, long offset,
                                            int expected, int value) {
        checkField(unsafe, base, offset, int.class, null);
        return (Boolean) invoke(unsafe, "compareAndSwapInt",
                new Class<?>[]{Object.class, long.class, int.class, int.class},
                base, offset, expected, value);
    }

    public static void putLong(Object unsafe, Object base, long offset, long value) {
        checkField(unsafe, base, offset, long.class, null);
        invoke(unsafe, "putLong", new Class<?>[]{Object.class, long.class, long.class},
                base, offset, value);
    }

    public static void putLongVolatile(Object unsafe, Object base, long offset, long value) {
        checkField(unsafe, base, offset, long.class, null);
        invoke(unsafe, "putLongVolatile", new Class<?>[]{Object.class, long.class, long.class},
                base, offset, value);
    }

    public static void putOrderedLong(Object unsafe, Object base, long offset, long value) {
        checkField(unsafe, base, offset, long.class, null);
        invoke(unsafe, "putOrderedLong", new Class<?>[]{Object.class, long.class, long.class},
                base, offset, value);
    }

    public static boolean compareAndSwapLong(Object unsafe, Object base, long offset,
                                             long expected, long value) {
        checkField(unsafe, base, offset, long.class, null);
        return (Boolean) invoke(unsafe, "compareAndSwapLong",
                new Class<?>[]{Object.class, long.class, long.class, long.class},
                base, offset, expected, value);
    }

    public static void putFloat(Object unsafe, Object base, long offset, float value) {
        checkField(unsafe, base, offset, float.class, null);
        invoke(unsafe, "putFloat", new Class<?>[]{Object.class, long.class, float.class},
                base, offset, value);
    }

    public static void putDouble(Object unsafe, Object base, long offset, double value) {
        checkField(unsafe, base, offset, double.class, null);
        invoke(unsafe, "putDouble", new Class<?>[]{Object.class, long.class, double.class},
                base, offset, value);
    }

    // ---- absolute-address writes and off-heap memory -------------------------------------

    /**
     * Native blocks the class under test obtained through {@code allocateMemory}, as
     * start address to size. A write to an absolute address is only passed on when it lies
     * inside one of them; anything else would be a write to an arbitrary address of the process.
     * The blocks are deliberately not released when the runtime is reset between tests: a class
     * may keep a buffer in a static field, and refusing its writes afterwards would change its
     * behaviour, whereas an unreleased block is exactly what the class would leak without us.
     */
    private static final ConcurrentSkipListMap<Long, Long> ALLOCATIONS = new ConcurrentSkipListMap<>();

    private static void requireLive(long address, long length) {
        Map.Entry<Long, Long> e = address < 0 ? null : ALLOCATIONS.floorEntry(address);
        if (length < 0 || e == null || address - e.getKey() > e.getValue() - length) {
            throw new IllegalArgumentException("Refusing Unsafe access to " + length
                    + " bytes at address " + address + ": not inside memory the class allocated");
        }
    }

    public static long allocateMemory(Object unsafe, long bytes) {
        long address = (Long) invoke(unsafe, "allocateMemory", new Class<?>[]{long.class}, bytes);
        if (bytes > 0) {
            ALLOCATIONS.put(address, bytes);
        }
        return address;
    }

    public static long reallocateMemory(Object unsafe, long address, long bytes) {
        if (address != 0) {
            Long size = ALLOCATIONS.get(address);
            if (size == null) {
                throw new IllegalArgumentException(
                        "Refusing Unsafe reallocation of address " + address + ": not allocated by the class");
            }
        }
        long result = (Long) invoke(unsafe, "reallocateMemory",
                new Class<?>[]{long.class, long.class}, address, bytes);
        ALLOCATIONS.remove(address);
        if (bytes > 0) {
            ALLOCATIONS.put(result, bytes);
        }
        return result;
    }

    public static void freeMemory(Object unsafe, long address) {
        if (address == 0) {
            return;
        }
        if (ALLOCATIONS.remove(address) == null) {
            throw new IllegalArgumentException(
                    "Refusing Unsafe free of address " + address + ": not allocated by the class");
        }
        invoke(unsafe, "freeMemory", new Class<?>[]{long.class}, address);
    }

    public static void setMemory(Object unsafe, long address, long bytes, byte value) {
        requireLive(address, bytes);
        invoke(unsafe, "setMemory", new Class<?>[]{long.class, long.class, byte.class},
                address, bytes, value);
    }

    public static void setMemory(Object unsafe, Object base, long offset, long bytes, byte value) {
        checkRegion(unsafe, base, offset, bytes);
        invoke(unsafe, "setMemory", new Class<?>[]{Object.class, long.class, long.class, byte.class},
                base, offset, bytes, value);
    }

    public static void copyMemory(Object unsafe, long from, long to, long bytes) {
        requireLive(from, bytes);
        requireLive(to, bytes);
        invoke(unsafe, "copyMemory", new Class<?>[]{long.class, long.class, long.class},
                from, to, bytes);
    }

    public static void copyMemory(Object unsafe, Object srcBase, long srcOffset, Object dstBase,
                                  long dstOffset, long bytes) {
        checkRegion(unsafe, srcBase, srcOffset, bytes);
        checkRegion(unsafe, dstBase, dstOffset, bytes);
        invoke(unsafe, "copyMemory",
                new Class<?>[]{Object.class, long.class, Object.class, long.class, long.class},
                srcBase, srcOffset, dstBase, dstOffset, bytes);
    }

    /** A byte range is legitimate in live native memory or inside the elements of an array. */
    private static void checkRegion(Object unsafe, Object base, long offset, long bytes) {
        if (base == null) {
            requireLive(offset, bytes);
            return;
        }
        Class<?> c = base.getClass();
        if (!c.isArray()) {
            throw new IllegalArgumentException(
                    "Refusing Unsafe bulk access to an instance of " + c.getName());
        }
        long[] layout = arrayLayout(unsafe, c);
        long start = layout[0];
        long scale = layout[1];
        long end = start + scale * Array.getLength(base);
        if (bytes < 0 || offset < start || offset > end - bytes) {
            throw new IllegalArgumentException("Refusing Unsafe bulk access outside " + c.getSimpleName());
        }
    }

    public static void putByte(Object unsafe, long address, byte value) {
        requireLive(address, 1);
        invoke(unsafe, "putByte", new Class<?>[]{long.class, byte.class}, address, value);
    }

    public static void putShort(Object unsafe, long address, short value) {
        requireLive(address, 2);
        invoke(unsafe, "putShort", new Class<?>[]{long.class, short.class}, address, value);
    }

    public static void putChar(Object unsafe, long address, char value) {
        requireLive(address, 2);
        invoke(unsafe, "putChar", new Class<?>[]{long.class, char.class}, address, value);
    }

    public static void putInt(Object unsafe, long address, int value) {
        requireLive(address, 4);
        invoke(unsafe, "putInt", new Class<?>[]{long.class, int.class}, address, value);
    }

    public static void putLong(Object unsafe, long address, long value) {
        requireLive(address, 8);
        invoke(unsafe, "putLong", new Class<?>[]{long.class, long.class}, address, value);
    }

    public static void putFloat(Object unsafe, long address, float value) {
        requireLive(address, 4);
        invoke(unsafe, "putFloat", new Class<?>[]{long.class, float.class}, address, value);
    }

    public static void putDouble(Object unsafe, long address, double value) {
        requireLive(address, 8);
        invoke(unsafe, "putDouble", new Class<?>[]{long.class, double.class}, address, value);
    }

    public static void putAddress(Object unsafe, long address, long value) {
        requireLive(address, 8);
        invoke(unsafe, "putAddress", new Class<?>[]{long.class, long.class}, address, value);
    }

    // ---- checks --------------------------------------------------------------------------

    /**
     * @param expectedType {@code Object.class} for a reference write, otherwise the primitive
     *                     class of the write
     * @param value        the reference being stored, ignored for a primitive write
     */
    static void checkField(Object unsafe, Object base, long offset, Class<?> expectedType,
                           Object value) {
        if (base == null) {
            // a null base makes the offset an absolute address
            if (expectedType == Object.class) {
                throw new IllegalArgumentException(
                        "Refusing Unsafe reference write to absolute address " + offset);
            }
            requireLive(offset, widthOf(expectedType));
            return;
        }
        Class<?> baseClass = base.getClass();

        if (baseClass.isArray()) {
            checkArray(unsafe, base, baseClass, offset, expectedType, value);
            return;
        }

        if (isProtected(base, baseClass)) {
            throw new IllegalArgumentException(
                    "Refusing Unsafe write to an instance of " + baseClass.getName());
        }

        Class<?> declared;
        FieldTable table;
        if (base instanceof Class) {
            table = tableFor(unsafe, (Class<?>) base);
            declared = table.statics.get(offset);
        } else {
            table = tableFor(unsafe, baseClass);
            declared = table.instance.get(offset);
        }
        if (declared == null) {
            throw new IllegalArgumentException("Refusing Unsafe write at offset " + offset
                    + " of " + baseClass.getName() + ": not the offset of a field of that class");
        }
        checkType(declared, expectedType, value, baseClass.getName() + " at offset " + offset);
    }

    private static final Map<Class<?>, long[]> ARRAY_LAYOUTS =
            Collections.synchronizedMap(new WeakHashMap<Class<?>, long[]>());

    /** @return base offset and index scale of an array class, looked up once per class */
    private static long[] arrayLayout(Object unsafe, Class<?> arrayClass) {
        long[] layout = ARRAY_LAYOUTS.get(arrayClass);
        if (layout == null) {
            layout = new long[]{
                    ((Number) call(unsafe, "arrayBaseOffset", new Class<?>[]{Class.class}, arrayClass)).longValue(),
                    ((Number) call(unsafe, "arrayIndexScale", new Class<?>[]{Class.class}, arrayClass)).longValue()};
            ARRAY_LAYOUTS.put(arrayClass, layout);
        }
        return layout;
    }

    private static long widthOf(Class<?> primitive) {
        if (primitive == boolean.class || primitive == byte.class) {
            return 1;
        }
        if (primitive == short.class || primitive == char.class) {
            return 2;
        }
        if (primitive == int.class || primitive == float.class) {
            return 4;
        }
        return 8;
    }

    private static void checkArray(Object unsafe, Object array, Class<?> arrayClass, long offset,
                                   Class<?> expectedType, Object value) {
        long[] layout = arrayLayout(unsafe, arrayClass);
        long base = layout[0];
        long scale = layout[1];
        long relative = offset - base;
        int length = Array.getLength(array);
        if (scale <= 0 || relative < 0 || relative % scale != 0 || relative / scale >= length) {
            throw new IllegalArgumentException("Refusing Unsafe write at offset " + offset
                    + " outside the elements of " + arrayClass.getSimpleName() + "[" + length + "]");
        }
        checkType(arrayClass.getComponentType(), expectedType, value,
                arrayClass.getSimpleName() + " element");
    }

    private static void checkType(Class<?> declared, Class<?> expectedType, Object value,
                                  String where) {
        if (expectedType == Object.class) {
            if (declared.isPrimitive()) {
                throw new IllegalArgumentException(
                        "Refusing Unsafe reference write to primitive " + declared + " " + where);
            }
            if (value != null && !declared.isInstance(value)) {
                throw new IllegalArgumentException("Refusing Unsafe write of "
                        + value.getClass().getName() + " to a " + declared.getName() + " " + where);
            }
        } else if (declared != expectedType) {
            throw new IllegalArgumentException("Refusing Unsafe write of " + expectedType
                    + " to a " + declared.getName() + " " + where);
        }
    }

    /**
     * The JVM's control objects and the shared immutable boxes: changing a field of any of
     * them changes behaviour for every user of the JVM, not only for the class under test.
     */
    private static boolean isProtected(Object base, Class<?> baseClass) {
        if (base instanceof ClassLoader || base instanceof Thread
                || base instanceof AccessibleObject || base instanceof Enum) {
            return true;
        }
        if (baseClass == Class.class) {
            // A Class base is only legitimate as the base of a static field, which the caller
            // distinguishes by offset; its own instance fields are the JVM's.
            return false;
        }
        String name = baseClass.getName();
        if (name.startsWith("java.lang.invoke.") || name.startsWith("jdk.internal.")
                || name.startsWith("sun.misc.") || name.startsWith("sun.reflect.")) {
            return true;
        }
        return isSharedBox(base);
    }

    private static boolean isSharedBox(Object base) {
        if (base instanceof Boolean) {
            return base == Boolean.TRUE || base == Boolean.FALSE;
        }
        if (base instanceof Character) {
            return base == Character.valueOf((Character) base);
        }
        if (base instanceof Byte) {
            return base == Byte.valueOf((Byte) base);
        }
        if (base instanceof Short) {
            return base == Short.valueOf((Short) base);
        }
        if (base instanceof Integer) {
            return base == Integer.valueOf((Integer) base);
        }
        if (base instanceof Long) {
            return base == Long.valueOf((Long) base);
        }
        return false;
    }

    private static FieldTable tableFor(Object unsafe, Class<?> c) {
        FieldTable table = TABLES.get(c);
        if (table != null) {
            return table;
        }
        table = new FieldTable();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            Field[] fields;
            try {
                fields = k.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fields) {
                boolean isStatic = Modifier.isStatic(f.getModifiers());
                if (isStatic && k != c) {
                    continue;
                }
                try {
                    long off = ((Number) call(unsafe, isStatic ? "staticFieldOffset" : "objectFieldOffset",
                            new Class<?>[]{Field.class}, f)).longValue();
                    (isStatic ? table.statics : table.instance).put(off, f.getType());
                } catch (Throwable t) {
                    // hidden classes and records refuse an offset; such a field is not writable
                }
            }
        }
        TABLES.put(c, table);
        return table;
    }

    // ---- reflection on Unsafe ------------------------------------------------------------

    private static Object call(Object unsafe, String name, Class<?>[] types, Object... args) {
        return invoke(unsafe, name, types, args);
    }

    private static Object invoke(Object unsafe, String name, Class<?>[] types, Object... args) {
        try {
            Method m = method(unsafe.getClass(), name, types);
            return m.invoke(unsafe, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot reach Unsafe." + name, e);
        }
    }

    private static Method method(Class<?> unsafeClass, String name, Class<?>[] types)
            throws NoSuchMethodException {
        StringBuilder key = new StringBuilder(name);
        for (Class<?> t : types) {
            key.append(',').append(t.getName());
        }
        Method m = METHODS.get(key.toString());
        if (m == null) {
            m = unsafeClass.getMethod(name, types);
            METHODS.put(key.toString(), m);
        }
        return m;
    }
}
