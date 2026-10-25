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
package org.evosuite;

import org.evosuite.runtime.mock.java.util.MockLocale;
import org.evosuite.runtime.mock.java.util.MockTimeZone;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.TimeZone;

/**
 * A system test runs the client in the JVM of the test, whose runtime resets the default time
 * zone and locale to the mocked values; the base class has to hand the originals back.
 */
public class SystemTestBaseRestoreTest {

    @Test
    public void defaultTimeZoneAndLocaleSurviveTheRuntimeReset() {
        TimeZone originalZone = TimeZone.getDefault();
        Locale originalLocale = Locale.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        Locale.setDefault(Locale.GERMANY);
        SystemTestBase base = new SystemTestBase();
        try {
            base.setDefaultPropertiesForTestCases();
            MockTimeZone.reset();
            MockLocale.reset();
            Assertions.assertEquals("GMT", TimeZone.getDefault().getID());

            base.resetStaticVariables();

            Assertions.assertEquals("Asia/Tokyo", TimeZone.getDefault().getID());
            Assertions.assertEquals(Locale.GERMANY, Locale.getDefault());
        } finally {
            TimeZone.setDefault(originalZone);
            Locale.setDefault(originalLocale);
        }
    }
}
