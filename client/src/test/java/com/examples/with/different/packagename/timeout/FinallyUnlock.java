package com.examples.with.different.packagename.timeout;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

/**
 * javac protects the first instruction of a finally handler with the handler itself
 * (exception range [handler, handler+1) -> handler), the shape of liferay's
 * CoalescedPipe.take(): lock; try { body } finally { unlock }.
 */
public class FinallyUnlock {

    private final ReentrantLock lock = new ReentrantLock();

    public void run(Callable<Void> body) throws Exception {
        lock.lock();
        try {
            body.call();
        } finally {
            lock.unlock();
        }
    }
}
