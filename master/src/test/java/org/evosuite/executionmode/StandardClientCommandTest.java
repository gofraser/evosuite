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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.executionmode;

import org.evosuite.ClientProcess;
import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launchers that run a single analysis client (dependencies, statistics, coverage) share one
 * command line layout; the client reads the class path and target from it.
 */
public class StandardClientCommandTest {

    private String targetClass;
    private int port;
    private String prefix;

    @BeforeEach
    public void remember() {
        // Loading the properties can set the project prefix, so load them before clearing it.
        Properties.getInstance();
        targetClass = Properties.TARGET_CLASS;
        port = Properties.PROCESS_COMMUNICATION_PORT;
        prefix = Properties.PROJECT_PREFIX;
        Properties.PROJECT_PREFIX = null;
    }

    @AfterEach
    public void restore() {
        Properties.TARGET_CLASS = targetClass;
        Properties.PROCESS_COMMUNICATION_PORT = port;
        Properties.PROJECT_PREFIX = prefix;
    }

    @Test
    public void layoutPlacesPropertiesBeforeTheClientMainClass() {
        List<String> args = Arrays.asList("-DCP=ignored", "-Dfoo=bar");
        List<String> cmd = ExecutionModeUtils.buildStandardClientCommand("a.jar", "proj.jar", true, args,
                "com.Foo", "-Djunit=x", 4711);

        assertEquals("-cp", cmd.get(1));
        assertEquals("a.jar", cmd.get(2));
        assertTrue(cmd.contains("-DCP=proj.jar"));
        assertFalse(cmd.contains("-DCP=ignored"));
        assertTrue(cmd.contains("-Dfoo=bar"));
        int target = cmd.indexOf("-DTARGET_CLASS=com.Foo");
        assertTrue(target > 0);
        assertEquals("-Djunit=x", cmd.get(target + 1));
        assertEquals("-Dclassloader=true", cmd.get(cmd.size() - 2));
        assertEquals(ClientProcess.class.getName(), cmd.get(cmd.size() - 1));
        assertEquals("com.Foo", Properties.TARGET_CLASS);
        assertEquals(4711, Properties.PROCESS_COMMUNICATION_PORT);
    }

    @Test
    public void headlessModeIsOptional() {
        List<String> withHeadless = ExecutionModeUtils.buildStandardClientCommand("a.jar", "p", true,
                Collections.<String>emptyList(), "com.Foo", null, 1);
        List<String> without = ExecutionModeUtils.buildStandardClientCommand("a.jar", "p", false,
                Collections.<String>emptyList(), "com.Foo", null, 1);

        assertEquals(withHeadless.size() - 1, without.size());
    }
}
