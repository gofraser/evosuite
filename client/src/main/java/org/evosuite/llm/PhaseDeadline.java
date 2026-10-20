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
package org.evosuite.llm;

import java.util.concurrent.TimeUnit;

/**
 * Arithmetic on the deadlines that bound LLM work: absolute
 * {@link System#nanoTime()} values, with {@link #NONE} for no deadline.
 * Computing a deadline saturates at {@link #NONE} rather than overflowing.
 */
public final class PhaseDeadline {

    /** The deadline of work that has none. */
    public static final long NONE = Long.MAX_VALUE;

    private PhaseDeadline() {
    }

    /**
     * The deadline {@code durationNanos} after {@code fromNanos}; a negative
     * duration counts as zero.
     */
    public static long after(long fromNanos, long durationNanos) {
        long duration = Math.max(0L, durationNanos);
        return fromNanos > NONE - duration ? NONE : fromNanos + duration;
    }

    /** The deadline {@code durationNanos} from now; a negative duration counts as zero. */
    public static long fromNow(long durationNanos) {
        return after(System.nanoTime(), durationNanos);
    }

    /** Whether {@code deadlineNanos} has passed; never for {@link #NONE}. */
    public static boolean isExpired(long deadlineNanos) {
        return deadlineNanos != NONE && System.nanoTime() >= deadlineNanos;
    }

    /**
     * Nanoseconds until {@code deadlineNanos}, zero or negative once it has
     * passed, and {@link Long#MAX_VALUE} for {@link #NONE}.
     */
    public static long remainingNanos(long deadlineNanos) {
        if (deadlineNanos == NONE) {
            return Long.MAX_VALUE;
        }
        return deadlineNanos - System.nanoTime();
    }

    /**
     * Whole milliseconds until {@code deadlineNanos}, zero once it has passed,
     * and {@link Long#MAX_VALUE} for {@link #NONE}.
     */
    public static long remainingMillis(long deadlineNanos) {
        if (deadlineNanos == NONE) {
            return Long.MAX_VALUE;
        }
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, deadlineNanos - System.nanoTime()));
    }
}
