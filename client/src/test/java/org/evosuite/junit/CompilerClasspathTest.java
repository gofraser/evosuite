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

import org.evosuite.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CompilerClasspathTest {

    @TempDir
    File tmp;

    private File jar(String name, String manifestClassPath, String... entries) throws IOException {
        File file = new File(tmp, name);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (manifestClassPath != null) {
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, manifestClassPath);
        }
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(file), manifest)) {
            for (String entry : entries) {
                out.putNextEntry(new JarEntry(entry));
                out.write("x".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    @Test
    public void wellFormedManifestClassPathIsAccepted() throws IOException {
        assertFalse(CompilerClasspath.hasMalformedManifestClassPath(
                jar("ok.jar", "lib/a.jar file:/tmp/b.jar", "A.class")));
    }

    @Test
    public void jarWithoutClassPathIsAccepted() throws IOException {
        assertFalse(CompilerClasspath.hasMalformedManifestClassPath(jar("none.jar", null, "A.class")));
    }

    @Test
    public void backslashInClassPathIsMalformed() throws IOException {
        assertTrue(CompilerClasspath.hasMalformedManifestClassPath(
                jar("bs.jar", "lib\\a.jar", "A.class")));
    }

    @Test
    public void invalidUriInClassPathIsMalformed() throws IOException {
        assertTrue(CompilerClasspath.hasMalformedManifestClassPath(
                jar("uri.jar", "lib/a.jar lib/with{brace}.jar", "A.class")));
    }

    @Test
    public void directoryAndMissingFileAreNeitherMalformedNorDotSegmented() {
        File dir = new File(tmp, "dir");
        assertTrue(dir.mkdir());
        File missing = new File(tmp, "missing.jar");
        assertFalse(CompilerClasspath.hasMalformedManifestClassPath(dir));
        assertFalse(CompilerClasspath.hasMalformedManifestClassPath(missing));
        assertFalse(CompilerClasspath.hasDotSegmentEntry(dir));
        assertFalse(CompilerClasspath.hasDotSegmentEntry(missing));
    }

    @Test
    public void dotSegmentsAreDetectedInNamesAndJars() throws IOException {
        assertTrue(CompilerClasspath.hasDotSegment("a/../b.class"));
        assertTrue(CompilerClasspath.hasDotSegment("/./b.class"));
        assertFalse(CompilerClasspath.hasDotSegment("a/b..c/d.class"));
        assertFalse(CompilerClasspath.hasDotSegment(""));
        assertFalse(CompilerClasspath.hasDotSegment(null));
        assertTrue(CompilerClasspath.hasDotSegmentEntry(jar("dots.jar", null, "A.class", "x/../B.class")));
        assertFalse(CompilerClasspath.hasDotSegmentEntry(jar("plain.jar", null, "A.class", "x/B.class")));
    }

    @Test
    public void extractionLeavesOutSkippedEntries() throws IOException {
        File source = jar("src.jar", "lib\\a.jar", "A.class", "p/B.class", "x/../C.class");
        String dir = CompilerClasspath.extractJar(source, "evosuite-test-cp-", CompilerClasspath::hasDotSegment);
        assertNotEquals(source.getAbsolutePath(), dir);
        assertTrue(new File(dir, "A.class").isFile());
        assertTrue(new File(dir, "p/B.class").isFile());
        assertTrue(new File(dir, "META-INF/MANIFEST.MF").isFile());
        assertFalse(new File(dir, "C.class").exists());
        assertTrue(new File(dir).getName().startsWith("evosuite-test-cp-"));

        String withoutManifest = CompilerClasspath.extractJar(source, "evosuite-test-cp-",
                "META-INF/MANIFEST.MF"::equalsIgnoreCase);
        assertFalse(new File(withoutManifest, "META-INF/MANIFEST.MF").exists());
        assertTrue(new File(withoutManifest, "A.class").isFile());
    }

    @Test
    public void extractionOfUnreadableJarFallsBackToTheJar() throws IOException {
        File notAJar = new File(tmp, "broken.jar");
        Files.write(notAJar.toPath(), "not a zip".getBytes(StandardCharsets.UTF_8));
        assertEquals(notAJar.getAbsolutePath(),
                CompilerClasspath.extractJar(notAJar, "evosuite-test-cp-", name -> false));
    }

    @Test
    public void compilerClasspathKeepsEntryOrderAndRepairsOnlyBrokenJars() throws Exception {
        File good1 = jar("good1.jar", null, "A.class");
        File broken = jar("broken-cp.jar", "lib\\a.jar", "B.class");
        File dir = new File(tmp, "classes");
        assertTrue(dir.mkdir());
        File missing = new File(tmp, "missing.jar");
        File good2 = jar("good2.jar", null, "C.class");
        String input = String.join(File.pathSeparator, good1.getPath(), broken.getPath(), dir.getPath(),
                missing.getPath(), good2.getPath());

        String[] out = CompilerClasspath.sanitize(input).split(File.pathSeparator);

        assertEquals(5, out.length);
        assertEquals(good1.getAbsolutePath(), out[0]);
        assertNotEquals(broken.getAbsolutePath(), out[1]);
        assertTrue(new File(out[1], "B.class").isFile());
        assertEquals(dir.getPath(), out[2]);
        assertEquals(missing.getPath(), out[3]);
        assertEquals(good2.getAbsolutePath(), out[4]);
    }

    private String snippetClasspath(File... loaderJars) throws Exception {
        URL[] urls = new URL[loaderJars.length];
        for (int i = 0; i < loaderJars.length; i++) {
            urls[i] = loaderJars[i].toURI().toURL();
        }
        String previousCp = Properties.CP;
        try (URLClassLoader loader = new URLClassLoader(urls, null)) {
            Properties.CP = "";
            return CompilerClasspath.forClassLoader(loader);
        } finally {
            Properties.CP = previousCp;
        }
    }

    @Test
    public void unreadableJarNamedInDiagnosticsIsReplaced() throws Exception {
        File safeJar = jar("a.jar", null);
        File malformedJar = jar("batik-squiggle.jar", "\\");
        String cp = safeJar.getAbsolutePath() + File.pathSeparator + malformedJar.getAbsolutePath();
        String diagnostics = "error: error reading " + malformedJar.getAbsolutePath() + "; "
                + "java.net.URISyntaxException: Illegal character in path at index 29: file:/sut/lib/\\";

        String repaired = CompilerClasspath.repairUnreadableEntries(cp, diagnostics);

        assertNotNull(repaired);
        assertFalse(repaired.contains(malformedJar.getAbsolutePath()));
        assertTrue(repaired.contains(safeJar.getAbsolutePath()));
        assertTrue(repaired.contains("evosuite-compiler-cp-"));
    }

    @Test
    public void snippetClasspathReplacesJarWithMalformedManifest() throws Exception {
        File malformedJar = jar("malformed-loader.jar", "lib/\\ batik-squiggle.jar");

        String classpath = snippetClasspath(malformedJar);

        assertFalse(classpath.contains(malformedJar.getAbsolutePath()));
        assertTrue(classpath.contains("evosuite-compiler-cp-"));
    }

    @Test
    public void snippetClasspathReplacesJarsReferencingAMalformedJar() throws Exception {
        File malformedJar = jar("batik-squiggle.jar", "\\");
        File referencingJar = jar("batik-script.jar", malformedJar.getName());

        String classpath = snippetClasspath(referencingJar, malformedJar);

        assertFalse(classpath.contains(referencingJar.getAbsolutePath()));
        assertFalse(classpath.contains(malformedJar.getAbsolutePath()));
        assertTrue(classpath.contains("evosuite-compiler-cp-"));
    }

    @Test
    public void snippetClasspathExpandsManifestOnlyJarAndKeepsSafeEntries() throws Exception {
        File safeJar = jar("junit-jupiter-api.jar", null);
        File malformedJar = jar("batik-squiggle.jar", "\\");
        File pathingJar = jar("EvoSuite_pathingJar123.jar", safeJar.getName() + " " + malformedJar.getName());

        String classpath = snippetClasspath(pathingJar);

        assertFalse(classpath.contains(pathingJar.getAbsolutePath()));
        assertTrue(classpath.contains(safeJar.getAbsolutePath()));
        assertFalse(classpath.contains(malformedJar.getAbsolutePath()));
        assertTrue(classpath.contains("evosuite-compiler-cp-"));
    }

    @Test
    public void normalizingAnEntryDropsTrailingBackslashAndFilePrefix() {
        String normalized = CompilerClasspath.normalizeClasspathEntry("file:/tmp/lib/\\");

        assertNotNull(normalized);
        assertFalse(normalized.endsWith("\\"));
        assertFalse(normalized.startsWith("file:"));
        assertTrue(normalized.contains("/tmp/lib"));
    }

    @Test
    public void snippetClasspathReplacesJarWithDotSegmentEntries() throws Exception {
        File dotted = jar("dotted-loader.jar", null, "A.class", "x/../B.class");

        String classpath = snippetClasspath(dotted);

        assertFalse(classpath.contains(dotted.getAbsolutePath()));
        assertTrue(classpath.contains("evosuite-compiler-cp-"));
        File extracted = null;
        for (String entry : classpath.split(File.pathSeparator)) {
            if (new File(entry, "A.class").isFile()) {
                extracted = new File(entry);
            }
        }
        assertNotNull(extracted);
        assertFalse(new File(extracted, "x/../B.class").exists());
        assertFalse(new File(extracted, "B.class").exists());
    }

    @Test
    public void generatedTestClasspathExpandsManifestOnlyJar() throws Exception {
        File safeJar = jar("safe.jar", null, "A.class");
        File pathingJar = jar("pathing.jar", safeJar.getName());

        String classpath = CompilerClasspath.sanitize(pathingJar.getAbsolutePath());

        assertEquals(safeJar.getAbsolutePath(), classpath);
    }

    @Test
    public void generatedTestClasspathReplacesJarsReferencingAMalformedJar() throws Exception {
        File malformedJar = jar("batik-squiggle.jar", "\\");
        File referencingJar = jar("batik-script.jar", malformedJar.getName(), "S.class");
        File safeJar = jar("safe.jar", null, "A.class");
        String input = String.join(File.pathSeparator, referencingJar.getPath(), malformedJar.getPath(),
                safeJar.getPath());

        String[] out = CompilerClasspath.sanitize(input).split(File.pathSeparator);

        assertEquals(3, out.length);
        assertTrue(new File(out[0]).isDirectory());
        assertTrue(new File(out[1]).isDirectory());
        assertEquals(safeJar.getAbsolutePath(), out[2]);
    }

    @Test
    public void generatedTestClasspathNormalizesAndDeduplicatesEntries() throws Exception {
        File safeJar = jar("safe.jar", null, "A.class");
        String input = "\"" + safeJar.getPath() + "\"" + File.pathSeparator + safeJar.getPath();

        assertEquals(safeJar.getAbsolutePath(), CompilerClasspath.sanitize(input));
    }
}
