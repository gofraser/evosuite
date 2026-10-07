package com.examples;

/** Loops that hit the iteration limit under catch handlers of different shapes. */
public class LoopLimitHandlers {

    public static int rounds = 0;

    /** No handler: the loop-limit exception reaches the caller. */
    public int propagate() {
        return count();
    }

    /** Recovers from a failure of its work with a fallback value. */
    public int fallback() {
        try {
            return countOther();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Swallows everything, and is interrupted in its second round; it clears the interrupt
     * like code that swallows an InterruptedException does.
     */
    public int interruptedSpin() {
        rounds = 0;
        while (true) {
            try {
                rounds++;
                if (rounds == 2) {
                    Thread.currentThread().interrupt();
                }
                count();
            } catch (Throwable t) {
                Thread.interrupted();
            }
        }
    }

    private int count() {
        int sum = 0;
        for (int i = 0; i < 1_000_000_000; i++) {
            sum += i & 1;
        }
        return sum;
    }

    private int countOther() {
        int sum = 0;
        for (int i = 0; i < 1_000_000_000; i++) {
            sum += i & 2;
        }
        return sum;
    }
}
