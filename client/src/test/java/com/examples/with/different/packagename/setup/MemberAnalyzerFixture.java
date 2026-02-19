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
package com.examples.with.different.packagename.setup;

/**
 * A minimal fixture for MemberAnalyzer unit tests.
 * Has a public constructor, a public method, and a public field.
 */
public class MemberAnalyzerFixture {

    public int publicField;

    public static final int CONSTANT = 42;

    public MemberAnalyzerFixture() {
    }

    public MemberAnalyzerFixture(int value) {
        this.publicField = value;
    }

    public int getValue() {
        return publicField;
    }

    public void setValue(int value) {
        this.publicField = value;
    }

    @Override
    public String toString() {
        return "MemberAnalyzerFixture{" + publicField + "}";
    }
}
