package org.evosuite.runtime.instrumentation;

import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The instrumentation replaces {@code invokevirtual Object.hashCode()} and
 * {@code Object.toString()} with the runtime's deterministic versions. Those
 * calls are what javac emits for any Object-typed or generic receiver, so the
 * replacement must still reach the receiver's own implementation when it has
 * one; only objects that inherit Object's get the mocked identity forms.
 */
public class ObjectMethodReplacementTest {

    private static final String CALLER = "com/examples/ObjectMethodCaller";

    private Method hash;
    private Method string;

    @BeforeEach
    public void setUp() throws Exception {
        RuntimeSettings.mockJVMNonDeterminism = true;
        MethodCallReplacementCache.resetSingleton();
        MockFramework.enable();

        byte[] original = getClass().getClassLoader()
                .getResourceAsStream(CALLER + ".class").readAllBytes();
        byte[] transformed = new RuntimeInstrumentation().transformBytes(
                getClass().getClassLoader(), CALLER, new ClassReader(original), false);
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(CALLER.replace('/', '.'))) {
                    Class<?> loaded = findLoadedClass(name);
                    return loaded != null ? loaded : defineClass(name, transformed, 0, transformed.length);
                }
                return super.loadClass(name, resolve);
            }
        };
        Class<?> caller = loader.loadClass(CALLER.replace('/', '.'));
        hash = caller.getMethod("hash", Object.class);
        string = caller.getMethod("string", Object.class);
    }

    @AfterEach
    public void tearDown() {
        MockFramework.disable();
        RuntimeSettings.mockJVMNonDeterminism = false;
        MethodCallReplacementCache.resetSingleton();
    }

    @Test
    public void receiversWithTheirOwnHashCodeKeepIt() throws Exception {
        String literal = "desc";
        String copy = new String("desc");
        assertEquals(literal.hashCode(), hash.invoke(null, literal));
        assertEquals(literal.hashCode(), hash.invoke(null, copy));
        assertEquals(42, hash.invoke(null, 42));
    }

    @Test
    public void receiversWithTheirOwnToStringKeepIt() throws Exception {
        assertEquals("desc", string.invoke(null, new String("desc")));
        assertEquals("42", string.invoke(null, 42));
    }

    @Test
    public void objectsInheritingObjectsMethodsGetTheMockedIdentityForms() throws Exception {
        Object first = new com.examples.ObjectMethodCaller.Plain();
        Object second = new com.examples.ObjectMethodCaller.Plain();
        int firstHash = (Integer) hash.invoke(null, first);
        assertEquals(firstHash, hash.invoke(null, first));
        assertNotEquals(firstHash, hash.invoke(null, second));
        assertNotEquals(java.lang.System.identityHashCode(first), firstHash);
        String expected = first.getClass().getName() + "@" + String.format("%010d", firstHash);
        assertEquals(expected, string.invoke(null, first));
        assertTrue(firstHash > 0);
    }
}
