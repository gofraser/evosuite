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

import org.evosuite.Properties;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

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
        private final int budgetCharsUsed;
        private final int perClassSoftCapUsed;
        private final boolean perClassSoftCapAuto;
        private final boolean compactSignaturesUsed;
        private final int candidateClasses;
        private final int emittedClasses;
        private final int emittedTier1Classes;
        private final int emittedTier2Classes;
        private final int emittedTier3Classes;
        private final int emittedInstantiators;
        private final int emittedModifiers;
        private final int droppedByPerClassCap;
        private final int droppedByGlobalBudget;

        public static final class Builder {
            private String text = "";
            private boolean truncated;
            private int totalCharsBeforeTruncation;
            private int budgetCharsUsed;
            private int perClassSoftCapUsed;
            private boolean perClassSoftCapAuto;
            private boolean compactSignaturesUsed;
            private int candidateClasses;
            private int emittedClasses;
            private int emittedTier1Classes;
            private int emittedTier2Classes;
            private int emittedTier3Classes;
            private int emittedInstantiators;
            private int emittedModifiers;
            private int droppedByPerClassCap;
            private int droppedByGlobalBudget;

            public Builder text(String text) {
                this.text = text;
                return this;
            }

            public Builder truncated(boolean truncated) {
                this.truncated = truncated;
                return this;
            }

            public Builder totalCharsBeforeTruncation(int totalCharsBeforeTruncation) {
                this.totalCharsBeforeTruncation = totalCharsBeforeTruncation;
                return this;
            }

            public Builder budgetCharsUsed(int budgetCharsUsed) {
                this.budgetCharsUsed = budgetCharsUsed;
                return this;
            }

            public Builder perClassSoftCapUsed(int perClassSoftCapUsed) {
                this.perClassSoftCapUsed = perClassSoftCapUsed;
                return this;
            }

            public Builder perClassSoftCapAuto(boolean perClassSoftCapAuto) {
                this.perClassSoftCapAuto = perClassSoftCapAuto;
                return this;
            }

            public Builder compactSignaturesUsed(boolean compactSignaturesUsed) {
                this.compactSignaturesUsed = compactSignaturesUsed;
                return this;
            }

            public Builder candidateClasses(int candidateClasses) {
                this.candidateClasses = candidateClasses;
                return this;
            }

            public Builder emittedClasses(int emittedClasses) {
                this.emittedClasses = emittedClasses;
                return this;
            }

            public Builder emittedTier1Classes(int emittedTier1Classes) {
                this.emittedTier1Classes = emittedTier1Classes;
                return this;
            }

            public Builder emittedTier2Classes(int emittedTier2Classes) {
                this.emittedTier2Classes = emittedTier2Classes;
                return this;
            }

            public Builder emittedTier3Classes(int emittedTier3Classes) {
                this.emittedTier3Classes = emittedTier3Classes;
                return this;
            }

            public Builder emittedInstantiators(int emittedInstantiators) {
                this.emittedInstantiators = emittedInstantiators;
                return this;
            }

            public Builder emittedModifiers(int emittedModifiers) {
                this.emittedModifiers = emittedModifiers;
                return this;
            }

            public Builder droppedByPerClassCap(int droppedByPerClassCap) {
                this.droppedByPerClassCap = droppedByPerClassCap;
                return this;
            }

            public Builder droppedByGlobalBudget(int droppedByGlobalBudget) {
                this.droppedByGlobalBudget = droppedByGlobalBudget;
                return this;
            }

            public DependencySummaryResult build() {
                return new DependencySummaryResult(this);
            }
        }

        private DependencySummaryResult(Builder builder) {
            this.text = builder.text;
            this.truncated = builder.truncated;
            this.totalCharsBeforeTruncation = builder.totalCharsBeforeTruncation;
            this.budgetCharsUsed = builder.budgetCharsUsed;
            this.perClassSoftCapUsed = builder.perClassSoftCapUsed;
            this.perClassSoftCapAuto = builder.perClassSoftCapAuto;
            this.compactSignaturesUsed = builder.compactSignaturesUsed;
            this.candidateClasses = builder.candidateClasses;
            this.emittedClasses = builder.emittedClasses;
            this.emittedTier1Classes = builder.emittedTier1Classes;
            this.emittedTier2Classes = builder.emittedTier2Classes;
            this.emittedTier3Classes = builder.emittedTier3Classes;
            this.emittedInstantiators = builder.emittedInstantiators;
            this.emittedModifiers = builder.emittedModifiers;
            this.droppedByPerClassCap = builder.droppedByPerClassCap;
            this.droppedByGlobalBudget = builder.droppedByGlobalBudget;
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

        public int getBudgetCharsUsed() {
            return budgetCharsUsed;
        }

        public int getPerClassSoftCapUsed() {
            return perClassSoftCapUsed;
        }

        public boolean isPerClassSoftCapAuto() {
            return perClassSoftCapAuto;
        }

        public boolean isCompactSignaturesUsed() {
            return compactSignaturesUsed;
        }

        public int getCandidateClasses() {
            return candidateClasses;
        }

        public int getEmittedClasses() {
            return emittedClasses;
        }

        public int getEmittedTier1Classes() {
            return emittedTier1Classes;
        }

        public int getEmittedTier2Classes() {
            return emittedTier2Classes;
        }

        public int getEmittedTier3Classes() {
            return emittedTier3Classes;
        }

        public int getEmittedInstantiators() {
            return emittedInstantiators;
        }

        public int getEmittedModifiers() {
            return emittedModifiers;
        }

        public int getDroppedByPerClassCap() {
            return droppedByPerClassCap;
        }

        public int getDroppedByGlobalBudget() {
            return droppedByGlobalBudget;
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
            return new DependencySummaryResult.Builder()
                    .text("")
                    .truncated(false)
                    .totalCharsBeforeTruncation(0)
                    .build();
        }
        boolean compactSignatures = Properties.LLM_CLUSTER_SUMMARY_COMPACT_SIGNATURES;

        // Collect direct dependency type names from CUT constructors and methods
        Set<String> directDependencyNames = collectDirectDependencies(cluster, targetClassName);

        // Derive the SUT package prefix (top two segments, e.g. "com.example")
        String sutPrefix = extractSutPrefix(targetClassName);

        // Collect all types from the generators map
        Map<GenericClass<?>, Set<GenericAccessibleObject<?>>> generatorsByType = cluster.getGeneratorsByType();

        // Build a modifier lookup: class name -> set of modifier methods from the cluster
        Map<String, Set<GenericAccessibleObject<?>>> modifiersByClassName = buildModifierLookup(cluster);
        Map<String, Map<String, Integer>> modifierFrequencyByClass = buildModifierFrequencyLookup(cluster);
        Map<String, Class<?>> candidatesByName = collectCandidateTypes(generatorsByType, targetClassName);
        Map<String, List<Class<?>>> concreteSubtypeLookup = buildConcreteSubtypeLookup(candidatesByName.values());

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
            Map<String, Integer> methodFrequency = modifierFrequencyByClass.getOrDefault(className,
                    Collections.<String, Integer>emptyMap());
            TypeSummary summary = buildTypeSummary(rawClass, generators, modifiers, compactSignatures,
                    methodFrequency, concreteSubtypeLookup.getOrDefault(className, Collections.<Class<?>>emptyList()));
            if (summary == null || summary.isEmpty()) {
                continue;
            }

            int tier = classifyTier(className, directDependencyNames, sutPrefix);
            summary.tier = tier;
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

        // Sort each tier alphabetically. Within Tier 1, emit abstract/interface
        // constructor-closure types first so their concrete-subtype hints survive
        // tight budgets (this is the class of failures that makes the LLM resort
        // to anonymous instantiation of framework base types).
        tier1DirectDeps.sort((a, b) -> {
            if (a.nonInstantiable != b.nonInstantiable) {
                return a.nonInstantiable ? -1 : 1;
            }
            return a.simpleName.compareToIgnoreCase(b.simpleName);
        });
        tier2SutTypes.sort((a, b) -> a.simpleName.compareToIgnoreCase(b.simpleName));
        tier3ThirdParty.sort((a, b) -> a.simpleName.compareToIgnoreCase(b.simpleName));

        // Merge tiers in order
        List<TypeSummary> allDeps = new ArrayList<>(tier1DirectDeps);
        allDeps.addAll(tier2SutTypes);
        allDeps.addAll(tier3ThirdParty);
        boolean perClassSoftCapAuto = Properties.LLM_CLUSTER_SUMMARY_PER_CLASS_SOFT_CAP_CHARS <= 0;
        int perClassSoftCap = resolvePerClassSoftCap(maxChars, allDeps.size());

        // Compute total chars without budget for metadata
        int totalChars = 0;
        for (TypeSummary ts : allDeps) {
            totalChars += ts.fullLength();
        }

        // Build output respecting global budget and soft per-class caps:
        // 1) breadth-first instantiators, then 2) breadth-first modifiers.
        StringBuilder sb = new StringBuilder();
        boolean budgetExceeded = false;
        DependencySummaryStats stats = new DependencySummaryStats();

        budgetExceeded = emitPhaseByTier(sb, allDeps, maxChars, perClassSoftCap, stats, true);

        if (!budgetExceeded) {
            budgetExceeded = emitPhaseByTier(sb, allDeps, maxChars, perClassSoftCap, stats, false);
        }

        if (budgetExceeded) {
            sb.append("  ... (truncated)\n");
        }

        return new DependencySummaryResult.Builder()
                .text(sb.toString().trim())
                .truncated(budgetExceeded)
                .totalCharsBeforeTruncation(totalChars)
                .budgetCharsUsed(maxChars)
                .perClassSoftCapUsed(perClassSoftCap)
                .perClassSoftCapAuto(perClassSoftCapAuto)
                .compactSignaturesUsed(compactSignatures)
                .candidateClasses(allDeps.size())
                .emittedClasses(stats.emittedClasses)
                .emittedTier1Classes(stats.emittedTier1Classes)
                .emittedTier2Classes(stats.emittedTier2Classes)
                .emittedTier3Classes(stats.emittedTier3Classes)
                .emittedInstantiators(stats.emittedInstantiators)
                .emittedModifiers(stats.emittedModifiers)
                .droppedByPerClassCap(stats.droppedByPerClassCap)
                .droppedByGlobalBudget(stats.droppedByGlobalBudget)
                .build();
    }

    /**
     * Formats a single type as a summary block.
     * For enums: "  EnumName { VALUE_A, VALUE_B, VALUE_C }"
     * For classes: constructors, static factory methods from generators,
     * and public modifier methods from the cluster.
     */
    private TypeSummary buildTypeSummary(Class<?> rawClass,
                                         Set<GenericAccessibleObject<?>> generators,
                                         Set<GenericAccessibleObject<?>> modifiers,
                                         boolean compactSignatures,
                                         Map<String, Integer> modifierFrequency,
                                         List<Class<?>> concreteSubtypes) {
        TypeSummary summary = new TypeSummary(rawClass.getSimpleName(), buildTypeHeader(rawClass));
        if (rawClass.isEnum()) {
            Object[] constants = rawClass.getEnumConstants();
            if (constants != null && constants.length > 0) {
                StringBuilder enumBlock = new StringBuilder(summary.header).append(" { ");
                for (int i = 0; i < constants.length; i++) {
                    if (i > 0) {
                        enumBlock.append(", ");
                    }
                    enumBlock.append(((Enum<?>) constants[i]).name());
                }
                enumBlock.append(" }");
                summary.header = enumBlock.toString();
            }
            return summary;
        } else {
            boolean nonInstantiable = rawClass.isInterface() || Modifier.isAbstract(rawClass.getModifiers());
            summary.nonInstantiable = nonInstantiable;
            // Constructors from reflection (include package-private for same-package access)
            if (!nonInstantiable) {
                List<Constructor<?>> constructors = new ArrayList<>();
                for (Constructor<?> ctor : rawClass.getDeclaredConstructors()) {
                    if (Modifier.isPrivate(ctor.getModifiers())
                            || Modifier.isProtected(ctor.getModifiers())) {
                        continue;
                    }
                    constructors.add(ctor);
                }
                constructors.sort(Comparator.comparing(Constructor::toGenericString));
                for (Constructor<?> ctor : constructors) {
                    summary.instantiators.add("  " + rawClass.getSimpleName()
                            + '(' + formatParameterList(ctor.getGenericParameterTypes(), compactSignatures) + ')');
                }
            } else if (concreteSubtypes != null && !concreteSubtypes.isEmpty()) {
                summary.instantiators.add("  concrete subtypes: "
                        + formatConcreteSubtypeHints(concreteSubtypes, compactSignatures));
            }

            // Static factory methods from generators (methods that aren't constructors)
            if (generators != null) {
                List<Method> factoryMethods = new ArrayList<>();
                for (GenericAccessibleObject<?> gen : generators) {
                    if (gen.isMethod() && gen.isStatic()) {
                        Method method = ((java.lang.reflect.AccessibleObject) gen.getAccessibleObject())
                                instanceof Method
                                ? (Method) gen.getAccessibleObject() : null;
                        if (method != null) {
                            factoryMethods.add(method);
                        }
                    }
                }
                factoryMethods.sort(Comparator.comparing(Method::toGenericString));
                for (Method method : factoryMethods) {
                    summary.instantiators.add("  static " + rawClass.getSimpleName()
                            + ' ' + method.getName()
                            + '(' + formatParameterList(method.getGenericParameterTypes(), compactSignatures) + ')');
                }
            }

            // Public modifier methods from the cluster
            if (modifiers != null) {
                Set<String> seen = new HashSet<>();
                List<ModifierCandidate> modifierMethods = new ArrayList<>();
                for (GenericAccessibleObject<?> mod : modifiers) {
                    if (mod.isMethod()) {
                        Method method = mod.getAccessibleObject() instanceof Method
                                ? (Method) mod.getAccessibleObject() : null;
                        if (method != null && Modifier.isPublic(method.getModifiers())) {
                            String sig = method.getName() + "("
                                    + formatParameterList(method.getGenericParameterTypes(), compactSignatures) + ")";
                            if (seen.add(sig)) {
                                int frequency = modifierFrequency.getOrDefault(methodFrequencyKey(method), 1);
                                int score = scoreModifierMethod(method, frequency);
                                modifierMethods.add(new ModifierCandidate(method, score));
                            }
                        }
                    }
                }
                modifierMethods.sort((a, b) -> {
                    int byScore = Integer.compare(b.score, a.score);
                    if (byScore != 0) {
                        return byScore;
                    }
                    int byName = a.method.getName().compareToIgnoreCase(b.method.getName());
                    if (byName != 0) {
                        return byName;
                    }
                    return a.method.toGenericString().compareTo(b.method.toGenericString());
                });
                for (ModifierCandidate candidate : modifierMethods) {
                    Method method = candidate.method;
                    String sig = method.getName() + "("
                            + formatParameterList(method.getGenericParameterTypes(), compactSignatures) + ")";
                    String returnType = formatTypeName(method.getGenericReturnType(), compactSignatures);
                    if (compactSignatures && "void".equals(returnType)) {
                        summary.modifiers.add("  " + sig);
                    } else {
                        summary.modifiers.add("  " + returnType + " " + sig);
                    }
                }
            }
        }
        return summary;
    }

    private String buildTypeHeader(Class<?> rawClass) {
        String kind;
        if (rawClass.isInterface()) {
            kind = "interface";
        } else if (rawClass.isEnum()) {
            kind = "enum";
        } else if (Modifier.isAbstract(rawClass.getModifiers())) {
            kind = "abstract";
        } else {
            kind = "class";
        }
        StringBuilder header = new StringBuilder("// ")
                .append(rawClass.getSimpleName())
                .append(" [").append(kind).append("]");
        if (rawClass.isInterface() || Modifier.isAbstract(rawClass.getModifiers())) {
            String relation = formatImmediateRelation(rawClass);
            if (!relation.isEmpty()) {
                header.append(' ').append(relation);
            }
        }
        return header.toString();
    }

    /**
     * Returns the single-hop inheritance relation of an abstract/interface type
     * (e.g., {@code "extends Application"} or {@code "extends Base implements Runnable"}).
     * Used in the header so the LLM sees the constructor-closure anchor without
     * needing to resolve the full type hierarchy. Limits interfaces to two to
     * keep the header short; EvoSuite/instrumentation interfaces are skipped.
     */
    private String formatImmediateRelation(Class<?> rawClass) {
        StringBuilder sb = new StringBuilder();
        if (!rawClass.isInterface()) {
            Class<?> sup = rawClass.getSuperclass();
            if (sup != null && sup != Object.class) {
                sb.append("extends ").append(sup.getSimpleName());
            }
        }
        List<String> ifaceNames = new ArrayList<>();
        for (Class<?> iface : rawClass.getInterfaces()) {
            String name = iface.getName();
            if (name.contains("evosuite") || name.contains("EvoSuite")) {
                continue;
            }
            ifaceNames.add(iface.getSimpleName());
            if (ifaceNames.size() >= 2) {
                break;
            }
        }
        if (!ifaceNames.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(rawClass.isInterface() ? "extends " : "implements ");
            sb.append(String.join(", ", ifaceNames));
        }
        return sb.toString();
    }

    private String formatConcreteSubtypeHints(List<Class<?>> concreteSubtypes, boolean compactSignatures) {
        if (concreteSubtypes == null || concreteSubtypes.isEmpty()) {
            return "(none known)";
        }
        List<String> hints = new ArrayList<>();
        int limit = Math.min(4, concreteSubtypes.size());
        for (int i = 0; i < limit; i++) {
            Class<?> subtype = concreteSubtypes.get(i);
            hints.add(formatSubtypeInstantiation(subtype, compactSignatures));
        }
        if (concreteSubtypes.size() > limit) {
            hints.add("...");
        }
        return String.join(", ", hints);
    }

    private String formatSubtypeInstantiation(Class<?> subtype, boolean compactSignatures) {
        List<Constructor<?>> ctors = new ArrayList<>();
        for (Constructor<?> ctor : subtype.getDeclaredConstructors()) {
            if (Modifier.isPrivate(ctor.getModifiers()) || Modifier.isProtected(ctor.getModifiers())) {
                continue;
            }
            if (ctor.isSynthetic()) {
                continue;
            }
            ctors.add(ctor);
        }
        if (ctors.isEmpty()) {
            return subtype.getSimpleName();
        }
        ctors.sort((a, b) -> {
            int byArity = Integer.compare(a.getParameterCount(), b.getParameterCount());
            if (byArity != 0) {
                return byArity;
            }
            return a.toGenericString().compareTo(b.toGenericString());
        });
        Constructor<?> ctor = ctors.get(0);
        return subtype.getSimpleName() + "("
                + formatParameterList(ctor.getGenericParameterTypes(), compactSignatures) + ")";
    }

    private Map<String, Class<?>> collectCandidateTypes(
            Map<GenericClass<?>, Set<GenericAccessibleObject<?>>> generatorsByType, String targetClassName) {
        Map<String, Class<?>> byName = new HashMap<>();
        for (Map.Entry<GenericClass<?>, Set<GenericAccessibleObject<?>>> entry : generatorsByType.entrySet()) {
            GenericClass<?> genClass = entry.getKey();
            if (genClass == null || genClass.getRawClass() == null) {
                continue;
            }
            Class<?> rawClass = genClass.getRawClass();
            String className = rawClass.getName();
            if (className.equals(targetClassName)) {
                continue;
            }
            if (rawClass.isPrimitive() || EXCLUDED_TYPES.contains(className)) {
                continue;
            }
            if (isJdkType(className)) {
                continue;
            }
            byName.put(className, rawClass);
        }
        return byName;
    }

    private Map<String, List<Class<?>>> buildConcreteSubtypeLookup(Collection<Class<?>> candidates) {
        Map<String, List<Class<?>>> lookup = new HashMap<>();
        if (candidates == null || candidates.isEmpty()) {
            return lookup;
        }
        List<Class<?>> candidateList = new ArrayList<>(candidates);
        List<Class<?>> concreteCandidates = new ArrayList<>();
        for (Class<?> clazz : candidateList) {
            if (clazz == null) {
                continue;
            }
            if (!clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers()) && !clazz.isEnum()) {
                concreteCandidates.add(clazz);
            }
        }
        for (Class<?> base : candidateList) {
            if (base == null || (!base.isInterface() && !Modifier.isAbstract(base.getModifiers()))) {
                continue;
            }
            List<Class<?>> subtypes = new ArrayList<>();
            for (Class<?> candidate : concreteCandidates) {
                if (base == candidate) {
                    continue;
                }
                if (base.isAssignableFrom(candidate)) {
                    subtypes.add(candidate);
                }
            }
            subtypes.sort(Comparator.comparing(Class::getSimpleName, String.CASE_INSENSITIVE_ORDER));
            lookup.put(base.getName(), subtypes);
        }
        return lookup;
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

    private static String compactTypeName(Type type) {
        String name = genericTypeName(type);
        // Drop package qualifiers to preserve more API surface in constrained budgets.
        return name.replaceAll("\\b(?:[a-z_]\\w*\\.)+([A-Za-z_$][\\w$]*)\\b", "$1");
    }

    private static String formatTypeName(Type type, boolean compactSignatures) {
        return compactSignatures ? compactTypeName(type) : genericTypeName(type);
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

    private static String formatParameterList(Type[] types, boolean compactSignatures) {
        if (!compactSignatures) {
            return genericParameterList(types);
        }
        if (types == null || types.length == 0) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (Type t : types) {
            names.add(compactTypeName(t));
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

    private Map<String, Map<String, Integer>> buildModifierFrequencyLookup(TestCluster cluster) {
        Map<String, Map<String, Integer>> lookup = new HashMap<>();
        Set<GenericAccessibleObject<?>> allModifiers = cluster.getModifiers();
        if (allModifiers == null) {
            return lookup;
        }
        for (GenericAccessibleObject<?> mod : allModifiers) {
            if (mod == null || !mod.isMethod()) {
                continue;
            }
            try {
                GenericClass<?> owner = mod.getOwnerClass();
                if (owner == null || owner.getRawClass() == null) {
                    continue;
                }
                Method method = mod.getAccessibleObject() instanceof Method
                        ? (Method) mod.getAccessibleObject() : null;
                if (method == null) {
                    continue;
                }
                String className = owner.getRawClass().getName();
                String key = methodFrequencyKey(method);
                lookup.computeIfAbsent(className, k -> new HashMap<>());
                Map<String, Integer> byMethod = lookup.get(className);
                byMethod.put(key, byMethod.getOrDefault(key, 0) + 1);
            } catch (Exception e) {
                logger.debug("Failed to compute modifier frequency for {}", mod, e);
            }
        }
        return lookup;
    }

    private String methodFrequencyKey(Method method) {
        return method.getName() + "(" + genericParameterList(method.getGenericParameterTypes()) + ")";
    }

    private int scoreModifierMethod(Method method, int frequency) {
        int score = 0;
        String lower = method.getName().toLowerCase(Locale.ROOT);
        if (lower.startsWith("set") || lower.startsWith("add")
                || lower.startsWith("with") || lower.startsWith("put")) {
            score += 10;
        }
        if (method.getName().length() <= 8) {
            score += 5;
        }
        score -= 5 * complexGenericParameterCount(method);
        // Best-effort frequency signal from available cluster data.
        score += Math.min(5, Math.max(0, frequency - 1));
        return score;
    }

    private int complexGenericParameterCount(Method method) {
        int count = 0;
        for (Type param : method.getGenericParameterTypes()) {
            String t = param.getTypeName();
            if (t.contains("<") || t.contains("?") || t.contains("&")) {
                count++;
            }
        }
        return count;
    }

    private boolean emitPhaseByTier(StringBuilder sb, List<TypeSummary> allDeps,
                                    int maxChars, int perClassSoftCap,
                                    DependencySummaryStats stats, boolean instantiatorPhase) {
        for (int tier = 1; tier <= 3; tier++) {
            for (TypeSummary ts : allDeps) {
                if (ts.tier != tier) {
                    continue;
                }
                List<String> members = instantiatorPhase ? ts.instantiators : ts.modifiers;
                if (!emitMembersForPhase(sb, ts, members, maxChars, perClassSoftCap, stats, instantiatorPhase)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean emitMembersForPhase(StringBuilder sb, TypeSummary ts, List<String> members,
                                        int maxChars, int perClassSoftCap,
                                        DependencySummaryStats stats, boolean instantiatorPhase) {
        if (members == null || members.isEmpty()) {
            if (!ts.headerEmitted && ts.hasNoMembers()) {
                AppendOutcome headerOutcome =
                        appendWithCaps(sb, ts, ts.header, maxChars, perClassSoftCap, false);
                if (headerOutcome == AppendOutcome.GLOBAL_BUDGET_EXCEEDED) {
                    stats.droppedByGlobalBudget++;
                    return false;
                }
                if (headerOutcome == AppendOutcome.APPENDED) {
                    markClassEmitted(ts, stats);
                }
            }
            return true;
        }
        for (int i = 0; i < members.size(); i++) {
            String member = members.get(i);
            // Reserve the first Tier-1 instantiator line from the per-class soft cap:
            // for abstract/interface constructor-closure types this is the
            // "concrete subtypes: ..." hint, which is the only signal that prevents
            // the LLM from emitting anonymous-class instantiations of framework
            // base types. Global budget still applies.
            int effectiveClassCap = (instantiatorPhase && ts.tier == 1 && i == 0)
                    ? 0 : perClassSoftCap;
            AppendOutcome memberOutcome =
                    appendLine(sb, ts, member, maxChars, effectiveClassCap, stats);
            if (memberOutcome == AppendOutcome.GLOBAL_BUDGET_EXCEEDED) {
                stats.droppedByGlobalBudget++;
                return false;
            }
            if (memberOutcome == AppendOutcome.PER_CLASS_CAP_SKIPPED) {
                stats.droppedByPerClassCap++;
                continue;
            }
            if (instantiatorPhase) {
                stats.emittedInstantiators++;
            } else {
                stats.emittedModifiers++;
            }
        }
        return true;
    }

    private AppendOutcome appendLine(StringBuilder sb, TypeSummary ts, String memberLine,
                                     int maxChars, int perClassSoftCap,
                                     DependencySummaryStats stats) {
        if (!ts.headerEmitted) {
            int headerCost = ts.header.length() + 1;
            int memberCost = memberLine.length() + 1;
            if (maxChars > 0 && sb.length() + headerCost + memberCost > maxChars) {
                return AppendOutcome.GLOBAL_BUDGET_EXCEEDED;
            }
            if (perClassSoftCap > 0 && ts.classChars + headerCost + memberCost > perClassSoftCap) {
                return AppendOutcome.PER_CLASS_CAP_SKIPPED;
            }
            sb.append(ts.header).append('\n');
            ts.classChars += headerCost;
            ts.headerEmitted = true;
            markClassEmitted(ts, stats);
            sb.append(memberLine).append('\n');
            ts.classChars += memberCost;
            return AppendOutcome.APPENDED;
        }
        return appendWithCaps(sb, ts, memberLine, maxChars, perClassSoftCap, true);
    }

    private AppendOutcome appendWithCaps(StringBuilder sb, TypeSummary ts, String line, int maxChars,
                                         int perClassSoftCap, boolean enforceClassCap) {
        int lineCost = line.length() + 1;
        if (maxChars > 0 && sb.length() + lineCost > maxChars) {
            return AppendOutcome.GLOBAL_BUDGET_EXCEEDED;
        }
        if (enforceClassCap && perClassSoftCap > 0 && ts.classChars + lineCost > perClassSoftCap) {
            return AppendOutcome.PER_CLASS_CAP_SKIPPED;
        }
        sb.append(line).append('\n');
        ts.classChars += lineCost;
        if (!ts.headerEmitted && line.equals(ts.header)) {
            ts.headerEmitted = true;
        }
        return AppendOutcome.APPENDED;
    }

    private void markClassEmitted(TypeSummary ts, DependencySummaryStats stats) {
        if (ts.classEmitted) {
            return;
        }
        ts.classEmitted = true;
        stats.emittedClasses++;
        if (ts.tier == 1) {
            stats.emittedTier1Classes++;
        } else if (ts.tier == 2) {
            stats.emittedTier2Classes++;
        } else {
            stats.emittedTier3Classes++;
        }
    }

    private int resolvePerClassSoftCap(int maxChars, int dependencyCount) {
        int configured = Properties.LLM_CLUSTER_SUMMARY_PER_CLASS_SOFT_CAP_CHARS;
        if (configured > 0) {
            return configured;
        }
        if (maxChars <= 0 || dependencyCount <= 0) {
            return 0;
        }
        // Auto-cap aims for broad coverage first: reserve room for up to ~10 classes.
        int divisor = Math.min(Math.max(dependencyCount, 1), 10);
        int autoCap = maxChars / divisor;
        return Math.max(240, Math.min(1200, autoCap));
    }

    private static class TypeSummary {
        final String simpleName;
        String header;
        int tier;
        boolean nonInstantiable;
        final List<String> instantiators = new ArrayList<>();
        final List<String> modifiers = new ArrayList<>();
        int classChars;
        boolean headerEmitted;
        boolean classEmitted;

        TypeSummary(String simpleName, String header) {
            this.simpleName = simpleName;
            this.header = header;
        }

        boolean hasNoMembers() {
            return instantiators.isEmpty() && modifiers.isEmpty();
        }

        boolean isEmpty() {
            return header == null || header.isEmpty();
        }

        int fullLength() {
            int total = header.length() + 1;
            for (String line : instantiators) {
                total += line.length() + 1;
            }
            for (String line : modifiers) {
                total += line.length() + 1;
            }
            return total;
        }
    }

    private static class DependencySummaryStats {
        int emittedClasses;
        int emittedTier1Classes;
        int emittedTier2Classes;
        int emittedTier3Classes;
        int emittedInstantiators;
        int emittedModifiers;
        int droppedByPerClassCap;
        int droppedByGlobalBudget;
    }

    private enum AppendOutcome {
        APPENDED,
        PER_CLASS_CAP_SKIPPED,
        GLOBAL_BUDGET_EXCEEDED
    }

    private static class ModifierCandidate {
        final Method method;
        final int score;

        ModifierCandidate(Method method, int score) {
            this.method = method;
            this.score = score;
        }
    }
}
