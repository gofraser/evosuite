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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.security.CodeSource;

public class MockCodeSourceTest {

    @Test
    public void returnsFallbackLocationWhenMockingEnabledAndCodeSourceLocationIsNull() {
        MockFramework.enable();
        try {
            CodeSource codeSource = new CodeSource((URL) null, (java.security.cert.Certificate[]) null);
            URL location = MockCodeSource.replacement_getLocation(codeSource);
            Assertions.assertNotNull(location);
        } finally {
            MockFramework.disable();
        }
    }

    @Test
    public void returnsOriginalLocationWhenPresent() throws Exception {
        URL original = new URL("file:/tmp/evosuite-test.jar");
        CodeSource codeSource = new CodeSource(original, (java.security.cert.Certificate[]) null);
        URL location = MockCodeSource.replacement_getLocation(codeSource);
        Assertions.assertEquals(original, location);
    }
}

