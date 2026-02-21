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
package org.evosuite;

import org.evosuite.Properties.NoSuchParameterException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

public class PropertiesTest {

    @BeforeAll
    public static void initClass() {
        Properties.getInstance();
    }

    @AfterEach
    public void reset() {
        Properties.getInstance().resetToDefaults();
    }

    @Test
    public void testParameterThatDoesNotExist() {
        try {
            Properties.getInstance().setValue("a_parameter_that_does_not_exist", 1);
            fail();
        } catch (IllegalArgumentException | IllegalAccessException
                | NoSuchParameterException e) {
            //expected
        }
    }

    @Test
    public void testOutOfRangeInput() throws IllegalArgumentException, IllegalAccessException, NoSuchParameterException {

        Properties.getInstance().setValue("crossover_rate", 0.6); //this should be OK

        try {
            Properties.getInstance().setValue("crossover_rate", 2.5);
            fail();
        } catch (Exception e) {/* OK*/}

        try {
            Properties.getInstance().setValue("crossover_rate", -10.6);
            fail();
        } catch (Exception e) {/* OK*/}
    }

    @Test
    public void testInvalidBooleanInput() throws Exception {

        final boolean defaultValue = Properties.TEST_CARVING;

        boolean value = Properties.getBooleanValue("test_carving");
        Assertions.assertEquals(defaultValue, value);

        Properties.getInstance().setValue("test_carving", !defaultValue);
        value = Properties.getBooleanValue("test_carving");
        Assertions.assertNotEquals(defaultValue, value);

        try {
            Properties.getInstance().setValue("test_carving", "tru");
            fail();
        } catch (Exception e) {
            //expected
        }
    }

    @Test
    public void testReset() {

        final String defaultValue = Properties.TARGET_CLASS;

        final String aString = "foo_foo_foo";
        Assertions.assertNotEquals(defaultValue, aString);

        Properties.TARGET_CLASS = aString;
        Assertions.assertEquals(aString, Properties.TARGET_CLASS);

        Properties.getInstance().resetToDefaults();
        Assertions.assertEquals(defaultValue, Properties.TARGET_CLASS);
    }
}
