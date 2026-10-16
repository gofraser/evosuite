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
package com.examples.with.different.packagename;

/**
 * Shaped after {@code com.liferay.portal.kernel.language.UnicodeLanguage#format}: an overload
 * set whose last parameter varies over a wrapper type, its array form, {@code Object} and
 * {@code Object[]}. A bare Mockito {@code any()} matcher rendered for the {@code Object}
 * overload is ambiguous between all four and fails to compile.
 */
public interface OverloadedFormatInterface {

    interface Wrapper {
    }

    String format(String context, String pattern, Wrapper argument);

    String format(String context, String pattern, Wrapper[] arguments);

    String format(String context, String pattern, Object argument);

    String format(String context, String pattern, Object[] arguments);

    /** Not overloaded: only this signature takes an {@code Object} at that position. */
    String formatSingleObject(Object argument);

    String formatWithArray(Object[] arguments);

    String formatWithPrimitive(int value);
}
