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
package org.evosuite.intellij.actions;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.*;

/** The same naming conventions and confirmed longest-prefix guess as Eclipse. */
public final class TestClassInference {
    private TestClassInference() {}

    public record Inference(List<String> candidates, String guess) {}

    public static Inference infer(Project project, TargetCollector.TestClass test, String suffix) {
        String pkg =
                test.name().contains(".")
                        ? test.name().substring(0, test.name().lastIndexOf('.'))
                        : "";
        String simple = test.name().substring(pkg.isEmpty() ? 0 : pkg.length() + 1);
        List<String> stems = stems(simple, suffix);
        var scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(test.module(), false);
        JavaPsiFacade psi = JavaPsiFacade.getInstance(project);
        List<String> candidates =
                stems.stream()
                        .map(name -> pkg.isEmpty() ? name : pkg + "." + name)
                        .filter(name -> isCut(project, psi.findClass(name, scope)))
                        .toList();
        if (!candidates.isEmpty()) return new Inference(candidates, candidates.getFirst());
        List<String> names = new ArrayList<>(stems);
        names.add(simple);
        PsiPackage sourcePackage = psi.findPackage(pkg);
        String prefix = "";
        if (sourcePackage != null)
            for (PsiClass type : sourcePackage.getClasses(scope)) {
                String name = type.getName();
                if (name != null
                        && name.length() > prefix.length()
                        && isCut(project, type)
                        && names.stream()
                                .anyMatch(n -> n.length() > name.length() && n.startsWith(name)))
                    prefix = name;
            }
        String guess = !prefix.isEmpty() ? prefix : stems.isEmpty() ? "" : stems.getFirst();
        return new Inference(
                List.of(), guess.isEmpty() || pkg.isEmpty() ? guess : pkg + "." + guess);
    }

    private static List<String> stems(String simple, String suffix) {
        List<String> names = new ArrayList<>(List.of(simple));
        if (simple.startsWith("Test")
                && simple.length() > 4
                && Character.isUpperCase(simple.charAt(4))) names.add(simple.substring(4));
        Set<String> stems = new LinkedHashSet<>();
        for (String name : names) {
            if (!name.equals(simple)) stems.add(name);
            for (String ending : List.of(suffix, "TestCase", "ITCase", "Tests", "Test", "IT"))
                if (!ending.isBlank() && name.endsWith(ending) && name.length() > ending.length())
                    stems.add(name.substring(0, name.length() - ending.length()));
        }
        return List.copyOf(stems);
    }

    public static boolean isCut(Project project, PsiClass type) {
        return TargetCollector.eligibleClass(project, type, TargetCollector.Mode.CLASSES);
    }
}
