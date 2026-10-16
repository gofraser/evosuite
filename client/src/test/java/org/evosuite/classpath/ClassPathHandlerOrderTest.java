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
package org.evosuite.classpath;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * openhre lists lib/xerces.jar before lib/xercesImpl.jar, but lib/xalan.jar (earlier still) names
 * xercesImpl.jar in its manifest Class-Path, so javac and the JVM read xercesImpl.jar first.
 */
class ClassPathHandlerOrderTest {

    @TempDir
    Path dir;

    private File jar(String name, String manifestClassPath) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (manifestClassPath != null) {
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, manifestClassPath);
        }
        File file = dir.resolve(name).toFile();
        try (OutputStream out = Files.newOutputStream(file.toPath());
             JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.flush();
        }
        return file;
    }

    private static String cp(File... files) {
        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            if (sb.length() > 0) {
                sb.append(File.pathSeparator);
            }
            sb.append(f.getPath());
        }
        return sb.toString();
    }

    @Test
    void manifestReferenceMovesBehindItsReferrer() throws IOException {
        File xalan = jar("xalan.jar", "xercesImpl.jar missing.jar xml-apis.jar");
        File xerces = jar("xerces.jar", null);
        File impl = jar("xercesImpl.jar", null);
        File apis = jar("xml-apis.jar", null);
        assertEquals(cp(xalan, impl, apis, xerces),
                ClassPathHandler.orderLikeJvm(cp(xalan, xerces, impl, apis)));
    }

    @Test
    void referencedEntryOffTheClasspathIsNotAdded() throws IOException {
        File a = jar("a.jar", "absent.jar");
        File b = jar("b.jar", null);
        String plain = cp(a, b);
        assertEquals(plain, ClassPathHandler.orderLikeJvm(plain));
    }

    @Test
    void cyclesAndAbsoluteUrisAreHandled() throws IOException {
        File b = jar("b.jar", "a.jar");
        File a = jar("a.jar", b.toURI().toString());
        File c = jar("c.jar", null);
        assertEquals(cp(a, b, c), ClassPathHandler.orderLikeJvm(cp(a, c, b)));
    }
}
