/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.continuous.persistency;

import org.apache.commons.io.FileUtils;
import org.evosuite.Properties;
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
}
