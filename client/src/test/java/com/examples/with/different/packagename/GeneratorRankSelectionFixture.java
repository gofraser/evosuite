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
 * Ten equally-simple static factory methods for {@link Target}, used to check the distribution
 * that {@code TestCluster#selectWeightedGenerator} produces when several generators for the same
 * type score identically: since all ten have the same {@code scoreGenerator} score, their
 * relative selection frequency is driven entirely by rank-selection position, letting a test
 * observe the raw distribution without needing to also model differing scores.
 */
public class GeneratorRankSelectionFixture {

    public static class Target {
    }

    public static Target create0() {
        return new Target();
    }

    public static Target create1() {
        return new Target();
    }

    public static Target create2() {
        return new Target();
    }

    public static Target create3() {
        return new Target();
    }

    public static Target create4() {
        return new Target();
    }

    public static Target create5() {
        return new Target();
    }

    public static Target create6() {
        return new Target();
    }

    public static Target create7() {
        return new Target();
    }

    public static Target create8() {
        return new Target();
    }

    public static Target create9() {
        return new Target();
    }
}
