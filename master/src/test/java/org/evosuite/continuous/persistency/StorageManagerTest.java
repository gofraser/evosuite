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
package org.evosuite.continuous.persistency;

import org.apache.commons.io.FileUtils;
import com.examples.with.different.packagename.continuous.MoreBranches;
import com.examples.with.different.packagename.continuous.SomeBranches;
import org.evosuite.Properties;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.continuous.project.ProjectAnalyzer;
import org.evosuite.continuous.project.ProjectStaticData;
import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.evosuite.xsd.Project;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class StorageManagerTest {

    private final String defaultCtgDir = Properties.CTG_DIR;

    @AfterEach
    public void restoreProperties() {
        Properties.CTG_DIR = defaultCtgDir;
    }

    @Test
    public void testDefaultProjectInfo() {

        StorageManager sm = new StorageManager();
        sm.clean();

        try {
            Project project = StorageManager.getDatabaseProject();
            Assertions.assertNotNull(project);
        } finally {
            sm.clean();
        }
    }


    @Test
    public void extractClassNameTest() {
        String z = File.separator;
        String base = z + "some" + z + "thing" + z;
        String packageName = "foo";
        String className = "boiade";
        String full = base + packageName + z + className + ".java";

        StorageManager storage = new StorageManager();
        String result = storage.extractClassName(new File(base), new File(full));

        Assertions.assertEquals(packageName + "." + className, result);
    }

    @Test
    public void testGetDatabaseProjectFallsBackWhenProjectInfoIsCorrupted() throws Exception {
        Path tempCtgRoot = Files.createTempDirectory("evosuite-ctg-corrupt-");
        Properties.CTG_DIR = tempCtgRoot.toString();
        Files.createDirectories(tempCtgRoot);
        Path projectInfo = tempCtgRoot.resolve(Properties.CTG_PROJECT_INFO);
        Files.write(projectInfo, "<project><broken".getBytes(StandardCharsets.UTF_8));

        Project project = StorageManager.getDatabaseProject();
        Assertions.assertNotNull(project);
        Assertions.assertEquals(BigInteger.ZERO, project.getTotalNumberOfTestableClasses());
        Assertions.assertNotNull(project.getCut());
        Assertions.assertTrue(project.getCut().isEmpty());

        FileUtils.deleteDirectory(tempCtgRoot.toFile());
    }

    @Test
    public void testGatherGeneratedTestsOnDiskIgnoresScaffoldingAndAuxiliaryArtifacts() throws Exception {
        Path tempCtgRoot = Files.createTempDirectory("evosuite-ctg-gather-");
        Properties.CTG_DIR = tempCtgRoot.toString();
        StorageManager storage = new StorageManager();
        Assertions.assertTrue(storage.createNewTmpFolders());

        Path tmpTests = storage.getTmpTests().toPath();
        Path tmpReports = storage.getTmpReports().toPath();
        Path tmpSeeds = storage.getTmpSeeds().toPath();
        Files.createDirectories(tmpTests.resolve("com/foo"));

        Files.write(tmpTests.resolve("com/foo/Foo_ESTest.java"),
                "public class Foo_ESTest {}".getBytes(StandardCharsets.UTF_8));
        Files.write(tmpTests.resolve("com/foo/Foo_ESTest_scaffolding.java"),
                "public class Foo_ESTest_scaffolding {}".getBytes(StandardCharsets.UTF_8));
        Files.write(tmpTests.resolve("com/foo/.scaffolding_list.tmp"),
                "com.foo.InitTarget".getBytes(StandardCharsets.UTF_8));

        String csv =
                "TARGET_CLASS,Length,Total_Time,Size,BranchCoverage,BranchCoverageBitString\n"
                        + "com.foo.Foo,1,1000,1,0.5,10\n";
        Files.write(tmpReports.resolve("Foo.csv"), csv.getBytes(StandardCharsets.UTF_8));
        Files.write(tmpSeeds.resolve("com.foo.Foo." + Properties.CTG_SEEDS_EXT),
                "seed".getBytes(StandardCharsets.UTF_8));

        List<StorageManager.TestsOnDisk> suites = storage.gatherGeneratedTestsOnDisk();
        Assertions.assertEquals(1, suites.size());
        Assertions.assertEquals("com.foo.Foo", suites.get(0).cut);
        Assertions.assertEquals("Foo_ESTest.java", suites.get(0).testSuite.getName());
        Assertions.assertNotNull(suites.get(0).serializedSuite);

        storage.clean();
        FileUtils.deleteDirectory(tempCtgRoot.toFile());
    }

    @Test
    public void aRunOnSelectedClassesKeepsTheTestsOfTheOthers() throws Exception {
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
        Path tempCtgRoot = Files.createTempDirectory("evosuite-ctg-selected-");
        Properties.CTG_DIR = tempCtgRoot.toString();
        String kept = SomeBranches.class.getName();
        String selected = MoreBranches.class.getName();
        try {
            // first run: tests are generated for SomeBranches
            StorageManager first = new StorageManager();
            Assertions.assertTrue(first.createNewTmpFolders());
            writeGeneratedSuite(first, kept);
            ProjectStaticData all = new ProjectAnalyzer(new String[]{kept}).analyze();
            first.mergeAndCommitChanges(all, null);
            Assertions.assertTrue(StorageManager.hasBestTestSuite(kept));

            // second run: only MoreBranches is selected, and SomeBranches was not even analyzed
            StorageManager second = new StorageManager();
            Assertions.assertTrue(second.createNewTmpFolders());
            writeJobLogs(second, selected); // its job ran, but produced no tests
            ProjectStaticData some = new ProjectAnalyzer(new String[]{selected}).analyze();
            second.mergeAndCommitChanges(some, new String[]{selected});

            Assertions.assertTrue(StorageManager.hasBestTestSuite(kept),
                    "a run on selected classes deleted the tests of another class");
            Assertions.assertTrue(StorageManager.getDatabaseProject().getCut().stream()
                    .anyMatch(cut -> cut.getFullNameOfTargetClass().equals(kept)));
        } finally {
            FileUtils.deleteDirectory(tempCtgRoot.toFile());
        }
    }

    private static void writeGeneratedSuite(StorageManager storage, String cut) throws Exception {
        String simpleName = cut.substring(cut.lastIndexOf('.') + 1);
        Path test = storage.getTmpTests().toPath().resolve(cut.replace('.', '/') + "_ESTest.java");
        Files.createDirectories(test.getParent());
        Files.write(test, ("public class " + simpleName + "_ESTest {}").getBytes(StandardCharsets.UTF_8));

        String csv = "TARGET_CLASS,Length,Total_Time,Size,BranchCoverage,BranchCoverageBitString\n"
                + cut + ",1,1000,1,0.5,10\n";
        Files.write(storage.getTmpReports().toPath().resolve(simpleName + ".csv"),
                csv.getBytes(StandardCharsets.UTF_8));

        writeJobLogs(storage, cut);
    }

    private static void writeJobLogs(StorageManager storage, String cut) throws Exception {
        Path logs = storage.getTmpLogs().toPath().resolve(cut);
        Files.createDirectories(logs);
        for (String log : new String[]{"std_err_CLIENT.log", "std_out_CLIENT.log",
                "std_err_MASTER.log", "std_out_MASTER.log"}) {
            Files.write(logs.resolve(log), new byte[0]);
        }
    }
}
