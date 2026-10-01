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
 * Produces a {@link NestedConstantBodyEnumFixture.Mode} constant through an
 * {@code Object}-typed accessor, without the generated test ever
 * constructing or naming {@link NestedConstantBodyEnumFixture} for any other
 * reason. Isolates whether an enum-valued assertion registers the import for
 * the constant's declaring (outer) class itself, as opposed to piggybacking
 * on an import some other statement in the same test already needed.
 */
public class ConstantBodyEnumProducer {

    public Object produce() {
        return NestedConstantBodyEnumFixture.Mode.FAST;
    }
}
