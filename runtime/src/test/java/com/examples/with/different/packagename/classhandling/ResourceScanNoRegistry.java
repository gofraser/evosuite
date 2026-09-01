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
package com.examples.with.different.packagename.classhandling;

import java.io.IOException;

/**
 * Control fixture for {@link org.evosuite.runtime.instrumentation.ClinitResetSafetyAnalyzer}:
 * its {@code <clinit>} reaches a classpath-scan call (same signal as {@link
 * RiskyRegistryClinit}), but it builds no {@code Map}/{@code Collection}
 * registry and no self-typed singleton - just a {@code String} field. The
 * detection rule must NOT fire here, otherwise it would be far too broad
 * (resource scanning alone is common and does not by itself imply an
 * unsafe-to-replay registry).
 */
public class ResourceScanNoRegistry {

    public static String marker = "unset";

    static {
        try {
            marker = scan();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String scan() throws IOException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl.getResource("META-INF/MANIFEST.MF") != null ? "found" : "not-found";
    }
}
