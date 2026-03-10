/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package com.examples.with.different.packagename.setup;

/**
 * Fixture for testing that removeDirectCycle does not over-aggressively
 * remove generators when the owner class has an alternative constructor
 * that does not require the generated type.
 *
 * <p>X has two constructors: X() and X(Y). The no-arg constructor means
 * that x.getY() should remain as a valid generator for Y, because we can
 * construct X without needing Y first.</p>
 */
public class CycleWithEscapeX {

    public CycleWithEscapeX() {
    }

    public CycleWithEscapeX(CycleWithEscapeY y) {
    }

    public CycleWithEscapeY getY() {
        return new CycleWithEscapeY();
    }
}
