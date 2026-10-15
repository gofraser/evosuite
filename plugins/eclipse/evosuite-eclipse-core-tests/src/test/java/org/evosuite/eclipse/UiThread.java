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
package org.evosuite.eclipse;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.widgets.Display;

/** Runs test code in the UI thread, which the harness keeps apart from the tests' own thread. */
public final class UiThread {

    private UiThread() {
    }

    public static <T> T call(Callable<T> code) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicReference<Error> error = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            try {
                result.set(code.call());
            } catch (Exception e) {
                failure.set(e);
            } catch (Error e) {
                error.set(e);
            }
        });
        if (error.get() != null) {
            throw error.get();
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    public interface Code {
        void run() throws Exception;
    }

    public static void run(Code code) throws Exception {
        call(() -> {
            code.run();
            return null;
        });
    }

    /** Lets the display handle the events that have queued up. */
    public static void drain() {
        Display display = Display.getCurrent();
        while (display.readAndDispatch()) {
            // keep going
        }
    }

    /** Lets the display handle events for a while, so that windows get painted. */
    public static void drain(long millis) {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            drain();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
