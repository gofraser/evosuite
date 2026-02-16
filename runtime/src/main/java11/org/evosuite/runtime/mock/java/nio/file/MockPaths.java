/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
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
