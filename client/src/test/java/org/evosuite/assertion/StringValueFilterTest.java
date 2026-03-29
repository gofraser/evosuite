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
package org.evosuite.assertion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringValueFilterTest {

    @Test
    void shouldFilter_defaultObjectToStringWithSingleHexDigit() {
        assertTrue(StringValueFilter.shouldFilter("ch.bfh.egov.nutzenportfolio.tos.Customizing@2"));
    }

    @Test
    void shouldFilter_defaultObjectToStringForInnerClass() {
        assertTrue(StringValueFilter.shouldFilter("com.example.Outer$Inner@1a"));
    }

    @Test
    void shouldFilter_nestedObjectReferencesInCollectionToString() {
        assertTrue(StringValueFilter.shouldFilter("[com.example.Foo@abc, com.example.Bar@DEF]"));
    }

    @Test
    void shouldNotFilter_regularText() {
        assertFalse(StringValueFilter.shouldFilter("hello-world"));
    }

    @Test
    void shouldNotFilter_emailAddress() {
        assertFalse(StringValueFilter.shouldFilter("user@example.com"));
    }
}
