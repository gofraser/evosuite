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
 * A boolean disjunction followed by a catch-all handler, the shape whose
 * instrumentation once lost the labels the testability transformation adds.
 */
public class DefaultOrConvert {

    private boolean useDefault;
    private Object defaultValue;

    public Object handleMissing(Class<?> type) {
        if (useDefault || type.equals(String.class)) {
            Object value = defaultValue;
            if (useDefault && value != null && !type.equals(value.getClass())) {
                try {
                    value = type.cast(defaultValue);
                } catch (Throwable t) {
                    System.err.println("conversion failed: " + t);
                }
            }
            return value;
        }
        throw new IllegalArgumentException("No value specified for " + type);
    }
}
