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
package org.evosuite.eclipse.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.evosuite.eclipse.TestProjects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TargetSelectionTest {

    private IJavaProject project;

    @BeforeEach
    public void createProject() throws Exception {
        project = TestProjects.create("selection", "src/main/java", "src/test/java");
        TestProjects.addSource(project, "src/main/java", "a.Plain",
                "package a; public class Plain { static class Nested {} }\nclass Helper {}");
        TestProjects.addSource(project, "src/main/java", "a.Shape", "package a; public interface Shape {}");
        TestProjects.addSource(project, "src/main/java", "a.Marker", "package a; public @interface Marker {}");
        TestProjects.addSource(project, "src/main/java", "a.b.Deep", "package a.b; public enum Deep { X }");
        TestProjects.addSource(project, "src/test/java", "a.PlainTest", "package a; public class PlainTest {}");
    }

    private static Set<String> names(Map<IJavaProject, Set<IType>> targets) {
        return targets.values().stream().flatMap(Set::stream).map(IType::getFullyQualifiedName)
                .collect(Collectors.toSet());
    }

    @Test
    public void aProjectStandsForItsMainClasses() throws Exception {
        Map<IJavaProject, Set<IType>> targets = TargetSelection.collect(List.of(project));
        assertEquals(Set.of(project), targets.keySet());
        assertEquals(Set.of("a.Plain", "a.Helper", "a.b.Deep"), names(targets));
    }

    @Test
    public void aPackageIsNotRecursive() throws Exception {
        assertEquals(Set.of("a.Plain", "a.Helper"),
                names(TargetSelection.collect(List.of(project.findType("a.Plain").getPackageFragment()))));
    }

    @Test
    public void aNestedTypeStandsForItsTopLevelClass() throws Exception {
        assertEquals(Set.of("a.Plain"),
                names(TargetSelection.collect(List.of(project.findType("a.Plain.Nested")))));
    }

    @Test
    public void resourcesAdaptToJavaElements() throws Exception {
        assertEquals(Set.of("a.b.Deep"), names(TargetSelection.collect(
                List.of(project.getProject().getFile("src/main/java/a/b/Deep.java")))));
    }

    @Test
    public void testSourcesAreLeftOut() throws Exception {
        assertTrue(TargetSelection.collect(List.of(project.findType("a.PlainTest"))).isEmpty());
        assertTrue(TargetSelection.collect(List.of(JavaCore.create(project.getProject().getFolder("src/test/java"))))
                .isEmpty());
    }
}
