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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the classpath javac compiles generated sources against and repairs jars it cannot
 * read: jars whose manifest Class-Path holds entries that are not valid URIs (or use
 * backslashes), and jars with entry names containing "." or ".." segments. A repaired jar is
 * replaced by a temporary directory holding its extracted content.
 */
public final class CompilerClasspath {

    private static final Logger logger = LoggerFactory.getLogger(CompilerClasspath.class);

    private static final Pattern UNREADABLE_CLASSPATH_ENTRY_PATTERN =
            Pattern.compile("error:\\s+error\\s+reading\\s+([^;\\r\\n]+);\\s+java\\.net\\.URISyntaxException",
                    Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> SANITIZED_SNIPPET_ENTRIES = new ConcurrentHashMap<>();
    private static final Map<String, String> SANITIZED_GENERATED_TEST_ENTRIES = new ConcurrentHashMap<>();

    /** Which jars are repaired, and how, before the classpath reaches javac. */
    public enum Policy {
        /** Expands manifest-only jars and replaces jars with a malformed manifest Class-Path. */
        SNIPPET,
        /** Replaces jars with a malformed manifest Class-Path or entries with dot segments. */
        GENERATED_TESTS
    }

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

    public static String forClassLoader(ClassLoader parentLoader) {
        Set<String> entries = new LinkedHashSet<>();
        appendNormalizedClasspathEntries(Properties.CP, entries);
        appendNormalizedClasspathEntries(System.getProperty("java.class.path"), entries);
        appendClassLoaderEntries(parentLoader, entries);
        appendClassLoaderEntries(Thread.currentThread().getContextClassLoader(), entries);
        return String.join(File.pathSeparator,
                sanitizeClasspathEntriesForCompiler(new ArrayList<>(entries), Collections.emptySet()));
    }

    private static void appendNormalizedClasspathEntries(String classpath, Set<String> entries) {
        if (classpath == null || classpath.trim().isEmpty()) {
            return;
        }
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
            String normalized = normalizeClasspathEntry(entry);
            if (normalized != null && !normalized.isEmpty()) {
                entries.add(normalized);
            }
        }
    }

    static String normalizeClasspathEntry(String entry) {
        if (entry == null) {
            return null;
        }
        String normalized = entry.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() >= 2
                && ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("'") && normalized.endsWith("'")))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        while (normalized.endsWith("\\")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.startsWith("file:")) {
            try {
                return new File(new URL(normalized).toURI()).getAbsolutePath();
            } catch (Exception ignored) {
                // Best effort fallback for malformed file: URL-like entries.
                if (normalized.startsWith("file:/")) {
                    normalized = normalized.substring("file:".length());
                }
            }
        }
        return normalized;
    }

    /**
     * Repairs the entries of a classpath under the policy, keeping their order.
     */
    public static String sanitize(String classpath, Policy policy) {
        if (classpath == null || classpath.trim().isEmpty()) {
            return classpath;
        }
        String[] entries = classpath.split(File.pathSeparator);
        if (policy == Policy.SNIPPET) {
            return String.join(File.pathSeparator,
                    sanitizeClasspathEntriesForCompiler(new ArrayList<>(Arrays.asList(entries)),
                            Collections.emptySet()));
        }
        List<String> sanitizedEntries = new ArrayList<>(entries.length);
        for (String entry : entries) {
            sanitizedEntries.add(sanitizeGeneratedTestEntry(entry));
        }
        return String.join(File.pathSeparator, sanitizedEntries);
    }

    private static String sanitizeGeneratedTestEntry(String entry) {
        if (entry == null || entry.isEmpty()) {
            return entry;
        }
        File jarFile = asExistingJarFile(entry);
        if (jarFile == null) {
            return entry;
        }
        String absolutePath = jarFile.getAbsolutePath();
        String cached = SANITIZED_GENERATED_TEST_ENTRIES.get(absolutePath);
        if (cached != null) {
            return cached;
        }
        if (!hasDotSegmentEntry(jarFile) && !hasMalformedManifestClassPath(jarFile)) {
            SANITIZED_GENERATED_TEST_ENTRIES.put(absolutePath, absolutePath);
            return absolutePath;
        }

        // Dot-segment names are dropped because javac cannot open such entries.
        String sanitized = extractJar(jarFile, "evosuite-compiler-cp-", CompilerClasspath::hasDotSegment);
        SANITIZED_GENERATED_TEST_ENTRIES.put(absolutePath, sanitized);
        if (!absolutePath.equals(sanitized)) {
            logger.warn("Using sanitized compiler classpath entry for malformed jar {} -> {}",
                    absolutePath, sanitized);
        }
        return sanitized;
    }

    /**
     * Removes unreadable classpath entries identified by compiler diagnostics.
     */
    public static String repairUnreadableEntries(String classpath, String diagnostics) {
        if (classpath == null || classpath.isEmpty() || diagnostics == null || diagnostics.isEmpty()) {
            return null;
        }

        LinkedHashSet<String> badBasenames = new LinkedHashSet<>();
        Matcher matcher = UNREADABLE_CLASSPATH_ENTRY_PATTERN.matcher(diagnostics);
        while (matcher.find()) {
            String entry = normalizeClasspathEntry(matcher.group(1));
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            String baseName = new File(entry).getName();
            if (!baseName.isEmpty()) {
                badBasenames.add(baseName);
            }
        }
        if (badBasenames.isEmpty()) {
            return null;
        }

        String repaired = String.join(File.pathSeparator,
                sanitizeClasspathEntriesForCompiler(
                        new ArrayList<>(java.util.Arrays.asList(classpath.split(Pattern.quote(File.pathSeparator)))),
                        badBasenames));
        if (repaired.equals(classpath)) {
            return null;
        }
        return repaired;
    }

    private static List<String> sanitizeClasspathEntriesForCompiler(List<String> rawEntries, Set<String> seedBadBasenames) {
        List<String> normalizedEntries = new ArrayList<>();
        LinkedHashSet<String> seenEntries = new LinkedHashSet<>();
        LinkedHashSet<String> problematicBasenames = new LinkedHashSet<>();
        if (seedBadBasenames != null) {
            problematicBasenames.addAll(seedBadBasenames);
        }
        LinkedHashSet<String> forcedSanitizationEntries = new LinkedHashSet<>();

        for (String rawEntry : rawEntries) {
            String normalizedEntry = normalizeClasspathEntry(rawEntry);
            if (normalizedEntry == null || normalizedEntry.isEmpty()) {
                continue;
            }
            File jarFile = asExistingJarFile(normalizedEntry);
            if (jarFile != null) {
                List<String> expandedEntries = expandManifestOnlyClasspathJar(jarFile);
                if (!expandedEntries.isEmpty()) {
                    for (String expandedEntry : expandedEntries) {
                        if (expandedEntry != null && !expandedEntry.isEmpty() && seenEntries.add(expandedEntry)) {
                            normalizedEntries.add(expandedEntry);
                        }
                    }
                    continue;
                }
            }

            if (seenEntries.add(normalizedEntry)) {
                normalizedEntries.add(normalizedEntry);
            }

            if (jarFile == null) {
                continue;
            }

            String baseName = jarFile.getName();
            boolean seedMatch = !baseName.isEmpty() && problematicBasenames.contains(baseName);
            if (seedMatch || CompilerClasspath.hasMalformedManifestClassPath(jarFile)) {
                forcedSanitizationEntries.add(jarFile.getAbsolutePath());
                if (!baseName.isEmpty()) {
                    problematicBasenames.add(baseName);
                }
            }
        }

        boolean changed;
        do {
            changed = false;
            for (String normalizedEntry : normalizedEntries) {
                File jarFile = asExistingJarFile(normalizedEntry);
                if (jarFile == null) {
                    continue;
                }

                String absolutePath = jarFile.getAbsolutePath();
                if (forcedSanitizationEntries.contains(absolutePath)) {
                    continue;
                }

                if (referencesManifestClassPathEntry(jarFile, problematicBasenames)) {
                    forcedSanitizationEntries.add(absolutePath);
                    String baseName = jarFile.getName();
                    if (!baseName.isEmpty()) {
                        problematicBasenames.add(baseName);
                    }
                    changed = true;
                }
            }
        } while (changed);

        List<String> sanitizedEntries = new ArrayList<>(normalizedEntries.size());
        for (String normalizedEntry : normalizedEntries) {
            File jarFile = asExistingJarFile(normalizedEntry);
            boolean forceSanitization = jarFile != null
                    && forcedSanitizationEntries.contains(jarFile.getAbsolutePath());
            sanitizedEntries.add(sanitizeClasspathEntryForCompiler(normalizedEntry, forceSanitization));
        }
        return sanitizedEntries;
    }

    private static List<String> expandManifestOnlyClasspathJar(File jarFile) {
        if (jarFile == null
                || !isManifestOnlyClasspathJar(jarFile)
                || CompilerClasspath.hasMalformedManifestClassPath(jarFile)) {
            return Collections.emptyList();
        }

        List<String> expandedEntries = readManifestClassPathEntries(jarFile);
        if (expandedEntries.isEmpty()) {
            return Collections.emptyList();
        }
        return expandedEntries;
    }

    private static File asExistingJarFile(String entry) {
        if (entry == null || entry.isEmpty() || !entry.endsWith(".jar")) {
            return null;
        }
        File jarFile = new File(entry);
        return jarFile.isFile() ? jarFile : null;
    }

    private static boolean isManifestOnlyClasspathJar(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            java.util.jar.Manifest manifest = jar.getManifest();
            if (manifest == null) {
                return false;
            }
            Attributes attributes = manifest.getMainAttributes();
            if (attributes == null) {
                return false;
            }
            String classPath = attributes.getValue(Attributes.Name.CLASS_PATH);
            if (classPath == null || classPath.trim().isEmpty()) {
                return false;
            }

            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                if (!"META-INF/MANIFEST.MF".equalsIgnoreCase(entry.getName())) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            logger.debug("Could not inspect jar {} for manifest-only classpath expansion: {}",
                    jarFile,
                    e.getMessage());
        }
        return false;
    }

    private static List<String> readManifestClassPathEntries(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            java.util.jar.Manifest manifest = jar.getManifest();
            if (manifest == null) {
                return Collections.emptyList();
            }
            Attributes attributes = manifest.getMainAttributes();
            if (attributes == null) {
                return Collections.emptyList();
            }
            String classPath = attributes.getValue(Attributes.Name.CLASS_PATH);
            if (classPath == null || classPath.trim().isEmpty()) {
                return Collections.emptyList();
            }

            List<String> expandedEntries = new ArrayList<>();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            String[] manifestEntries = classPath.trim().split("\\s+");
            for (String manifestEntry : manifestEntries) {
                String resolved = resolveManifestClassPathEntry(jarFile, manifestEntry);
                if (resolved != null && !resolved.isEmpty() && seen.add(resolved)) {
                    expandedEntries.add(resolved);
                }
            }
            return expandedEntries;
        } catch (IOException e) {
            logger.debug("Could not read manifest classpath entries from {}: {}",
                    jarFile,
                    e.getMessage());
        }
        return Collections.emptyList();
    }

    private static String resolveManifestClassPathEntry(File jarFile, String manifestEntry) {
        if (jarFile == null || manifestEntry == null || manifestEntry.trim().isEmpty()) {
            return null;
        }

        String normalizedEntry = manifestEntry.trim();
        File directFile = new File(normalizedEntry);
        if (directFile.isAbsolute()) {
            return normalizeClasspathEntry(directFile.getAbsolutePath());
        }

        try {
            URI resolved = jarFile.getParentFile().toURI().resolve(normalizedEntry);
            if (resolved.getScheme() == null || "file".equalsIgnoreCase(resolved.getScheme())) {
                return normalizeClasspathEntry(new File(resolved).getAbsolutePath());
            }
            return normalizeClasspathEntry(resolved.toString());
        } catch (IllegalArgumentException ignored) {
            // Fall through to path-style resolution for malformed relative entries.
        }

        return normalizeClasspathEntry(new File(jarFile.getParentFile(), normalizedEntry).getAbsolutePath());
    }

    private static boolean referencesManifestClassPathEntry(File jarFile, Set<String> badBasenames) {
        if (jarFile == null || badBasenames == null || badBasenames.isEmpty()) {
            return false;
        }
        try (JarFile jar = new JarFile(jarFile)) {
            java.util.jar.Manifest manifest = jar.getManifest();
            if (manifest == null) {
                return false;
            }
            Attributes attributes = manifest.getMainAttributes();
            if (attributes == null) {
                return false;
            }
            String classPath = attributes.getValue(Attributes.Name.CLASS_PATH);
            if (classPath == null || classPath.trim().isEmpty()) {
                return false;
            }

            String[] entries = classPath.trim().split("\\s+");
            for (String manifestEntry : entries) {
                String baseName = manifestEntryBaseName(manifestEntry);
                if (!baseName.isEmpty() && badBasenames.contains(baseName)) {
                    return true;
                }
            }
        } catch (IOException e) {
            logger.debug("Could not inspect jar manifest {} for snippet compiler dependency sanitization: {}",
                    jarFile,
                    e.getMessage());
        }
        return false;
    }

    private static String manifestEntryBaseName(String manifestEntry) {
        if (manifestEntry == null) {
            return "";
        }

        String normalized = manifestEntry.trim();
        while (normalized.endsWith("\\") || normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.isEmpty()) {
            return "";
        }

        try {
            URI uri = new URI(normalized);
            if (uri.getPath() != null && !uri.getPath().isEmpty()) {
                normalized = uri.getPath();
            }
        } catch (URISyntaxException ignored) {
            // Fall back to path-style parsing for malformed or relative manifest entries.
        }

        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        int fragmentIndex = normalized.indexOf('#');
        if (fragmentIndex >= 0) {
            normalized = normalized.substring(0, fragmentIndex);
        }
        normalized = normalized.replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        if (slashIndex >= 0) {
            normalized = normalized.substring(slashIndex + 1);
        }
        return normalized.trim();
    }

    private static String sanitizeClasspathEntryForCompiler(String entry, boolean forceSanitization) {
        if (entry == null || entry.isEmpty()) {
            return entry;
        }
        File jarFile = asExistingJarFile(entry);
        if (jarFile == null) {
            return entry;
        }

        String absolutePath = jarFile.getAbsolutePath();
        String cached = SANITIZED_SNIPPET_ENTRIES.get(absolutePath);
        if (cached != null && (!forceSanitization || !absolutePath.equals(cached))) {
            return cached;
        }

        if (!forceSanitization && !CompilerClasspath.hasMalformedManifestClassPath(jarFile)) {
            SANITIZED_SNIPPET_ENTRIES.put(absolutePath, absolutePath);
            return absolutePath;
        }

        // The manifest is dropped so javac does not follow its malformed Class-Path.
        String sanitized = CompilerClasspath.extractJar(jarFile, "evosuite-snippet-compiler-cp-",
                "META-INF/MANIFEST.MF"::equalsIgnoreCase);
        SANITIZED_SNIPPET_ENTRIES.put(absolutePath, sanitized);
        if (!absolutePath.equals(sanitized)) {
            logger.warn("Using sanitized snippet compiler classpath entry {} -> {}",
                    absolutePath, sanitized);
        }
        return sanitized;
    }

    private static void appendClassLoaderEntries(ClassLoader loader, Set<String> entries) {
        ClassLoader current = loader;
        while (current != null) {
            if (current instanceof URLClassLoader) {
                for (URL url : ((URLClassLoader) current).getURLs()) {
                    if (!"file".equalsIgnoreCase(url.getProtocol())) {
                        continue;
                    }
                    try {
                        String path = new File(url.toURI()).getAbsolutePath();
                        if (!path.isEmpty()) {
                            entries.add(path);
                        }
                    } catch (Exception ignored) {
                        // Best effort only.
                    }
                }
            }
            current = current.getParent();
        }
    }
}
