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
import java.text.DateFormat;
import java.util.*;

/**
 * Mock implementation of java.util.Calendar.
 */
public abstract class MockCalendar extends Calendar implements OverrideMock {
    private static final long serialVersionUID = 7787669189246845968L;

    /*
        Note: there are many methods in Calendar, but here we just need to mock the ones that
        create new instances
     */

    //----- constructors  ---------

    protected MockCalendar() {
        super();
    }

    protected MockCalendar(TimeZone zone, Locale locale) {
        super(zone, locale);
    }

    // ------ static methods ----------

    public static Calendar getInstance() {
        return createCalendar(TimeZone.getDefault(), Locale.getDefault(Locale.Category.FORMAT));
    }

    public static Calendar getInstance(TimeZone zone) {
        return createCalendar(zone, Locale.getDefault(Locale.Category.FORMAT));
    }

    public static Calendar getInstance(Locale locale) {
        return createCalendar(TimeZone.getDefault(), locale);
    }

    public static Calendar getInstance(TimeZone zone, Locale locale) {
        return createCalendar(zone, locale);
    }

    private static Calendar createCalendar(TimeZone zone, Locale locale) {
        return new MockGregorianCalendar(zone, locale);
    }

    public static synchronized Locale[] getAvailableLocales() {
        //TODO do we need to mock it?
        return DateFormat.getAvailableLocales();
    }

}
