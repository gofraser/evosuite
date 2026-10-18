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
package com.examples.with.different.packagename.llm;

/**
 * Fixture for {@code BranchOperandHintSwitchInstrumentationTest}: a real instrumented switch
 * whose selector is a field, so the recorded branch operands and the field name the CFG resolves
 * for it can both be checked against actual bytecode rather than a mock.
 */
public class SwitchOperandFixture {

    public int code;

    public String route() {
        switch (code) {
            case 1:
                return "one";
            case 5:
                return "five";
            default:
                return "other";
        }
    }
}
