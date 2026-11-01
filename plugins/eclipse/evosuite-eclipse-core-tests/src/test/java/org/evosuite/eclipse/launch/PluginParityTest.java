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

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.evosuite.eclipse.TestProjects;
import org.evosuite.eclipse.preferences.EvoSuitePreferences;
import org.evosuite.eclipse.ui.SuiteComparison;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Regression coverage for functionality ported from the modern IntelliJ plugin. */
public class PluginParityTest {
    private static List<String> arguments(GenerationOptions options) {
        return new EvoSuiteCommand(
                        "java",
                        "engine.jar",
                        "demo.Foo",
                        List.of("classes"),
                        new File("."),
                        new File("tests"),
                        new File("reports"),
                        options)
                .toArguments(21);
    }

    @Test
    public void newSettingsPersistAndReachTheEngine() throws Exception {
        var project = TestProjects.create("parity-settings", "src", null);
        var prefs = InstanceScope.INSTANCE.getNode(EvoSuitePreferences.QUALIFIER);
        try {
            prefs.putBoolean(SettingKeys.MINIMIZE_VALUES, true);
            prefs.putBoolean(SettingKeys.NO_RUNTIME_DEPENDENCY, true);
            prefs.put(SettingKeys.RANDOM_SEED, "-123456789012345");
            prefs.put(SettingKeys.LLM_PROVIDER, "OLLAMA");
            prefs.put(SettingKeys.LLM_MODEL, "local");
            prefs.put(SettingKeys.LLM_CONTEXT_MODE, "SOURCE_CODE");
            prefs.putInt(SettingKeys.LLM_MAX_TOKENS, 1024);
            prefs.putInt(SettingKeys.LLM_TIMEOUT_SECONDS, 15);
            prefs.putInt(SettingKeys.LLM_RETRY_MAX_ATTEMPTS, 3);
            prefs.put(SettingKeys.LLM_TEMPERATURE, "0.2");
            var options = EvoSuitePreferences.load(project.getProject());
            options.llmSourcePath = "/custom sources/Foo.java";
            var args = arguments(options);
            assertTrue(
                    args.containsAll(
                            List.of(
                                    "-Dminimize_values=true",
                                    "-Dno_runtime_dependency=true",
                                    "-Drandom_seed=-123456789012345",
                                    "-Dllm_sut_context_mode=SOURCE_CODE",
                                    "-Dllm_source_path=/custom sources/Foo.java",
                                    "-Dllm_max_tokens=1024",
                                    "-Dllm_timeout_seconds=15",
                                    "-Dllm_retry_max_attempts=3",
                                    "-Dllm_temperature=0.2")),
                    args.toString());
            assertEquals("-Djava.security.manager=allow", args.get(1));
            options.extraArguments = "-Dminimize_values=false -Dno_runtime_dependency=false";
            assertFalse(options.effectiveNoRuntimeDependency());
            assertTrue(
                    arguments(options).lastIndexOf("-Dminimize_values=false")
                            > arguments(options).indexOf("-Dminimize_values=true"));
        } finally {
            prefs.clear();
        }
    }

    @Test
    public void coverageRunsCompiledTestsWithoutParsingSeedsOrUsingLlm() {
        var options = new GenerationOptions();
        options.extraArguments = "-Dllm_provider=OPENAI -Dllm_api_key=secret";
        var args =
                new EvoSuiteCommand(
                                "java",
                                "engine.jar",
                                "demo.Foo",
                                List.of("test-classes"),
                                new File("."),
                                new File("tests"),
                                new File("reports"),
                                options)
                        .seededBy(List.of("demo.FooTest"), new File("src/test/java"))
                        .measureCoverage()
                        .toArguments(17);
        assertTrue(args.contains("-measureCoverage"));
        assertTrue(args.contains("-Djunit=demo.FooTest"));
        assertFalse(
                args.stream()
                        .anyMatch(
                                a ->
                                        a.startsWith("-Dtest_factory=")
                                                || a.startsWith("-Dselected_junit=")
                                                || a.contains("secret")));
        assertEquals(
                "-Dllm_provider=NONE",
                args.stream().filter(a -> a.startsWith("-Dllm_provider=")).toList().getLast());
    }

    @Test
    public void runtimeFreeOutputOnlyAddsJUnit() throws Exception {
        var project = TestProjects.create("parity-runtime-free", "src", null);
        var options = new GenerationOptions();
        options.noRuntimeDependency = true;
        var added = ProjectSetup.addMissingLibraries(project, options, new NullProgressMonitor());
        assertFalse(added.stream().anyMatch(s -> s.contains("Runtime")), added.toString());
        assertNull(project.findType("org.evosuite.runtime.EvoRunnerParameters"));
        assertNotNull(project.findType("org.junit.jupiter.api.Test"));
    }

    @Test
    public void runtimeFreeOwnershipRequiresLocalRecordAndARealComment(@TempDir Path dir)
            throws Exception {
        File suite = dir.resolve("Foo_ESTest.java").toFile();
        String generated = GeneratedSuiteRegistry.decorate(suite, "public class Foo_ESTest {}\n");
        Files.writeString(suite.toPath(), generated);
        assertFalse(EvoSuiteCommand.isEvoSuiteSuite(suite));
        GeneratedSuiteRegistry.record(suite, generated);
        assertTrue(EvoSuiteCommand.isEvoSuiteSuite(suite));
        File copied = dir.resolve("Copied.java").toFile();
        Files.writeString(copied.toPath(), generated);
        assertFalse(
                EvoSuiteCommand.isEvoSuiteSuite(copied),
                "ownership must not transfer with copied source");
        Files.writeString(
                suite.toPath(),
                "public class Foo_ESTest { String text = \""
                        + generated.lines().findFirst().orElseThrow()
                        + "\"; }");
        assertFalse(
                EvoSuiteCommand.isEvoSuiteSuite(suite), "a string literal is not a marker comment");
        Files.writeString(suite.toPath(), "// @EvoRunnerParameters\npublic class Foo_ESTest {}");
        assertFalse(
                EvoSuiteCommand.isEvoSuiteSuite(suite), "a comment is not the runner annotation");
    }

    @Test
    public void mavenSetupIsIdempotentAndKeepsExistingExecutionAndScopes() throws Exception {
        var options = new GenerationOptions();
        options.testFormat = "JUNIT4";
        options.testFolder = "custom/tests";
        String source =
                "<project><modelVersion>4.0.0</modelVersion><dependencies><dependency><groupId>junit</groupId>"
                    + "<artifactId>junit</artifactId><version>4.13.2</version><scope>provided</scope></dependency></dependencies>"
                    + "<build><plugins><plugin><groupId>org.evosuite.plugins</groupId><artifactId>evosuite-maven-plugin</artifactId>"
                    + "<version>1.2.1-SNAPSHOT</version><executions><execution><id>keep-this-id</id><goals><goal>setup</goal></goals>"
                    + "<configuration><agent>false</agent></configuration></execution></executions></plugin></plugins></build></project>";
        String once = BuildToolSetup.updatePom(source, options, true, true, true, "1.2.1-SNAPSHOT");
        assertEquals(
                once, BuildToolSetup.updatePom(once, options, true, true, true, "1.2.1-SNAPSHOT"));
        assertTrue(
                once.contains("keep-this-id")
                        && once.contains("provided")
                        && once.contains("<agent>false</agent>")
                        && once.contains("<testFolder>custom/tests</testFolder>"),
                once);
        assertEquals(1, once.split("<artifactId>junit</artifactId>", -1).length - 1);
        assertTrue(once.contains("evosuite-standalone-runtime"));
    }

    @Test
    public void gradleSetupIsIdempotentAndUpdatesManagedFolder() {
        for (boolean kotlin : List.of(true, false)) {
            var options = new GenerationOptions();
            options.testFormat = "JUNIT5";
            String once =
                    BuildToolSetup.updateGradle(
                            "plugins { java }\n", options, true, true, kotlin, "1.2.1-SNAPSHOT");
            assertEquals(
                    once,
                    BuildToolSetup.updateGradle(
                            once, options, true, true, kotlin, "1.2.1-SNAPSHOT"));
            options.testFolder = "custom/tests";
            String changed =
                    BuildToolSetup.updateGradle(
                            once, options, false, false, kotlin, "1.2.1-SNAPSHOT");
            assertFalse(changed.contains("src/test/evosuite"));
            assertTrue(
                    changed.contains("custom/tests")
                            && changed.contains("useJUnitPlatform()")
                            && changed.contains("junit-platform-launcher"));
        }
    }

    @Test
    public void gradleJUnit4OverridesInheritedPlatformTasks() {
        for (boolean kotlin : List.of(true, false)) {
            var options = new GenerationOptions();
            options.testFormat = "JUNIT4";
            String source =
                    "// inherited configuration\n"
                        + "tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {"
                        + " useJUnitPlatform() }\n";
            String updated =
                    BuildToolSetup.updateGradle(source, options, true, false, kotlin, "1.2.1");
            assertTrue(updated.startsWith(source));
            assertTrue(updated.indexOf("useJUnit()") > updated.indexOf("useJUnitPlatform()"));
            assertTrue(updated.contains("junit:junit:4.13.2"));
            assertEquals(
                    updated,
                    BuildToolSetup.updateGradle(updated, options, true, false, kotlin, "1.2.1"));
        }
    }

    @Test
    public void missingGradleScriptUsesNearestBuildDsl(@TempDir Path dir) throws Exception {
        var project =
                org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
                        .getRoot()
                        .getProject("parity-gradle-dsl");
        var description =
                org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
                        .newProjectDescription(project.getName());
        description.setLocation(
                org.eclipse.core.runtime.IPath.fromOSString(dir.resolve("app").toString()));
        project.create(description, null);
        project.open(null);
        Files.writeString(dir.resolve("settings.gradle.kts"), "include(\"app\")");
        assertEquals("build.gradle.kts", BuildToolSetup.gradleBuildFile(project).getName());
        Files.delete(dir.resolve("settings.gradle.kts"));
        Files.writeString(dir.resolve("settings.gradle"), "include 'app'");
        assertEquals("build.gradle", BuildToolSetup.gradleBuildFile(project).getName());
        Files.writeString(dir.resolve("settings.gradle.kts"), "include(\"app\")");
        project.getFile("build.gradle").create(new ByteArrayInputStream(new byte[0]), false, null);
        assertEquals(
                "build.gradle",
                BuildToolSetup.gradleBuildFile(project).getName(),
                "an existing subproject script wins over the parent's DSL");
    }

    @Test
    public void delegatedMavenOutputsBecomeAvailableToHistory() throws Exception {
        var project = TestProjects.create("parity-maven-output", "src", null);
        var options = new GenerationOptions();
        var record =
                RunHistory.create(
                        project, GenerationTarget.wholeClass("demo.Foo"), options, null, false);
        var missing =
                RunHistory.create(
                        project, GenerationTarget.wholeClass("demo.Missing"), options, null, false);
        var output = project.getProject().getFile(options.testFolder + "/demo/Foo_ESTest.java");
        var path = output.getLocation().toFile().toPath();
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                "package demo; public class Foo_ESTest { @org.junit.jupiter.api.Test public void"
                    + " test0() {} }");
        RunHistory.update(record, "output", output.getFullPath().toPortableString());
        RunHistory.update(
                missing,
                "output",
                project.getProject()
                        .getFile(options.testFolder + "/demo/Missing_ESTest.java")
                        .getFullPath()
                        .toPortableString());
        assertFalse(output.exists(), "external Maven writes are not yet workspace resources");
        assertEquals(
                List.of(output), MavenGeneration.refreshOutputs(project, List.of(record, missing)));
        assertTrue(record.output().exists());
        assertEquals("1", record.get("tests"));
        RunHistory.finish(record, "Maven stopped / failed", "partial output remains usable");
        RunHistory.finish(missing, "Maven stopped / failed", "no output");
    }

    @Test
    public void historyMasksWholeCredentialTokensAndKeepsFollowingDiagnostics() throws Exception {
        var project = TestProjects.create("parity-history-masking", "src", null);
        var record =
                RunHistory.create(
                        project,
                        GenerationTarget.wholeClass("demo.Foo"),
                        new GenerationOptions(),
                        null,
                        false);
        RunHistory.line(record, "java -Dllm_api_key=secret -Dtimeout=20");
        RunHistory.line(record, "java -Dllm_api_key=\"secret with spaces\" -Drandom_seed=42");
        RunHistory.line(record, "java -Dllm_api_key='another secret' -Dcriterion=BRANCH");
        RunHistory.line(record, "* Total coverage: 72,5%");
        String log = Files.readString(RunHistory.directory(record).resolve("console.log"));
        assertFalse(log.contains("secret"), log);
        assertTrue(log.contains("-Dllm_api_key=**** -Dtimeout=20"), log);
        assertTrue(log.contains("-Dllm_api_key=**** -Drandom_seed=42"), log);
        assertTrue(log.contains("-Dllm_api_key=**** -Dcriterion=BRANCH"), log);
        assertEquals("72.5%", record.get("coverage"));
        RunHistory.finish(record, "Completed", "");
    }

    @Test
    public void historyRedactsKeysAndRetainsReplayAndComparisonData() throws Exception {
        var project = TestProjects.create("parity-history", "src", null);
        var options = new GenerationOptions();
        options.randomSeed = "123";
        options.extraArguments = "-Dllm_api_key=top-secret -Dtimeout=2000";
        options.pomProperties = Map.of("llm_api_key", "another-secret", "minimize_values", "true");
        var target = GenerationTarget.wholeClass("demo.Foo");
        var entry = RunHistory.create(project, target, options, null, false);
        var destination = project.getProject().getFile("src/test/evosuite/demo/Foo_ESTest.java");
        RunHistory.comparisons(
                entry, List.of(new SuiteComparison.Pair(destination, "class Foo_ESTest {}")));
        RunHistory.line(entry, "* Total coverage: 75.5%");
        RunHistory.finish(entry, "Review comparisons", "Review new suite");
        String disk = Files.readString(RunHistory.directory(entry).resolve("run.properties"));
        assertFalse(disk.contains("secret"), disk);
        assertEquals("75.5%", entry.get("coverage"));
        assertEquals("123", entry.options().randomSeed);
        assertEquals(target, entry.target());
        assertEquals("class Foo_ESTest {}", entry.comparisons().getFirst().generatedSource());
        RunHistory.prune(true);
        assertTrue(Files.isDirectory(RunHistory.directory(entry)), "pending comparison deleted");
        RunHistory.reviewed(entry);
    }

    @Test
    public void validatesSeedTemperatureAndOutputPaths() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SettingKeys.resolve(Map.of("randomSeed", "9223372036854775808")));
        assertThrows(
                IllegalArgumentException.class,
                () -> SettingKeys.resolve(Map.of("llmTemperature", "NaN")));
        assertThrows(
                IllegalArgumentException.class,
                () -> SettingKeys.resolve(Map.of("llmMaxTokens", "0")));
        assertThrows(
                IllegalArgumentException.class,
                () -> SettingKeys.resolve(Map.of("testFolder", "../outside")));
        assertEquals(
                "-9223372036854775808",
                SettingKeys.resolve(Map.of("randomSeed", "-9223372036854775808")).randomSeed);
    }
}
