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
package org.evosuite.utils;

import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The code under test creates its loggers while the streams are muted, and a console handler
 * keeps the stream it was created with. What it writes afterwards must not pile up in memory.
 */
public class LoggingUtilsMuteTest {

    private static long usedHeap() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    @Test
    public void mutedOutputIsNotRetainedByAHandlerThatOutlivesTheMute() {
        PrintStream originalErr = System.err;
        ConsoleHandler handler;
        long before;
        long after;
        LoggingUtils.muteCurrentOutAndErrStream();
        try {
            // Created while muted, as java.util.logging's root handler is when the first
            // logger is used inside a test.
            handler = new ConsoleHandler();
            handler.setLevel(Level.ALL);
            before = usedHeap();
            StringBuilder message = new StringBuilder();
            for (int i = 0; i < 1024; i++) {
                message.append("an exception occurred in the code under test ");
            }
            LogRecord record = new LogRecord(Level.SEVERE, message.toString());
            // About 180 MB of text, which a buffering stream would still hold.
            for (int i = 0; i < 4000; i++) {
                handler.publish(record);
            }
            handler.flush();
            after = usedHeap();
        } finally {
            LoggingUtils.restorePreviousOutAndErrStream();
        }
        assertTrue(System.err == originalErr, "the streams must be restored");
        assertTrue(after - before < 40L * 1024 * 1024,
                "muted output was retained: " + (after - before) + " bytes");
        handler.close();
    }
}
