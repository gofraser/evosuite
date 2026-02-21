package org.evosuite.runtime.instrumentation;

import org.evosuite.runtime.RuntimeSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;

public class MethodCallReplacementCacheTest {

    @BeforeEach
    public void enableJvmMocking() {
        RuntimeSettings.mockJVMNonDeterminism = true;
    }

    @AfterEach
    public void resetState() {
        RuntimeSettings.mockJVMNonDeterminism = false;
        MethodCallReplacementCache.resetSingleton();
    }

    @Test
    public void testStaticReplacementRequiresMethodInMockClass() throws Exception {
        Method console = java.lang.System.class.getMethod("console");
        String consoleKey = console.getName() + Type.getMethodDescriptor(console);

        Method arraycopy = java.lang.System.class.getMethod(
                "arraycopy",
                Object.class,
                int.class,
                Object.class,
                int.class,
                int.class);
        String arraycopyKey = arraycopy.getName() + Type.getMethodDescriptor(arraycopy);

        MethodCallReplacementCache cache = MethodCallReplacementCache.getInstance();
        Assertions.assertTrue(cache.hasReplacementCall("java/lang/System", consoleKey));
        Assertions.assertFalse(cache.hasReplacementCall("java/lang/System", arraycopyKey));
    }
}
