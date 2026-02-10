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
package org.evosuite.runtime.mock.java.time;

import org.evosuite.runtime.mock.StaticReplacementMock;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;

/**
 * Mock class for {@link java.time.LocalDateTime}.
 */
public class MockLocalDateTime implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return LocalDateTime.class.getName();
    }

    /**
     * Obtains the current date-time from the system clock in the default time-zone.
     *
     * @return the current date-time using the system clock and default time-zone.
     */
    public static LocalDateTime now() {
        return now(MockClock.systemDefaultZone());
    }

    /**
     * Obtains the current date-time from the system clock in the specified time-zone.
     *
     * @param zone the zone ID to use.
     * @return the current date-time using the system clock.
     */
    public static LocalDateTime now(ZoneId zone) {
        return now(MockClock.system(zone));
    }

    /**
     * Obtains the current date-time from the specified clock.
     *
     * @param clock the clock to use.
     * @return the current date-time.
     */
    public static LocalDateTime now(Clock clock) {
        return LocalDateTime.now(clock);
    }

    /**
     * Obtains an instance of {@code LocalDateTime} from year, month,
     * day, hour and minute, setting the second and nanosecond to zero.
     *
     * @param year the year to represent.
     * @param month the month-of-year to represent.
     * @param dayOfMonth the day-of-month to represent.
     * @param hour the hour-of-day to represent.
     * @param minute the minute-of-hour to represent.
     * @return the local date-time.
     */
    public static LocalDateTime of(int year, Month month, int dayOfMonth, int hour, int minute) {
        return LocalDateTime.of(year, month, dayOfMonth, hour, minute);
    }

    /**
     * Obtains an instance of {@code LocalDateTime} from year, month,
     * day, hour, minute and second, setting the nanosecond to zero.
     *
     * @param year the year to represent.
     * @param month the month-of-year to represent.
     * @param dayOfMonth the day-of-month to represent.
     * @param hour the hour-of-day to represent.
     * @param minute the minute-of-hour to represent.
     * @param second the second-of-minute to represent.
     * @return the local date-time.
     */
    public static LocalDateTime of(int year, Month month, int dayOfMonth, int hour, int minute, int second) {
        return LocalDateTime.of(year, month, dayOfMonth, hour, minute, second);
    }

    /**
     * Obtains an instance of {@code LocalDateTime} from year, month,
     * day, hour, minute, second and nanosecond.
     *
     * @param year the year to represent.
     * @param month the month-of-year to represent.
     * @param dayOfMonth the day-of-month to represent.
     * @param hour the hour-of-day to represent.
     * @param minute the minute-of-hour to represent.
     * @param second the second-of-minute to represent.
     * @param nanoOfSecond the nanosecond-of-second to represent.
     * @return the local date-time.
     */
    public static LocalDateTime of(int year, Month month, int dayOfMonth,
                                   int hour, int minute, int second, int nanoOfSecond) {
        return LocalDateTime.of(year, month, dayOfMonth, hour, minute, second, nanoOfSecond);
    }

    /**
     * Obtains an instance of {@code LocalDateTime} from year, month,
     * day, hour and minute, setting the second and nanosecond to zero.
     *
     * @param year the year to represent.
     * @param month the month-of-year to represent.
     * @param dayOfMonth the day-of-month to represent.
     * @param hour the hour-of-day to represent.
     * @param minute the minute-of-hour to represent.
     * @return the local date-time.
     */
    public static LocalDateTime of(int year, int month, int dayOfMonth, int hour, int minute) {
        return LocalDateTime.of(year, month, dayOfMonth, hour, minute);
    }

    /**
     * Obtains an instance of {@code LocalDateTime} from year, month,
     * day, hour, minute and second, setting the nanosecond to zero.
     *
     * @param year the year to represent.
     * @param month the month-of-year to represent.
     * @param dayOfMonth the day-of-month to represent.
     * @param hour the hour-of-day to represent.
     * @param minute the minute-of-hour to represent.
     * @param second the second-of-minute to represent.
     * @return the local date-time.
     */
    public static LocalDateTime of(int year, int month, int dayOfMonth, int hour, int minute, int second) {
        return LocalDateTime.of(year, month, dayOfMonth, hour, minute, second);
    }

    /**
     * Obtains an instance of {@code LocalDateTime} from year, month,
     * day, hour, minute, second and nanosecond.
     *
     * @param year the year to represent.
     * @param month the month-of-year to represent.
     * @param dayOfMonth the day-of-month to represent.
     * @param hour the hour-of-day to represent.
     * @param minute the minute-of-hour to represent.
     * @param second the second-of-minute to represent.
     * @param nanoOfSecond the nanosecond-of-second to represent.
     * @return the local date-time.
     */
    public static LocalDateTime of(int year, int month, int dayOfMonth,
                                   int hour, int minute, int second, int nanoOfSecond) {
        return LocalDateTime.of(year, month, dayOfMonth, hour, minute, second, nanoOfSecond);
    }

    /**
     * Obtains an instance of {@code LocalDateTime} from a date and time.
     *
     * @param date the local date.
     * @param time the local time.
     * @return the local date-time.
     */
    public static LocalDateTime of(LocalDate date, LocalTime time) {
        return LocalDateTime.of(date, time);
    }

    /**
     * Obtains an instance of {@code LocalDateTime} from an {@code Instant} and zone ID.
     *
     * @param instant the instant to create the date-time from.
     * @param zone the time-zone.
     * @return the local date-time.
     */
    public static LocalDateTime ofInstant(Instant instant, ZoneId zone) {
        return LocalDateTime.ofInstant(instant, zone);
    }

    /**
     * Obtains an instance of {@code LocalDateTime} using seconds from the
     * epoch of 1970-01-01T00:00:00Z.
     *
     * @param epochSecond the number of seconds from the epoch of 1970-01-01T00:00:00Z.
     * @param nanoOfSecond the nanosecond within the second.
     * @param offset the zone offset.
     * @return the local date-time.
     */
    public static LocalDateTime ofEpochSecond(long epochSecond, int nanoOfSecond, ZoneOffset offset) {
        return LocalDateTime.ofEpochSecond(epochSecond, nanoOfSecond, offset);
    }

    /**
     * Obtains an instance of {@code LocalDateTime} from a temporal object.
     *
     * @param temporal the temporal object to convert.
     * @return the local date-time.
     */
    public static LocalDateTime from(TemporalAccessor temporal) {
        return LocalDateTime.from(temporal);
    }

    /**
     * Obtains an instance of {@code LocalDateTime} from a text string such as {@code 2007-12-03T10:15:30}.
     *
     * @param text the text to parse.
     * @return the parsed local date-time.
     */
    public static LocalDateTime parse(CharSequence text) {
        return LocalDateTime.parse(text);
    }

    /**
     * Obtains an instance of {@code LocalDateTime} from a text string using a specific formatter.
     *
     * @param text the text to parse.
     * @param formatter the formatter to use.
     * @return the parsed local date-time.
     */
    public static LocalDateTime parse(CharSequence text, DateTimeFormatter formatter) {
        return LocalDateTime.parse(text, formatter);
    }
}
