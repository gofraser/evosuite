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
package com.examples.with.different.packagename.fm;

/**
 * A non-final class with a {@code public final} method, mimicking classes such as
 * {@code com.fasterxml.jackson.databind.DeserializationContext} whose {@code isEnabled}
 * method Mockito's subclass mock maker cannot intercept.
 */
public class ExampleWithFinalMethod {

    public final boolean isEnabled(String feature) {
        if (feature == null) {
            throw new NullPointerException("feature is null");
        }
        return feature.length() > 0;
    }

    public boolean isOverridable(String feature) {
        return feature != null;
    }
}
