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
package org.evosuite.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LegacyPropertyTest {

    private final Properties commandLine = new Properties();

    private AbstractEvoSuiteMojo mojo;

    @BeforeEach
    public void setUp() {
        mojo = new AbstractEvoSuiteMojo() {
            @Override
            public void execute() {
            }
        };
        mojo.session = mock(MavenSession.class);
        when(mojo.session.getUserProperties()).thenReturn(commandLine);
        mojo.project = new MavenProject();
    }

    @Test
    public void oldNameOnTheCommandLine() {
        commandLine.setProperty("cores", "4");
        assertEquals("4", mojo.legacyProperty("evosuite.cores", "cores"));
    }

    @Test
    public void oldNameAsAPomProperty() {
        mojo.project.getProperties().setProperty("cores", "4");
        assertEquals("4", mojo.legacyProperty("evosuite.cores", "cores"));
    }

    @Test
    public void newNameWinsWhereItIsGiven() {
        commandLine.setProperty("evosuite.cores", "2");
        commandLine.setProperty("cores", "4");
        assertNull(mojo.legacyProperty("evosuite.cores", "cores"));

        commandLine.clear();
        mojo.project.getProperties().setProperty("evosuite.cores", "2");
        mojo.project.getProperties().setProperty("cores", "4");
        assertNull(mojo.legacyProperty("evosuite.cores", "cores"));
    }

    @Test
    public void commandLineBeatsThePom() {
        commandLine.setProperty("cores", "4");
        mojo.project.getProperties().setProperty("evosuite.cores", "2");
        assertEquals("4", mojo.legacyProperty("evosuite.cores", "cores"));
    }

    @Test
    public void nothingGiven() {
        assertNull(mojo.legacyProperty("evosuite.cores", "cores"));
    }
}
