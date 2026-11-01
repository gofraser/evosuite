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
package org.evosuite;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ratchet on the client package layering. A package may depend on packages in its own or a lower
 * layer; every dependency on a higher layer must be listed in {@code package-back-edges.txt}. A new
 * upward dependency fails the test, and so does a listed one that has gone, so the list only shrinks.
 */
public class PackageLayeringTest {

    private static final Path SOURCES = Paths.get("src/main/java/org/evosuite");

    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?org\\.evosuite\\.(\\w+)",
            Pattern.MULTILINE);

    private static final String ROOT = "(root)";

    private static final Map<String, Integer> LAYERS = new HashMap<>();

    static {
        layer(0, "utils", "classpath", "annotations", "Properties");
        layer(1, "instrumentation", "graphs");
        layer(2, "setup", "seeding");
        layer(3, "testcase", "assertion", "contracts", "testcarver", "testparser");
        layer(4, "ga", "testsuite", "coverage", "novelty", "lm");
        layer(5, "symbolic", "dse", "junit");
        layer(6, "llm");
        layer(7, "strategy", "statistics", "result", "rmi", ROOT);
    }

    private static void layer(int level, String... packages) {
        for (String name : packages) {
            LAYERS.put(name, level);
        }
    }

    /**
     * The first package segment below {@code org.evosuite}; a class directly in {@code org.evosuite}
     * is the root, except {@code Properties}, which sits at the bottom.
     */
    private static String topPackage(String segment) {
        if (Character.isUpperCase(segment.charAt(0))) {
            return segment.equals("Properties") ? "Properties" : ROOT;
        }
        return segment;
    }

    private static int layerOf(String topPackage) {
        Integer level = LAYERS.get(topPackage);
        if (level == null) {
            throw new AssertionError("Package org.evosuite." + topPackage + " has no layer in "
                    + PackageLayeringTest.class.getSimpleName());
        }
        return level;
    }

    /**
     * True for a package of another module, such as {@code org.evosuite.runtime}.
     */
    private static boolean isOtherModule(String segment) {
        return Character.isLowerCase(segment.charAt(0)) && !Files.isDirectory(SOURCES.resolve(segment));
    }

    private static Set<String> upwardDependencies() throws IOException {
        Set<String> edges = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
                Path relative = SOURCES.relativize(file);
                String from = relative.getNameCount() == 1
                        ? topPackage(relative.toString().replace(".java", ""))
                        : relative.getName(0).toString();
                Matcher matcher = IMPORT.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                while (matcher.find()) {
                    String to = topPackage(matcher.group(1));
                    if (isOtherModule(matcher.group(1))) {
                        continue;
                    }
                    if (!to.equals(from) && layerOf(to) > layerOf(from)) {
                        edges.add(from + " -> " + to);
                    }
                }
            }
        }
        return edges;
    }

    private static Set<String> allowed() throws IOException {
        try (InputStream in = PackageLayeringTest.class.getResourceAsStream("package-back-edges.txt")) {
            List<String> lines = Arrays.asList(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"));
            return lines.stream().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    @Test
    public void noNewUpwardPackageDependencies() throws IOException {
        Set<String> actual = upwardDependencies();
        Set<String> listed = allowed();
        Set<String> added = new TreeSet<>(actual);
        added.removeAll(listed);
        Set<String> gone = new TreeSet<>(listed);
        gone.removeAll(actual);
        assertTrue(added.isEmpty(), "New upward package dependencies (depend downwards instead, or move the class): "
                + added);
        assertTrue(gone.isEmpty(), "Upward dependencies that no longer exist; remove them from "
                + "package-back-edges.txt: " + gone);
    }
}
