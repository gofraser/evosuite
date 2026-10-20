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
package org.evosuite.llm.search;

import org.evosuite.llm.LlmBudgetExceededException;
import org.evosuite.llm.LlmCallFailedException;
import org.evosuite.llm.response.TargetedResponse;
import org.evosuite.llm.search.operator.OperatorAttemptLog;
import org.evosuite.llm.search.operator.OperatorTarget;
import org.evosuite.testcase.TestChromosome;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One prompt attempt registered with a {@link RepeatedInjectionMemory}, owned by whoever holds the
 * ticket until it is settled exactly once: {@link #close} records the goals the attempt gained,
 * {@link #release} lets it go without judgement because no response was delivered. Later calls
 * are no-ops, so every exit path of a caller may settle the ticket without checking whether an
 * earlier one already did.
 *
 * <p>Whether a failed call counts as "delivered" is decided here and nowhere else: a budget
 * refusal or a failed call ({@link TargetedResponse.Status#NO_BUDGET}/{@link
 * TargetedResponse.Status#FAILED}, {@link LlmBudgetExceededException}, {@link
 * LlmCallFailedException}) never produced an outcome and is released; anything else that yields
 * no usable test is a delivered response without a gain.
 *
 * <p>Operator attempts additionally own a row in the {@link OperatorAttemptLog}, opened by {@link
 * #openLogRow}; {@link #evaluated} and {@link #admitted} forward to that row and do nothing for
 * any other ticket.
 *
 * <p>{@link #untracked()} stands in when there is no memory or nothing to track: its id is
 * {@code ""} and every method is a no-op.
 */
public final class AttemptTicket {

    /** Where a ticket is in its life. */
    public enum State {
        /** Registered and still owed an outcome (in flight if registered so). */
        OPEN,
        /** Closed with a judged outcome; {@link #getGainedGoals()} holds the gain. */
        CLOSED,
        /** Let go without an outcome (no response was delivered). */
        RELEASED
    }

    private static final int OPEN = 0;
    private static final int CLOSED = 1;
    private static final int RELEASED = 2;

    private static final AttemptTicket UNTRACKED = new AttemptTicket(null, "");

    private final RepeatedInjectionMemory memory;
    private final String id;
    private final AtomicInteger state = new AtomicInteger(OPEN);
    private volatile int gainedGoals = -1;
    private volatile boolean logRow;

    private AttemptTicket(RepeatedInjectionMemory memory, String id) {
        this.memory = memory;
        this.id = id;
    }

    /**
     * Registers an attempt for {@code targets} with {@code memory} and returns its ticket; the
     * {@linkplain #untracked() untracked} ticket when {@code memory} is null or no target has a
     * key.
     *
     * @param memory the shared memory, may be null
     * @param targets the targets the prompt covers
     * @param inFlight whether the attempt counts as in flight until it is settled (see {@link
     *                 RepeatedInjectionMemory#registerAttempt})
     */
    public static AttemptTicket open(RepeatedInjectionMemory memory, List<RepeatedInjectionTarget> targets,
                                     boolean inFlight) {
        if (memory == null) {
            return UNTRACKED;
        }
        RepeatedInjectionMemory.PromptAttemptRegistration registration = memory.registerAttempt(targets, inFlight);
        return registration == null || registration.isEmpty() ? UNTRACKED : new AttemptTicket(memory, registration.getAttemptId());
    }

    /** The ticket of an attempt nothing tracks: id {@code ""}, every method a no-op. */
    public static AttemptTicket untracked() {
        return UNTRACKED;
    }

    /** Whether a {@code status} without a usable test means no response was delivered. */
    public static boolean isUndelivered(TargetedResponse.Status status) {
        return status == TargetedResponse.Status.NO_BUDGET || status == TargetedResponse.Status.FAILED;
    }

    /** Whether a call that threw {@code failure} delivered no response. */
    public static boolean isUndelivered(Throwable failure) {
        return failure instanceof LlmBudgetExceededException || failure instanceof LlmCallFailedException;
    }

    /** The memory's attempt id, {@code ""} for the untracked ticket. */
    public String getId() {
        return id;
    }

    /** Whether this ticket stands for an attempt registered with a memory. */
    public boolean isTracked() {
        return memory != null;
    }

    public State getState() {
        switch (state.get()) {
            case CLOSED:
                return State.CLOSED;
            case RELEASED:
                return State.RELEASED;
            default:
                return State.OPEN;
        }
    }

    /** Whether this tracked ticket is still owed an outcome. */
    public boolean isOpen() {
        return isTracked() && state.get() == OPEN;
    }

    /** The gain {@link #close} recorded, {@code -1} unless closed. */
    public int getGainedGoals() {
        return gainedGoals;
    }

    /** Records that the attempt newly covered {@code gained} goals; first settlement only. */
    public void close(int gained) {
        if (memory != null && state.compareAndSet(OPEN, CLOSED)) {
            gainedGoals = gained;
            memory.recordAttemptOutcome(id, gained);
        }
    }

    /** Lets the attempt go without judgement, because no response was delivered; first settlement only. */
    public void release() {
        if (memory != null && state.compareAndSet(OPEN, RELEASED)) {
            memory.releaseUndeliveredAttempt(id);
        }
    }

    /** Settles an attempt that yielded no usable test: released if undelivered, else closed with no gain. */
    public void settleWithoutGain(boolean delivered) {
        if (delivered) {
            close(0);
        } else {
            release();
        }
    }

    /** {@link #settleWithoutGain} for a response with {@code status}. */
    public void settleWithoutGain(TargetedResponse.Status status) {
        settleWithoutGain(!isUndelivered(status));
    }

    /** {@link #settleWithoutGain} for a call that threw {@code failure}. */
    public void settleWithoutGain(Throwable failure) {
        settleWithoutGain(!isUndelivered(failure));
    }

    /** Opens this attempt's row in the {@link OperatorAttemptLog}; no-op for the untracked ticket. */
    public void openLogRow(int generation, OperatorTarget target, String mode, int batchSize) {
        if (memory == null) {
            return;
        }
        logRow = true;
        OperatorAttemptLog.getInstance().open(id, generation, target, mode, batchSize);
    }

    /** Whether {@link #openLogRow} opened an attempt-log row for this ticket. */
    public boolean hasLogRow() {
        return logRow;
    }

    /** Records the evaluated distance to the target goal on this attempt's log row, if it has one. */
    public void evaluated(double distanceAfter) {
        if (logRow) {
            OperatorAttemptLog.getInstance().recordEvaluated(id, distanceAfter, distanceAfter == 0.0);
        }
    }

    /** Records that {@code offspring} entered the population of {@code generation}, if this ticket has a log row. */
    public void admitted(TestChromosome offspring, int generation) {
        if (logRow) {
            OperatorAttemptLog.getInstance().recordAdmitted(id, offspring, generation);
        }
    }

    @Override
    public String toString() {
        return isTracked() ? id + ":" + getState() : "untracked";
    }
}
