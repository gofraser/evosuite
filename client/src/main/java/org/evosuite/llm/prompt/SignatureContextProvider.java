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
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassImpl;

import java.util.Optional;

/**
 * Provides signature-only context (API surface) for the CUT using TestClusterSummarizer.
 */
public class SignatureContextProvider implements SutContextProvider {

    private final TestClusterSummarizer summarizer;

    public SignatureContextProvider() {
        this(new TestClusterSummarizer());
    }

    public SignatureContextProvider(TestClusterSummarizer summarizer) {
        this.summarizer = summarizer;
    }

    @Override
    public Optional<String> getContext(String className, TestCluster cluster) {
        // Only provide CUT signatures here; dependency context is added
        // separately by PromptBuilder.addDependencyContext() for all modes.
        GenericClass<?> targetClass = findTargetClass(className, cluster);
        if (targetClass == null) {
            targetClass = loadClassByName(className);
        }
        if (targetClass != null) {
            String detail = summarizer.summarizeClass(targetClass);
            if (isNonBlank(detail)) {
                return Optional.of(detail);
            }
        }
        return Optional.empty();
    }

    @Override
    public String modeLabel() {
        return "API signatures";
    }

    private GenericClass<?> findTargetClass(String className, TestCluster cluster) {
        if (cluster == null) {
            return null;
        }
        if (className == null || className.trim().isEmpty()) {
            return null;
        }
        try {
            for (Class<?> clazz : cluster.getAnalyzedClasses()) {
                if (clazz.getName().equals(className)) {
                    return new GenericClassImpl(clazz);
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return null;
    }

    private GenericClass<?> loadClassByName(String className) {
        if (className == null || className.trim().isEmpty()) {
            return null;
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try {
            Class<?> clazz = Class.forName(className, false, cl);
            return new GenericClassImpl(clazz);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isNonBlank(String text) {
        return text != null && !text.trim().isEmpty();
    }
}
