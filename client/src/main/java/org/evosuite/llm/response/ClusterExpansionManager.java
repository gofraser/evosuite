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

    public ClusterExpansionManager() {
        this(TestGenerationContext.getInstance().getClassLoaderForSUT());
    }

    public ClusterExpansionManager(ClassLoader sutClassLoader) {
        this.sutClassLoader = sutClassLoader;
    }

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
