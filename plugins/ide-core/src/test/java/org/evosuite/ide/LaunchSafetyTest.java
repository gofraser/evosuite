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
package org.evosuite.ide;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class LaunchSafetyTest {
    @Test void searchOnlyDoesNotReadEitherSecureStore() {
        GenerationOptions options = new GenerationOptions(); options.llmApproach = GenerationOptions.APPROACH_SEARCH_ONLY;
        options.llmProvider = "OPENAI"; options.llmApiKeyServerId = "secret";
        options.pomProperties = Map.of("llm_provider", "ANTHROPIC");
        assertEquals(Map.of(), LlmEnvironment.resolve(options, () -> { fail("read PasswordSafe"); return ""; }, () -> { fail("read Maven password"); return ""; }, Map.of(), ignored -> {}));
    }
    @Test void mavenKeyWinsAndNeverBecomesAnOption() {
        GenerationOptions options = new GenerationOptions(); options.llmProvider = "OPENAI"; options.llmApiKeyServerId = "server";
        assertEquals(Map.of("EVOSUITE_LLM_API_KEY", "key"), LlmEnvironment.resolve(options, () -> { fail("read fallback"); return ""; }, () -> "key", Map.of(), ignored -> {}));
        assertFalse(SettingKeys.all().stream().anyMatch(k -> k.toLowerCase().contains("key")));
    }
    @Test void unsetProjectValuesUseEngineDefaultsAndOutputMustStayInProject() {
        GenerationOptions options = SettingKeys.resolve(Map.of());
        assertEquals(60, options.searchBudget);
        assertThrows(IllegalArgumentException.class, () -> SettingKeys.resolve(Map.of("testFolder", "../outside")));
        assertThrows(IllegalArgumentException.class, () -> SettingKeys.resolve(Map.of("testSuffix", "../Test")));
        assertThrows(IllegalArgumentException.class, () -> SettingKeys.resolve(Map.of("searchBudget", "0")));
        assertEquals("JUNIT4", SettingKeys.junitFormat(true, false));
        assertEquals("JUNIT5", SettingKeys.junitFormat(true, true));
    }
    @Test void markerInACommentDoesNotAuthorizeOverwritingHandwrittenTests() {
        assertFalse(SuiteMerger.isGeneratedSuite("// EvoRunnerParameters\nclass FooTest {}", "FooTest"));
        assertFalse(SuiteMerger.isGeneratedSuite("class FooTest { String text = \"EvoRunnerParameters\"; }", "FooTest"));
        assertTrue(SuiteMerger.isGeneratedSuite("@org.evosuite.runtime.EvoRunnerParameters class FooTest {}", "FooTest"));
    }
    @Test void explicitPomKeyTravelsInEnvironmentAndNotTheCommandLine() {
        GenerationOptions options = new GenerationOptions(); options.llmProvider = "OPENAI";
        options.pomProperties = Map.of("llm_api_key", "pom-secret");
        var env = LlmEnvironment.resolve(options, () -> "stored-key", () -> "", Map.of(), ignored -> {});
        assertEquals("pom-secret", env.get(EvoSuiteCommand.API_KEY_VARIABLE));
        var command = new EvoSuiteCommand("java", "engine.jar", "demo.Foo", List.of("classes"), new java.io.File("."), new java.io.File("tests"), new java.io.File("reports"), options);
        assertFalse(command.toArguments().stream().anyMatch(a -> a.contains("pom-secret") || a.startsWith("-Dllm_api_key=")));
    }
    @Test void cancellationStopsMasterAndClientJvm() throws Exception {
        var lines = new CopyOnWriteArrayList<String>();
        long start = System.nanoTime();
        int exit = EvoSuiteProcess.run(javaCommand("parent"), Path.of(System.getProperty("java.io.tmpdir")).toFile(), new ProgressParser.Listener() {
            public void line(String line) { lines.add(line); }
            public void progress(int search, int coverage) {}
        }, () -> !lines.isEmpty() && System.nanoTime() - start > 600_000_000L);
        assertEquals(EvoSuiteProcess.CANCELLED, exit);
        long pid = Long.parseLong(lines.get(0));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "client JVM survived cancellation");
    }
    private static List<String> javaCommand(String mode) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), FakeProcess.class.getName(), mode);
    }
    public static class FakeProcess {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("parent")) { Process child = new ProcessBuilder(javaCommand("child")).start(); System.out.println(child.pid()); System.out.flush(); }
            Thread.sleep(30000);
        }
    }
}
