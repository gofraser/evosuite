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
package org.evosuite.runtime.mock.java.text;

import org.evosuite.runtime.mock.StaticReplacementMock;
import org.evosuite.runtime.mock.java.util.MockCalendar;
import java.text.DateFormat;
import java.util.Locale;

/**
 * Mock class for {@link java.text.DateFormat}.
 *
 * <p>This replacement ensures that {@link MockCalendar} is used instead of the default {@link java.util.Calendar}.
 */
public class MockDateFormat implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return DateFormat.class.getName();
    }

    /**
     * Gets the time formatter with the default formatting style for the default
     * {@link java.util.Locale.Category#FORMAT FORMAT} locale.
     *
     * @return a time formatter.
     */
    public static DateFormat getTimeInstance() {
        DateFormat format = DateFormat.getTimeInstance();
        format.setCalendar(MockCalendar.getInstance());
        return format;
    }

    /**
     * Gets the time formatter with the given formatting style for the default
     * {@link java.util.Locale.Category#FORMAT FORMAT} locale.
     *
     * @param style the given formatting style. For example,
     *     SHORT for "h:mm a" in the US locale.
     * @return a time formatter.
     */
    public static DateFormat getTimeInstance(int style) {
        DateFormat format = DateFormat.getTimeInstance(style);
        format.setCalendar(MockCalendar.getInstance());
        return format;
    }

    /**
     * Gets the time formatter with the given formatting style for the given locale.
     *
     * @param style the given formatting style. For example,
     *     SHORT for "h:mm a" in the US locale.
     * @param locale the given locale.
     * @return a time formatter.
     */
    public static DateFormat getTimeInstance(int style,
                                             Locale locale) {
        DateFormat format = DateFormat.getTimeInstance(style, locale);
        format.setCalendar(MockCalendar.getInstance());
        return format;
    }

    /**
     * Gets the date formatter with the default formatting style for the default
     * {@link java.util.Locale.Category#FORMAT FORMAT} locale.
     *
     * @return a date formatter.
     */
    public static DateFormat getDateInstance() {
        DateFormat format = DateFormat.getDateInstance();
        format.setCalendar(MockCalendar.getInstance());
        return format;
    }

    /**
     * Gets the date formatter with the given formatting style for the default
     * {@link java.util.Locale.Category#FORMAT FORMAT} locale.
     *
     * @param style the given formatting style. For example,
     *     SHORT for "M/d/yy" in the US locale.
     * @return a date formatter.
     */
    public static DateFormat getDateInstance(int style) {
        DateFormat format = DateFormat.getDateInstance(style);
        format.setCalendar(MockCalendar.getInstance());
        return format;
    }

    /**
     * Gets the date formatter with the given formatting style for the given locale.
     *
     * @param style the given formatting style. For example,
     *     SHORT for "M/d/yy" in the US locale.
     * @param locale the given locale.
     * @return a date formatter.
     */
    public static DateFormat getDateInstance(int style,
                                             Locale locale) {
        DateFormat format = DateFormat.getDateInstance(style, locale);
        format.setCalendar(MockCalendar.getInstance());
        return format;
    }

    /**
     * Gets the date/time formatter with the default formatting style for the default
     * {@link java.util.Locale.Category#FORMAT FORMAT} locale.
     *
     * @return a date/time formatter.
     */
    public static DateFormat getDateTimeInstance() {
        DateFormat format = DateFormat.getDateTimeInstance();
        format.setCalendar(MockCalendar.getInstance());
        return format;
    }

    /**
     * Gets the date/time formatter with the given date and time formatting styles
     * for the default {@link java.util.Locale.Category#FORMAT FORMAT} locale.
     *
     * @param dateStyle the given date formatting style. For example,
     *     SHORT for "M/d/yy" in the US locale.
     * @param timeStyle the given time formatting style. For example,
     *     SHORT for "h:mm a" in the US locale.
     * @return a date/time formatter.
     */
    public static DateFormat getDateTimeInstance(int dateStyle,
                                                 int timeStyle) {
        DateFormat format = DateFormat.getDateTimeInstance(dateStyle, timeStyle);
        format.setCalendar(MockCalendar.getInstance());
        return format;
    }

    /**
     * Gets the date/time formatter with the given formatting styles for the given locale.
     *
     * @param dateStyle the given date formatting style.
     * @param timeStyle the given time formatting style.
     * @param locale the given locale.
     * @return a date/time formatter.
     */
    public static DateFormat getDateTimeInstance(int dateStyle, int timeStyle, Locale locale) {
        DateFormat format = DateFormat.getDateTimeInstance(dateStyle, timeStyle, locale);
        format.setCalendar(MockCalendar.getInstance());
        return format;
    }

    /**
     * Gets a default date/time formatter that uses the SHORT style for both the
     * date and the time.
     *
     * @return a date/time formatter.
     */
    public static DateFormat getInstance() {
        return getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
    }

    /**
     * Returns an array of all locales for which the <code>get*Instance</code> methods
     * of this class can return localized instances.
     *
     * @return an array of locales for which localized <code>DateFormat</code> instances are available.
     */
    public static Locale[] getAvailableLocales() {
        return DateFormat.getAvailableLocales();
    }
}
