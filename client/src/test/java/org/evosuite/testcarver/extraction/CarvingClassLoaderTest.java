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
package org.evosuite.testcarver.extraction;

import org.evosuite.classpath.ClassPathHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;

public class CarvingClassLoaderTest {

    @Test
    public void testClassOnlyOnTargetClasspathIsFoundThroughResourceList() throws Exception {
        File localFolder = new File("local_test_data" + File.separator + "aCpEntry");
        Assertions.assertTrue(localFolder.exists());
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(localFolder.getAbsolutePath());

        Assertions.assertNull(ClassLoader.getSystemResource("foo/ExternalClass.class"));
        try (InputStream stream = new CarvingClassLoader().openClass("foo.ExternalClass")) {
            Assertions.assertNotNull(stream);
        }
    }
}
