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
package org.evosuite.runtime.mock.java.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

public class MockGregorianCalendarTest {

    private static final long FEB_14_2014 = 1392409281320L;

    private static final long JAN_1_2014 = 1388534400000L;

    @Test
    public void testGetTimeInMillisAfterSetReflectsTheNewFields() {
        Calendar calendar = MockCalendar.getInstance(TimeZone.getTimeZone("GMT"), Locale.ROOT);
        calendar.setTimeInMillis(FEB_14_2014);

        calendar.set(2014, Calendar.JANUARY, 1, 0, 0, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        Assertions.assertEquals(JAN_1_2014, calendar.getTimeInMillis());
        Assertions.assertEquals(JAN_1_2014, calendar.getTime().getTime());
    }

    @Test
    public void testGetTimeInMillisWithoutChangeKeepsTheMockedTime() {
        Calendar calendar = MockCalendar.getInstance(TimeZone.getTimeZone("GMT"), Locale.ROOT);
        calendar.setTimeInMillis(FEB_14_2014);

        Assertions.assertEquals(FEB_14_2014, calendar.getTimeInMillis());
    }
}
