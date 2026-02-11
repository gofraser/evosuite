/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.runtime.mock.java.util;

import org.evosuite.runtime.mock.OverrideMock;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Mock implementation of java.util.GregorianCalendar.
 */
public class MockGregorianCalendar extends GregorianCalendar implements OverrideMock {

    private static final long serialVersionUID = 4768096296715665262L;

    /**
     * Constructs a default MockGregorianCalendar using the current time
     * in the default time zone with the default locale.
     */
    public MockGregorianCalendar() {
        this.setTimeInMillis(org.evosuite.runtime.System.currentTimeMillis());
    }

    /**
     * Constructs a MockGregorianCalendar with the given date set
     * in the default time zone with the default locale.
     *
     * @param year       the value used to set the YEAR calendar field in the calendar.
     * @param month      the value used to set the MONTH calendar field in the calendar.
     *                   Month value is 0-based. e.g., 0 for January.
     * @param dayOfMonth the value used to set the DAY_OF_MONTH calendar field in the calendar.
     */
    public MockGregorianCalendar(int year, int month, int dayOfMonth) {
        super(year, month, dayOfMonth);
    }

    /**
     * Constructs a MockGregorianCalendar with the given date and time set
     * in the default time zone with the default locale.
     *
     * @param year       the value used to set the YEAR calendar field in the calendar.
     * @param month      the value used to set the MONTH calendar field in the calendar.
     *                   Month value is 0-based. e.g., 0 for January.
     * @param dayOfMonth the value used to set the DAY_OF_MONTH calendar field in the calendar.
     * @param hourOfDay  the value used to set the HOUR_OF_DAY calendar field in the calendar.
     * @param minute     the value used to set the MINUTE calendar field in the calendar.
     */
    public MockGregorianCalendar(int year, int month, int dayOfMonth, int hourOfDay, int minute) {
        super(year, month, dayOfMonth, hourOfDay, minute);
    }

    /**
     * Constructs a MockGregorianCalendar with the given date and time set
     * in the default time zone with the default locale.
     *
     * @param year       the value used to set the YEAR calendar field in the calendar.
     * @param month      the value used to set the MONTH calendar field in the calendar.
     *                   Month value is 0-based. e.g., 0 for January.
     * @param dayOfMonth the value used to set the DAY_OF_MONTH calendar field in the calendar.
     * @param hourOfDay  the value used to set the HOUR_OF_DAY calendar field in the calendar.
     * @param minute     the value used to set the MINUTE calendar field in the calendar.
     * @param second     the value used to set the SECOND calendar field in the calendar.
     */
    public MockGregorianCalendar(int year, int month, int dayOfMonth, int hourOfDay, int minute, int second) {
        super(year, month, dayOfMonth, hourOfDay, minute, second);
    }

    /**
     * Constructs a MockGregorianCalendar based on the current time
     * in the default time zone with the given locale.
     *
     * @param locale the given locale.
     */
    public MockGregorianCalendar(Locale locale) {
        super(locale);
        this.setTimeInMillis(org.evosuite.runtime.System.currentTimeMillis());
    }

    /**
     * Constructs a MockGregorianCalendar based on the current time
     * in the given time zone with the default locale.
     *
     * @param zone the given time zone.
     */
    public MockGregorianCalendar(TimeZone zone) {
        super(zone);
        this.setTimeInMillis(org.evosuite.runtime.System.currentTimeMillis());
    }

    /**
     * Constructs a MockGregorianCalendar based on the current time
     * in the given time zone with the given locale.
     *
     * @param zone   the given time zone.
     * @param locale the given locale.
     */
    public MockGregorianCalendar(TimeZone zone, Locale locale) {
        super(zone, locale);
        this.setTimeInMillis(org.evosuite.runtime.System.currentTimeMillis());
    }

    // TODO: This code in Calendar seems to cause access to time
    //       but I don't understand how.
    //    public long getTimeInMillis() {
    //        if (!isTimeSet) {
    //            updateTime();
    //        }
    //        return time;
    //    }
    @Override
    public long getTimeInMillis() {
        return time;
    }

    /**
     * Creates a {@link GregorianCalendar} from a {@link ZonedDateTime}.
     *
     * @param zdt the zoned date-time to convert
     * @return the Gregorian calendar
     * @throws IllegalArgumentException if the date-time is out of range
     */
    public static GregorianCalendar from(ZonedDateTime zdt) {
        GregorianCalendar cal = new MockGregorianCalendar(MockTimeZone.getTimeZone(zdt.getZone()));
        cal.setGregorianChange(new MockDate(Long.MIN_VALUE));
        cal.setFirstDayOfWeek(MONDAY);
        cal.setMinimalDaysInFirstWeek(4);
        try {
            cal.setTimeInMillis(Math.addExact(Math.multiplyExact(zdt.toEpochSecond(), 1000),
                    zdt.get(ChronoField.MILLI_OF_SECOND)));
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(ex);
        }
        return cal;
    }
}
