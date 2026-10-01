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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks how many bytes a single test execution has allocated across every
 * NEWARRAY/ANEWARRAY/MULTIANEWARRAY site in the classes under analysis, and refuses
 * further allocation once the running total crosses
 * {@link RuntimeSettings#maxCumulativeArrayBytes}.
 *
 * <p>{@code org.evosuite.instrumentation.ArrayAllocationLimitMethodAdapter} already
 * rejects any single allocation over {@code array_limit}, but a loop that allocates many
 * individually-legal arrays -- e.g. one chunk per iteration in a chunked-array growth
 * method, each well under the per-call limit -- is invisible to that check and can still
 * exhaust the heap before any other safety net (the loop-iteration cap, the -mem
 * watchdog) gets a chance to fire. Counting the cumulative size closes that gap.
 *
 * <p>Counted in bytes rather than elements: an element of a {@code long[]} or
 * {@code double[]} is 8 times the footprint of an element of a {@code byte[]}, so a
 * budget expressed in elements let wide-element arrays allocate up to 8x the memory of
 * narrow ones before tripping. The caller supplies the element width (in bytes) of the
 * array being allocated, and this class does the multiplication itself.
 *
 * <p>Concurrency: {@link #checkAndRecord(long, int)} and
 * {@link #checkAndRecord(int[], int)} are a single atomic check-and-charge (a
 * compare-and-swap loop on {@link #allocatedBytes}), not a read-modify-write on a plain
 * {@code volatile}, so concurrent charges from multiple SUT threads are all counted
 * rather than racing to overwrite one another.
 *
 * <p>Reset lifecycle: {@link Runtime#resetRuntime()} calls {@link #reset()} once per
 * test, on the thread about to run that test. Unlike {@link LoopCounter}, this class does
 * not quarantine a leftover SUT thread that keeps running after its own test has been
 * abandoned: such a thread keeps charging whichever total is current, including a later
 * test's. That is deliberate -- the abandoned thread's allocations are still real heap
 * pressure on this process, so still counting them keeps the budget meaningful. The cost
 * is an occasional false rejection of an innocent later test, not a true cap bypass.
 */
public class ArrayAllocationBudget {

    /**
     * Pointer width used to charge a reference-typed array slot (an element of an
     * {@code Object[]}, or any intermediate level of a multi-dimensional array other
     * than its innermost, populated dimension): 8 bytes, matching
     * {@code ArrayAllocationMethodAdapter.REFERENCE_ELEMENT_WIDTH_BYTES}, which charges
     * the single-dimension {@code ANEWARRAY} case the same way.
     */
    private static final int REFERENCE_ELEMENT_WIDTH_BYTES = 8;

    /**
     * Conservative, deliberately approximate per-object overhead charged for every array
     * object a multi-dimensional allocation creates, in addition to its payload.
     *
     * <p>HotSpot's array header on a 64-bit JVM with compressed oops (the default for
     * heaps under ~32 GB, i.e. the common case) is a mark word (8 bytes) plus a
     * compressed class pointer (4 bytes) plus a length field (4 bytes), rounded up to
     * 8-byte alignment: 16 bytes. Without compressed oops it is larger (up to 24 bytes).
     * 16 is used as a representative, order-of-magnitude figure, not an attempt to
     * replicate any particular JVM's exact layout: its job is only to make sure every
     * array object a multi-dimensional allocation creates -- including a zero-length
     * leaf like the inner arrays of {@code new byte[1000][0]} -- contributes a non-zero
     * charge, so that shape can no longer bypass the cumulative budget entirely.
     */
    private static final int OBJECT_HEADER_OVERHEAD_BYTES = 16;

    /**
     * Running total of bytes allocated so far by whichever test execution(s) are
     * currently using this process. See the class-level javadoc for why this is a
     * single shared counter rather than being thread-confined, and for the reset
     * lifecycle of a leftover SUT thread.
     */
    private static final AtomicLong allocatedBytes = new AtomicLong(0L);

    private ArrayAllocationBudget() {
    }

    /**
     * Reset the running total for the next test.
     */
    public static void reset() {
        allocatedBytes.set(0L);
    }

    /**
     * Record an allocation of {@code elements} array slots, each {@code elementWidthBytes}
     * bytes wide, and reject it if the cumulative total for this test now exceeds the
     * configured budget.
     *
     * <p>Called from bytecode instrumented into the classes under analysis, right after
     * a NEWARRAY/ANEWARRAY whose own size already passed the per-call {@code array_limit}
     * check.
     *
     * <p>{@code elements} can be negative: it is read directly off the stack before the
     * real array instruction executes, and that instruction has not validated it yet. A
     * negative count never allocates anything -- the JVM throws
     * {@code NegativeArraySizeException} from the real NEWARRAY/ANEWARRAY right after
     * this call returns -- so it must not be charged either way here: charging it as a
     * negative byte count would credit the budget for an allocation that never happened
     * (letting a later, legitimate allocation through that should have been refused),
     * and rejecting it from here instead would substitute
     * {@link TooManyResourcesException} for the {@code NegativeArraySizeException} the
     * class under test may itself be catching.
     *
     * <p>Charges {@link #OBJECT_HEADER_OVERHEAD_BYTES} in addition to the element
     * payload, for the same reason {@link #checkAndRecord(int[], int)} charges it at
     * every level: the array being allocated is itself a real heap object even when
     * {@code elements} is zero. Without this, every empty array -- primitive or
     * reference -- would cost nothing at all, so a CUT retaining many empty arrays
     * could allocate without ever advancing the cumulative guard.
     *
     * @param elements          number of elements the allocation about to happen will occupy
     * @param elementWidthBytes size in bytes of a single element (e.g. 8 for {@code long}/
     *                          {@code double}, 1 for {@code byte}/{@code boolean}, or a
     *                          pointer width for a reference-typed array)
     * @throws TooManyResourcesException if the cumulative budget has been exceeded
     */
    public static void checkAndRecord(long elements, int elementWidthBytes) {
        if (RuntimeSettings.maxCumulativeArrayBytes < 0) {
            return; // disabled
        }
        if (elements < 0) {
            // Let the real NEWARRAY/ANEWARRAY throw NegativeArraySizeException; see the
            // javadoc above for why this must neither charge nor reject.
            return;
        }
        long payloadBytes = saturatingMultiply(elements, (long) elementWidthBytes);
        long bytes = saturatingAdd(OBJECT_HEADER_OVERHEAD_BYTES, payloadBytes);
        chargeAndCheck(bytes);
    }

    /**
     * Record a multi-dimensional allocation and reject it if the cumulative total for
     * this test now exceeds the configured budget.
     *
     * <p>Called from bytecode instrumented right after a MULTIANEWARRAY whose own
     * dimensions already passed the per-call {@code array_limit} check. A MULTIANEWARRAY
     * with {@code dimensionSizes.length == n} allocates a full, non-jagged structure of
     * {@code n} levels: one array object at level 0 with {@code dimensionSizes[0]} slots,
     * {@code dimensionSizes[0]} array objects at level 1 each with
     * {@code dimensionSizes[1]} slots, {@code dimensionSizes[0] * dimensionSizes[1]}
     * array objects at level 2, and so on down to the leaf level, whose
     * {@code dimensionSizes[0] * ... * dimensionSizes[n - 2]} array objects hold
     * {@code dimensionSizes[n - 1]} elements of width {@code leafElementWidthBytes}
     * each. Charging only the product of all dimensions times the leaf width -- as a
     * single-dimension-shaped charge would -- ignores every reference array at every
     * level but the leaf, and charges zero for a populated outer dimension whose leaves
     * are themselves zero-length (e.g. {@code new byte[1000][0]}: the outer array and
     * all 1000 (empty) inner arrays are real, charged objects, even though the product
     * of dimensions is zero). This charges every level: its reference slots (or, at the
     * leaf, its elements) plus {@link #OBJECT_HEADER_OVERHEAD_BYTES} per array object.
     *
     * <p>Like {@link #checkAndRecord(long, int)}, a negative size at any level is never
     * charged -- the real MULTIANEWARRAY throws {@code NegativeArraySizeException} for
     * the same reason.
     *
     * @param dimensionSizes       the sizes given to MULTIANEWARRAY, outermost first
     * @param leafElementWidthBytes width in bytes of one element at the innermost
     *                              populated dimension (a primitive width, or a pointer
     *                              width if that dimension itself holds references)
     * @throws TooManyResourcesException if the cumulative budget has been exceeded
     */
    public static void checkAndRecord(int[] dimensionSizes, int leafElementWidthBytes) {
        if (RuntimeSettings.maxCumulativeArrayBytes < 0) {
            return; // disabled
        }
        for (int size : dimensionSizes) {
            if (size < 0) {
                // Let the real MULTIANEWARRAY throw NegativeArraySizeException.
                return;
            }
        }

        long bytes = 0L;
        long objectsAtThisLevel = 1L;
        int leafLevel = dimensionSizes.length - 1;
        for (int level = 0; level <= leafLevel; level++) {
            int width = (level == leafLevel) ? leafElementWidthBytes : REFERENCE_ELEMENT_WIDTH_BYTES;
            long slotBytes = saturatingMultiply((long) dimensionSizes[level], (long) width);
            long perObjectBytes = saturatingAdd(OBJECT_HEADER_OVERHEAD_BYTES, slotBytes);
            long levelBytes = saturatingMultiply(objectsAtThisLevel, perObjectBytes);
            bytes = saturatingAdd(bytes, levelBytes);
            objectsAtThisLevel = saturatingMultiply(objectsAtThisLevel, (long) dimensionSizes[level]);
        }
        chargeAndCheck(bytes);
    }

    /**
     * Atomically adds {@code bytes} (never negative -- both callers have already ruled
     * that out) to {@link #allocatedBytes}, saturating at {@link Long#MAX_VALUE} rather
     * than wrapping, and rejects once the new total exceeds the configured budget.
     */
    private static void chargeAndCheck(long bytes) {
        long total = addSaturating(bytes);
        if (total > RuntimeSettings.maxCumulativeArrayBytes) {
            throw new TooManyResourcesException("Cumulative array allocation for this test exceeds the "
                    + "configured cumulative_array_limit_bytes of " + RuntimeSettings.maxCumulativeArrayBytes
                    + " bytes (" + total + " requested so far)");
        }
    }

    /**
     * Compare-and-swap loop implementing a saturating {@code allocatedBytes += delta},
     * so that concurrent charges from multiple SUT threads are all counted (finding 6:
     * a plain {@code volatile long +=} is a read-modify-write and silently loses
     * concurrent updates) and a running total near {@link Long#MAX_VALUE} saturates
     * instead of wrapping around to a negative number that would then read as under
     * budget.
     */
    private static long addSaturating(long delta) {
        while (true) {
            long current = allocatedBytes.get();
            long next = saturatingAdd(current, delta);
            if (allocatedBytes.compareAndSet(current, next)) {
                return next;
            }
        }
    }

    private static long saturatingAdd(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatingMultiply(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
