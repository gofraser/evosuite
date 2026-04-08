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
package org.evosuite.llm.prompt;

import org.evosuite.setup.TestCluster;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * Result of {@link #summarizeDependencies} carrying the text and truncation metadata.
     */
    public static class DependencySummaryResult {
        private final String text;
        private final boolean truncated;
        private final int totalCharsBeforeTruncation;

        DependencySummaryResult(String text, boolean truncated, int totalCharsBeforeTruncation) {
            this.text = text;
            this.truncated = truncated;
            this.totalCharsBeforeTruncation = totalCharsBeforeTruncation;
        }

        public String getText() {
            return text;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public int getTotalCharsBeforeTruncation() {
            return totalCharsBeforeTruncation;
        }
    }

    /**
     * Produces a compact dependency summary showing available types,
     * their constructors, static factory methods, and modifier methods.
     * Types are sorted by relevance tier: direct CUT dependencies first,
     * then SUT types, then third-party types. JDK types are omitted entirely.
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

        // Collect direct dependency type names from CUT constructors and methods
        Set<String> directDependencyNames = collectDirectDependencies(cluster, targetClassName);

        // Derive the SUT package prefix (top two segments, e.g. "com.example")
        String sutPrefix = extractSutPrefix(targetClassName);

        // Collect all types from the generators map
        Map<GenericClass<?>, Set<GenericAccessibleObject<?>>> generatorsByType = cluster.getGeneratorsByType();

        // Build a modifier lookup: class name -> set of modifier methods from the cluster
        Map<String, Set<GenericAccessibleObject<?>>> modifiersByClassName = buildModifierLookup(cluster);

        // Build per-type summaries, partitioned into 3 tiers
        List<TypeSummary> tier1DirectDeps = new ArrayList<>();
        List<TypeSummary> tier2SutTypes = new ArrayList<>();
        List<TypeSummary> tier3ThirdParty = new ArrayList<>();

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
            // Omit JDK types entirely — LLMs already know their APIs
            if (isJdkType(className)) {
                continue;
            }

            Set<GenericAccessibleObject<?>> generators = entry.getValue();
            Set<GenericAccessibleObject<?>> modifiers = modifiersByClassName.getOrDefault(className,
                    Collections.emptySet());
            String line = formatTypeSummary(rawClass, generators, modifiers);
            if (line == null || line.isEmpty()) {
                continue;
            }

            TypeSummary summary = new TypeSummary(rawClass.getSimpleName(), line);
            int tier = classifyTier(className, directDependencyNames, sutPrefix);
            switch (tier) {
                case 1:
                    tier1DirectDeps.add(summary);
                    break;
                case 2:
                    tier2SutTypes.add(summary);
                    break;
                default:
                    tier3ThirdParty.add(summary);
                    break;
            }
        }

        // Sort each tier alphabetically
        tier1DirectDeps.sort((a, b) -> a.simpleName.compareToIgnoreCase(b.simpleName));
        tier2SutTypes.sort((a, b) -> a.simpleName.compareToIgnoreCase(b.simpleName));
        tier3ThirdParty.sort((a, b) -> a.simpleName.compareToIgnoreCase(b.simpleName));

        // Merge tiers in order
        List<TypeSummary> allDeps = new ArrayList<>(tier1DirectDeps);
        allDeps.addAll(tier2SutTypes);
        allDeps.addAll(tier3ThirdParty);

        // Compute total chars without budget for metadata
        int totalChars = 0;
        for (TypeSummary ts : allDeps) {
            totalChars += ts.line.length() + 1; // +1 for newline
        }

        // Build output respecting char budget
        StringBuilder sb = new StringBuilder();
        boolean budgetExceeded = false;

        for (TypeSummary ts : allDeps) {
            if (maxChars > 0 && sb.length() + ts.line.length() + 1 > maxChars) {
                budgetExceeded = true;
                break;
            }
            sb.append(ts.line).append('\n');
        }

        if (budgetExceeded) {
            sb.append("  ... (truncated)\n");
        }

        return new DependencySummaryResult(sb.toString().trim(), budgetExceeded, totalChars);
    }

    /**
     * Formats a single type as a summary block.
     * For enums: "  EnumName { VALUE_A, VALUE_B, VALUE_C }"
     * For classes: constructors, static factory methods from generators,
     * and public modifier methods from the cluster.
     */
    private String formatTypeSummary(Class<?> rawClass,
                                     Set<GenericAccessibleObject<?>> generators,
                                     Set<GenericAccessibleObject<?>> modifiers) {
        StringBuilder sb = new StringBuilder();
        // Type header so the LLM knows which class these members belong to
        sb.append("// ").append(rawClass.getSimpleName());
        if (rawClass.isEnum()) {
            Object[] constants = rawClass.getEnumConstants();
            if (constants != null && constants.length > 0) {
                sb.append(" { ");
                for (int i = 0; i < constants.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(((Enum<?>) constants[i]).name());
                }
                sb.append(" }");
            }
        } else {
            // Constructors from reflection (include package-private for same-package access)
            for (Constructor<?> ctor : rawClass.getDeclaredConstructors()) {
                if (Modifier.isPrivate(ctor.getModifiers())
                        || Modifier.isProtected(ctor.getModifiers())) {
                    continue;
                }
                sb.append('\n');
                sb.append("  ").append(rawClass.getSimpleName())
                        .append('(').append(genericParameterList(ctor.getGenericParameterTypes())).append(')');
            }

            // Static factory methods from generators (methods that aren't constructors)
            if (generators != null) {
                for (GenericAccessibleObject<?> gen : generators) {
                    if (gen.isMethod() && gen.isStatic()) {
                        Method method = ((java.lang.reflect.AccessibleObject) gen.getAccessibleObject())
                                instanceof Method
                                ? (Method) gen.getAccessibleObject() : null;
                        if (method != null) {
                            sb.append('\n');
                            sb.append("  static ").append(rawClass.getSimpleName())
                                    .append(' ').append(method.getName())
                                    .append('(').append(genericParameterList(method.getGenericParameterTypes()))
                                    .append(')');
                        }
                    }
                }
            }

            // Public modifier methods from the cluster
            if (modifiers != null) {
                Set<String> seen = new HashSet<>();
                for (GenericAccessibleObject<?> mod : modifiers) {
                    if (mod.isMethod()) {
                        Method method = mod.getAccessibleObject() instanceof Method
                                ? (Method) mod.getAccessibleObject() : null;
                        if (method != null && Modifier.isPublic(method.getModifiers())) {
                            String sig = method.getName() + "("
                                    + genericParameterList(method.getGenericParameterTypes()) + ")";
                            if (seen.add(sig)) {
                                sb.append('\n');
                                sb.append("  ").append(genericTypeName(method.getGenericReturnType()))
                                        .append(' ').append(sig);
                            }
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Collects fully qualified names of types that appear as constructor parameters,
     * public method parameters, or public method return types of the CUT (direct dependencies).
     */
    private Set<String> collectDirectDependencies(TestCluster cluster, String targetClassName) {
        Set<String> deps = new HashSet<>();
        try {
            Class<?> cutClass = Class.forName(targetClassName, false,
                    Thread.currentThread().getContextClassLoader());
            // Constructor parameter types
            for (Constructor<?> ctor : cutClass.getConstructors()) {
                for (Class<?> paramType : ctor.getParameterTypes()) {
                    addIfRelevant(deps, paramType);
                }
            }
            // Public method parameter and return types
            for (Method method : cutClass.getMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || method.getDeclaringClass() == Object.class) {
                    continue;
                }
                for (Class<?> paramType : method.getParameterTypes()) {
                    addIfRelevant(deps, paramType);
                }
                Class<?> returnType = method.getReturnType();
                addIfRelevant(deps, returnType);
            }
        } catch (ClassNotFoundException e) {
            logger.debug("Could not load CUT class for dependency analysis: {}", targetClassName);
        }
        return deps;
    }

    private void addIfRelevant(Set<String> deps, Class<?> type) {
        if (!type.isPrimitive() && !EXCLUDED_TYPES.contains(type.getName())) {
            deps.add(type.getName());
        }
    }

    /** Summarizes the class as a rich pseudo-Java declaration with fields, generics, and exceptions. */
    public String summarizeClass(GenericClass<?> clazz) {
        if (clazz == null || clazz.getRawClass() == null) {
            return "Unknown class";
        }

        Class<?> raw = clazz.getRawClass();
        StringBuilder b = new StringBuilder();

        // Class declaration line
        int mod = raw.getModifiers();
        if (Modifier.isPublic(mod)) {
            b.append("public ");
        }
        if (Modifier.isAbstract(mod) && !raw.isInterface() && !raw.isEnum()) {
            b.append("abstract ");
        }

        if (raw.isEnum()) {
            b.append("enum ");
        } else if (raw.isInterface()) {
            b.append("interface ");
        } else {
            b.append("class ");
        }
        b.append(raw.getSimpleName());

        // Type parameters
        TypeVariable<?>[] typeParams = raw.getTypeParameters();
        if (typeParams.length > 0) {
            b.append('<');
            for (int i = 0; i < typeParams.length; i++) {
                if (i > 0) {
                    b.append(", ");
                }
                b.append(typeParams[i].getName());
                Type[] bounds = typeParams[i].getBounds();
                if (bounds.length > 0 && !(bounds.length == 1 && bounds[0] == Object.class)) {
                    b.append(" extends ");
                    for (int j = 0; j < bounds.length; j++) {
                        if (j > 0) {
                            b.append(" & ");
                        }
                        b.append(genericTypeName(bounds[j]));
                    }
                }
            }
            b.append('>');
        }

        // Superclass (skip Object and Enum)
        if (!raw.isInterface() && !raw.isEnum()) {
            Type superType = raw.getGenericSuperclass();
            if (superType != null && superType != Object.class) {
                b.append(" extends ").append(genericTypeName(superType));
            }
        }

        // Interfaces (filter out EvoSuite instrumentation interfaces)
        List<Type> userIfaces = new ArrayList<>();
        for (Type iface : raw.getGenericInterfaces()) {
            String ifaceName = genericTypeName(iface);
            if (ifaceName.contains("evosuite") || ifaceName.contains("EvoSuite")) {
                continue;
            }
            userIfaces.add(iface);
        }
        if (!userIfaces.isEmpty()) {
            b.append(raw.isInterface() ? " extends " : " implements ");
            for (int i = 0; i < userIfaces.size(); i++) {
                if (i > 0) {
                    b.append(", ");
                }
                b.append(genericTypeName(userIfaces.get(i)));
            }
        }

        b.append(" {").append(System.lineSeparator());

        // Enum constants
        if (raw.isEnum()) {
            Object[] constants = raw.getEnumConstants();
            if (constants != null && constants.length > 0) {
                b.append("  ");
                for (int i = 0; i < constants.length; i++) {
                    if (i > 0) {
                        b.append(", ");
                    }
                    b.append(((Enum<?>) constants[i]).name());
                }
                b.append(System.lineSeparator());
            }
        }

        // Fields (skip private and protected — not accessible from test code)
        Field[] fields = raw.getDeclaredFields();
        boolean hasFields = false;
        for (Field field : fields) {
            if (field.isSynthetic()) {
                continue;
            }
            int fm = field.getModifiers();
            if (Modifier.isPrivate(fm) || Modifier.isProtected(fm)) {
                continue;
            }
            // Skip enum internal fields ($VALUES, etc.)
            if (raw.isEnum() && (field.getName().equals("$VALUES")
                    || (Modifier.isStatic(fm) && Modifier.isFinal(fm)
                        && field.getType() == raw))) {
                continue;
            }
            if (!hasFields) {
                b.append(System.lineSeparator()).append("  // Fields").append(System.lineSeparator());
                hasFields = true;
            }
            b.append("  ");
            if (Modifier.isPublic(fm)) {
                b.append("public ");
            }
            if (Modifier.isStatic(fm)) {
                b.append("static ");
            }
            if (Modifier.isFinal(fm)) {
                b.append("final ");
            }
            b.append(genericTypeName(field.getGenericType())).append(' ').append(field.getName());
            b.append(System.lineSeparator());
        }

        // Constructors (include public and package-private for same-package test access)
        boolean hasConstructorHeader = false;
        for (Constructor<?> ctor : raw.getDeclaredConstructors()) {
            int cm = ctor.getModifiers();
            if (Modifier.isPrivate(cm) || Modifier.isProtected(cm)) {
                continue;
            }
            if (ctor.isSynthetic()) {
                continue;
            }
            if (!hasConstructorHeader) {
                b.append(System.lineSeparator()).append("  // Constructors").append(System.lineSeparator());
                hasConstructorHeader = true;
            }
            b.append("  ").append(raw.getSimpleName())
                    .append('(').append(genericParameterList(ctor.getGenericParameterTypes())).append(')');
            b.append(throwsClause(ctor.getGenericExceptionTypes()));
            b.append(System.lineSeparator());
        }

        // Public methods
        Method[] methods = raw.getMethods();
        boolean hasMethodHeader = false;
        for (Method method : methods) {
            if (!Modifier.isPublic(method.getModifiers()) || method.getDeclaringClass() == Object.class) {
                continue;
            }
            if (!hasMethodHeader) {
                b.append(System.lineSeparator()).append("  // Public methods").append(System.lineSeparator());
                hasMethodHeader = true;
            }
            b.append("  ");
            int mm = method.getModifiers();
            if (Modifier.isStatic(mm)) {
                b.append("static ");
            }
            if (Modifier.isAbstract(mm)) {
                b.append("abstract ");
            }
            b.append(genericTypeName(method.getGenericReturnType())).append(' ')
                    .append(method.getName())
                    .append('(').append(genericParameterList(method.getGenericParameterTypes())).append(')');
            b.append(throwsClause(method.getGenericExceptionTypes()));
            b.append(System.lineSeparator());
        }

        b.append('}').append(System.lineSeparator());
        return b.toString();
    }

    /** Returns a string listing the constructors that can generate instances of the given type. */
    public String summarizeGenerators(GenericClass<?> type) {
        if (type == null || type.getRawClass() == null) {
            return "";
        }
        Class<?> raw = type.getRawClass();
        List<String> lines = new ArrayList<>();
        for (Constructor<?> constructor : raw.getConstructors()) {
            lines.add(raw.getSimpleName() + "(" + genericParameterList(constructor.getGenericParameterTypes()) + ")");
        }
        return String.join(System.lineSeparator(), lines);
    }

    /**
     * Returns a semicolon-separated summary of constructors and static factory methods
     * for the given type, using both reflection and the test cluster's generator map.
     */
    public String summarizeGeneratorsFromCluster(GenericClass<?> type, TestCluster cluster) {
        if (type == null || type.getRawClass() == null) {
            return "";
        }
        Class<?> raw = type.getRawClass();
        List<String> signatures = new ArrayList<>();

        // Public constructors from reflection
        for (Constructor<?> ctor : raw.getConstructors()) {
            signatures.add(raw.getSimpleName() + "(" + genericParameterList(ctor.getGenericParameterTypes()) + ")");
        }

        // Static factory methods from cluster generators
        if (cluster != null) {
            Map<GenericClass<?>, Set<GenericAccessibleObject<?>>> generatorsByType = cluster.getGeneratorsByType();
            Set<GenericAccessibleObject<?>> generators = generatorsByType.get(type);
            if (generators != null) {
                for (GenericAccessibleObject<?> gen : generators) {
                    if (gen.isMethod() && gen.isStatic()) {
                        if (gen.getAccessibleObject() instanceof Method) {
                            Method method = (Method) gen.getAccessibleObject();
                            signatures.add("static " + raw.getSimpleName() + " "
                                    + method.getName() + "("
                                    + genericParameterList(method.getGenericParameterTypes()) + ")");
                        }
                    }
                }
            }
        }

        return String.join("; ", signatures);
    }

    private String parameterList(Class<?>[] parameterTypes) {
        List<String> names = new ArrayList<>();
        for (Class<?> parameterType : parameterTypes) {
            names.add(parameterType.getSimpleName());
        }
        return String.join(", ", names);
    }

    /**
     * Returns the type name with {@code java.lang.} prefix stripped for readability.
     */
    static String genericTypeName(Type type) {
        if (type == null) {
            return "void";
        }
        String name = type.getTypeName();
        // Strip java.lang. prefix but not java.lang.reflect. or sub-packages
        return name.replaceAll("\\bjava\\.lang\\.(?![a-z])", "");
    }

    /** Joins generic type names into a comma-separated parameter list. */
    static String genericParameterList(Type[] types) {
        if (types == null || types.length == 0) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (Type t : types) {
            names.add(genericTypeName(t));
        }
        return String.join(", ", names);
    }

    /** Returns " throws X, Y" or empty string if no exceptions. */
    static String throwsClause(Type[] exceptionTypes) {
        if (exceptionTypes == null || exceptionTypes.length == 0) {
            return "";
        }
        return " throws " + genericParameterList(exceptionTypes);
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

    /** Returns true if the class name belongs to a JDK package (java.* or javax.*) or is a primitive array. */
    static boolean isJdkType(String className) {
        if (className.startsWith("java.") || className.startsWith("javax.")) {
            return true;
        }
        // Primitive array types (e.g. "[C" for char[], "[I" for int[]) and their
        // component types are well-known to LLMs; their generators (e.g.
        // Character.toChars) just add noise to the dependency context.
        if (className.startsWith("[") || className.endsWith("[]")) {
            return true;
        }
        return false;
    }

    /**
     * Classifies a type into a relevance tier.
     * @return 1 for direct CUT dependency, 2 for SUT type, 3 for third-party
     */
    static int classifyTier(String className, Set<String> directDependencyNames, String sutPrefix) {
        if (directDependencyNames.contains(className)) {
            return 1;
        }
        if (sutPrefix != null && !sutPrefix.isEmpty() && className.startsWith(sutPrefix + ".")) {
            return 2;
        }
        return 3;
    }

    /**
     * Extracts the top-level SUT package prefix from a fully qualified class name.
     * Uses the first two package segments (e.g., "com.example.foo.Bar" → "com.example").
     */
    static String extractSutPrefix(String className) {
        if (className == null) {
            return "";
        }
        int firstDot = className.indexOf('.');
        if (firstDot < 0) {
            return "";
        }
        int secondDot = className.indexOf('.', firstDot + 1);
        if (secondDot < 0) {
            return className.substring(0, firstDot);
        }
        return className.substring(0, secondDot);
    }

    /**
     * Builds a lookup from class name to the set of modifier objects from the cluster.
     */
    private Map<String, Set<GenericAccessibleObject<?>>> buildModifierLookup(TestCluster cluster) {
        Map<String, Set<GenericAccessibleObject<?>>> lookup = new HashMap<>();
        Set<GenericAccessibleObject<?>> allModifiers = cluster.getModifiers();
        if (allModifiers == null) {
            return lookup;
        }
        for (GenericAccessibleObject<?> mod : allModifiers) {
            if (mod == null) {
                continue;
            }
            try {
                GenericClass<?> owner = mod.getOwnerClass();
                if (owner != null && owner.getRawClass() != null) {
                    String name = owner.getRawClass().getName();
                    lookup.computeIfAbsent(name, k -> new HashSet<>()).add(mod);
                }
            } catch (Exception e) {
                logger.debug("Failed to get owner class from modifier {}", mod, e);
            }
        }
        return lookup;
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
