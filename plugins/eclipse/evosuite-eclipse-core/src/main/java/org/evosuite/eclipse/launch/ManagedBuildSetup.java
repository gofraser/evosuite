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

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.ui.PlatformUI;
import org.evosuite.eclipse.Activator;
import org.evosuite.eclipse.EvoSuiteJars;
import org.evosuite.eclipse.maven.MavenSupport;
import org.evosuite.ide.GenerationOptions;

final class ManagedBuildSetup {
    static final String BUILDSHIP = "org.eclipse.buildship.core";

    record Dependency(String group, String artifact, String version) {
        String coordinate() { return group + ":" + artifact + ":" + version; }
    }

    private ManagedBuildSetup() {}

    static void configure(IJavaProject java, GenerationOptions options, IProgressMonitor monitor)
            throws CoreException {
        var project = java.getProject();
        boolean maven = project.hasNature(ProjectSetup.MAVEN_NATURE);
        if (!maven && !project.hasNature(ProjectSetup.GRADLE_NATURE)) return;

        boolean jupiter = !GenerationOptions.JUNIT4.equals(options.effectiveTestFormat());
        List<Dependency> dependencies = new ArrayList<>();
        if (jupiter && java.findType("org.junit.jupiter.api.Test") == null) {
            dependencies.add(new Dependency("org.junit.jupiter", "junit-jupiter", "5.14.3"));
        } else if (!jupiter && java.findType("org.junit.Test") == null) {
            dependencies.add(new Dependency("junit", "junit", "4.13.2"));
        }
        String version = EvoSuiteJars.version();
        if (!options.effectiveNoRuntimeDependency() && java.findType("org.evosuite.runtime.EvoRunnerParameters") == null) {
            dependencies.add(new Dependency("org.evosuite", "evosuite-standalone-runtime", version));
        }

        IFile file = project.getFile(maven ? "pom.xml" : gradleFile(project));
        file.refreshLocal(IResource.DEPTH_ZERO, monitor);
        String before = file.exists() ? SuiteOwnership.contents(file) : "";
        String proposed;
        String dependenciesOnly = before;
        if (maven) {
            proposed = MavenBuildEdits.configure(before, dependencies, options.testFolder, version);
            if (!dependencies.isEmpty()) dependenciesOnly = MavenBuildEdits.configure(before, dependencies, null, version);
        } else {
            if (jupiter && java.findType("org.junit.platform.launcher.Launcher") == null) {
                dependencies.add(new Dependency("org.junit.platform", "junit-platform-launcher", "1.14.3"));
            }
            proposed = GradleBuildEdits.configure(before, dependencies, options.testFolder,
                    sourceSet(java, options.testFolder), file.getName().endsWith(".kts"), jupiter);
        }
        if (before.equals(proposed)) {
            var folder = ProjectSetup.testFolder(project, options);
            if (!folder.exists()) {
                ProjectSetup.createFolders(folder);
                refresh(project, maven, monitor);
            }
            return;
        }
        boolean offerDependenciesOnly = maven && !before.equals(dependenciesOnly) && !proposed.equals(dependenciesOnly);
        List<String> changes = new ArrayList<>(dependencies.stream().map(Dependency::coordinate).toList());
        changes.add("Generated test source folder: " + options.testFolder);
        String tool = maven ? "Maven" : "Gradle";
        int choice = prompt("Configure " + tool, "Update " + file.getFullPath() + " and refresh the project?\n\n"
                + String.join("\n", changes), offerDependenciesOnly
                        ? new String[]{"Configure " + tool, "Dependencies Only", "Skip Setup", "Cancel"}
                        : new String[]{"Configure " + tool, "Skip Setup", "Cancel"});
        if (choice < 0 || choice == (offerDependenciesOnly ? 3 : 2)) throw new OperationCanceledException();
        if (choice == (offerDependenciesOnly ? 2 : 1)) return;
        if (!maven && Platform.getBundle(BUILDSHIP) == null) {
            throw new CoreException(Activator.error("Buildship is required to refresh " + project.getName() + "."));
        }
        String after = choice == 0 ? proposed : dependenciesOnly;
        ResourcesPlugin.getWorkspace().run(pm -> {
            write(file, before, after, pm);
            if (choice == 0) ProjectSetup.createFolders(ProjectSetup.testFolder(project, options));
        }, project, IWorkspace.AVOID_UPDATE, monitor);
        refresh(project, maven, monitor);
    }

    private static void refresh(org.eclipse.core.resources.IProject project, boolean maven, IProgressMonitor monitor)
            throws CoreException {
        if (maven) MavenSupport.refresh(project, monitor);
        else GradleSync.refresh(project, monitor);
    }

    static int prompt(String title, String message, String[] choices) {
        if (!PlatformUI.isWorkbenchRunning()) return -1;
        var workbench = PlatformUI.getWorkbench();
        var display = workbench.getDisplay();
        AtomicInteger result = new AtomicInteger(-1);
        display.syncExec(() -> {
            var window = workbench.getActiveWorkbenchWindow();
            if (window != null) {
                result.set(MessageDialog.open(MessageDialog.QUESTION, window.getShell(), title, message,
                        SWT.NONE, choices));
            }
        });
        return result.get();
    }

    private static String sourceSet(IJavaProject project, String folder) throws CoreException {
        var output = IPath.fromPortableString(folder);
        if (output.segmentCount() >= 3 && "src".equals(output.segment(0))) {
            String candidate = output.segment(1);
            var prefix = project.getPath().append("src").append(candidate);
            if (candidate.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                for (IClasspathEntry entry : project.getRawClasspath()) {
                    if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE && entry.isTest()
                            && prefix.isPrefixOf(entry.getPath())) return candidate;
                }
            }
        }
        return "test";
    }

    private static String gradleFile(org.eclipse.core.resources.IProject project) {
        if (project.getFile("build.gradle.kts").exists()) return "build.gradle.kts";
        if (project.getFile("build.gradle").exists()) return "build.gradle";
        if (project.getLocation() != null) {
            for (var dir = project.getLocation().toFile().toPath(); dir != null; dir = dir.getParent()) {
                if (java.nio.file.Files.exists(dir.resolve("build.gradle.kts"))
                        || java.nio.file.Files.exists(dir.resolve("settings.gradle.kts"))) return "build.gradle.kts";
                if (java.nio.file.Files.exists(dir.resolve("build.gradle"))
                        || java.nio.file.Files.exists(dir.resolve("settings.gradle"))) break;
            }
        }
        return "build.gradle";
    }

    private static void write(IFile file, String expected, String source, IProgressMonitor monitor)
            throws CoreException {
        file.refreshLocal(IResource.DEPTH_ZERO, monitor);
        var buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
        if ((buffer != null && buffer.isDirty()) || !(file.exists() ? SuiteOwnership.contents(file) : "").equals(expected)) {
            throw new CoreException(Activator.error(file.getFullPath() + " changed. Save it and retry EvoSuite setup."));
        }
        var bytes = new ByteArrayInputStream(source.getBytes(java.nio.charset.Charset.forName(file.exists()
                ? file.getCharset() : file.getParent().getDefaultCharset())));
        if (file.exists()) file.setContents(bytes, IResource.KEEP_HISTORY, monitor);
        else file.create(bytes, IResource.NONE, monitor);
    }
}
