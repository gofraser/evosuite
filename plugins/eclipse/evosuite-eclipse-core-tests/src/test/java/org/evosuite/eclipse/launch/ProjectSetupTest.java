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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.junit.JUnitCore;
import org.evosuite.eclipse.TestProjects;
import org.evosuite.eclipse.classpath.EvoSuiteClasspathContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ProjectSetupTest {

    @Test
    public void addsTheTestFolderAsATestSourceFolder() throws Exception {
        IJavaProject project = TestProjects.create("setup-folder", "src/main/java", null);
        GenerationOptions options = new GenerationOptions();
        ProjectSetup.prepareTestFolder(project, options, null);

        IClasspathEntry entry = TestProjects.entry(project, IPath.fromOSString("/setup-folder/src/test/evosuite"));
        assertTrue(project.getProject().getFolder("src/test/evosuite").exists());
        assertEquals(IClasspathEntry.CPE_SOURCE, entry.getEntryKind());
        assertTrue(entry.isTest());
        assertEquals(IPath.fromOSString("/setup-folder/bin-test"), entry.getOutputLocation());

        // A second run changes nothing.
        IClasspathEntry[] before = project.getRawClasspath();
        ProjectSetup.prepareTestFolder(project, options, null);
        assertArrayEquals(before, project.getRawClasspath());
    }

    @Test
    public void anEnclosingSourceFolderExcludesTheTestFolder() throws Exception {
        IJavaProject project = TestProjects.create("setup-nested", "src", null);
        GenerationOptions options = new GenerationOptions();
        ProjectSetup.prepareTestFolder(project, options, null);

        IClasspathEntry src = TestProjects.entry(project, IPath.fromOSString("/setup-nested/src"));
        assertArrayEquals(new IPath[]{IPath.fromOSString("test/evosuite/")}, src.getExclusionPatterns());
        assertTrue(JavaCore.create(project.getProject()).isOnClasspath(
                project.getProject().getFolder("src/test/evosuite")));
    }

    @Test
    public void reusesTheOutputFolderOfExistingTests() throws Exception {
        IJavaProject project = TestProjects.create("setup-output", "src/main/java", "src/test/java");
        ProjectSetup.prepareTestFolder(project, new GenerationOptions(), null);
        IClasspathEntry entry = TestProjects.entry(project, IPath.fromOSString("/setup-output/src/test/evosuite"));
        assertEquals(IPath.fromOSString("/setup-output/bin-test"), entry.getOutputLocation());
    }

    @Test
    public void putsTestClassesBesideMavenStyleClasses() throws Exception {
        IJavaProject project = TestProjects.create("setup-maven", "src/main/java", null);
        project.setOutputLocation(IPath.fromOSString("/setup-maven/target/classes"), null);
        ProjectSetup.prepareTestFolder(project, new GenerationOptions(), null);
        IClasspathEntry entry = TestProjects.entry(project, IPath.fromOSString("/setup-maven/src/test/evosuite"));
        assertEquals(IPath.fromOSString("/setup-maven/target/test-classes"), entry.getOutputLocation());
    }

    @Test
    public void aProjectThatIsItsOwnOutputFolderOnlyExcludesTheTestFolder() throws Exception {
        // The simplest Eclipse project layout: sources and classes both in the project root.
        IJavaProject project = TestProjects.create("setup-root", "src/main/java", null);
        project.setRawClasspath(new IClasspathEntry[]{JavaCore.newSourceEntry(IPath.fromOSString("/setup-root")),
                project.getRawClasspath()[1]}, IPath.fromOSString("/setup-root"), null);
        GenerationOptions options = new GenerationOptions();
        ProjectSetup.prepareTestFolder(project, options, null);
        ProjectSetup.addMissingLibraries(project, options, null);

        assertTrue(project.getProject().getFolder("src/test/evosuite").exists());
        assertEquals(null, TestProjects.entry(project, IPath.fromOSString("/setup-root/src/test/evosuite")));
        assertFalse(TestProjects.entry(project, EvoSuiteClasspathContainer.PATH).isTest());
        assertArrayEquals(new IPath[]{IPath.fromOSString("src/test/evosuite/")},
                TestProjects.entry(project, IPath.fromOSString("/setup-root")).getExclusionPatterns());
    }

    @Test
    public void aRootOutputProjectIsNotRewrittenWhenNothingChanges() throws Exception {
        IJavaProject project = TestProjects.create("setup-stable", "src/main/java", null);
        project.setRawClasspath(new IClasspathEntry[]{JavaCore.newSourceEntry(IPath.fromOSString("/setup-stable")),
                project.getRawClasspath()[1]}, IPath.fromOSString("/setup-stable"), null);
        ProjectSetup.prepareTestFolder(project, new GenerationOptions(), null);
        long stamp = project.getProject().getFile(".classpath").getModificationStamp();
        ProjectSetup.prepareTestFolder(project, new GenerationOptions(), null);
        assertEquals(stamp, project.getProject().getFile(".classpath").getModificationStamp());
    }

    @Test
    public void testLibrariesStayOffTheClasspathEvenAsMainEntries() throws Exception {
        IJavaProject project = TestProjects.create("setup-root-cp", "src/main/java", null);
        project.setRawClasspath(new IClasspathEntry[]{JavaCore.newSourceEntry(IPath.fromOSString("/setup-root-cp")),
                project.getRawClasspath()[1]}, IPath.fromOSString("/setup-root-cp"), null);
        ProjectSetup.addMissingLibraries(project, new GenerationOptions(), null);
        assertFalse(TestProjects.entry(project, EvoSuiteClasspathContainer.PATH).isTest());
        assertEquals(List.of(project.getProject().getLocation().toOSString()), ProjectSetup.runtimeClasspath(project));
    }

    @Test
    public void anOldMainSourceFolderBecomesATestSourceFolder() throws Exception {
        IJavaProject project = TestProjects.create("setup-upgrade", "src", null);
        IClasspathEntry[] raw = project.getRawClasspath();
        TestProjects.folder(project.getProject(), "evosuite-tests");
        project.setRawClasspath(new IClasspathEntry[]{raw[0],
                JavaCore.newSourceEntry(IPath.fromOSString("/setup-upgrade/evosuite-tests")), raw[1]}, null);
        GenerationOptions options = new GenerationOptions();
        options.testFolder = "evosuite-tests";
        ProjectSetup.prepareTestFolder(project, options, null);

        IClasspathEntry entry = TestProjects.entry(project, IPath.fromOSString("/setup-upgrade/evosuite-tests"));
        assertTrue(entry.isTest());
        assertEquals(IPath.fromOSString("/setup-upgrade/bin-test"), entry.getOutputLocation());
        assertEquals(3, project.getRawClasspath().length);
    }

    @Test
    public void aTestFolderExcludesTheSourceFoldersItEncloses() throws Exception {
        IJavaProject project = TestProjects.create("setup-enclosing", "src/main/java", "src/test/java");
        GenerationOptions options = new GenerationOptions();
        options.testFolder = "src/test";
        ProjectSetup.prepareTestFolder(project, options, null);
        assertArrayEquals(new IPath[]{IPath.fromOSString("java/")},
                TestProjects.entry(project, IPath.fromOSString("/setup-enclosing/src/test")).getExclusionPatterns());
    }

    @Test
    public void rejectsTestFoldersOutsideTheProject() throws Exception {
        IJavaProject project = TestProjects.create("setup-invalid", "src/main/java", null);
        for (String folder : List.of("../tests", "/tests", ".", "a/../../b")) {
            GenerationOptions options = new GenerationOptions();
            options.testFolder = folder;
            CoreException e = assertThrows(CoreException.class,
                    () -> ProjectSetup.testFolder(project.getProject(), options), folder);
            assertTrue(e.getStatus().getMessage().contains("not a folder inside the project"), folder);
        }
    }

    @Test
    public void readsTheJavaVersion(@TempDir Path home) throws Exception {
        assertEquals(8, ProjectSetup.featureRelease("1.8.0_352"));
        assertEquals(17, ProjectSetup.featureRelease("17.0.5"));
        assertEquals(21, ProjectSetup.featureRelease("21"));
        assertEquals(27, ProjectSetup.featureRelease("27-ea"));
        assertEquals(0, ProjectSetup.featureRelease("unknown"));

        assertEquals(0, ProjectSetup.javaVersion(home.toFile()));
        Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"Amazon\"\nJAVA_VERSION=\"1.8.0_352\"\n");
        assertEquals(8, ProjectSetup.javaVersion(home.toFile()));
    }

    @Test
    public void noticesAJreWithoutJavac(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve("bin"));
        Files.createFile(home.resolve("bin/java"));
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"21.0.1\"\n");
        IJavaProject project = TestProjects.create("setup-jre", "src/main/java", null);
        GenerationOptions options = new GenerationOptions();
        options.javaHome = home.toString();

        ProjectSetup.JavaInstallation java = ProjectSetup.javaInstallation(project, options);
        assertEquals(home.resolve("bin/java").toFile(), java.executable);
        assertEquals(21, java.version);
        assertFalse(java.hasCompiler);
        assertTrue(java.readsArgumentFiles());

        Files.createFile(home.resolve("bin/javac"));
        assertTrue(ProjectSetup.javaInstallation(project, options).hasCompiler);
    }

    @Test
    public void leavesTheBuildPathAloneWhenAskedTo() throws Exception {
        IJavaProject project = TestProjects.create("setup-off", "src/main/java", null);
        GenerationOptions options = new GenerationOptions();
        options.configureBuildPath = false;
        IClasspathEntry[] before = project.getRawClasspath();
        ProjectSetup.prepareTestFolder(project, options, null);
        assertArrayEquals(before, project.getRawClasspath());
    }

    @Test
    public void addsJUnitAndTheRuntimeOnce() throws Exception {
        IJavaProject project = TestProjects.create("setup-libraries", "src/main/java", null);
        GenerationOptions options = new GenerationOptions();
        assertEquals(List.of("JUnit 5", "EvoSuite Runtime"), ProjectSetup.addMissingLibraries(project, options, null));
        assertTrue(TestProjects.entry(project, JUnitCore.JUNIT5_CONTAINER_PATH).isTest());
        assertTrue(TestProjects.entry(project, EvoSuiteClasspathContainer.PATH).isTest());
        assertEquals(List.of(), ProjectSetup.addMissingLibraries(project, options, null));
        // The container resolves to the runtime jar that ships with the plug-in.
        assertTrue(project.findType("org.evosuite.runtime.EvoRunnerParameters") != null);
    }

    @Test
    public void addsJUnit4ForJUnit4Tests() throws Exception {
        IJavaProject project = TestProjects.create("setup-junit4", "src/main/java", null);
        GenerationOptions options = new GenerationOptions();
        options.testFormat = GenerationOptions.JUNIT4;
        assertEquals(List.of("JUnit 4", "EvoSuite Runtime"), ProjectSetup.addMissingLibraries(project, options, null));
    }

    @Test
    public void theClasspathHasOutputAndLibrariesButNoTestCodeOrJre() throws Exception {
        IJavaProject project = TestProjects.create("setup-classpath", "src/main/java", "src/test/java");
        File jar = File.createTempFile("library", ".jar");
        jar.deleteOnExit();
        java.util.List<IClasspathEntry> entries = new java.util.ArrayList<>(List.of(project.getRawClasspath()));
        entries.add(JavaCore.newLibraryEntry(IPath.fromFile(jar), null, null));
        project.setRawClasspath(entries.toArray(IClasspathEntry[]::new), null);
        ProjectSetup.addMissingLibraries(project, new GenerationOptions(), null);

        List<String> classpath = ProjectSetup.runtimeClasspath(project);
        String bin = project.getProject().getFolder("bin").getLocation().toOSString();
        assertEquals(List.of(bin, jar.getAbsolutePath()), classpath);
        assertFalse(classpath.stream().anyMatch(p -> p.contains("evosuite-standalone-runtime")));
    }
}
