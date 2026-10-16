package com.examples;

/** A public method that loops for ever and swallows everything its work throws. */
public class SwallowingSpinner {

    public static int rounds = 0;

    public int spin() {
        while (true) {
            try {
                rounds++;
                work();
            } catch (Throwable t) {
                // swallowed, like vuze's GlobalManagerImpl.Checker
            }
        }
    }

    private void work() {
        long sum = 0;
        for (int i = 0; i < 1_000_000_000; i++) {
            sum += i;
        }
        rounds += (int) (sum & 1);
    }
}
