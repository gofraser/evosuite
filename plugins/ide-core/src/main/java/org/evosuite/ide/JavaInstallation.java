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
package org.evosuite.ide;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Filesystem-only Java home validation, shared by IDE launch adapters. */
public record JavaInstallation(Path executable, int version, boolean hasCompiler) {
    public boolean readsArgumentFiles() {
        return version >= 9;
    }

    public static JavaInstallation at(Path home) throws IOException {
        Path bin = home.resolve("bin");
        Path java = executable(bin, "java");
        if (java == null) throw new IOException("No java executable in " + bin);
        Properties release = new Properties();
        Path file = home.resolve("release");
        if (Files.isRegularFile(file))
            try (var reader = Files.newBufferedReader(file)) {
                release.load(reader);
            }
        String version = release.getProperty("JAVA_VERSION", "0").replace("\"", "");
        String[] parts = version.split("[._+\\-]");
        int feature;
        try {
            feature =
                    Integer.parseInt(
                            parts[0].equals("1") && parts.length > 1 ? parts[1] : parts[0]);
        } catch (NumberFormatException ex) {
            feature = 0;
        }
        return new JavaInstallation(java, feature, executable(bin, "javac") != null);
    }

    private static Path executable(Path bin, String name) {
        for (String suffix : new String[] {"", ".exe"}) {
            Path path = bin.resolve(name + suffix);
            if (Files.isRegularFile(path)) return path;
        }
        return null;
    }
}
