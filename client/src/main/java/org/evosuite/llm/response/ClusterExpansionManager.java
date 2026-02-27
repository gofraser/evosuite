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
package org.evosuite.llm.response;

import org.evosuite.TestGenerationContext;
import org.evosuite.setup.TestCluster;
import org.evosuite.setup.TestClusterGenerator;
import org.evosuite.setup.TestUsageChecker;
import org.evosuite.testparser.ParseDiagnostic;
import org.evosuite.testparser.ParseResult;

import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles best-effort, serialized expansion of TestCluster for unresolved symbols.
 */
public class ClusterExpansionManager {

    private static final Pattern FQCN_PATTERN = Pattern.compile("\\b([a-zA-Z_][\\w$]*(?:\\.[a-zA-Z_][\\w$]*)+)\\b");
    private static final Pattern SIMPLE_CLASS_PATTERN = Pattern.compile("\\b([A-Z][\\w$]*)\\b");

    private final ClassLoader sutClassLoader;
    private final Lock expansionLock = new ReentrantLock();
    private volatile List<String> lastExpandedClasses = Collections.emptyList();

    /** Creates an expansion manager using the SUT class loader from the current test generation context. */
    public ClusterExpansionManager() {
        this(TestGenerationContext.getInstance().getClassLoaderForSUT());
    }

    public ClusterExpansionManager(ClassLoader sutClassLoader) {
        this.sutClassLoader = sutClassLoader;
    }

    /** Attempts to expand the test cluster with classes inferred from unresolved symbols in the parse results. */
    public boolean tryExpandFrom(List<ParseResult> parseResults) {
        Set<String> unresolved = extractUnresolvedSymbols(parseResults);
        if (unresolved.isEmpty()) {
            return false;
        }

        Set<Class<?>> candidates = resolveCandidates(unresolved);
        if (candidates.isEmpty()) {
            return false;
        }

        TestClusterGenerator generator = TestGenerationContext.getInstance().getTestClusterGenerator();
        if (generator == null) {
            return false;
        }

        expansionLock.lock();
        try {
            generator.addNewDependencies(candidates);
            List<String> expanded = new ArrayList<>();
            for (Class<?> candidate : candidates) {
                expanded.add(candidate.getName());
            }
            lastExpandedClasses = Collections.unmodifiableList(expanded);
            return true;
        } finally {
            expansionLock.unlock();
        }
    }

    /** Extracts unresolved symbol names from the diagnostics of the given parse results. */
    public Set<String> extractUnresolvedSymbols(List<ParseResult> parseResults) {
        Set<String> unresolved = new LinkedHashSet<>();
        if (parseResults == null) {
            return unresolved;
        }

        for (ParseResult parseResult : parseResults) {
            for (ParseDiagnostic diagnostic : parseResult.getDiagnostics()) {
                if (diagnostic.getSeverity() != ParseDiagnostic.Severity.ERROR) {
                    continue;
                }
                collectSymbols(diagnostic.getMessage(), unresolved);
                collectSymbols(diagnostic.getSourceSnippet(), unresolved);
            }
        }

        return unresolved;
    }

    public List<String> getLastExpandedClasses() {
        return lastExpandedClasses;
    }

    private Set<Class<?>> resolveCandidates(Set<String> unresolved) {
        Set<Class<?>> candidates = new LinkedHashSet<>();
        for (String symbol : unresolved) {
            Class<?> clazz = resolveClass(symbol);
            if (clazz != null && TestUsageChecker.canUse(clazz)) {
                candidates.add(clazz);
            }
        }
        return candidates;
    }

    private Class<?> resolveClass(String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) {
            return null;
        }

        String trimmed = symbol.trim();
        if (trimmed.contains(".")) {
            return loadClass(trimmed);
        }

        Class<?> fromSimple = loadClass(trimmed);
        if (fromSimple != null) {
            return fromSimple;
        }

        Class<?> fromJavaLang = loadClass("java.lang." + trimmed);
        if (fromJavaLang != null) {
            return fromJavaLang;
        }

        Class<?> fromJavaUtil = loadClass("java.util." + trimmed);
        if (fromJavaUtil != null) {
            return fromJavaUtil;
        }

        Class<?> fromJavaIo = loadClass("java.io." + trimmed);
        if (fromJavaIo != null) {
            return fromJavaIo;
        }

        for (Class<?> analyzed : TestCluster.getInstance().getAnalyzedClasses()) {
            if (analyzed.getSimpleName().equals(trimmed)) {
                return analyzed;
            }
        }

        return null;
    }

    private Class<?> loadClass(String className) {
        try {
            return sutClassLoader.loadClass(className);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private void collectSymbols(String source, Set<String> output) {
        if (source == null || source.isEmpty()) {
            return;
        }

        Matcher fqcn = FQCN_PATTERN.matcher(source);
        while (fqcn.find()) {
            output.add(fqcn.group(1));
        }

        Matcher simple = SIMPLE_CLASS_PATTERN.matcher(source);
        while (simple.find()) {
            output.add(simple.group(1));
        }
    }
}
