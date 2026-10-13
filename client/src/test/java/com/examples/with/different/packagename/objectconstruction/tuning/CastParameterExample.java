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
package com.examples.with.different.packagename.objectconstruction.tuning;

/**
 * An {@code Object} parameter that the branch reads only through a downcast. Synthesis should
 * construct the cast type for it, whatever the other parameters of the method are.
 */
public class CastParameterExample {

    public boolean objectOnly(Object value) {
        StringBuilder builder = (StringBuilder) value;
        if (builder.length() > 3) {
            return true;
        }
        return false;
    }

    public boolean afterPrimitive(int limit, Object value) {
        StringBuilder builder = (StringBuilder) value;
        if (builder.length() > limit) {
            return true;
        }
        return false;
    }
}
