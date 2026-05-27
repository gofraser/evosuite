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
package com.examples.with.different.packagename.classhandling;

/**
 * Fixture for the {@link org.evosuite.runtime.classhandling.ClassResetter}
 * poison-set behavior: the {@code <clinit>} body increments a counter and
 * then throws if the {@code throwingclinit.fail} system property is set.
 *
 * <p>The fixture is loaded once with the property unset (counter goes to 1,
 * no throw), then the property is flipped on and the class is asked to
 * reset. The instrumented {@code __STATIC_RESET()} first zeroes static
 * fields, then replays the {@code <clinit>} body — which increments the
 * counter back to 1 and throws. The expectation is that subsequent reset
 * calls are skipped, so the counter stays at 1.
 */
public class ThrowingClinit {

    public static int counter;

    static {
        counter++;
        if ("true".equals(System.getProperty("throwingclinit.fail"))) {
            throw new RuntimeException("forced failure in <clinit>");
        }
    }
}
