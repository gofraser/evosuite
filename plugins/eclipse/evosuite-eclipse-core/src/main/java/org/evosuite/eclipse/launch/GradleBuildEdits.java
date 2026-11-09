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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;
import org.evosuite.eclipse.Activator;

final class GradleBuildEdits {
    private static final String START = "// EvoSuite managed test configuration: start";
    private static final String END = "// EvoSuite managed test configuration: end";
    private static final String DEP_START = "// EvoSuite managed test dependencies: start";
    private static final String DEP_END = "// EvoSuite managed test dependencies: end";

    private GradleBuildEdits() {}

    static String configure(String source, List<ManagedBuildSetup.Dependency> dependencies, String folder,
                            String sourceSet, boolean kotlin, boolean jupiter) throws CoreException {
        if (!dependencies.isEmpty()) {
            int start = marker(source, DEP_START);
            int end = marker(source, DEP_END);
            String current = start >= 0 && end > start ? source.substring(start, end) : "";
            int brace = current.lastIndexOf('}');
            if (!current.isEmpty() && brace < 0) {
                throw new CoreException(Activator.error("Invalid EvoSuite dependency block in the Gradle script."));
            }
            var additions = new LinkedHashSet<String>();
            for (var dependency : dependencies) {
                String scope = sourceSet + (dependency.artifact().equals("junit-platform-launcher")
                        ? "RuntimeOnly" : "Implementation");
                String declaration = "add(\"" + scope + "\", \"" + dependency.coordinate() + "\")";
                if (current.lines().noneMatch(line -> line.strip().equals(declaration))) {
                    additions.add("    " + declaration + "\n");
                }
            }
            if (!additions.isEmpty()) {
                String block = current.isEmpty() ? DEP_START + "\ndependencies {\n" : current.substring(0, brace);
                source = replace(source, DEP_START, DEP_END, block + String.join("", additions) + "}\n" + DEP_END);
            }
        }
        String path = folder.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
                .replace("\n", "\\n").replace("\r", "\\r");
        String tasks = kotlin ? "tasks.withType<org.gradle.api.tasks.testing.Test>()"
                : "tasks.withType(org.gradle.api.tasks.testing.Test)";
        String block = START + "\nsourceSets.named(\"" + sourceSet + "\") { java.srcDir(\"" + path + "\") }\n"
                + tasks + ".matching { it.name == \"" + sourceSet + "\" }.configureEach { "
                + (jupiter ? "useJUnitPlatform()" : "useJUnit()") + " }\n" + END;
        return replace(source, START, END, block);
    }

    private static String replace(String source, String start, String end, String block) throws CoreException {
        int from = marker(source, start);
        int to = marker(source, end);
        if (from < 0 && to < 0) return source.isBlank() ? block + "\n" : source.stripTrailing() + "\n\n" + block + "\n";
        if (from < 0 || to <= from) {
            throw new CoreException(Activator.error("Conflicting EvoSuite markers in the Gradle script."));
        }
        return source.substring(0, from) + block + source.substring(to + end.length());
    }

    private static int marker(String source, String marker) throws CoreException {
        var matcher = Pattern.compile("(?m)^" + Pattern.quote(marker) + "$").matcher(source);
        if (!matcher.find()) return -1;
        int index = matcher.start();
        if (matcher.find()) throw new CoreException(Activator.error("Duplicate EvoSuite markers in the Gradle script."));
        return index;
    }
}
