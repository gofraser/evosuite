package org.evosuite.runtime.instrumentation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * A generated test is replayed in a fresh JVM that only has the runtime: the code under test is
 * instrumented by RuntimeInstrumentation, not by the client's class loader. A loop whose body
 * swallows the loop-limit exception must end there as it does during the search.
 */
public class StandaloneReplayLoopLimitTest {

    /** Runs in the child JVM. */
    public static class Probe {
        public static void main(String[] args) throws Exception {
            org.evosuite.runtime.RuntimeSettings.maxNumberOfIterationsPerLoop = 10_000;
            String name = "com/examples/SwallowingSpinner";
            byte[] bytes;
            try (InputStream in = Probe.class.getClassLoader().getResourceAsStream(name + ".class")) {
                bytes = new RuntimeInstrumentation().transformBytes(Probe.class.getClassLoader(), name,
                        new ClassReader(in), false);
            }
            ClassLoader loader = new ClassLoader(Probe.class.getClassLoader()) {
                @Override
                protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
                    if (n.equals("com.examples.SwallowingSpinner")) {
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
            Class<?> cls = loader.loadClass("com.examples.SwallowingSpinner");
            Object sut = cls.getDeclaredConstructor().newInstance();
            org.evosuite.runtime.LoopCounter.getInstance().reset();
            try {
                cls.getMethod("spin").invoke(sut);
                System.out.println("REPLAY_RETURNED");
            } catch (java.lang.reflect.InvocationTargetException e) {
                System.out.println("REPLAY_THREW " + e.getCause().getClass().getName());
            }
            System.exit(0);
        }
    }

    @Test
    public void loopLimitExceptionSwallowedByTheCodeEndsTheReplay() throws Exception {
        Path output = Files.createTempFile("replay", ".txt");
        String java = new File(System.getProperty("java.home"), "bin/java").getPath();
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                Probe.class.getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished = child.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            child.destroyForcibly();
        }
        String log = new String(Files.readAllBytes(output));
        Assertions.assertTrue(finished, "the replayed method never ended: " + log);
        Assertions.assertTrue(log.contains("REPLAY_THREW org.evosuite.runtime.TooManyResourcesException"), log);
    }
}
