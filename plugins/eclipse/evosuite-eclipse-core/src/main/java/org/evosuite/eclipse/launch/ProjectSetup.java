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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.launching.IRuntimeClasspathEntry;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.evosuite.eclipse.Activator;
import org.evosuite.eclipse.classpath.EvoSuiteClasspathContainer;

/** What a project must provide before EvoSuite can run on it, and what it must get afterwards. */
public final class ProjectSetup {

    static final String MAVEN_NATURE = "org.eclipse.m2e.core.maven2Nature";
    static final String GRADLE_NATURE = "org.eclipse.buildship.core.gradleprojectnature";

    private ProjectSetup() {
    }

    /**
     * The project's runtime classpath without its test code and without the JRE: what EvoSuite
     * passes to the class under test as {@code -projectCP}. Leaving out test code also keeps
     * earlier generated tests, and the JUnit and EvoSuite runtime libraries they need, away from
     * the search.
     */
    public static List<String> runtimeClasspath(IJavaProject project) throws CoreException {
        Set<String> testLibraries = testLibraryLocations(project);
        Set<String> locations = new LinkedHashSet<>();
        for (IRuntimeClasspathEntry entry : JavaRuntime.computeUnresolvedRuntimeClasspath(project, true)) {
            if (isJre(entry)) {
                continue;
            }
            for (IRuntimeClasspathEntry resolved : JavaRuntime.resolveRuntimeClasspathEntry(entry, project, true)) {
                if (resolved.getClasspathProperty() == IRuntimeClasspathEntry.BOOTSTRAP_CLASSES
                        || resolved.getClasspathProperty() == IRuntimeClasspathEntry.STANDARD_CLASSES) {
                    continue;
                }
                String location = resolved.getLocation();
                if (location != null && new File(location).exists() && !testLibraries.contains(location)) {
                    locations.add(location);
                }
            }
        }
        return new ArrayList<>(locations);
    }

    /**
     * The jars of the project's JUnit and EvoSuite Runtime containers. They are usually test-only
     * and left out already, but a project that cannot separate test code gets them as main
     * entries (see {@link #separatesTestCode}), and EvoSuite must not take them for part of the
     * program. The runtime classpath arrives as one entry for the whole project, so the jars are
     * recognised by location.
     */
    private static Set<String> testLibraryLocations(IJavaProject project) throws CoreException {
        Set<String> locations = new HashSet<>();
        for (IClasspathEntry entry : project.getRawClasspath()) {
            IPath path = entry.getPath();
            if (entry.getEntryKind() != IClasspathEntry.CPE_CONTAINER
                    || !(JUnitCore.JUNIT_CONTAINER_ID.equals(path.segment(0)) || EvoSuiteClasspathContainer.PATH.equals(path))) {
                continue;
            }
            IClasspathContainer container = JavaCore.getClasspathContainer(path, project);
            if (container != null) {
                for (IClasspathEntry library : container.getClasspathEntries()) {
                    locations.add(library.getPath().toOSString());
                }
            }
        }
        return locations;
    }

    private static boolean isJre(IRuntimeClasspathEntry entry) {
        return entry.getType() == IRuntimeClasspathEntry.CONTAINER
                && JavaRuntime.JRE_CONTAINER.equals(entry.getPath().segment(0));
    }

    /** The Java installation that runs EvoSuite, and what it can do. */
    public static final class JavaInstallation {
        public final File executable;
        /** The feature release, such as 17, or 0 when unknown. */
        public final int version;
        /** EvoSuite compiles the tests it generates to check them, which needs javac. */
        public final boolean hasCompiler;

        JavaInstallation(File executable, int version, boolean hasCompiler) {
            this.executable = executable;
            this.version = version;
            this.hasCompiler = hasCompiler;
        }

        /** The java launcher reads {@code @argument} files since Java 9. */
        public boolean readsArgumentFiles() {
            return version >= 9;
        }
    }

    /**
     * The Java installation that runs EvoSuite: the configured Java home if there is one,
     * otherwise the project's own JRE, which is guaranteed to read the project's class files.
     */
    public static JavaInstallation javaInstallation(IJavaProject project, GenerationOptions options)
            throws CoreException {
        File home;
        if (!options.javaHome.isEmpty()) {
            home = new File(options.javaHome);
        } else {
            IVMInstall vm = JavaRuntime.getVMInstall(project);
            if (vm == null) {
                vm = JavaRuntime.getDefaultVMInstall();
            }
            if (vm == null || vm.getInstallLocation() == null) {
                throw new CoreException(Activator.error("Project " + project.getElementName()
                        + " has no Java runtime. Configure one under Build Path or set a Java home "
                        + "in the EvoSuite preferences."));
            }
            home = vm.getInstallLocation();
        }
        File bin = new File(home, "bin");
        File executable = executable(bin, "java");
        if (executable == null) {
            throw new CoreException(Activator.error("No java executable in " + bin));
        }
        return new JavaInstallation(executable, javaVersion(home), executable(bin, "javac") != null);
    }

    private static File executable(File bin, String name) {
        for (String candidate : new String[]{name, name + ".exe"}) {
            File file = new File(bin, candidate);
            if (file.isFile()) {
                return file;
            }
        }
        return null;
    }

    /** The feature release named by the {@code release} file of a Java home, or 0. */
    static int javaVersion(File home) {
        try {
            for (String line : Files.readAllLines(new File(home, "release").toPath(), StandardCharsets.ISO_8859_1)) {
                if (line.startsWith("JAVA_VERSION=")) {
                    return featureRelease(line.substring("JAVA_VERSION=".length()).replace("\"", ""));
                }
            }
        } catch (IOException | RuntimeException e) {
            // No release file: an unusual layout, treated as an unknown version.
        }
        return 0;
    }

    /** 8 for "1.8.0_352", 17 for "17.0.5", 0 if unparseable. */
    static int featureRelease(String version) {
        String[] parts = version.trim().split("[._+-]");
        try {
            int first = Integer.parseInt(parts[0]);
            return first == 1 && parts.length > 1 ? Integer.parseInt(parts[1]) : first;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static boolean isManagedByBuildTool(IProject project) {
        try {
            return project.hasNature(MAVEN_NATURE) || project.hasNature(GRADLE_NATURE);
        } catch (CoreException e) {
            return false;
        }
    }

    /** The folder that receives the generated tests; it need not exist yet. */
    public static IFolder testFolder(IProject project, GenerationOptions options) throws CoreException {
        IPath path = IPath.fromPortableString(options.testFolder);
        List<String> segments = Arrays.asList(path.segments());
        if (path.isAbsolute() || path.getDevice() != null || path.isEmpty()
                || segments.contains("..") || segments.contains(".")) {
            throw new CoreException(Activator.error("The test folder \"" + options.testFolder
                    + "\" is not a folder inside the project. Use a project-relative path such as "
                    + "src/test/evosuite in the EvoSuite settings."));
        }
        IFolder folder = project.getFolder(path);
        if (folder.getLocation() == null) {
            throw new CoreException(Activator.error("Project " + project.getName()
                    + " is not on the local file system, which EvoSuite needs to write its tests."));
        }
        return folder;
    }

    /** Creates the test folder, and makes it a test source folder if {@code configure} is set. */
    public static IFolder prepareTestFolder(IJavaProject project, GenerationOptions options,
                                            IProgressMonitor monitor) throws CoreException {
        IFolder folder = testFolder(project.getProject(), options);
        create(folder, monitor);
        if (options.configureBuildPath) {
            addTestSourceFolder(project, folder, monitor);
        }
        return folder;
    }

    private static void create(IContainer container, IProgressMonitor monitor) throws CoreException {
        if (container.exists() || !(container instanceof IFolder)) {
            return;
        }
        create(container.getParent(), monitor);
        ((IFolder) container).create(IResource.FORCE, true, monitor);
    }

    static void addTestSourceFolder(IJavaProject project, IFolder folder, IProgressMonitor monitor)
            throws CoreException {
        IPath path = folder.getFullPath();
        IClasspathEntry[] raw = project.getRawClasspath();
        boolean separate = separatesTestCode(project);
        List<IClasspathEntry> entries = new ArrayList<>(raw.length + 1);
        List<IPath> nested = new ArrayList<>();
        boolean present = false;
        boolean changed = false;
        for (IClasspathEntry entry : raw) {
            IClasspathEntry updated = entry;
            if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
                IPath source = entry.getPath();
                if (source.equals(path)) {
                    present = true;
                    // Such as the evosuite-tests folder of earlier versions, which was main code:
                    // the test-only libraries added below are invisible to main code.
                    if (separate && !entry.isTest()) {
                        updated = asTestSource(entry, testOutputLocation(project, raw));
                    }
                } else if (source.isPrefixOf(path)) {
                    // JDT rejects nested source folders unless the outer one excludes the inner.
                    updated = excluding(entry, path.makeRelativeTo(source).addTrailingSeparator());
                } else if (path.isPrefixOf(source)) {
                    nested.add(source.makeRelativeTo(path).addTrailingSeparator());
                }
            }
            changed |= updated != entry;
            entries.add(updated);
        }
        if (!present && separate) {
            entries.add(JavaCore.newSourceEntry(path, new IPath[0], nested.toArray(IPath[]::new),
                    testOutputLocation(project, raw), testAttribute(project)));
            changed = true;
        }
        // An unchanged build path is not written again: every write triggers a build.
        if (changed) {
            project.setRawClasspath(entries.toArray(IClasspathEntry[]::new), monitor);
        }
    }

    private static IClasspathEntry asTestSource(IClasspathEntry entry, IPath defaultOutput) {
        List<IClasspathAttribute> attributes = new ArrayList<>();
        for (IClasspathAttribute attribute : entry.getExtraAttributes()) {
            if (!IClasspathAttribute.TEST.equals(attribute.getName())) {
                attributes.add(attribute);
            }
        }
        attributes.add(JavaCore.newClasspathAttribute(IClasspathAttribute.TEST, "true"));
        IPath output = entry.getOutputLocation() != null ? entry.getOutputLocation() : defaultOutput;
        return JavaCore.newSourceEntry(entry.getPath(), entry.getInclusionPatterns(), entry.getExclusionPatterns(),
                output, attributes.toArray(IClasspathAttribute[]::new));
    }

    /**
     * JDT requires test sources to compile into a folder of their own: the output folder of an
     * existing test source folder, else {@code test-classes} beside a Maven-style
     * {@code classes}, else the default output folder's name with {@code -test} appended.
     */
    static IPath testOutputLocation(IJavaProject project, IClasspathEntry[] raw) throws CoreException {
        for (IClasspathEntry entry : raw) {
            if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE && entry.isTest()
                    && entry.getOutputLocation() != null) {
                return entry.getOutputLocation();
            }
        }
        IPath output = project.getOutputLocation();
        if ("classes".equals(output.lastSegment())) {
            return output.removeLastSegments(1).append("test-classes");
        }
        return output.removeLastSegments(1).append(output.lastSegment() + "-test");
    }

    private static IClasspathEntry excluding(IClasspathEntry entry, IPath pattern) {
        List<IPath> exclusions = new ArrayList<>(Arrays.asList(entry.getExclusionPatterns()));
        if (exclusions.contains(pattern)) {
            return entry;
        }
        exclusions.add(pattern);
        return JavaCore.newSourceEntry(entry.getPath(), entry.getInclusionPatterns(),
                exclusions.toArray(IPath[]::new), entry.getOutputLocation(), entry.getExtraAttributes());
    }

    /**
     * Adds JUnit and the EvoSuite runtime as test libraries unless the project already has them,
     * from any source: a Maven or Gradle dependency counts as much as a container.
     *
     * @return the libraries that were added, for reporting
     */
    public static List<String> addMissingLibraries(IJavaProject project, GenerationOptions options,
                                                   IProgressMonitor monitor) throws CoreException {
        List<IClasspathEntry> additions = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (GenerationOptions.JUNIT4.equals(options.testFormat)) {
            if (project.findType("org.junit.Test") == null) {
                additions.add(JavaCore.newContainerEntry(JUnitCore.JUNIT4_CONTAINER_PATH, null, testAttribute(project), false));
                names.add("JUnit 4");
            }
        } else if (project.findType("org.junit.jupiter.api.Test") == null) {
            additions.add(JavaCore.newContainerEntry(JUnitCore.JUNIT5_CONTAINER_PATH, null, testAttribute(project), false));
            names.add("JUnit 5");
        }
        if (project.findType("org.evosuite.runtime.EvoRunnerParameters") == null) {
            additions.add(JavaCore.newContainerEntry(EvoSuiteClasspathContainer.PATH, null, testAttribute(project), false));
            names.add("EvoSuite Runtime");
        }
        if (!additions.isEmpty()) {
            List<IClasspathEntry> entries = new ArrayList<>(Arrays.asList(project.getRawClasspath()));
            entries.addAll(additions);
            project.setRawClasspath(entries.toArray(IClasspathEntry[]::new), monitor);
        }
        return names;
    }

    /**
     * Whether the project can have a test source folder. A project whose output folder is the
     * project itself cannot: JDT nests no other output folder inside it, requires one for test
     * sources, and lets no other source folder share the project's. There the test folder is only
     * excluded from the enclosing source folder, so that the generated tests do not compile as
     * main code in the wrong package, and the libraries become ordinary build path entries.
     */
    public static boolean separatesTestCode(IJavaProject project) throws CoreException {
        return project.getOutputLocation().segmentCount() > 1;
    }

    private static IClasspathAttribute[] testAttribute(IJavaProject project) throws CoreException {
        return separatesTestCode(project)
                ? new IClasspathAttribute[]{JavaCore.newClasspathAttribute(IClasspathAttribute.TEST, "true")}
                : new IClasspathAttribute[0];
    }
}
