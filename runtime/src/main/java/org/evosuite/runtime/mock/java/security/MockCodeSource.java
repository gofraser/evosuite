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
package org.evosuite.runtime.mock.java.security;

import org.evosuite.runtime.mock.MockFramework;

import java.net.URL;
import java.security.CodeSource;

/**
 * Headless-safe/runtime-safe replacement for {@link CodeSource#getLocation()}.
 * In some instrumented class-loader paths, CodeSource may be present while location is null.
 */
public final class MockCodeSource {

    private static final URL FALLBACK_LOCATION;

    static {
        URL tmp;
        try {
            String userDir = System.getProperty("user.dir");
            if (userDir == null || userDir.isEmpty()) {
                userDir = ".";
            }
            tmp = new java.io.File(userDir).toURI().toURL();
        } catch (Throwable ignored) {
            tmp = null;
        }
        FALLBACK_LOCATION = tmp;
    }

    private MockCodeSource() {
    }

    public static URL replacement_getLocation(CodeSource source) {
        if (source == null) {
            throw new NullPointerException();
        }

        URL location = source.getLocation();
        if (location != null) {
            return location;
        }

        if (MockFramework.isEnabled()) {
            return FALLBACK_LOCATION;
        }
        return null;
    }
}

