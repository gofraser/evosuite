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
 * Reproduces the generator shape of {@code org.firebirdsql.jdbc.AbstractConnection}: the only way
 * to obtain a {@link Managed} is through a {@link Holder}, and the only way to obtain a
 * {@link Holder} is to already have a {@link Managed}.
 */
public class CircularGeneratorFixture {

    /** Stands in for {@code FBManagedConnection}: not publicly constructible. */
    public static class Managed {
        Managed() {
            // package private on purpose: not usable as a generator
        }

        /** Only used by the control cases, where a way out of the cycle must exist. */
        public static Managed create() {
            return new Managed();
        }
    }

    /** Stands in for {@code AbstractConnection}/{@code FBConnection}. */
    public static class Holder {
        public Managed managed;

        public Holder(Managed managed) {
            this.managed = managed;
        }

        public Managed getManaged() {
            return managed;
        }
    }
}
