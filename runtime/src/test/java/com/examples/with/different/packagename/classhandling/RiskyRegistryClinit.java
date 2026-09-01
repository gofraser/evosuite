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
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * Fixture mirroring the {@code org.firebirdsql.gds.impl.GDSFactory} pattern
 * that motivates {@link org.evosuite.runtime.instrumentation.ClinitResetSafetyAnalyzer}:
 * a {@code <clinit>} that builds a static {@code Map} registry and, through a
 * private static helper, scans the classpath via {@code
 * Thread.currentThread().getContextClassLoader()} and {@code
 * ClassLoader.getResources(...)}. Replaying this {@code <clinit>} is not
 * idempotent, so no {@code __STATIC_RESET()} should be generated for it.
 */
public class RiskyRegistryClinit {

    public static final Map<Object, Object> registry = new HashMap<>();

    static {
        registry.put(new Object(), "seed");
        try {
            scanClassLoader();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void scanClassLoader() throws IOException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> res = cl.getResources("META-INF/MANIFEST.MF");
        while (res.hasMoreElements()) {
            res.nextElement();
        }
    }
}
