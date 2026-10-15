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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class EvoSuiteCommandTest {

    private static List<String> arguments(GenerationOptions options) {
        return new EvoSuiteCommand("/jdk/bin/java", "/plugin/evosuite.jar", "demo.Calculator",
                List.of("/p/bin", "/p/lib/a.jar"), new File("/p"), new File("/p/src/test/evosuite"),
                new File("/state/reports"), options).toArguments();
    }

    @Test
    public void runsTheJarOnTheProjectClasspath() {
        List<String> args = arguments(new GenerationOptions());
        assertEquals(List.of("/jdk/bin/java", "-jar", "/plugin/evosuite.jar", "-class", "demo.Calculator",
                "-projectCP", "/p/bin" + File.pathSeparator + "/p/lib/a.jar", "-base_dir",
                new File("/p").getAbsolutePath()), args.subList(0, 9));
        assertTrue(args.contains("-Dtest_dir=" + new File("/p/src/test/evosuite").getAbsolutePath()));
        assertTrue(args.contains("-Dreport_dir=" + new File("/state/reports").getAbsolutePath()));
        assertTrue(args.contains("-Dshow_progress=true"));
    }

    @Test
    public void passesTheSettings() {
        GenerationOptions options = new GenerationOptions();
        options.searchBudget = 15;
        options.assertions = false;
        options.minimize = false;
        options.testFormat = GenerationOptions.JUNIT4;
        options.testSuffix = "_GenTest";
        List<String> args = arguments(options);
        assertTrue(args.contains("-Dsearch_budget=15"));
        assertTrue(args.contains("-Dassertions=false"));
        assertTrue(args.contains("-Dminimize=false"));
        assertTrue(args.contains("-Dtest_format=JUNIT4"));
        assertTrue(args.contains("-Djunit_suffix=_GenTest"));
    }

    @Test
    public void leavesEngineDefaultsAloneWhenUnset() {
        List<String> args = arguments(new GenerationOptions());
        assertFalse(args.contains("-criterion"));
        assertFalse(args.contains("-mem"));
    }

    @Test
    public void passesCriteriaAndMemoryWhenSet() {
        GenerationOptions options = new GenerationOptions();
        options.criterion = "LINE:BRANCH";
        options.clientMemory = 3000;
        List<String> args = arguments(options);
        assertEquals("LINE:BRANCH", args.get(args.indexOf("-criterion") + 1));
        assertEquals("3000", args.get(args.indexOf("-mem") + 1));
    }

    @Test
    public void extraArgumentsComeLastSoTheyWin() {
        GenerationOptions options = new GenerationOptions();
        options.extraArguments = "-Dsearch_budget=5 -Dsandbox=false";
        List<String> args = arguments(options);
        assertEquals(List.of("-Dsearch_budget=5", "-Dsandbox=false"), args.subList(args.size() - 2, args.size()));
    }

    @Test
    public void splitsArgumentsLikeAShell() {
        assertEquals(List.of(), EvoSuiteCommand.splitArguments("  "));
        assertEquals(List.of("-Da=1", "-Db=two words", "-Dc=it's"),
                EvoSuiteCommand.splitArguments(" -Da=1  -Db=\"two words\" \"-Dc=it's\""));
        assertEquals(List.of("-Dx=a b"), EvoSuiteCommand.splitArguments("-Dx=a\\ b"));
        assertEquals(List.of("-Dp='q'"), EvoSuiteCommand.splitArguments("\"-Dp='q'\""));
        assertEquals(List.of(""), EvoSuiteCommand.splitArguments("''"));
    }

    @Test
    public void keepsBackslashesInWindowsPaths() {
        assertEquals(List.of("-Dtest_dir=C:\\work\\tests"),
                EvoSuiteCommand.splitArguments("-Dtest_dir=C:\\work\\tests"));
        assertEquals(List.of("-Dtest_dir=C:\\my work"),
                EvoSuiteCommand.splitArguments("\"-Dtest_dir=C:\\my work\""));
    }

    @Test
    public void shortCommandLinesStayInline() throws Exception {
        List<String> args = List.of("java", "-jar", "evosuite.jar");
        assertEquals(args, EvoSuiteCommand.fitCommandLine(args, new File("unused"), 100));
    }

    @Test
    public void longCommandLinesGoThroughAnArgumentFileTheLauncherReads(@TempDir Path dir) throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        String probe = "a b\\c \"q\" " + "x".repeat(200);
        List<String> args = List.of(java, "-Dprobe=" + probe, "-XshowSettings:properties", "-version");
        File argumentFile = dir.resolve("args.txt").toFile();

        List<String> fitted = EvoSuiteCommand.fitCommandLine(args, argumentFile, 100);
        assertEquals(List.of(java, "@" + argumentFile.getAbsolutePath()), fitted);

        Process process = new ProcessBuilder(fitted).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        process.waitFor();
        assertTrue(output.contains("probe = " + probe), output);
    }

    @Test
    public void locatesTheSuiteFile() {
        assertEquals(new File(new File("/t"), "demo" + File.separator + "sub" + File.separator + "Calc_ESTest.java"),
                EvoSuiteCommand.suiteFile(new File("/t"), "demo.sub.Calc", "_ESTest"));
        assertEquals(new File(new File("/t"), "Calc_ESTest.java"),
                EvoSuiteCommand.suiteFile(new File("/t"), "Calc", "_ESTest"));
    }
}
