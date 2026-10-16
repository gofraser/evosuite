package org.evosuite.runtime.instrumentation;

import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * A class under test that writes fields through {@code sun.misc.Unsafe} must get an exception,
 * not a corrupted JVM, when a generated test hands it a write that does not fit.
 */
public class UnsafeWriteInstrumentationTest {

    private static final String WRITER = "com.examples.UnsafeFieldWriter";
    private static final String HOLDER = WRITER + "$Holder";

    private Class<?> writer;
    private Class<?> holder;

    /** Defines the instrumented bytes of the examples; everything else is the parent's. */
    private static final class InstrumentingLoader extends ClassLoader {
        private final java.util.Map<String, byte[]> bytes = new java.util.HashMap<>();

        InstrumentingLoader(ClassLoader parent) {
            super(parent);
        }

        void add(String name, byte[] b) {
            bytes.put(name, b);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] b = bytes.get(name);
            if (b == null) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    c = defineClass(name, b, 0, b.length);
                }
                return c;
            }
        }
    }

    @BeforeEach
    public void setup() throws Exception {
        RuntimeSettings.mockJVMNonDeterminism = true;
        MethodCallReplacementCache.resetSingleton();
        // the replacement is guarded by MockFramework.isEnabled(), i.e. active while a test runs
        MockFramework.enable();
        ClassLoader parent = getClass().getClassLoader();
        InstrumentingLoader loader = new InstrumentingLoader(parent);
        for (String name : new String[]{WRITER, HOLDER}) {
            String resource = name.replace('.', '/');
            byte[] original;
            try (InputStream in = parent.getResourceAsStream(resource + ".class")) {
                Assertions.assertNotNull(in, "Missing test class bytes for " + name);
                original = in.readAllBytes();
            }
            byte[] transformed = new RuntimeInstrumentation()
                    .transformBytes(parent, resource, new ClassReader(original), false);
            if (name.equals(WRITER)) {
                Assertions.assertTrue(new String(transformed, java.nio.charset.StandardCharsets.ISO_8859_1)
                        .contains("org/evosuite/runtime/UnsafeGuard"), "writes were not redirected");
            }
            loader.add(name, transformed);
        }
        writer = loader.loadClass(WRITER);
        holder = loader.loadClass(HOLDER);
    }

    @AfterEach
    public void cleanup() {
        MockFramework.disable();
        RuntimeSettings.mockJVMNonDeterminism = false;
        MethodCallReplacementCache.resetSingleton();
    }

    private Object newHolder() throws Exception {
        return holder.getDeclaredConstructor().newInstance();
    }

    private Object call(String method, Class<?>[] types, Object... args) throws Throwable {
        Method m = writer.getMethod(method, types);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static final Class<?>[] PUT_OBJECT = {Object.class, Field.class, Object.class};
    private static final Class<?>[] PUT_INT = {Object.class, Field.class, int.class};
    private static final Class<?>[] PUT_INT_AT = {Object.class, long.class, int.class};

    @Test
    public void writeOfMatchingTypeStillGoesThrough() throws Throwable {
        Object h = newHolder();
        call("putObject", PUT_OBJECT, h, holder.getField("text"), "changed");
        Assertions.assertEquals("changed", holder.getField("text").get(h));

        call("putInt", PUT_INT, h, holder.getField("number"), 42);
        Assertions.assertEquals(42, holder.getField("number").get(h));
    }

    @Test
    public void referenceOfWrongTypeIsRefused() throws Throwable {
        Object h = newHolder();
        Field text = holder.getField("text");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> call("putObject", PUT_OBJECT, h, text, new Object()));
        Assertions.assertEquals("initial", text.get(h));
    }

    @Test
    public void primitiveWriteToReferenceFieldIsRefused() throws Throwable {
        Object h = newHolder();
        Field text = holder.getField("text");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> call("putInt", PUT_INT, h, text, 7));
    }

    @Test
    public void fieldOfAnotherClassIsRefused() throws Throwable {
        // A field of String used as the offset into an Object is how a test that remaps
        // classes to fields writes beyond the end of the object.
        Field stringHash = String.class.getDeclaredField("hash");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> call("putInt", PUT_INT, new Object(), stringHash, 1));
    }

    @Test
    public void arbitraryOffsetIsRefused() throws Throwable {
        Object h = newHolder();
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> call("putIntAt", PUT_INT_AT, h, 4096L, 1));
    }

    @Test
    public void nullBaseAndAbsoluteAddressAreRefused() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> call("putIntAt", PUT_INT_AT, null, 8L, 1));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> call("putIntAtAddress", new Class<?>[]{long.class, int.class}, 8L, 1));
    }

    @Test
    public void sharedBoxCacheIsNotWritable() throws Throwable {
        Field value = Integer.class.getDeclaredField("value");
        Integer cached = Integer.valueOf(5);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> call("putInt", PUT_INT, cached, value, 6));
        Assertions.assertEquals(5, cached.intValue());

        Field boolValue = Boolean.class.getDeclaredField("value");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> call("putInt", PUT_INT, Boolean.TRUE, boolValue, 0));
        Assertions.assertTrue(Boolean.TRUE.booleanValue());
    }

    @Test
    public void freshBoxIsWritable() throws Throwable {
        Field value = Integer.class.getDeclaredField("value");
        @SuppressWarnings("deprecation")
        Integer fresh = new Integer(900);
        call("putInt", PUT_INT, fresh, value, 901);
        Assertions.assertEquals(901, fresh.intValue());
    }

    private static final Class<?>[] LONG = {long.class};
    private static final Class<?>[] LONG_LONG = {long.class, long.class};

    @Test
    public void offHeapBlockCanBeAllocatedWrittenReadAndFreed() throws Throwable {
        long a = (Long) call("allocate", LONG, 32L);
        try {
            call("putLongAtAddress", LONG_LONG, a, 11L);
            call("putLongAtAddress", LONG_LONG, a + 24, 22L);
            Assertions.assertEquals(11L, call("getLongAtAddress", LONG, a));
            Assertions.assertEquals(22L, call("getLongAtAddress", LONG, a + 24));
            call("setBytes", LONG_LONG, a, 32L);
            long b = (Long) call("allocate", LONG, 32L);
            call("copy", new Class<?>[]{long.class, long.class, long.class}, a, b, 32L);
            Assertions.assertEquals(0x0707070707070707L, call("getLongAtAddress", LONG, b));
            call("free", LONG, b);
        } finally {
            call("free", LONG, a);
        }
    }

    @Test
    public void writeOutsideTheBlockIsRefused() throws Throwable {
        long a = (Long) call("allocate", LONG, 16L);
        try {
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> call("putLongAtAddress", LONG_LONG, a + 9, 1L)); // straddles the end
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> call("putLongAtAddress", LONG_LONG, a + 16, 1L));
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> call("putLongAtAddress", LONG_LONG, a - 8, 1L));
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> call("setBytes", LONG_LONG, a, 17L));
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> call("copy", new Class<?>[]{long.class, long.class, long.class}, a, a + 8, 16L));
        } finally {
            call("free", LONG, a);
        }
    }

    @Test
    public void writeAfterFreeAndDoubleFreeAreRefused() throws Throwable {
        long a = (Long) call("allocate", LONG, 16L);
        call("free", LONG, a);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> call("putLongAtAddress", LONG_LONG, a, 1L));
        Assertions.assertThrows(IllegalArgumentException.class, () -> call("free", LONG, a));
    }

    @Test
    public void writeToArbitraryAddressAndFreeOfForeignAddressAreRefused() throws Throwable {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> call("putLongAtAddress", LONG_LONG, 4096L, 1L));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> call("putLongAtAddress", LONG_LONG, -1L, 1L));
        Assertions.assertThrows(IllegalArgumentException.class, () -> call("free", LONG, 4096L));
    }
}
