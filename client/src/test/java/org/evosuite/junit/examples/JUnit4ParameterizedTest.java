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
package org.evosuite.junit.examples;

import com.examples.with.different.packagename.FlagExample1;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JUnit4ParameterizedTest {

    private Integer input;
    private Boolean output;
    private FlagExample1 cut;

    @BeforeEach
    public void initialize() {
        this.cut = new FlagExample1();
    }

    public void initJUnit4ParameterizedTest(Integer a, Boolean b) {
        this.input = a;
        this.output = b;
    }

    public static Collection<Object[]> primeNumbers() {
        return Arrays.asList(new Object[][]{{7, false}, {28241, true}});
    }

    @MethodSource("primeNumbers")
    @ParameterizedTest
    public void test(Integer a, Boolean b) {
        initJUnit4ParameterizedTest(a, b);
        assertEquals(output, cut.testMe(input));
    }
}
