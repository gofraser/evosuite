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
package org.evosuite.eclipse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;

/** Java projects for tests, built in the test workspace. */
public final class TestProjects {

    private TestProjects() {
    }

    /** A Java 17 project with a main source folder and, if given, a test source folder. */
    public static IJavaProject create(String name, String mainFolder, String testFolder) throws CoreException {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        if (project.exists()) {
            project.delete(true, true, null);
        }
        project.create(null);
        project.open(null);
        IProjectDescription description = project.getDescription();
        description.setNatureIds(new String[]{JavaCore.NATURE_ID});
        project.setDescription(description, null);

        IJavaProject javaProject = JavaCore.create(project);
        Map<String, String> options = javaProject.getOptions(false);
        JavaCore.setComplianceOptions("17", options);
        javaProject.setOptions(options);

        List<IClasspathEntry> entries = new ArrayList<>();
        entries.add(JavaCore.newSourceEntry(folder(project, mainFolder).getFullPath()));
        if (testFolder != null) {
            entries.add(JavaCore.newSourceEntry(folder(project, testFolder).getFullPath(), new IPath[0],
                    new IPath[0], project.getFolder("bin-test").getFullPath(),
                    new IClasspathAttribute[]{JavaCore.newClasspathAttribute(IClasspathAttribute.TEST, "true")}));
        }
        entries.add(JavaRuntime.getDefaultJREContainerEntry());
        javaProject.setRawClasspath(entries.toArray(IClasspathEntry[]::new), folder(project, "bin").getFullPath(), null);
        return javaProject;
    }

    /** Adds a classpath container, such as JUnit's, as a test-only library. */
    public static void addTestContainer(IJavaProject project, IPath path) throws CoreException {
        List<IClasspathEntry> entries = new ArrayList<>(Arrays.asList(project.getRawClasspath()));
        entries.add(JavaCore.newContainerEntry(path, null,
                new IClasspathAttribute[]{JavaCore.newClasspathAttribute(IClasspathAttribute.TEST, "true")}, false));
        project.setRawClasspath(entries.toArray(IClasspathEntry[]::new), null);
    }

    public static IFolder folder(IProject project, String path) throws CoreException {
        IFolder folder = project.getFolder(path);
        create(folder);
        return folder;
    }

    private static void create(IContainer container) throws CoreException {
        if (container.exists() || !(container instanceof IFolder)) {
            return;
        }
        create(container.getParent());
        ((IFolder) container).create(true, true, null);
    }

    public static IFile addSource(IJavaProject project, String sourceFolder, String className, String source)
            throws CoreException {
        IFile file = project.getProject().getFile(sourceFolder + "/" + className.replace('.', '/') + ".java");
        create(file.getParent());
        file.create(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), true, null);
        return file;
    }

    public static void build(IJavaProject project) throws CoreException {
        project.getProject().build(IncrementalProjectBuilder.FULL_BUILD, null);
    }

    public static List<String> compileErrors(IJavaProject project) throws CoreException {
        List<String> errors = new ArrayList<>();
        for (IMarker marker : project.getProject().findMarkers(IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER, true,
                IResource.DEPTH_INFINITE)) {
            if (marker.getAttribute(IMarker.SEVERITY, -1) == IMarker.SEVERITY_ERROR) {
                errors.add(marker.getResource().getProjectRelativePath() + ":"
                        + marker.getAttribute(IMarker.LINE_NUMBER, -1) + ": "
                        + marker.getAttribute(IMarker.MESSAGE, ""));
            }
        }
        return errors;
    }

    public static boolean hasEntry(IJavaProject project, IPath path) throws CoreException {
        return Arrays.stream(project.getRawClasspath()).anyMatch(e -> e.getPath().equals(path));
    }

    public static IClasspathEntry entry(IJavaProject project, IPath path) throws CoreException {
        return Arrays.stream(project.getRawClasspath()).filter(e -> e.getPath().equals(path)).findFirst()
                .orElse(null);
    }
}
