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
package org.evosuite.junit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Detects and repairs jars that javac cannot read from a classpath: jars whose manifest
 * Class-Path holds entries that are not valid URIs (or use backslashes), and jars with
 * entry names containing "." or ".." segments. A repaired jar is replaced by a temporary
 * directory holding its extracted content.
 */
public final class CompilerClasspath {

    private static final Logger logger = LoggerFactory.getLogger(CompilerClasspath.class);

    private CompilerClasspath() {
    }

    /**
     * True if the manifest Class-Path of the jar lists an entry with a backslash or a syntax
     * that is not a valid URI. A jar that cannot be read is reported as well formed.
     */
    public static boolean hasMalformedManifestClassPath(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            java.util.jar.Manifest manifest = jar.getManifest();
            if (manifest == null) {
                return false;
            }
            Attributes mainAttributes = manifest.getMainAttributes();
            if (mainAttributes == null) {
                return false;
            }
            String classPath = mainAttributes.getValue(Attributes.Name.CLASS_PATH);
            if (classPath == null || classPath.trim().isEmpty()) {
                return false;
            }

            for (String manifestEntry : classPath.trim().split("\\s+")) {
                if (manifestEntry.isEmpty()) {
                    continue;
                }
                if (manifestEntry.indexOf('\\') >= 0) {
                    return true;
                }
                try {
                    new URI(manifestEntry);
                } catch (URISyntaxException e) {
                    return true;
                }
            }
        } catch (IOException e) {
            logger.debug("Could not inspect jar manifest {} for compiler sanitization: {}",
                    jarFile, e.getMessage());
        }
        return false;
    }

    /**
     * True if any entry of the jar has a "." or ".." path segment. A jar that cannot be read
     * is reported as free of them.
     */
    public static boolean hasDotSegmentEntry(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                if (hasDotSegment(entries.nextElement().getName())) {
                    return true;
                }
            }
        } catch (IOException e) {
            logger.debug("Could not inspect jar {} for compiler sanitization: {}", jarFile, e.getMessage());
        }
        return false;
    }

    /**
     * True if the entry name has a "." or ".." path segment.
     */
    public static boolean hasDotSegment(String entryName) {
        if (entryName == null || entryName.isEmpty()) {
            return false;
        }
        String normalized = entryName.startsWith("/") ? entryName.substring(1) : entryName;
        for (String segment : normalized.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts the jar into a new temporary directory, leaving out the entries accepted by
     * {@code skipEntry}. Leading slashes of entry names are dropped. The directory and its
     * files are deleted on JVM exit.
     *
     * @param tempDirPrefix prefix of the temporary directory name
     * @param skipEntry     decides on the raw entry name whether the entry is left out
     * @return the absolute path of the directory, or of the jar if it could not be extracted
     */
    public static String extractJar(File jarFile, String tempDirPrefix, Predicate<String> skipEntry) {
        try {
            File targetDir = Files.createTempDirectory(tempDirPrefix).toFile();
            targetDir.deleteOnExit();

            try (JarFile jar = new JarFile(jarFile)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (skipEntry.test(name)) {
                        continue;
                    }
                    if (name.startsWith("/")) {
                        name = name.substring(1);
                    }
                    if (name.isEmpty()) {
                        continue;
                    }

                    File out = new File(targetDir, name);
                    if (entry.isDirectory()) {
                        if (!out.exists() && !out.mkdirs()) {
                            logger.debug("Could not create directory while sanitizing jar: {}", out);
                        }
                        continue;
                    }

                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        logger.debug("Could not create parent directory while sanitizing jar: {}", parent);
                        continue;
                    }

                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    out.deleteOnExit();
                }
            }
            return targetDir.getAbsolutePath();
        } catch (IOException e) {
            logger.warn("Failed to sanitize malformed jar {} for compiler classpath: {}",
                    jarFile.getAbsolutePath(), e.getMessage());
            return jarFile.getAbsolutePath();
        }
    }
}
