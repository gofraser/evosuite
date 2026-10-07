package com.examples;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * Mimics a deserialiser that writes fields through {@code sun.misc.Unsafe} on behalf of a caller
 * that decides the object, the field and the value.
 */
public class UnsafeFieldWriter {

    public static class Holder {
        public String text = "initial";
        public int number = 1;
    }

    private static Unsafe unsafe() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    }

    public static void putObject(Object dest, Field field, Object value) throws Exception {
        Unsafe u = unsafe();
        u.putObject(dest, u.objectFieldOffset(field), value);
    }

    public static void putInt(Object dest, Field field, int value) throws Exception {
        Unsafe u = unsafe();
        u.putInt(dest, u.objectFieldOffset(field), value);
    }

    public static void putIntAt(Object dest, long offset, int value) throws Exception {
        unsafe().putInt(dest, offset, value);
    }

    public static void putIntAtAddress(long address, int value) throws Exception {
        unsafe().putInt(address, value);
    }

    public static long allocate(long bytes) throws Exception {
        return unsafe().allocateMemory(bytes);
    }

    public static void free(long address) throws Exception {
        unsafe().freeMemory(address);
    }

    public static void putLongAtAddress(long address, long value) throws Exception {
        unsafe().putLong(address, value);
    }

    public static long getLongAtAddress(long address) throws Exception {
        return unsafe().getLong(address);
    }

    public static void setBytes(long address, long bytes) throws Exception {
        unsafe().setMemory(address, bytes, (byte) 7);
    }

    public static void copy(long from, long to, long bytes) throws Exception {
        unsafe().copyMemory(from, to, bytes);
    }
}
