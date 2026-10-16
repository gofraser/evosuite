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
package org.evosuite.continuous;

import org.evosuite.Properties;
import org.evosuite.Properties.AvailableSchedule;
import org.evosuite.classpath.ClassPathHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which outcomes of a continuous test generation run are errors, which make EvoSuite exit with a failure
 * status, and which are only reported.
 */
public class CtgErrorsTest {

    private final String ctgDir = Properties.CTG_DIR;

    @TempDir
    Path tmp;

    @BeforeAll
    public static void initClass() {
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
    }

    @AfterEach
    public void reset() {
        Properties.CTG_DIR = ctgDir;
        Properties.CTG_EXTRA_PROPERTIES_FILE = null;
    }

    @Test
    public void unusableStorageIsAnError() throws Exception {
        Path readOnly = Files.createDirectories(tmp.resolve(".evosuite"));
        Assumptions.assumeTrue(readOnly.toFile().setWritable(false) && !Files.isWritable(readOnly),
                "cannot make a folder read-only here, eg when running as root");
        Properties.CTG_DIR = readOnly.toString();
        try {
            CtgException e = assertThrows(CtgException.class, () -> ctg(tmp.toString()).execute());
            assertTrue(e.getMessage().contains("storage"), e.getMessage());
        } finally {
            readOnly.toFile().setWritable(true);
        }
    }

    @Test
    public void invalidExtraPropertiesAreAnError() throws Exception {
        Properties.CTG_DIR = tmp.resolve(".evosuite").toString();
        Properties.CTG_EXTRA_PROPERTIES_FILE = Files.write(tmp.resolve("extra.properties"),
                java.util.Collections.singletonList("no_such_property=1")).toString();

        CtgException e = assertThrows(CtgException.class, () -> ctg(tmp.toString()).execute());
        assertTrue(e.getMessage().contains("no_such_property"), e.getMessage());
    }

    @Test
    public void aModuleWithoutTestableClassesIsNotAnError() throws Exception {
        Properties.CTG_DIR = tmp.resolve(".evosuite").toString();
        Path noClasses = Files.createDirectories(tmp.resolve("classes"));

        String result = ctg(noClasses.toString()).execute();

        assertTrue(result.contains("There is no class to test"), result);
    }

    private static ContinuousTestGeneration ctg(String target) {
        CtgConfiguration conf = new CtgConfiguration(1000, 1, 1, 1, false, AvailableSchedule.BUDGET);
        return new ContinuousTestGeneration(target, target, "", conf, null, null);
    }
}
