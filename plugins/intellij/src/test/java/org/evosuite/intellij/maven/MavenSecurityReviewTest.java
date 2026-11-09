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
package org.evosuite.intellij.maven;

import junit.framework.TestCase;

import org.sonatype.plexus.components.cipher.DefaultPlexusCipher;

import java.nio.file.*;
import java.util.*;

public class MavenSecurityReviewTest extends TestCase {
    public void testConfiguredSecurityFileAndRelativeRelocation() throws Exception {
        Path root = Files.createTempDirectory("evosuite-security-");
        String previous = System.getProperty("settings.security");
        try {
            var cipher = new DefaultPlexusCipher();
            Files.writeString(
                    root.resolve("settings.xml"),
                    "<settings><servers><server><id>key</id><password>"
                            + cipher.encryptAndDecorate("secret", "master")
                            + "</password></server></servers></settings>");
            Files.writeString(
                    root.resolve("security.xml"),
                    "<settingsSecurity><relocation>nested/master.xml</relocation></settingsSecurity>");
            Files.createDirectories(root.resolve("nested"));
            Files.writeString(
                    root.resolve("nested/master.xml"),
                    "<settingsSecurity><master>"
                            + cipher.encryptAndDecorate("master", "settings.security")
                            + "</master></settingsSecurity>");
            System.setProperty("settings.security", root.resolve("security.xml").toString());
            assertEquals(
                    "secret",
                    MavenServerPassword.read(
                            root.resolve("settings.xml"), "key", message -> fail(message)));
            assertEquals(
                    Path.of(System.getProperty("user.home"), "secret.xml"),
                    MavenServerPassword.securityPath("~/secret.xml", root));
            Files.writeString(
                    root.resolve("nested/master.xml"),
                    "<settingsSecurity><relocation>../security.xml</relocation></settingsSecurity>");
            assertEquals(
                    "",
                    MavenServerPassword.read(root.resolve("settings.xml"), "key", ignored -> {}));
        } finally {
            if (previous == null) System.clearProperty("settings.security");
            else System.setProperty("settings.security", previous);
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }
}
