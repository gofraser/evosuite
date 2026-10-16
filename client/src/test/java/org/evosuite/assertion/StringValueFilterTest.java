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

    @Test
    void shouldFilter_defaultAwtComponentName() {
        assertTrue(StringValueFilter.shouldFilter("dialog771", new java.awt.Panel()));
        assertTrue(StringValueFilter.isAwtDefaultName("frame3", java.awt.Component.class));
        assertTrue(StringValueFilter.isAwtDefaultName("java.awt.Panel[panel0,0,0,0x0,invalid]",
                java.awt.Panel.class));
    }

    @Test
    void shouldNotFilter_awtNameWithoutNumericSuffixOrOtherProducer() {
        assertFalse(StringValueFilter.shouldFilter("dialogue", new java.awt.Panel()));
        assertFalse(StringValueFilter.shouldFilter("myPanel", new java.awt.Panel()));
        assertFalse(StringValueFilter.shouldFilter("dialog771", "not a component"));
        assertFalse(StringValueFilter.isAwtDefaultName("dialog771", String.class));
    }

    @Test
    void relayedAwtDefaultName_isFilteredOnlyWithAComponentInScope() {
        org.evosuite.testcase.execution.Scope withPanel = new org.evosuite.testcase.execution.Scope();
        withPanel.setObject(new org.evosuite.testcase.variable.VariableReferenceImpl(
                new org.evosuite.testcase.DefaultTestCase(), java.awt.Panel.class), new java.awt.Panel());
        org.evosuite.testcase.execution.Scope withoutPanel = new org.evosuite.testcase.execution.Scope();

        assertTrue(StringValueFilter.isRelayedAwtDefaultName("dialog549", withPanel));
        assertTrue(StringValueFilter.isRelayedAwtDefaultName("x.Win[dialog540,0,0,0x0,invalid]", withPanel));
        assertFalse(StringValueFilter.isRelayedAwtDefaultName("dialog549", withoutPanel));
        assertFalse(StringValueFilter.isRelayedAwtDefaultName("dialogue", withPanel));
        assertFalse(StringValueFilter.isRelayedAwtDefaultName("see dialog549 here", withPanel));
        assertFalse(StringValueFilter.isRelayedAwtDefaultName(null, withPanel));
    }
}
