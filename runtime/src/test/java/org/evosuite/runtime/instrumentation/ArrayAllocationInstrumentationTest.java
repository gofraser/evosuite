/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.runtime.instrumentation;

import org.evosuite.runtime.ArrayAllocationBudget;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.TooManyResourcesException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Regression coverage for array accounting in the runtime replay pipeline. */
public class ArrayAllocationInstrumentationTest {

    private static final String FIXTURE = "com.examples.ArrayAllocationFixture";
    private static final String FIXTURE_RESOURCE = FIXTURE.replace('.', '/') + ".class";

    private long originalBudget;

    @BeforeEach
    public void setUp() {
        originalBudget = RuntimeSettings.maxCumulativeArrayBytes;
        RuntimeSettings.maxCumulativeArrayBytes = 32L;
        ArrayAllocationBudget.reset();
    }

    @AfterEach
    public void tearDown() {
        RuntimeSettings.maxCumulativeArrayBytes = originalBudget;
        ArrayAllocationBudget.reset();
    }

    @Test
    public void enabledRuntimePipelineChargesPrimitiveReferenceAndMultiArrays() throws Exception {
        byte[] transformed = transform(true);
        assertTrue(countBudgetCalls(transformed) >= 3,
                "Expected budget calls for primitive, reference, and multi-dimensional arrays");

        assertEquals(2, invoke(transformed, "primitive", 2));
        ArrayAllocationBudget.reset();
        assertEquals(2, invoke(transformed, "reference", 2));
        ArrayAllocationBudget.reset();
        // multi-dimensional charging now accounts for the outer reference array and the
        // per-object header overhead at every level (see ArrayAllocationBudget.checkAndRecord
        // (int[], int)), not just the product of dimensions times the leaf width, so a
        // 2x2 byte[][] already costs more than the 32-byte budget used for the
        // single-dimension cases above: give it a budget wide enough to tell a small,
        // legal shape from a clearly oversized one.
        RuntimeSettings.maxCumulativeArrayBytes = 100L;
        assertEquals(2, invoke(transformed, "multi", 2, 2));

        RuntimeSettings.maxCumulativeArrayBytes = 32L;
        ArrayAllocationBudget.reset();
        assertRejected(transformed, "primitive", 9);
        ArrayAllocationBudget.reset();
        assertRejected(transformed, "reference", 5);
        RuntimeSettings.maxCumulativeArrayBytes = 100L;
        ArrayAllocationBudget.reset();
        assertRejected(transformed, "multi", 6, 6);
    }

    @Test
    public void disabledRuntimePipelineLeavesArrayAllocationsUninstrumented() throws Exception {
        RuntimeSettings.maxCumulativeArrayBytes = -1L;
        byte[] transformed = transform(false);

        assertEquals(0, countBudgetCalls(transformed));
        assertEquals(1000, invoke(transformed, "primitive", 1000));
        assertEquals(1000, invoke(transformed, "reference", 1000));
        assertEquals(100, invoke(transformed, "multi", 100, 100));
    }

    /**
     * Finding 4: charging only the product of all dimensions times the leaf width
     * ignores every reference array but the leaf, and charges zero whenever a leaf
     * dimension is zero. {@code multi(1000, 0)} allocates one outer reference array of
     * 1000 slots plus 1000 (empty) inner {@code byte[]} objects -- real, non-zero
     * allocation -- but the old charge was {@code 1000 * 0 * 1 == 0}, so it could never
     * trip the budget no matter how large {@code rows} got. A 3-D shape with a zero
     * leaf dimension is charged the same way, one level further.
     */
    @Test
    public void zeroSizedLeafDimensionsStillChargeTheOuterArrays() throws Exception {
        byte[] transformed = transform(true);

        ArrayAllocationBudget.reset();
        assertRejected(transformed, "multi", 1000, 0);
        ArrayAllocationBudget.reset();
        assertRejected(transformed, "multi3", 10, 10, 0);
    }

    /**
     * Re-review finding, 2026-10-01: the single-dimension charge was
     * {@code elements * elementWidthBytes}, so every EMPTY array -- primitive or
     * reference -- cost zero bytes, even though each is a real heap object. A CUT
     * retaining many empty arrays could allocate forever without ever advancing the
     * cumulative guard, while the MULTIANEWARRAY path already charges a per-array
     * header at every level, including empty leaves (see
     * {@code zeroSizedLeafDimensionsStillChargeTheOuterArrays} above). A zero-byte cap
     * must therefore already refuse a single empty array of either kind.
     */
    @Test
    public void emptyArrayStillChargesTheObjectHeaderOverhead() throws Exception {
        byte[] transformed = transform(true);
        RuntimeSettings.maxCumulativeArrayBytes = 0L;
        ArrayAllocationBudget.reset();

        assertRejected(transformed, "primitive", 0);
        ArrayAllocationBudget.reset();
        assertRejected(transformed, "reference", 0);
    }

    /**
     * Same gap as {@link #emptyArrayStillChargesTheObjectHeaderOverhead()}, but under a
     * budget wide enough to admit a couple of empty arrays before tripping: pins that
     * the charge is per-array (not a one-off), so retaining many empty arrays in a loop
     * is still bounded by the cumulative cap.
     */
    @Test
    public void repeatedEmptyArraysEventuallyTripTheCumulativeBudget() throws Exception {
        byte[] transformed = transform(true);

        // Each empty array costs exactly the 16-byte header: two fit in 40 bytes, a
        // third does not.
        RuntimeSettings.maxCumulativeArrayBytes = 40L;
        ArrayAllocationBudget.reset();
        assertEquals(0, invoke(transformed, "primitive", 0));
        assertEquals(0, invoke(transformed, "primitive", 0));
        assertRejected(transformed, "primitive", 0);

        RuntimeSettings.maxCumulativeArrayBytes = 40L;
        ArrayAllocationBudget.reset();
        assertEquals(0, invoke(transformed, "reference", 0));
        assertEquals(0, invoke(transformed, "reference", 0));
        assertRejected(transformed, "reference", 0);
    }

    /**
     * Finding 5: the charge happens before the real array instruction runs, so a
     * negative dimension must not be charged at all -- not as a negative byte count
     * (which would credit the budget for an allocation that never happened) and not by
     * throwing {@link TooManyResourcesException} itself (which would replace the
     * {@code NegativeArraySizeException} the class under test may be relying on). The
     * real NEWARRAY still throws it, and the running total is left exactly where it was.
     */
    @Test
    public void negativeDimensionDoesNotCreditTheBudgetAndJvmStillThrows() throws Exception {
        byte[] transformed = transform(true);
        ArrayAllocationBudget.reset();

        try {
            invoke(transformed, "primitive", -5);
            fail("Expected the real JVM array instruction to throw NegativeArraySizeException");
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof NegativeArraySizeException,
                    "Expected NegativeArraySizeException but got " + e.getCause());
        }
        assertEquals(0L, readAllocatedBytes(), "A negative dimension must not change the running total");

        // The budget (32 bytes) must still reject an allocation that is actually over
        // it (9 ints = 36 bytes): if the negative attempt above had credited the
        // budget, this would wrongly be accepted instead.
        assertRejected(transformed, "primitive", 9);
    }

    /**
     * Finding 6: {@code allocatedBytes += bytes} on a plain {@code volatile long} is a
     * read-modify-write, not an atomic increment, so concurrent SUT threads lose each
     * other's updates. Eight threads each charging a single byte 100,000 times must add
     * up to exactly 800,000 once they have all joined -- not merely "more than before".
     */
    @Test
    public void concurrentChargesFromMultipleThreadsAreNotLost() throws Exception {
        RuntimeSettings.maxCumulativeArrayBytes = Long.MAX_VALUE;
        ArrayAllocationBudget.reset();

        int threadCount = 8;
        int chargesPerThread = 100_000;
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < chargesPerThread; j++) {
                    ArrayAllocationBudget.checkAndRecord(1, 1);
                }
            });
        }
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        // Each charge is now 17 bytes (the 16-byte per-array header plus the 1-byte
        // payload), not 1, now that the single-dimension charge includes the same
        // per-object header overhead as the MULTIANEWARRAY path.
        assertEquals((long) threadCount * chargesPerThread * 17, readAllocatedBytes());
    }

    /**
     * Finding 6 (overflow half): a charge whose byte count overflows a {@code long}
     * multiplication, or whose addition to the running total would overflow it, must
     * saturate at {@link Long#MAX_VALUE} and be refused -- never wrap around to a
     * negative total that would then read as comfortably under budget.
     */
    @Test
    public void overflowingChargeSaturatesInsteadOfWrappingNegative() throws Exception {
        RuntimeSettings.maxCumulativeArrayBytes = 1_000L;
        ArrayAllocationBudget.reset();

        // Long.MAX_VALUE * 2 overflows a naive long multiplication to a small negative
        // number; saturating arithmetic must instead treat this as "as large as
        // possible" and refuse it.
        assertThrows(TooManyResourcesException.class,
                () -> ArrayAllocationBudget.checkAndRecord(Long.MAX_VALUE, 2));
        assertTrue(readAllocatedBytes() >= 1_000L,
                "Overflow must saturate the running total upward, not wrap it negative");
    }

    private static byte[] transform(boolean enabled) throws Exception {
        if (!enabled) {
            RuntimeSettings.maxCumulativeArrayBytes = -1L;
        }
        byte[] original;
        try (InputStream stream = ArrayAllocationInstrumentationTest.class
                .getClassLoader().getResourceAsStream(FIXTURE_RESOURCE)) {
            assertTrue(stream != null, "Missing fixture class bytes");
            original = readAll(stream);
        }
        return new RuntimeInstrumentation().transformBytes(
                ArrayAllocationInstrumentationTest.class.getClassLoader(),
                FIXTURE_RESOURCE.substring(0, FIXTURE_RESOURCE.length() - ".class".length()),
                new ClassReader(original), false);
    }

    private static int countBudgetCalls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
        String owner = Type.getInternalName(ArrayAllocationBudget.class);
        int count = 0;
        for (org.objectweb.asm.tree.MethodNode method : node.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                 instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode methodInsn = (MethodInsnNode) instruction;
                    if (owner.equals(methodInsn.owner)
                            && "checkAndRecord".equals(methodInsn.name)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static int invoke(byte[] bytes, String methodName, int... arguments) throws Exception {
        String binaryName = FIXTURE;
        ClassLoader loader = new ClassLoader(ArrayAllocationInstrumentationTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (binaryName.equals(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
                return super.loadClass(name, resolve);
            }
        };
        Class<?> fixture = loader.loadClass(binaryName);
        Class<?>[] parameterTypes = new Class<?>[arguments.length];
        java.util.Arrays.fill(parameterTypes, int.class);
        Method method = fixture.getMethod(methodName, parameterTypes);
        return (Integer) method.invoke(null, toObjects(arguments));
    }

    private static Object[] toObjects(int[] arguments) {
        Object[] values = new Object[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            values[i] = arguments[i];
        }
        return values;
    }

    private static void assertRejected(byte[] bytes, String methodName, int... arguments) throws Exception {
        try {
            invoke(bytes, methodName, arguments);
            fail("Expected cumulative array budget rejection from " + methodName);
        } catch (InvocationTargetException exception) {
            assertTrue(exception.getCause() instanceof TooManyResourcesException,
                    "Unexpected rejection: " + exception.getCause());
        }
    }

    /**
     * Reads the private running total directly, via reflection, rather than inferring it
     * indirectly from whether a given charge was accepted or rejected -- the point of
     * several of the tests above is to pin down the total's exact value, not merely
     * whether it crossed some threshold.
     */
    private static long readAllocatedBytes() throws Exception {
        Field field = ArrayAllocationBudget.class.getDeclaredField("allocatedBytes");
        field.setAccessible(true);
        Object value = field.get(null);
        if (value instanceof java.util.concurrent.atomic.AtomicLong) {
            return ((java.util.concurrent.atomic.AtomicLong) value).get();
        }
        return ((Number) value).longValue();
    }

    private static byte[] readAll(InputStream stream) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

}
