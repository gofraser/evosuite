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
package org.evosuite.executionmode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An argument that does not start with a dash ends the JVM options and is taken as the main
 * class, so the log folder must be passed as a plain -D option.
 */
public class LogFolderPropertyTest {

    private static final String KEY = "evosuite.log.folder";

    private String saved;

    @BeforeEach
    public void remember() {
        saved = System.getProperty(KEY);
    }

    @AfterEach
    public void restore() {
        if (saved == null) {
            System.clearProperty(KEY);
        } else {
            System.setProperty(KEY, saved);
        }
    }

    @Test
    public void logFolderIsForwardedAsDefinitionOption() {
        System.setProperty(KEY, "/var/logs");
        List<String> cmd = new ArrayList<>();

        ExecutionModeUtils.addLogFolderPropertyIfPresent(cmd);

        assertEquals(1, cmd.size());
        assertEquals("-Devosuite.log.folder=/var/logs", cmd.get(0));
    }

    @Test
    public void nothingIsAddedWithoutLogFolder() {
        System.clearProperty(KEY);
        List<String> cmd = new ArrayList<>();

        ExecutionModeUtils.addLogFolderPropertyIfPresent(cmd);

        assertTrue(cmd.isEmpty());
    }
}
