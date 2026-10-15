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
package org.evosuite.maventest;

import org.apache.commons.io.FileUtils;
import org.apache.maven.it.VerificationException;
import org.apache.maven.it.Verifier;
import org.evosuite.runtime.InitializingListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class MavenPluginIT {

    private static final long timeoutInMs = 3 * 60 * 1_000;

    private final Path projects = Paths.get("projects");
    private final Path simple = projects.resolve("SimpleModule");
    private final Path dependency = projects.resolve("ModuleWithOneDependency");
    private final Path env = projects.resolve("EnvModule");
    private final Path coverage = projects.resolve("CoverageModule");
    private final Path extension = projects.resolve("ExtensionModeModule");
    private final Path noRuntime = Paths.get("standalone-projects").resolve("NoRuntimeModule");
    private final Path llm = Paths.get("standalone-projects").resolve("LlmModule");

    private final String srcEvo = "src/evo";

    @BeforeEach
    @AfterEach
    public void clean() throws Exception{
        Verifier verifier  = getVerifier(projects);
        verifier.addCliOption("evosuite:clean");
        verifier.executeGoal("clean");

        for(Path p : Arrays.asList(projects,simple,dependency,env,coverage,extension)){
            FileUtils.deleteDirectory(getESFolder(p).toFile());
            FileUtils.deleteDirectory(p.resolve(srcEvo).toFile());
            FileUtils.deleteQuietly(p.resolve("log.txt").toFile());
            FileUtils.deleteQuietly(p.resolve(InitializingListener.getScaffoldingListFilePath()).toFile());
            FileUtils.deleteQuietly(p.resolve("coverage.check.failed").toFile());
        }
        for (String generated : Arrays.asList(".evosuite", "src/test/evosuite", "evosuite-report", "target")) {
            FileUtils.deleteDirectory(llm.resolve(generated).toFile());
        }
        FileUtils.deleteQuietly(llm.resolve("log.txt").toFile());
    }


    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testCompile() throws Exception{
        Verifier verifier  = getVerifier(projects);
        verifier.executeGoal("compile");
        verifier.verifyTextInLog("SimpleModule");
        verifier.verifyTextInLog("ModuleWithOneDependency");
    }


    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testESClean() throws Exception{
        Verifier verifier  = getVerifier(simple);
        verifier.addCliOption("evosuite:clean");
        verifier.executeGoal("clean");

        Path es = getESFolder(simple);
        assertFalse(Files.exists(es));
    }


    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testSimpleClass() throws Exception{

        String cut = "org.maven_test_project.sm.SimpleClass";

        Verifier verifier  = getVerifier(simple);
        addGenerateAndExportOption(verifier);
        verifier.addCliOption("-DtimeInMinutesPerClass=1");
        verifier.addCliOption("-Dcuts="+cut);
        verifier.executeGoal("compile");

        Path es = getESFolder(simple);
        assertTrue(Files.exists(es));

        verifier.verifyTextInLog("Going to generate tests with EvoSuite");
        Path exportedFolder = simple.resolve(srcEvo);
        boolean generatedSuiteExists = Files.exists(exportedFolder) && Files.find(exportedFolder, Integer.MAX_VALUE,
                (path, attrs) -> attrs.isRegularFile()
                        && path.getFileName().toString().startsWith("SimpleClass_ESTest")
                        && path.getFileName().toString().endsWith(".java"))
                .findAny().isPresent();
        assertTrue(generatedSuiteExists);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testSimpleMultiCore() throws Exception {

        String a = "org.maven_test_project.sm.SimpleClass";
        String b = "org.maven_test_project.sm.ThrowException";

        Verifier tooLittle = getVerifier(simple);
        tooLittle.addCliOption("evosuite:generate");
        tooLittle.addCliOption("-DtimeInMinutesPerClass=1");
        tooLittle.addCliOption("-Dcores=2");
        tooLittle.addCliOption("-DmemoryInMB=600");
        try {
            tooLittle.executeGoal("compile");
            fail();
        } catch (VerificationException e){
            //expected, because CTG needs 500 MB per core
        }

        // without memory configured, it follows the number of cores
        Verifier verifier  = getVerifier(simple);
        verifier.addCliOption("evosuite:generate");
        verifier.addCliOption("-DtimeInMinutesPerClass=1");
        verifier.addCliOption("-Dcores=2");
        verifier.executeGoal("compile");
        verifier.verifyTextInLog("Total memory: 2000mb");

        verifyLogFilesExist(simple, a);
        verifyLogFilesExist(simple, b);
    }


    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testModuleWithDependency() throws Exception{

        String cut = "org.maven_test_project.mwod.OneDependencyClass";

        Verifier verifier  = getVerifier(dependency);
        verifier.addCliOption("evosuite:generate");
        verifier.executeGoal("compile");

        verifyLogFilesExist(dependency, cut);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testExportWithTests() throws Exception {

        Verifier verifier  = getVerifier(dependency);
        verifier.addCliOption("evosuite:generate");
        verifier.addCliOption("evosuite:export");
        verifier.addCliOption("-DtargetFolder="+srcEvo);

        verifier.executeGoal("test");

        Files.exists(dependency.resolve(srcEvo));
        verifyLogFilesExist(dependency,"org.maven_test_project.mwod.OneDependencyClass");
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testExportWithTestsWithAgent() throws Exception {

        Verifier verifier  = getVerifier(dependency);
        addGenerateAndExportOption(verifier);
        verifier.addCliOption("-DforkCount=1");

        verifier.executeGoal("test");

        Files.exists(dependency.resolve(srcEvo));
        String cut = "org.maven_test_project.mwod.OneDependencyClass";
        verifyLogFilesExist(dependency,cut);
        verifyESTestsRunFor(verifier,cut);
        verifyAgentConfigured(verifier);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testExportWithTestsWithAgentNoFork() throws Exception {

        Verifier verifier  = getVerifier(dependency);
        addGenerateAndExportOption(verifier);
        verifier.addCliOption("-DforkCount=0");

        verifier.executeGoal("test");

        Files.exists(dependency.resolve(srcEvo));
        String cut = "org.maven_test_project.mwod.OneDependencyClass";
        verifyLogFilesExist(dependency,cut);
        verifyESTestsRunFor(verifier,cut);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testEnv() throws Exception{
        Verifier verifier  = getVerifier(env);
        addGenerateAndExportOption(verifier);

        verifier.executeGoal("test");

        Files.exists(env.resolve(srcEvo));
        String cut = "org.maven_test_project.em.FileCheck";
        verifyLogFilesExist(env,cut);
        verifyESTestsRunFor(verifier,cut);
    }

    @Test
    @Tag("smoke")
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testSetupWithExtensionModeFixture() throws Exception {
        Verifier verifier = getVerifier(extension);
        verifier.executeGoal("test");

        verifier.verifyTextInLog("Running org.maven_test_project.xm.ExtensionTarget_ESTest");
        verifyAgentConfigured(verifier);
    }

    //--- the generate/setup workflow ---------------------------------------

    @Test
    @Tag("smoke")
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testGenerateWritesTestsThatSetupRuns() throws Exception {
        String cut = "org.maven_test_project.sm.SimpleClass";
        // a test left over from a class that was deleted
        Path stale = simple.resolve(srcEvo).resolve("org/maven_test_project/sm/Deleted_ESTest.java");
        Files.createDirectories(stale.getParent());
        Files.write(stale, Arrays.asList("package org.maven_test_project.sm;",
                "public class Deleted_ESTest { does not compile }"));

        Verifier verifier = getVerifier(simple);
        verifier.addCliOption("evosuite:generate");
        verifier.addCliOption("-Devosuite.timeInMinutesPerClass=1");
        verifier.addCliOption("-Devosuite.cuts=" + cut);
        verifier.executeGoal("test");

        // generate wrote the tests to the folder setup added, without export or build-helper
        assertTrue(Files.exists(simple.resolve(srcEvo).resolve("org/maven_test_project/sm/SimpleClass_ESTest.java")));
        assertFalse(Files.exists(stale), "the test of a deleted class was not removed");
        verifyESTestsRunFor(verifier, cut);
        verifyAgentConfigured(verifier);
    }

    @Test
    @Timeout(value = 2 * timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testSecondRunOnlyTestsChangedClasses() throws Exception {
        Verifier first = getVerifier(simple);
        first.addCliOption("evosuite:generate");
        first.addCliOption("-Devosuite.timeInMinutesPerClass=1");
        first.addCliOption("-Devosuite.cores=2");
        first.addCliOption("-Devosuite.memoryInMB=1200");
        first.executeGoal("compile");
        verifyLogFilesExist(simple, "org.maven_test_project.sm.SimpleClass");

        Verifier second = getVerifier(simple);
        second.addCliOption("evosuite:generate");
        second.addCliOption("-Devosuite.timeInMinutesPerClass=1");
        second.executeGoal("compile");
        second.verifyTextInLog("testable classes are unchanged since their tests were generated");
    }

    @Test
    @Tag("smoke")
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testSetupNamesTheMissingRuntimeDependency() throws Exception {
        Verifier verifier = getVerifier(noRuntime);
        try {
            verifier.executeGoal("test-compile");
            fail("the generated test cannot compile without the EvoSuite runtime");
        } catch (VerificationException e) {
            // expected
        }
        verifier.verifyTextInLog("The EvoSuite tests in src/test/evosuite cannot compile: add these test dependencies to the pom of NoRuntimeModule");
        verifier.verifyTextInLog("<artifactId>evosuite-standalone-runtime</artifactId>");
    }

    @Test
    @Tag("smoke")
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testLlmSettingsAndKeyReachTheGenerationJob() throws Exception {
        String cut = "org.maven_test_project.llm.Sign";
        String key = "sk-maven-plugin-it"; // the password of the server in the fixture's settings.xml

        Verifier verifier = getVerifier(llm);
        verifier.addCliOption("-s");
        verifier.addCliOption(llm.resolve("settings.xml").toAbsolutePath().toString());
        verifier.addCliOption("evosuite:generate");
        verifier.addCliOption("-Devosuite.timeInMinutesPerClass=1");
        verifier.executeGoal("compile");

        // the provider is unreachable, so the job generates tests without LLM help
        verifyLogFilesExist(llm, cut);

        Path dir = getESFolder(llm);
        Path tmp = Files.find(dir, 1, (p, a) -> p.getFileName().toString().startsWith("tmp_")).findFirst().get();
        StringBuilder jobLogs = new StringBuilder();
        try (java.util.stream.Stream<Path> logs = Files.list(tmp.resolve("logs").resolve(cut))) {
            for (Path log : (Iterable<Path>) logs::iterator) {
                jobLogs.append(new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        String job = jobLogs.toString();
        assertTrue(job.contains("model=model with spaces"), "evosuiteProperties did not reach the job intact");
        assertFalse(job.contains("API key must be configured"), "the key from settings.xml did not reach the job");
        assertTrue(job.contains("127.0.0.1:9"), "the job did not try the configured provider");

        String maven = new String(Files.readAllBytes(llm.resolve("log.txt")), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(maven.contains(key), "the API key was printed");
        assertFalse(job.contains(key), "the API key was printed");

        assertTrue(Files.exists(dir.resolve("llm-traces")), "LLM traces not under .evosuite");
        assertFalse(Files.exists(llm.resolve("evosuite-report").resolve("llm-traces")));
    }

    @Test
    @Tag("smoke")
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testGenerationFailureFailsTheBuild() throws Exception {
        // EvoSuite cannot keep its data in a folder it may not write to
        Path blocked = getESFolder(simple);
        FileUtils.deleteDirectory(blocked.toFile());
        Files.createDirectories(blocked);
        Assumptions.assumeTrue(blocked.toFile().setWritable(false) && !Files.isWritable(blocked),
                "cannot make a folder read-only here, eg when running as root");
        try {
            Verifier verifier = getVerifier(simple);
            verifier.addCliOption("evosuite:generate");
            verifier.addCliOption("-Devosuite.cuts=org.maven_test_project.sm.SimpleClass");
            try {
                verifier.executeGoal("compile");
                fail("the build passed although EvoSuite could not run");
            } catch (VerificationException e) {
                // expected
            }
            verifier.verifyTextInLog("Failed to initialize local storage system");
        } finally {
            blocked.toFile().setWritable(true);
        }
    }

    @Test
    @Tag("smoke")
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testParallelReactorBuildGeneratesForEachModule() throws Exception {
        String a = "org.maven_test_project.sm.SimpleClass";
        String b = "org.maven_test_project.mwod.OneDependencyClass";

        Verifier verifier = getVerifier(projects);
        verifier.addCliOption("-T");
        verifier.addCliOption("2");
        verifier.addCliOption("-pl");
        verifier.addCliOption("SimpleModule,ModuleWithOneDependency");
        verifier.addCliOption("evosuite:generate");
        verifier.addCliOption("-Devosuite.timeInMinutesPerClass=1");
        verifier.addCliOption("-Devosuite.cuts=" + a + "," + b);
        verifier.executeGoal("compile");

        verifyLogFilesExist(simple, a);
        verifyLogFilesExist(dependency, b);
    }

    @Test
    @Tag("smoke")
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testLegacyPropertyNamesStillWork() throws Exception {
        String cut = "org.maven_test_project.sm.SimpleClass";

        Verifier verifier = getVerifier(simple);
        verifier.addCliOption("evosuite:generate");
        verifier.addCliOption("-DtimeInMinutesPerClass=1");
        verifier.addCliOption("-Dcuts=" + cut);
        verifier.executeGoal("compile");

        verifier.verifyTextInLog("Property 'cuts' is deprecated, use 'evosuite.cuts' instead");
        verifier.verifyTextInLog("Time per class: 1 minutes");
        verifyLogFilesExist(simple, cut);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testExtensionModeWithEnv() throws Exception {
        String cut = "org.maven_test_project.xm.ExtensionProfileTarget";

        Verifier verifier = getVerifier(extension);
        addGenerateAndExportOption(verifier);
        addExtensionModeArgs(verifier);
        verifier.addCliOption("-Dcuts=" + cut);
        verifier.executeGoal("test");

        verifyLogFilesExist(extension, cut);
        verifyESTestsRunFor(verifier, cut);
        verifyGeneratedExtensionTestExists(extension, "ExtensionProfileTarget_ESTest");
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testPitWithExtensionModeEnv() throws Exception {
        String cut = "org.maven_test_project.xm.ExtensionProfileTarget";

        Verifier verifier = getVerifier(extension);
        addGenerateAndExportOption(verifier);
        addExtensionModeArgs(verifier);
        verifier.addCliOption("-Dcuts=" + cut);
        verifier.addCliOption("-Ppit");
        verifier.executeGoal("verify");

        verifyLogFilesExist(extension, cut);
        verifyESTestsRunFor(verifier, cut);
        verifyGeneratedExtensionTestExists(extension, "ExtensionProfileTarget_ESTest");
        verifyPitFolderExists(extension);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testJaCoCoWithExtensionModeEnv() throws Exception {
        String cut = "org.maven_test_project.xm.ExtensionProfileTarget";

        Verifier verifier = getVerifier(extension);
        addGenerateAndExportOption(verifier);
        addExtensionModeArgs(verifier);
        verifier.addCliOption("-Dcuts=" + cut);
        verifier.addCliOption("-Pjacoco");
        verifier.executeGoal("verify");

        verifyLogFilesExist(extension, cut);
        verifyESTestsRunFor(verifier, cut);
        verifyGeneratedExtensionTestExists(extension, "ExtensionProfileTarget_ESTest");
        verifyJaCoCoFileExists(extension);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testJMockitWithExtensionModeEnv() throws Exception {
        assumeJMockitSupported();
        String cut = "org.maven_test_project.xm.ExtensionProfileTarget";

        Verifier verifier = getVerifier(extension);
        addGenerateAndExportOption(verifier);
        addExtensionModeArgs(verifier);
        verifier.addCliOption("-Dcuts=" + cut);
        verifier.addCliOption("-Pjmockit");
        verifier.executeGoal("verify");

        verifyLogFilesExist(extension, cut);
        verifyESTestsRunFor(verifier, cut);
        verifyGeneratedExtensionTestExists(extension, "ExtensionProfileTarget_ESTest");
        verifyJMockitFolderExists(extension);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testExtensionModeWithEnvNoFork() throws Exception {
        String cut = "org.maven_test_project.xm.ExtensionProfileTarget";

        Verifier verifier = getVerifier(extension);
        addGenerateAndExportOption(verifier);
        addExtensionModeArgs(verifier);
        verifier.addCliOption("-Dcuts=" + cut);
        verifier.addCliOption("-DforkCount=0");
        verifier.executeGoal("test");

        verifyLogFilesExist(extension, cut);
        verifyESTestsRunFor(verifier, cut);
        verifyGeneratedExtensionTestExists(extension, "ExtensionProfileTarget_ESTest");
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testExtensionModeVerifyWithEnv() throws Exception {
        String cut = "org.maven_test_project.xm.ExtensionProfileTarget";

        Verifier verifier = getVerifier(extension);
        addGenerateAndExportOption(verifier);
        addExtensionModeArgs(verifier);
        verifier.addCliOption("-Dcuts=" + cut);
        verifier.executeGoal("verify");

        verifyLogFilesExist(extension, cut);
        verifyESTestsRunFor(verifier, cut);
        verifyGeneratedExtensionTestExists(extension, "ExtensionProfileTarget_ESTest");
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testExtensionModeWithEnvRepeatedVerifyIsStable() throws Exception {
        String cut = "org.maven_test_project.xm.ExtensionProfileTarget";

        Verifier first = getVerifier(extension);
        addGenerateAndExportOption(first);
        addExtensionModeArgs(first);
        first.addCliOption("-Dcuts=" + cut);
        first.executeGoal("verify");
        verifyESTestsRunFor(first, cut);

        Verifier second = getVerifier(extension);
        addGenerateAndExportOption(second);
        addExtensionModeArgs(second);
        second.addCliOption("-Dcuts=" + cut);
        second.executeGoal("verify");

        verifyLogFilesExist(extension, cut);
        verifyESTestsRunFor(second, cut);
        verifyGeneratedExtensionTestExists(extension, "ExtensionProfileTarget_ESTest");
    }

    //--- JaCoCo --------------------------------------------------------------


    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testJaCoCoNoEnv() throws Exception{
        testVerifyNoEnv("jacoco");
        verifyJaCoCoFileExists(dependency);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testJaCoCoWithEnv() throws Exception{
        testVerfiyWithEnv("jacoco");
        verifyJaCoCoFileExists(env);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testJaCoCoPass() throws Exception{
        testCoveragePass("jacoco");
        verifyJaCoCoFileExists(coverage);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testJaCoCoFail() throws Exception{
        testCoverageFail("jacoco");
        verifyJaCoCoFileExists(coverage);
    }


    //--- JMockit --------------------------------------------------------------


    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testJMockitNoEnv() throws Exception{
        assumeJMockitSupported();
        testVerifyNoEnv("jmockit", 1);
        verifyJMockitFolderExists(dependency);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testJMockitWithEnv() throws Exception{
        assumeJMockitSupported();
        testVerfiyWithEnv("jmockit", 1);
        verifyJMockitFolderExists(env);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testJMockitPass() throws Exception{
        assumeJMockitSupported();
        testCoveragePass("jmockit");
        verifyJMockitFolderExists(coverage);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testJMockitFail() throws Exception{
        assumeJMockitSupported();
        testCoverageFail("jmockit");
        verifyJMockitFolderExists(coverage);
    }


    //--- PowerMock --------------------------------------------------------------

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testPowerMockNoEnv() throws Exception{
        testVerifyNoEnv("powermock",1);
    }


    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testPowerMockWithEnv() throws Exception{
        testVerfiyWithEnv("powermock",1);
    }


    //--- Cobertura --------------------------------------------------------------

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testCoberturaNoEnv() throws Exception{
        assumeCoberturaSupported();
        testVerifyNoEnv("cobertura");
        verifyCoberturaFileExists(dependency);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testCoberturaWithEnv() throws Exception{
        assumeCoberturaSupported();
        testVerfiyWithEnv("cobertura");
        verifyCoberturaFileExists(env);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testCoberturaPass() throws Exception{
        assumeCoberturaSupported();
        testCoveragePass("cobertura");
        verifyCoberturaFileExists(coverage);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testCoberturaFail() throws Exception{
        assumeCoberturaSupported();
        testCoverageFail("cobertura");
        verifyCoberturaFileExists(coverage);
    }

    //--- PIT --------------------------------------------------------------

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testPitNoEnv() throws Exception{
        testVerifyNoEnv("pit");
        verifyPitFolderExists(dependency);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testPitWithEnv() throws Exception{
        assumeLegacyPitEnvSupported();
        testVerfiyWithEnv("pit");
        verifyPitFolderExists(env);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testPitWithEnvExtensionMode() throws Exception {
        String cut = "org.maven_test_project.xm.ExtensionEnvTarget";

        Verifier verifier = getVerifier(extension);
        addGenerateAndExportOption(verifier);
        addExtensionModeArgs(verifier);
        verifier.addCliOption("-Dcuts=" + cut);
        verifier.addCliOption("-Ppit");
        verifier.executeGoal("verify");

        verifyLogFilesExist(extension, cut);
        verifyESTestsRunFor(verifier, cut);
        verifyGeneratedExtensionTestExists(extension, "ExtensionEnvTarget_ESTest");
        verifyPitFolderExists(extension);
    }


    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testPitPass() throws Exception{
        testCoveragePass("pit");
        verifyPitFolderExists(coverage);
    }

    @Test
    @Timeout(value = timeoutInMs, unit = TimeUnit.MILLISECONDS)
    public void testPitFail() throws Exception{
        testCoverageFail("pit,pitOneTest"); //PIT has its filters for test execution
        verifyPitFolderExists(coverage);
    }


    //------------------------------------------------------------------------------------------------------------------

    private void testVerfiyWithEnv(String profile) throws Exception{
        testVerfiyWithEnv(profile, 1);
    }

    private void testVerfiyWithEnv(String profile, int forkCount) throws Exception{

        Verifier verifier  = getVerifier(env);
        addGenerateAndExportOption(verifier);
        addCoverageToolClassloaderOptionIfNeeded(verifier, profile);
        verifier.addCliOption("-P"+profile);
        verifier.addCliOption("-DforkCount="+forkCount);

        verifier.executeGoal("verify");

        Files.exists(env.resolve(srcEvo));
        String cut = "org.maven_test_project.em.FileCheck";
        verifyLogFilesExist(env,cut);
        verifyESTestsRunFor(verifier,cut);
    }

    private void testVerifyNoEnv(String profile) throws Exception {
        testVerifyNoEnv(profile, 1);
    }

    private void testVerifyNoEnv(String profile, int forkCount) throws Exception{

        Verifier verifier  = getVerifier(dependency);
        addGenerateAndExportOption(verifier);
        addCoverageToolClassloaderOptionIfNeeded(verifier, profile);
        verifier.addCliOption("-P"+profile);
        verifier.addCliOption("-DforkCount="+forkCount);

        verifier.executeGoal("verify");

        Files.exists(dependency.resolve(srcEvo));
        String cut = "org.maven_test_project.mwod.OneDependencyClass";
        verifyLogFilesExist(dependency,cut);
        verifyESTestsRunFor(verifier,cut);
    }

    private void testCoveragePass(String profile) throws Exception{
        Verifier verifier = getVerifier(coverage);
        verifier.addCliOption("-P"+profile);
        verifier.executeGoal("verify");
    }

    private void testCoverageFail(String profile) throws Exception{
        Verifier verifier = getVerifier(coverage);
        verifier.addCliOption("-Dtest=SimpleClassPartialTest");

        verifier.executeGoal("verify");

        verifier.executeGoal("clean");
        verifier.addCliOption("-P"+profile);
        try{
            verifier.executeGoal("verify");
            fail();
        } catch (Exception e){
            //expected, as coverage check should had failed
        }
    }



    private void verifyESTestsRunFor(Verifier verifier, String className) throws Exception{
        //Note: this depends on Maven / Surefire, so might change in future with new versions
        verifier.verifyTextInLog("Running "+className);
    }

    private void addGenerateAndExportOption(Verifier verifier){
        verifier.addCliOption("evosuite:generate");
        verifier.addCliOption("evosuite:export");
        verifier.addCliOption("-DtargetFolder="+srcEvo);
    }

    private void addExtensionModeArgs(Verifier verifier) {
        verifier.addCliOption("-DextraArgs=\"-Dtest_format=JUNIT5 -Dtest_extension_mode=true -Duse_separate_classloader=false\"");
    }

    private void addCoverageToolClassloaderOptionIfNeeded(Verifier verifier, String profile) {
        if (profile != null && (profile.contains("jacoco") || profile.contains("cobertura") || profile.contains("pit"))) {
            verifier.addCliOption("-DextraArgs=\"-Duse_separate_classloader=false\"");
        }
    }

    private void verifyJaCoCoFileExists(Path targetProject){
        assertTrue(Files.exists(targetProject.resolve("target").resolve("jacoco.exec")));
    }

    private void verifyJMockitFolderExists(Path targetProject){
        assertTrue(Files.exists(targetProject.resolve("target").resolve("jmockit")));
    }

    private void verifyPitFolderExists(Path targetProject){
        assertTrue(Files.exists(targetProject.resolve("target").resolve("pit-reports")));
    }

    private void verifyCoberturaFileExists(Path targetProject){
        assertTrue(Files.exists(targetProject.resolve("target").resolve("cobertura").resolve("cobertura.ser")));
    }

    private static void assumeCoberturaSupported() {
        File toolsJar = new File(System.getProperty("java.home"), "../lib/tools.jar");
        Assumptions.assumeTrue(toolsJar.exists(),
                "Cobertura requires tools.jar (typically JDK 8 layout). Current java.home="
                        + System.getProperty("java.home"));
    }

    private static void assumeJMockitSupported() {
        int feature = Runtime.version().feature();
        Assumptions.assumeTrue(feature <= 17,
                "JMockit coverage agent is not reliable on this JDK. Detected feature version: " + feature);
    }

    private static void assumeLegacyPitEnvSupported() {
        int feature = Runtime.version().feature();
        Assumptions.assumeTrue(feature <= 17,
                "Legacy JUnit4 listener-based EnvModule + PIT is not reliable on this JDK. "
                        + "Use extension-mode Env PIT coverage instead. Detected feature version: " + feature);
    }

    private void verifyLogFilesExist(Path targetProject, String className) throws Exception{
        Path dir = getESFolder(targetProject);
        Path tmp = Files.find(dir,1, (p,a) -> p.getFileName().toString().startsWith("tmp_")).findFirst().get();
        Path logs = tmp.resolve("logs").resolve(className);

        assertTrue(Files.exists(logs.resolve("std_err_CLIENT.log")));
        assertTrue(Files.exists(logs.resolve("std_err_MASTER.log")));
        assertTrue(Files.exists(logs.resolve("std_out_CLIENT.log")));
        assertTrue(Files.exists(logs.resolve("std_out_MASTER.log")));

        // a job that failed writes its logs as well, so also check that it produced tests
        Path suite = dir.resolve("best-tests").resolve(className.replace('.', '/') + "_ESTest.java");
        assertTrue(Files.exists(suite), "no tests were generated for " + className
                + ", see " + logs.resolve("std_err_MASTER.log"));
    }

    private void verifyAgentConfigured(Verifier verifier) throws VerificationException {
        // logged by the setup goal; Surefire then passes the agent to the test JVM
        verifier.verifyTextInLog("argLine set to");
        verifier.verifyTextInLog("-javaagent:");
    }

    private void verifyGeneratedExtensionTestExists(Path targetProject, String generatedSimpleName) throws IOException {
        Path generated = targetProject.resolve(srcEvo)
                .resolve("org")
                .resolve("maven_test_project")
                .resolve("xm")
                .resolve(generatedSimpleName + ".java");
        Path generatedScaffolding = targetProject.resolve(srcEvo)
                .resolve("org")
                .resolve("maven_test_project")
                .resolve("xm")
                .resolve(generatedSimpleName + "_scaffolding.java");
        assertTrue(Files.exists(generated));
        String code = Files.readString(generated);
        assertTrue(code.contains("@RegisterExtension"));
        assertTrue(code.contains("EvoSuiteExtension"));
        assertFalse(code.contains("extends " + generatedSimpleName + "_scaffolding"));
        assertFalse(Files.exists(generatedScaffolding));
    }

    private Path getESFolder(Path project){
        return project.resolve(".evosuite");
    }

    private Verifier getVerifier(Path targetProject) throws Exception{
        Verifier verifier  = new Verifier(targetProject.toAbsolutePath().toString());
        Properties props = new Properties(System.getProperties());
        // Prefer explicit override, then Maven project version, then current snapshot.
        props.put("evosuiteVersion",
                System.getProperty("evosuiteVersion",
                        System.getProperty("project.version", "1.2.1-SNAPSHOT")));
        verifier.setSystemProperties(props);
        return verifier;
    }

}
