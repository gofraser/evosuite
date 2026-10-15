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

import java.util.List;
import java.util.stream.Collectors;

/**
 * A class to generate tests for, and optionally the methods to restrict the tests to.
 *
 * @param className the class's binary name
 * @param methods   the methods as EvoSuite's {@code target_method_list} names them, a name
 *                  followed by the JVM descriptor such as {@code sign(I)Ljava/lang/String;};
 *                  empty for the whole class
 */
public record GenerationTarget(String className, List<String> methods) {

    public GenerationTarget {
        methods = List.copyOf(methods);
    }

    public static GenerationTarget wholeClass(String className) {
        return new GenerationTarget(className, List.of());
    }

    public static List<GenerationTarget> wholeClasses(List<String> classNames) {
        return classNames.stream().map(GenerationTarget::wholeClass).collect(Collectors.toList());
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
}
