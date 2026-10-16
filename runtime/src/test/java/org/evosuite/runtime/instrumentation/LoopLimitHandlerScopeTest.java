package org.evosuite.runtime.instrumentation;

import org.evosuite.runtime.LoopCounter;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.TooManyResourcesException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.objectweb.asm.ClassReader;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A loop-limit exception that the code under test swallowed is let through the instrumented
 * catch handlers when the same loop hits the limit again. Every other loop-limit exception
 * must reach the handlers of the code under test like any other exception.
 */
public class LoopLimitHandlerScopeTest {

    private static final String SUT = "com.examples.LoopLimitHandlers";

    private long limit;
    private boolean active;

    @BeforeEach
    public void setUp() {
        limit = RuntimeSettings.maxNumberOfIterationsPerLoop;
        RuntimeSettings.maxNumberOfIterationsPerLoop = 1_000;
        active = LoopCounter.getInstance().isActivated();
        LoopCounter.getInstance().setActive(true);
        LoopCounter.getInstance().reset();
    }

    @AfterEach
    public void tearDown() {
        RuntimeSettings.maxNumberOfIterationsPerLoop = limit;
        LoopCounter.getInstance().setActive(active);
        LoopCounter.getInstance().reset();
    }

    private static Class<?> loadInstrumented() throws Exception {
        ClassLoader parent = LoopLimitHandlerScopeTest.class.getClassLoader();
        String name = SUT.replace('.', '/');
        byte[] bytes;
        try (InputStream in = parent.getResourceAsStream(name + ".class")) {
            bytes = new RuntimeInstrumentation().transformBytes(parent, name, new ClassReader(in), false);
        }
        ClassLoader loader = new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
                if (n.equals(SUT)) {
                    synchronized (this) {
                        Class<?> c = findLoadedClass(n);
                        if (c == null) {
                            c = defineClass(n, bytes, 0, bytes.length);
                        }
                        return c;
                    }
                }
                return super.loadClass(n, resolve);
            }
        };
        return loader.loadClass(SUT);
    }

    private static Object call(Object sut, String method) throws Throwable {
        try {
            return sut.getClass().getMethod(method).invoke(sut);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * The first statement hits the limit and the exception reaches the test; nothing swallowed
     * it. The second statement hits the limit in another loop, under a handler that recovers
     * with a fallback value: that handler must run.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    public void earlierPropagatedLimitDoesNotBypassAnotherLoopsHandler() throws Throwable {
        Object sut = loadInstrumented().getDeclaredConstructor().newInstance();
        Assertions.assertThrows(TooManyResourcesException.class, () -> call(sut, "propagate"));
        Assertions.assertEquals(-1, call(sut, "fallback"));
    }

    /**
     * A worker abandoned by the previous test keeps hitting the limit after the reset for the
     * next test. The next test's own first loop-limit exception must reach its handler.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    public void limitOnAnAbandonedThreadDoesNotBypassTheNextTestsHandler() throws Throwable {
        Object sut = loadInstrumented().getDeclaredConstructor().newInstance();
        CountDownLatch nextTestStarted = new CountDownLatch(1);
        AtomicReference<Object> abandonedResult = new AtomicReference<>();
        Thread abandoned = new Thread(() -> {
            try {
                nextTestStarted.await();
                abandonedResult.set(call(sut, "fallback"));
                abandonedResult.set(call(sut, "fallback"));
            } catch (Throwable t) {
                abandonedResult.set(t);
            }
        });
        abandoned.start();
        LoopCounter.getInstance().protectFromReset(abandoned);
        LoopCounter.getInstance().reset(); // the next test starts
        nextTestStarted.countDown();
        abandoned.join();

        Assertions.assertEquals(-1, call(sut, "fallback"));
    }

    /**
     * A loop that swallows the loop-limit exception is interrupted once, and clears the
     * interrupt. The next time its inner loop hits the limit, the exception must get through:
     * the interrupt does not make the code under test start over with a clean record.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    public void interruptDoesNotForgetTheSwallowedLimit() throws Throwable {
        Class<?> cls = loadInstrumented();
        Object sut = cls.getDeclaredConstructor().newInstance();
        Assertions.assertThrows(TooManyResourcesException.class, () -> call(sut, "interruptedSpin"));
        Assertions.assertFalse(Thread.currentThread().isInterrupted());
        // round 1: limit, swallowed; round 2: interrupted, swallowed; round 3: limit again, let through
        Assertions.assertEquals(3, cls.getField("rounds").getInt(null));
    }
}
