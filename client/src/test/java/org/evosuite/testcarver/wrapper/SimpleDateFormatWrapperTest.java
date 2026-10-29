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
package org.evosuite.testcarver.wrapper;

import org.evosuite.testcarver.capture.CaptureLog;
import org.evosuite.testcarver.capture.Capturer;
import org.evosuite.testcarver.wrapper.java.text.SimpleDateFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SimpleDateFormatWrapperTest {

    @BeforeEach
    public void setUp() {
        Capturer.clear();
        Capturer.startCapture();
    }

    @AfterEach
    public void tearDown() {
        Capturer.clear();
    }

    private int indexOfRecord(CaptureLog log, String methodName) {
        final int index = log.methodNames.indexOf(methodName);
        Assertions.assertTrue(index >= 0, methodName + " must be captured");
        return index;
    }

    @Test
    public void testApplyPatternRecordsThePattern() {
        final SimpleDateFormat format = new SimpleDateFormat("yyyy");
        format.applyPattern("MM");
        final CaptureLog log = Capturer.stopCapture();

        // the pattern is recorded as an argument (strings are replaced by object ids in the log)
        Assertions.assertEquals(1, log.params.get(indexOfRecord(log, "applyPattern")).length);
    }

    @Test
    public void testApplyLocalizedPatternRecordsThePattern() {
        final SimpleDateFormat format = new SimpleDateFormat("yyyy");
        format.applyLocalizedPattern("MM");
        final CaptureLog log = Capturer.stopCapture();

        Assertions.assertEquals(1, log.params.get(indexOfRecord(log, "applyLocalizedPattern")).length);
    }
}
