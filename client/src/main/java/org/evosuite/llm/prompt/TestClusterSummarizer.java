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
package org.evosuite.llm.prompt;

import org.evosuite.setup.TestCluster;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Produces concise prompt context from the current test cluster.
 */
public class TestClusterSummarizer {

    private static final Logger logger = LoggerFactory.getLogger(TestClusterSummarizer.class);

    private static final Set<String> EXCLUDED_TYPES = new HashSet<>();
    static {
        EXCLUDED_TYPES.add("java.lang.Object");
        EXCLUDED_TYPES.add("java.lang.String");
        EXCLUDED_TYPES.add("java.lang.Boolean");
        EXCLUDED_TYPES.add("java.lang.Byte");
        EXCLUDED_TYPES.add("java.lang.Character");
        EXCLUDED_TYPES.add("java.lang.Short");
        EXCLUDED_TYPES.add("java.lang.Integer");
        EXCLUDED_TYPES.add("java.lang.Long");
        EXCLUDED_TYPES.add("java.lang.Float");
        EXCLUDED_TYPES.add("java.lang.Double");
        EXCLUDED_TYPES.add("java.lang.Void");
        EXCLUDED_TYPES.add("java.lang.Number");
    }

    /** Summarizes available classes and generators from the given cluster. */
    public String summarize(TestCluster cluster) {
        if (cluster == null) {
            return "No test cluster available.";
        }

        Set<Class<?>> classes = collectAvailableClasses(cluster);
        StringBuilder builder = new StringBuilder();
        builder.append("Available classes:").append(System.lineSeparator());
        int shown = 0;
        for (Class<?> clazz : classes) {
            builder.append("- ").append(clazz.getName()).append(System.lineSeparator());
            shown++;
            if (shown >= 20) {
                builder.append("- ... (truncated)").append(System.lineSeparator());
                break;
            }
        }
        if (shown == 0) {
            builder.append("- (none discovered)").append(System.lineSeparator());
        }
        return builder.toString();
    }

    /** Result of {@link #summarizeDependencies} carrying the text and truncation metadata. */
    public static class DependencySummaryResult {
        private final String text;
        private final boolean truncated;
        private final int totalCharsBeforeTruncation;

        DependencySummaryResult(String text, boolean truncated, int totalCharsBeforeTruncation) {
            this.text = text;
            this.truncated = truncated;
            this.totalCharsBeforeTruncation = totalCharsBeforeTruncation;
        }

        public String getText() { return text; }
        public boolean isTruncated() { return truncated; }
        public int getTotalCharsBeforeTruncation() { return totalCharsBeforeTruncation; }
    }

    /**
     * Produces a compact dependency summary showing available types,
     * their constructors (with parameter types), and enum constants.
     *
     * @param cluster         the test cluster
     * @param targetClassName fully qualified name of the CUT (excluded from output)
     * @param maxChars        maximum characters for the summary (0 = unlimited)
     * @return result containing the formatted summary and truncation metadata
     */
    public DependencySummaryResult summarizeDependencies(TestCluster cluster, String targetClassName, int maxChars) {
        if (cluster == null) {
            return new DependencySummaryResult("", false, 0);
        }

        // Collect direct dependency type names from CUT constructors
        Set<String> directDependencyNames = collectDirectDependencies(cluster, targetClassName);

        // Collect all types from the generators map
        Map<GenericClass<?>, Set<GenericAccessibleObject<?>>> generatorsByType = cluster.getGeneratorsByType();

        // Build per-type summaries, partitioned into direct deps vs others
        List<TypeSummary> directDeps = new ArrayList<>();
        List<TypeSummary> otherDeps = new ArrayList<>();

        for (Map.Entry<GenericClass<?>, Set<GenericAccessibleObject<?>>> entry : generatorsByType.entrySet()) {
            GenericClass<?> genClass = entry.getKey();
            if (genClass == null || genClass.getRawClass() == null) {
                continue;
            }
            Class<?> rawClass = genClass.getRawClass();
            String className = rawClass.getName();

            // Exclude CUT, primitives/wrappers, and java.lang basics
            if (className.equals(targetClassName)) {
                continue;
            }
            if (rawClass.isPrimitive() || EXCLUDED_TYPES.contains(className)) {
                continue;
            }

            String line = formatTypeSummary(rawClass);
            if (line == null || line.isEmpty()) {
                continue;
            }

            TypeSummary summary = new TypeSummary(rawClass.getSimpleName(), line);
            if (directDependencyNames.contains(className)) {
                directDeps.add(summary);
            } else {
                otherDeps.add(summary);
            }
        }

        // Sort each partition alphabetically
        directDeps.sort((a, b) -> a.simpleName.compareToIgnoreCase(b.simpleName));
        otherDeps.sort((a, b) -> a.simpleName.compareToIgnoreCase(b.simpleName));

        // Compute total chars without budget for metadata
        List<TypeSummary> allDeps = new ArrayList<>(directDeps);
        allDeps.addAll(otherDeps);
        int totalChars = 0;
        for (TypeSummary ts : allDeps) {
            totalChars += ts.line.length() + 1; // +1 for newline
        }

        // Build output respecting char budget
        StringBuilder sb = new StringBuilder();
        boolean budgetExceeded = false;

        for (TypeSummary ts : directDeps) {
            if (maxChars > 0 && sb.length() + ts.line.length() + 1 > maxChars) {
                budgetExceeded = true;
                break;
            }
            sb.append(ts.line).append('\n');
        }

        if (!budgetExceeded) {
            for (TypeSummary ts : otherDeps) {
                if (maxChars > 0 && sb.length() + ts.line.length() + 1 > maxChars) {
                    budgetExceeded = true;
                    break;
                }
                sb.append(ts.line).append('\n');
            }
        }

        if (budgetExceeded) {
            sb.append("  ... (truncated)\n");
        }

        return new DependencySummaryResult(sb.toString().trim(), budgetExceeded, totalChars);
    }

    /**
     * Formats a single type as a summary line.
     * For enums: "  EnumName { VALUE_A, VALUE_B, VALUE_C }"
     * For classes: one line per constructor "  ClassName(ParamType1, ParamType2)"
     */
    private String formatTypeSummary(Class<?> rawClass) {
        StringBuilder sb = new StringBuilder();
        if (rawClass.isEnum()) {
            Object[] constants = rawClass.getEnumConstants();
            if (constants != null && constants.length > 0) {
                sb.append("  ").append(rawClass.getSimpleName()).append(" { ");
                for (int i = 0; i < constants.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(((Enum<?>) constants[i]).name());
                }
                sb.append(" }");
            }
        } else {
            Constructor<?>[] constructors = rawClass.getConstructors();
            for (Constructor<?> ctor : constructors) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("  ").append(rawClass.getSimpleName())
                        .append('(').append(parameterList(ctor.getParameterTypes())).append(')');
            }
        }
        return sb.toString();
    }

    /**
     * Collects fully qualified names of types that appear as constructor parameters
     * of the CUT (direct dependencies).
     */
    private Set<String> collectDirectDependencies(TestCluster cluster, String targetClassName) {
        Set<String> deps = new HashSet<>();
        try {
            Class<?> cutClass = Class.forName(targetClassName, false,
                    Thread.currentThread().getContextClassLoader());
            for (Constructor<?> ctor : cutClass.getConstructors()) {
                for (Class<?> paramType : ctor.getParameterTypes()) {
                    if (!paramType.isPrimitive() && !EXCLUDED_TYPES.contains(paramType.getName())) {
                        deps.add(paramType.getName());
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            logger.debug("Could not load CUT class for dependency analysis: {}", targetClassName);
        }
        return deps;
    }

    /** Summarizes the constructor and public method signatures of the given class. */
    public String summarizeClass(GenericClass<?> clazz) {
        if (clazz == null || clazz.getRawClass() == null) {
            return "Unknown class";
        }

        Class<?> raw = clazz.getRawClass();
        StringBuilder builder = new StringBuilder();
        builder.append("Class: ").append(raw.getName()).append(System.lineSeparator());

        builder.append("Constructors:").append(System.lineSeparator());
        for (Constructor<?> constructor : raw.getConstructors()) {
            builder.append("  ").append(constructor.getName())
                    .append('(').append(parameterList(constructor.getParameterTypes())).append(')')
                    .append(System.lineSeparator());
        }

        builder.append("Public methods:").append(System.lineSeparator());
        for (Method method : raw.getMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.getDeclaringClass() == Object.class) {
                continue;
            }
            builder.append("  ").append(method.getReturnType().getSimpleName()).append(' ')
                    .append(method.getName())
                    .append('(').append(parameterList(method.getParameterTypes())).append(')')
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }

    /** Returns a string listing the constructors that can generate instances of the given type. */
    public String summarizeGenerators(GenericClass<?> type) {
        if (type == null || type.getRawClass() == null) {
            return "";
        }
        Class<?> raw = type.getRawClass();
        List<String> lines = new ArrayList<>();
        for (Constructor<?> constructor : raw.getConstructors()) {
            lines.add(raw.getSimpleName() + "(" + parameterList(constructor.getParameterTypes()) + ")");
        }
        return String.join(System.lineSeparator(), lines);
    }

    private String parameterList(Class<?>[] parameterTypes) {
        List<String> names = new ArrayList<>();
        for (Class<?> parameterType : parameterTypes) {
            names.add(parameterType.getSimpleName());
        }
        return String.join(", ", names);
    }

    private Set<Class<?>> collectAvailableClasses(TestCluster cluster) {
        Set<Class<?>> classes = new LinkedHashSet<>();
        try {
            classes.addAll(cluster.getAnalyzedClasses());
        } catch (Exception e) {
            logger.debug("Failed to collect analyzed classes from test cluster", e);
        }
        addFromCalls(classes, cluster.getGenerators());
        addFromCalls(classes, cluster.getModifiers());
        addFromCalls(classes, cluster.getTestCalls());
        return classes;
    }

    private void addFromCalls(Set<Class<?>> classes, Collection<GenericAccessibleObject<?>> calls) {
        if (calls == null) {
            return;
        }
        for (GenericAccessibleObject<?> call : calls) {
            if (call == null) {
                continue;
            }
            try {
                GenericClass<?> owner = call.getOwnerClass();
                if (owner != null && owner.getRawClass() != null) {
                    classes.add(owner.getRawClass());
                }
            } catch (Exception e) {
                logger.debug("Failed to collect owner class from cluster call {}", call, e);
            }
            try {
                GenericClass<?> generated = call.getGeneratedClass();
                if (generated != null && generated.getRawClass() != null) {
                    classes.add(generated.getRawClass());
                }
            } catch (Exception e) {
                logger.debug("Failed to collect generated class from cluster call {}", call, e);
            }
        }
    }

    private static class TypeSummary {
        final String simpleName;
        final String line;

        TypeSummary(String simpleName, String line) {
            this.simpleName = simpleName;
            this.line = line;
        }
    }
}
