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
package org.evosuite.testcarver.capture;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

public class StaticWriteCaptureTest {

    public static int counter;

    public void touch() {
        // target of the instance call captured after the static write
    }

    private Level originalLevel;

    @BeforeEach
    public void setUp() {
        Capturer.clear();
        originalLevel = capturerLogger().getLevel();
    }

    @AfterEach
    public void tearDown() {
        capturerLogger().setLevel(originalLevel);
        Capturer.clear();
    }

    private static Logger capturerLogger() {
        return (Logger) LoggerFactory.getLogger(Capturer.class);
    }

    private void checkInstanceCallFollowsStaticWrite() {
        final StaticWriteCaptureTest instance = new StaticWriteCaptureTest();
        final String className = StaticWriteCaptureTest.class.getName().replace('.', '/');

        Capturer.startCapture();
        Capturer.capture(0, instance, CaptureLog.OBSERVED_INIT, "()V", CaptureLog.NO_ARGS);
        Capturer.enable(0, instance, CaptureLog.RETURN_TYPE_VOID);
        FieldRegistry.register(instance);

        FieldRegistry.notifyModification(null, 1, className, "counter", "I");
        Assertions.assertTrue(Capturer.isCapturing(), "a static write must not disable capture");

        Capturer.capture(2, instance, "touch", "()V", CaptureLog.NO_ARGS);
        Capturer.enable(2, instance, CaptureLog.RETURN_TYPE_VOID);

        final CaptureLog log = Capturer.stopCapture();
        Assertions.assertTrue(log.methodNames.contains(CaptureLog.PUTSTATIC));
        Assertions.assertTrue(log.methodNames.contains("touch"),
                "the instance call after the static write must be captured");
    }

    @Test
    public void testInstanceCallAfterStaticWrite() {
        capturerLogger().setLevel(Level.INFO);
        checkInstanceCallFollowsStaticWrite();
    }

    @Test
    public void testInstanceCallAfterStaticWriteWithDebugLogging() {
        capturerLogger().setLevel(Level.DEBUG);
        checkInstanceCallFollowsStaticWrite();
    }

    @Test
    public void testCaptureIsRestoredAfterFailingLog() {
        Capturer.startCapture();
        // a call without a receiver cannot be logged
        Capturer.capture(0, null, "touch", "()V", CaptureLog.NO_ARGS);
        Assertions.assertTrue(Capturer.isCapturing());
        Capturer.stopCapture();
    }
}
