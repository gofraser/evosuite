/**
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
package org.evosuite.runtime.mock.java.nio.file;

import org.evosuite.runtime.mock.StaticReplacementMock;
import org.evosuite.runtime.mock.java.io.MockFile;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * NIO path replacements that route through {@link MockFile}.
 */
public class MockPaths implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return Paths.class.getName();
    }

    public static Path get(String first, String... more) {
        return new MockFile(first, join(more)).toPath();
    }

    public static Path get(URI uri) {
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return new MockFile(uri).toPath();
        }
        return Paths.get(uri);
    }

    private static String join(String[] parts) {
        if (parts == null || parts.length == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
