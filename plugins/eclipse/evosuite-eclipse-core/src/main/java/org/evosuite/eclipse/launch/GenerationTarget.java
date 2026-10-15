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
package org.evosuite.eclipse.launch;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A class to generate tests for, and optionally the methods to restrict the tests to or the
 * existing test classes to extend.
 *
 * @param className the class's binary name
 * @param methods   the methods as EvoSuite's {@code target_method_list} names them, a name
 *                  followed by the JVM descriptor such as {@code sign(I)Ljava/lang/String;};
 *                  empty for the whole class
 * @param seeds     existing test classes of the class: EvoSuite starts its search from their
 *                  tests, and keeps only new tests that cover what they do not; empty for an
 *                  ordinary run
 */
public record GenerationTarget(String className, List<String> methods, List<Seed> seeds) {

    /**
     * An existing test class, which EvoSuite parses from its source and runs compiled.
     *
     * @param className  the test class's binary name
     * @param sourceRoot the source folder that contains it, in the file system
     */
    public record Seed(String className, File sourceRoot) {

        /** The test class's source file. */
        public File sourceFile() {
            return new File(sourceRoot, className.replace('.', File.separatorChar) + ".java");
        }

        public String simpleName() {
            return className.substring(className.lastIndexOf('.') + 1);
        }
    }

    public GenerationTarget {
        methods = List.copyOf(methods);
        seeds = List.copyOf(seeds);
    }

    public GenerationTarget(String className, List<String> methods) {
        this(className, methods, List.of());
    }

    public static GenerationTarget wholeClass(String className) {
        return new GenerationTarget(className, List.of());
    }

    public static List<GenerationTarget> wholeClasses(List<String> classNames) {
        return classNames.stream().map(GenerationTarget::wholeClass).collect(Collectors.toList());
    }

    /** New tests for the whole class that extend the tests of {@code seeds}. */
    public static GenerationTarget extending(String className, List<Seed> seeds) {
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException("no test classes to extend");
        }
        return new GenerationTarget(className, List.of(), seeds);
    }

    /** Whether the run extends existing tests. */
    public boolean extendsTests() {
        return !seeds.isEmpty();
    }

    /**
     * {@code Calculator}; for methods {@code Calculator.sign}, {@code the constructor of Calculator}
     * or {@code 2 methods of Calculator}.
     */
    public String label() {
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        if (methods.size() == 1) {
            String name = methods.get(0).substring(0, methods.get(0).indexOf('('));
            return "<init>".equals(name) ? "the constructor of " + simpleName : simpleName + "." + name;
        }
        return methods.isEmpty() ? simpleName : methods.size() + " methods of " + simpleName;
    }

    /** The simple names of the seed test classes, such as {@code CalculatorTest and CalculatorIT}. */
    public String seedNames() {
        List<String> names = seeds.stream().map(Seed::simpleName).collect(Collectors.toList());
        if (names.size() <= 1) {
            return String.join("", names);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }
}
