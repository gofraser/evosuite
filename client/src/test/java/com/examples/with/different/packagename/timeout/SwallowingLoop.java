package com.examples.with.different.packagename.timeout;

import java.util.concurrent.Callable;

/**
 * A class under test whose loop swallows every Throwable, like the timer loop of vuze's
 * TRTrackerServerImpl: the only way a kill switch can end it is by having the instrumented
 * catch block rethrow TimeoutExceeded.
 */
public class SwallowingLoop {

    public static int rounds;

    public static int run(Callable<Void> body, int maxRounds) {
        rounds = 0;
        while (rounds < maxRounds) {
            try {
                body.call();
            } catch (Throwable t) {
                // swallow and keep going
            }
            rounds++;
        }
        return rounds;
    }
}
