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
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpServer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.junit.JUnitCore;
import org.evosuite.eclipse.Activator;
import org.evosuite.eclipse.TestProjects;
import org.evosuite.eclipse.classpath.EvoSuiteClasspathContainer;
import org.evosuite.eclipse.preferences.EvoSuitePreferences;
import org.evosuite.eclipse.preferences.LlmApiKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End to end: the bundled EvoSuite generates tests for a class of a fresh Java project, and the
 * project compiles them. Takes about a minute; skip with {@code -Devosuite.it.skip=true}.
 */
public class TestGenerationJobTest {

    private static final String CALCULATOR = "package demo;\n"
            + "public class Calculator {\n"
            + "    public int max(int a, int b) {\n"
            + "        if (a > b) {\n"
            + "            return a;\n"
            + "        }\n"
            + "        return b;\n"
            + "    }\n"
            + "    public String sign(int x) {\n"
            + "        if (x < 0) {\n"
            + "            return \"negative\";\n"
            + "        }\n"
            + "        return x == 0 ? \"zero\" : \"positive\";\n"
            + "    }\n"
            + "}\n";

    /** A class with branches that only the right magic values reach, which its tests below know. */
    private static final String VAULT = "package demo;\n"
            + "public class Vault {\n"
            + "    private boolean open;\n"
            + "    private int attempts;\n"
            + "    public String unlock(String code, int pin) {\n"
            + "        attempts++;\n"
            + "        if (code == null) {\n"
            + "            return \"null\";\n"
            + "        }\n"
            + "        if (code.hashCode() == 1544637504 && code.length() == 14) {\n"
            + "            if (pin * 31 + 7 == 28466470) {\n"
            + "                open = true;\n"
            + "                return \"open\";\n"
            + "            }\n"
            + "            return \"pin\";\n"
            + "        }\n"
            + "        if (code.length() > 20) {\n"
            + "            return \"long\";\n"
            + "        }\n"
            + "        return \"closed\";\n"
            + "    }\n"
            + "    public int magic(long x) {\n"
            + "        if (x * 3 == 22093554879L) {\n"
            + "            return 1;\n"
            + "        }\n"
            + "        if (x < 0) {\n"
            + "            return -1;\n"
            + "        }\n"
            + "        return 0;\n"
            + "    }\n"
            + "    public boolean isOpen() {\n"
            + "        return open;\n"
            + "    }\n"
            + "    public int getAttempts() {\n"
            + "        return attempts;\n"
            + "    }\n"
            + "}\n";

    /** Hand-written tests of {@link #VAULT}, in a test source folder. */
    private static final String VAULT_TEST = "package demo;\n"
            + "import org.junit.jupiter.api.Test;\n"
            + "import static org.junit.jupiter.api.Assertions.*;\n"
            + "public class VaultJ5Test {\n"
            + "    @Test\n"
            + "    public void unlocksWithMagic() {\n"
            + "        Vault v = new Vault();\n"
            + "        String r = v.unlock(\"XYZZY-42-plugh\", 918273);\n"
            + "        assertEquals(\"open\", r);\n"
            + "        assertTrue(v.isOpen());\n"
            + "    }\n"
            + "    @Test\n"
            + "    void magicNumber() {\n"
            + "        Vault v = new Vault();\n"
            + "        assertEquals(1, v.magic(7364518293L));\n"
            + "    }\n"
            + "}\n";

    /** The calls each test of {@link #VAULT_TEST} makes, outside its assertions; see {@link #calls}. */
    private static final List<List<String>> VAULT_TEST_CALLS = List.of(
            List.of("unlock(\"XYZZY-42-plugh\", 918273)", "isOpen()"), List.of("magic(7364518293L)"));

    private final IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode(EvoSuitePreferences.QUALIFIER);

    @BeforeEach
    public void configure() {
        assumeFalse(Boolean.getBoolean("evosuite.it.skip"), "end-to-end test skipped");
        // Tycho passes an empty property on as the string "null".
        String javaHome = System.getProperty("evosuite.it.javaHome", "");
        preferences.put(EvoSuitePreferences.JAVA_HOME, "null".equals(javaHome) ? "" : javaHome);
    }

    @AfterEach
    public void restore() throws Exception {
        preferences.clear();
    }

    @Test
    public void generatesATestSuiteThatCompiles() throws Exception {
        preferences.putInt(EvoSuitePreferences.SEARCH_BUDGET, 10);
        IJavaProject project = TestProjects.create("generation", "src/main/java", null);
        TestProjects.addSource(project, "src/main/java", "demo.Calculator", CALCULATOR);
        TestProjects.build(project);

        // Run in this thread, so that the test sees the progress the workbench would show.
        TestGenerationJob job = new TestGenerationJob(project, List.of("demo.Calculator"));
        RecordingMonitor monitor = new RecordingMonitor();
        IStatus result = job.run(monitor);

        assertEquals(IStatus.OK, result.getSeverity(), result::toString);
        // The search's own progress moved the bar, not just the steps around it: before
        // the search was given its share, the status bar stopped at 14 of 120 units, 11%.
        assertTrue(monitor.fractionBeforeDone() > 0.9, "progress reached only " + monitor.fractionBeforeDone());
        IFile suite = project.getProject().getFile("src/test/evosuite/demo/Calculator_ESTest.java");
        assertTrue(suite.exists(), "no test suite in the workspace");
        assertEquals(List.of(suite), job.getGeneratedSuites());

        // The suite compiles against the libraries the job added to the build path.
        TestProjects.build(project);
        assertEquals(List.of(), TestProjects.compileErrors(project));
        assertTrue(TestProjects.hasEntry(project, JUnitCore.JUNIT5_CONTAINER_PATH), "JUnit 5 not added");
        assertTrue(TestProjects.hasEntry(project, EvoSuiteClasspathContainer.PATH), "EvoSuite runtime not added");
        // EvoSuite wrote its statistics to the plug-in's state area, not into the project.
        assertFalse(project.getProject().getFolder("evosuite-report").exists(), "report folder in the project");
    }

    /**
     * Tests for one method call only that method, and a second run for another method adds its
     * tests to the suite without touching the first run's tests.
     */
    @Test
    public void methodLevelRunsTargetTheirMethodAndAddToTheSuite() throws Exception {
        preferences.putInt(EvoSuitePreferences.SEARCH_BUDGET, 10);
        IJavaProject project = TestProjects.create("methods", "src/main/java", null);
        TestProjects.addSource(project, "src/main/java", "demo.Calculator", CALCULATOR);
        TestProjects.build(project);
        GenerationOptions options = EvoSuitePreferences.resolve(project.getProject(), null);
        IFile suite = project.getProject().getFile("src/test/evosuite/demo/Calculator_ESTest.java");

        TestGenerationJob signOnly = new TestGenerationJob(project,
                List.of(new GenerationTarget("demo.Calculator", List.of("sign(I)Ljava/lang/String;"))), options,
                SuiteAction.ADD);
        IStatus result = signOnly.run(new NullProgressMonitor());

        assertEquals(IStatus.OK, result.getSeverity(), result::toString);
        assertEquals(List.of(suite), signOnly.getGeneratedSuites());
        String first = read(suite);
        assertTrue(first.contains(".sign("), first);
        assertFalse(first.contains(".max("), "a test calls max:\n" + first);
        List<String> firstTests = tests(first);
        assertFalse(firstTests.isEmpty(), first);

        TestGenerationJob maxOnly = new TestGenerationJob(project,
                List.of(new GenerationTarget("demo.Calculator", List.of("max(II)I"))), options, SuiteAction.ADD);
        result = maxOnly.run(new NullProgressMonitor());

        assertEquals(IStatus.OK, result.getSeverity(), result::toString);
        assertEquals(List.of(suite), maxOnly.getGeneratedSuites());
        assertEquals(List.of(), maxOnly.getComparisons());
        String merged = read(suite);
        for (String test : firstTests) {
            assertTrue(merged.contains(test), "lost or changed:\n" + test + "\nin\n" + merged);
        }
        assertTrue(merged.contains(".max("), merged);
        assertTrue(tests(merged).size() > firstTests.size(), merged);
        TestProjects.build(project);
        assertEquals(List.of(), TestProjects.compileErrors(project));
        // The new suites went through the plug-in's state area, which keeps nothing of them.
        String[] left = Activator.getDefault().getStateLocation().append("generated").toFile().list();
        assertTrue(left == null || left.length == 0, "temporary output left behind");
    }

    /**
     * Extending hand-written tests: EvoSuite parses them, never carves them, starts its search
     * from them and leaves out what running them covers. The new suite compiles, uses the values
     * only the hand-written tests know, and repeats none of them.
     */
    @Test
    public void extendsHandWrittenTestsWithoutRepeatingThem() throws Exception {
        preferences.putInt(EvoSuitePreferences.SEARCH_BUDGET, 10);
        IJavaProject project = vaultProject("extension");
        IFile suite = project.getProject().getFile("src/test/evosuite/demo/Vault_ESTest.java");

        List<String> log = new CopyOnWriteArrayList<>();
        TestGenerationJob job = extend(project, SuiteAction.REPLACE, log);

        assertEquals(IStatus.OK, job.getResult().getSeverity(), job.getResult()::toString);
        assertTrue(job.getResult().getMessage().startsWith("Extended VaultJ5Test for Vault: "),
                job.getResult().getMessage());
        assertSeededByParsingAndRanTheTests(log);
        assertEquals(List.of(suite), job.getGeneratedSuites());
        String source = read(suite);
        List<String> tests = tests(source);
        assertFalse(tests.isEmpty(), source);
        // Only the hand-written tests know this code; the search on its own never finds it.
        assertTrue(source.contains("\"XYZZY-42-plugh\""), "the search did not start from the tests:\n" + source);
        for (String test : tests) {
            assertFalse(VAULT_TEST_CALLS.contains(calls(test)), "repeats a hand-written test:\n" + test);
        }
        assertEquals(VAULT_TEST, read(project.getProject().getFile("src/test/java/demo/VaultJ5Test.java")));
        TestProjects.build(project);
        assertEquals(List.of(), TestProjects.compileErrors(project));
    }

    /**
     * Extending tests of a class that has an EvoSuite suite already: with Add New Tests, the new
     * tests go after the suite's own, which stay as they are.
     */
    @Test
    public void extendingAddsTheNewTestsToTheExistingSuite() throws Exception {
        preferences.putInt(EvoSuitePreferences.SEARCH_BUDGET, 10);
        IJavaProject project = vaultProject("extension-add");
        IFile suite = project.getProject().getFile("src/test/evosuite/demo/Vault_ESTest.java");
        TestGenerationJob plain = new TestGenerationJob(project, List.of("demo.Vault"));
        IStatus result = plain.run(new NullProgressMonitor());
        assertEquals(IStatus.OK, result.getSeverity(), result::toString);
        String before = read(suite);
        List<String> existingTests = tests(before);
        assertFalse(before.contains("XYZZY-42-plugh"), "the plain search found the magic code:\n" + before);

        List<String> log = new CopyOnWriteArrayList<>();
        TestGenerationJob job = extend(project, SuiteAction.ADD, log);

        assertEquals(IStatus.OK, job.getResult().getSeverity(), job.getResult()::toString);
        assertSeededByParsingAndRanTheTests(log);
        assertEquals(List.of(suite), job.getGeneratedSuites());
        assertEquals(List.of(), job.getComparisons());
        String merged = read(suite);
        List<String> mergedTests = tests(merged);
        assertTrue(mergedTests.size() > existingTests.size(), merged);
        assertEquals(existingTests, mergedTests.subList(0, existingTests.size()), "existing tests changed");
        assertTrue(merged.contains("\"XYZZY-42-plugh\""), merged);
        for (String test : mergedTests.subList(existingTests.size(), mergedTests.size())) {
            assertFalse(VAULT_TEST_CALLS.contains(calls(test)), "repeats a hand-written test:\n" + test);
        }
        assertTrue(job.getResult().getMessage().contains("added to Vault_ESTest.java"), job.getResult().getMessage());
        TestProjects.build(project);
        assertEquals(List.of(), TestProjects.compileErrors(project));
    }

    /** A project with {@link #VAULT} and, in a test source folder, {@link #VAULT_TEST}, built. */
    private static IJavaProject vaultProject(String name) throws Exception {
        IJavaProject project = TestProjects.create(name, "src/main/java", "src/test/java");
        TestProjects.addTestContainer(project, JUnitCore.JUNIT5_CONTAINER_PATH);
        TestProjects.addSource(project, "src/main/java", "demo.Vault", VAULT);
        TestProjects.addSource(project, "src/test/java", "demo.VaultJ5Test", VAULT_TEST);
        TestProjects.build(project);
        assertEquals(List.of(), TestProjects.compileErrors(project));
        return project;
    }

    /** Runs a job that extends VaultJ5Test, in this thread, with EvoSuite's output in {@code log}. */
    private static TestGenerationJob extend(IJavaProject project, SuiteAction action, List<String> log) {
        GenerationTarget target = GenerationTarget.extending("demo.Vault", List.of(new GenerationTarget.Seed(
                "demo.VaultJ5Test", project.getProject().getFolder("src/test/java").getLocation().toFile())));
        TestGenerationJob job = new TestGenerationJob(project, List.of(target),
                EvoSuitePreferences.resolve(project.getProject(), null), action);
        TestGenerationJob.outputListener = log::add;
        try {
            job.schedule();
            assertTrue(job.join(TimeUnit.MINUTES.toMillis(5), null), "generation did not finish in time");
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        } finally {
            TestGenerationJob.outputListener = line -> {
            };
        }
        return job;
    }

    private static void assertSeededByParsingAndRanTheTests(List<String> log) {
        String output = String.join("\n", log);
        List<String> lines = log.stream().map(String::strip).toList();
        assertTrue(lines.contains("* Using 2 parsed tests from JUnit source for seeding"), output);
        assertTrue(lines.contains("* Executed 2 unit test(s)"), output);
        assertTrue(lines.stream().anyMatch(l -> l.matches("\\* Removing [1-9]\\d* goals already covered by JUnit .*")),
                output);
        assertFalse(Pattern.compile("(?i)carv").matcher(output).find(), output);
    }

    /**
     * The calls a test makes outside its assertions, such as {@code magic(7364518293L)}: equal
     * for a hand-written test and a generated one that does the same.
     */
    private static List<String> calls(String test) {
        List<String> calls = new ArrayList<>();
        for (String line : test.split("\n")) {
            if (line.trim().startsWith("assert")) {
                continue;
            }
            Matcher call = Pattern.compile("\\.(\\w+)\\((.*)\\);").matcher(line);
            if (call.find()) {
                calls.add(call.group(1) + "(" + call.group(2) + ")");
            }
        }
        return calls;
    }

    private static String read(IFile file) throws Exception {
        try (InputStream in = file.getContents()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The test methods of a suite, each from its {@code @Test} to its closing brace. */
    private static List<String> tests(String suite) {
        List<String> tests = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?s)  @Test.*?\n  }\n").matcher(suite);
        while (matcher.find()) {
            tests.add(matcher.group());
        }
        return tests;
    }

    /**
     * The LLM settings reach EvoSuite, and the API key arrives through the environment, at an
     * OpenAI-compatible stand-in that answers every request with the same small test.
     */
    @Test
    public void llmSettingsReachEvoSuiteWithTheKeyInTheEnvironment() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestURI().getPath() + " | "
                    + exchange.getRequestHeaders().getFirst("Authorization") + " | " + body);
            byte[] response = ("{\"id\":\"fake\",\"object\":\"chat.completion\",\"created\":0,"
                    + "\"model\":\"fake-model\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"```java\\n@Test\\n"
                    + "public void test0() {\\n  Calculator c = new Calculator();\\n  c.max(1, 2);\\n}\\n```\"}}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            preferences.putInt(EvoSuitePreferences.SEARCH_BUDGET, 10);
            preferences.put(EvoSuitePreferences.LLM_PROVIDER, GenerationOptions.LLM_OPENAI);
            preferences.put(EvoSuitePreferences.LLM_MODEL, "fake-model");
            preferences.put(EvoSuitePreferences.LLM_BASE_URL,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            preferences.put(EvoSuitePreferences.LLM_SEEDING, "FULL");
            TestGenerationJob.apiKeySource = () -> "test-key";
            IJavaProject project = TestProjects.create("llm", "src/main/java", null);
            TestProjects.addSource(project, "src/main/java", "demo.Calculator", CALCULATOR);
            TestProjects.build(project);

            TestGenerationJob job = new TestGenerationJob(project, List.of("demo.Calculator"));
            job.schedule();
            assertTrue(job.join(TimeUnit.MINUTES.toMillis(5), null), "generation did not finish in time");

            assertEquals(IStatus.OK, job.getResult().getSeverity(), job.getResult()::toString);
            assertTrue(project.getProject().getFile("src/test/evosuite/demo/Calculator_ESTest.java").exists());
            assertFalse(requests.isEmpty(), "EvoSuite never called the LLM");
            for (String request : requests) {
                assertTrue(request.contains("chat/completions | Bearer test-key | "), request);
                assertTrue(request.contains("fake-model"), request);
            }
        } finally {
            server.stop(0);
            TestGenerationJob.apiKeySource = LlmApiKey::get;
        }
    }

    /** Records the work reported to it, as the progress view and status bar receive it. */
    private static final class RecordingMonitor extends NullProgressMonitor {
        private double total;
        private double worked;
        private double workedBeforeDone = -1;

        @Override
        public void beginTask(String name, int totalWork) {
            total = totalWork;
        }

        @Override
        public void worked(int work) {
            internalWorked(work);
        }

        @Override
        public void internalWorked(double work) {
            worked += work;
        }

        @Override
        public void done() {
            if (workedBeforeDone < 0) {
                workedBeforeDone = worked;
            }
        }

        double fractionBeforeDone() {
            return total == 0 ? 0 : (workedBeforeDone < 0 ? worked : workedBeforeDone) / total;
        }
    }

    @Test
    public void cancellingStopsEvoSuiteAndKeepsFinishedSuites() throws Exception {
        preferences.putInt(EvoSuitePreferences.SEARCH_BUDGET, 10);
        IJavaProject project = TestProjects.create("cancellation", "src/main/java", null);
        TestProjects.addSource(project, "src/main/java", "demo.Calculator", CALCULATOR);
        TestProjects.addSource(project, "src/main/java", "demo.Other",
                CALCULATOR.replace("class Calculator", "class Other"));
        TestProjects.build(project);

        TestGenerationJob job = new TestGenerationJob(project, List.of("demo.Calculator", "demo.Other"));
        job.schedule();
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3);
        while (job.getGeneratedSuites().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertEquals(1, job.getGeneratedSuites().size(), "the first class was not done in time");
        // Long enough for the second class's master and client to be running, short of its budget.
        Thread.sleep(TimeUnit.SECONDS.toMillis(4));
        assertTrue(ProcessHandle.current().descendants().anyMatch(ProcessHandle::isAlive), "EvoSuite is not running");
        job.cancel();
        assertTrue(job.join(TimeUnit.SECONDS.toMillis(30), null), "cancelled generation did not stop");

        assertEquals(IStatus.CANCEL, job.getResult().getSeverity());
        assertEquals(0, ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count(),
                "EvoSuite processes outlived the cancelled job");
        IFile first = project.getProject().getFile("src/test/evosuite/demo/Calculator_ESTest.java");
        assertTrue(first.exists() && first.isSynchronized(IResource.DEPTH_ZERO),
                "the suite finished before the cancel is not in the workspace");
    }
}
