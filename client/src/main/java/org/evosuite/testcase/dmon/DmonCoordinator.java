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
package org.evosuite.testcase.dmon;

import org.evosuite.Properties;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Small coordinator for DMoN detection in V1.
 */
public class DmonCoordinator {

    private static final DmonCoordinator INSTANCE = new DmonCoordinator();

    private final DmonNpeAnalyzer analyzer = new DmonNpeAnalyzer();
    // One cache for all four analyses: the discriminator in each key (CTOR, METHOD and their
    // _ASSIST variants) keeps them apart. Ephemeral assist bypasses the target-class
    // restriction, so its population of failure sites is much larger; caching it means a
    // failing analysis (e.g. an ASM bytecode read that yields no hint) is computed once per
    // failure signature rather than once per retry attempt or execution of the same site.
    private final Map<CacheKey, Optional<DmonPromotionPlan>> analysisCache = new ConcurrentHashMap<>();

    /**
     * Returns the singleton instance of the DmonCoordinator.
     *
     * @return the singleton instance
     */
    public static DmonCoordinator getInstance() {
        return INSTANCE;
    }

    /**
     * Analyzes a constructor failure to determine if it can be promoted.
     *
     * @param statement the constructor statement
     * @param throwable the throwable that occurred
     * @return an optional promotion plan
     */
    public Optional<DmonPromotionPlan> analyzeConstructorFailure(ConstructorStatement statement, Throwable throwable) {
        return analyze("CTOR", statement == null ? null : statement.getDeclaringClassName(), throwable,
                () -> analyzer.analyzeConstructorNpe(statement, throwable));
    }

    /**
     * Analyzes a method-invocation failure to determine if it can be promoted. Mirrors
     * {@link #analyzeConstructorFailure(ConstructorStatement, Throwable)} for NPEs raised
     * by a method call rather than a constructor call.
     *
     * @param statement the method statement
     * @param throwable the throwable that occurred
     * @return an optional promotion plan
     */
    public Optional<DmonPromotionPlan> analyzeMethodFailure(MethodStatement statement, Throwable throwable) {
        return analyze("METHOD", statement == null ? null : statement.getDeclaringClassName(), throwable,
                () -> analyzer.analyzeMethodNpe(statement, throwable));
    }

    /**
     * Analyzes a constructor failure for ephemeral assist without the target-class restriction.
     * Cached (when {@link Properties#DMON_CACHE_ANALYSIS} is on) since the analysis is a pure
     * function of the declaring class name, the NPE message and the top stack frame — caching
     * it (including the empty/"no plan" outcome) avoids repeating message parsing and the ASM
     * bytecode fallback on every retry attempt and every execution of the same crashing site.
     *
     * @param statement the constructor statement
     * @param throwable the throwable that occurred
     * @return an optional promotion plan
     */
    public Optional<DmonPromotionPlan> analyzeForEphemeralAssist(ConstructorStatement statement, Throwable throwable) {
        return analyze("CTOR_ASSIST", statement == null ? null : statement.getDeclaringClassName(), throwable,
                () -> analyzer.analyzeConstructorNpe(statement, throwable, false));
    }

    /**
     * Analyzes a method-invocation failure for ephemeral assist without the target-class
     * restriction. See {@link #analyzeForEphemeralAssist(ConstructorStatement, Throwable)}.
     *
     * @param statement the method statement
     * @param throwable the throwable that occurred
     * @return an optional promotion plan
     */
    public Optional<DmonPromotionPlan> analyzeForEphemeralAssist(MethodStatement statement, Throwable throwable) {
        return analyze("METHOD_ASSIST", statement == null ? null : statement.getDeclaringClassName(), throwable,
                () -> analyzer.analyzeMethodNpe(statement, throwable, false));
    }

    private Optional<DmonPromotionPlan> analyze(String discriminator, String declaringClassName,
                                                Throwable throwable,
                                                Supplier<Optional<DmonPromotionPlan>> analysis) {
        if (!Properties.DMON_ENABLED || Properties.NO_RUNTIME_DEPENDENCY) {
            return Optional.empty();
        }
        if (!Properties.DMON_CACHE_ANALYSIS) {
            return analysis.get();
        }
        CacheKey key = CacheKey.from(discriminator, declaringClassName, throwable);
        return analysisCache.computeIfAbsent(key, k -> analysis.get());
    }

    private static final class CacheKey {
        private final String discriminator;
        private final String owner;
        private final String message;
        private final String topFrame;

        private CacheKey(String discriminator, String owner, String message, String topFrame) {
            this.discriminator = discriminator == null ? "" : discriminator;
            this.owner = owner == null ? "" : owner;
            this.message = message == null ? "" : message;
            this.topFrame = topFrame == null ? "" : topFrame;
        }

        private static CacheKey from(String discriminator, String declaringClassName, Throwable throwable) {
            String owner = declaringClassName == null ? "" : declaringClassName;
            String message = throwable == null ? "" : String.valueOf(throwable.getMessage());
            String topFrame = "";
            StackTraceElement[] trace = throwable == null ? null : throwable.getStackTrace();
            if (trace != null && trace.length > 0) {
                StackTraceElement first = trace[0];
                topFrame = first.getClassName() + "#" + first.getMethodName() + ":" + first.getLineNumber();
            }
            return new CacheKey(discriminator, owner, message, topFrame);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CacheKey)) {
                return false;
            }
            CacheKey other = (CacheKey) o;
            return discriminator.equals(other.discriminator)
                    && owner.equals(other.owner)
                    && message.equals(other.message)
                    && topFrame.equals(other.topFrame);
        }

        @Override
        public int hashCode() {
            int result = discriminator.hashCode();
            result = 31 * result + owner.hashCode();
            result = 31 * result + message.hashCode();
            result = 31 * result + topFrame.hashCode();
            return result;
        }
    }
}
