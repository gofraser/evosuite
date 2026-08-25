/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package com.examples.with.different.packagename.llm;

/**
 * Target fixture used by LLM provenance copy-path tests.
 */
public class Target {

    public int field;
    public static int staticField;

    public Target() {
    }

    public Target(int x) {
        this.field = x;
    }

    public int doSomething(int x) {
        return x;
    }

    public static int staticDoSomething() {
        return 1;
    }

    public static int staticTakesInt(int x) {
        return x;
    }
}
