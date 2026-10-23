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
package org.evosuite;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommandLineParametersTest {

    @AfterEach
    public void reset() {
        Properties.getInstance().resetToDefaults();
        System.clearProperty("search_budget");
    }

    private static List<String> addOptions(String... args) throws Exception {
        CommandLine line = new DefaultParser().parse(CommandLineParameters.getCommandLineOptions(), args);
        List<String> javaOpts = new ArrayList<>();
        CommandLineParameters.addJavaDOptions(javaOpts, line);
        return javaOpts;
    }

    @Test
    public void retiredPropertyIsIgnored() throws Exception {
        List<String> javaOpts = addOptions("-Depsilon=0.5", "-Dsearch_budget=7");

        assertEquals(Collections.singletonList("-Dsearch_budget=7"), javaOpts);
        assertEquals(7, Properties.SEARCH_BUDGET);
    }

    @Test
    public void unknownPropertyStillFails() {
        Error error = assertThrows(Error.class, () -> addOptions("-Dno_such_key=1"));
        assertTrue(error.getMessage().contains("no_such_key"), error.getMessage());
    }
}
