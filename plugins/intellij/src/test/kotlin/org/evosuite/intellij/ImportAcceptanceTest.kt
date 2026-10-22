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
package org.evosuite.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.evosuite.ide.*
import org.evosuite.intellij.run.ClasspathResolver
import org.evosuite.intellij.run.EngineJars
import org.evosuite.intellij.run.IdeThread
import org.evosuite.intellij.run.SuitePlacement
import org.evosuite.intellij.settings.SettingsResolver
import org.evosuite.intellij.setup.ProjectSetupService
import org.jetbrains.idea.maven.buildtool.MavenSyncSpec
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.project.MavenProjectsManagerEx
import org.jetbrains.plugins.gradle.settings.DistributionType
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants

/** Opt-in real Maven/Gradle imports; no constructed imported models or UI automation. */
class ImportAcceptanceTest : JavaCodeInsightFixtureTestCase() {
    private lateinit var root: Path
    private lateinit var jdkName: String
    private val gradleUserHome =
        Path.of(System.getProperty("java.io.tmpdir"), "evosuite-import-gradle-home")

    override fun setUp() {
        check(java.lang.Boolean.getBoolean("evosuite.import.it")) {
            "Enable with -Pevosuite.import.it=true"
        }
        super.setUp()
        val home = System.getProperty("evosuite.it.javaHome").orEmpty()
        require(home.isNotBlank()) { "Pass -Pevosuite.it.javaHome=/path/to/jdk-17" }
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(
            testRootDisposable,
            home,
            Path.of(home).toRealPath().toString(),
            System.getProperty("evosuite.import.gradleHome"),
            Path.of(System.getProperty("evosuite.import.gradleHome")).toRealPath().toString(),
        )
        Files.createDirectories(gradleUserHome)
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(
            testRootDisposable,
            gradleUserHome.toString(),
            gradleUserHome.toRealPath().toString(),
        )
        jdkName = "EvoSuite import acceptance " + System.nanoTime()
        val jdk = JavaSdk.getInstance().createJdk(jdkName, home, false)
        WriteCommandAction.runWriteCommandAction(project) {
            ProjectJdkTable.getInstance().addJdk(jdk, testRootDisposable)
            ProjectRootManager.getInstance(project).projectSdk = jdk
        }
        root = Path.of(myFixture.tempDirPath)
    }

    private fun fixture(kind: String) {
        val source = Path.of(System.getProperty("evosuite.import.fixtures"), kind)
        Files.walk(source).use { paths ->
            paths.forEach { from ->
                val to = root.resolve(source.relativize(from).toString())
                if (Files.isDirectory(from)) Files.createDirectories(to)
                else
                    Files.writeString(
                        to,
                        Files.readString(from).replace("@EVOSUITE_VERSION@", EngineJars.VERSION),
                    )
            }
        }
        if (kind == "gradle")
            Files.writeString(
                root.resolve("gradle.properties"),
                "org.gradle.java.installations.paths=" +
                    System.getProperty("evosuite.it.javaHome") +
                    "\n",
            )
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!.refresh(false, true)
    }

    fun testMavenImportAndReimportKeepConfiguredTestFolder() {
        fixture("maven")
        val manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        manager.enableAutoImportInTests()
        manager.addManagedFilesOrUnignoreNoUpdate(
            listOf(LocalFileSystem.getInstance().findFileByNioFile(root.resolve("pom.xml"))!!)
        )
        importMaven(manager)
        val main = moduleFor("app/src/main/java/demo/Vault.java")
        val options = background { SettingsResolver.resolve(main) }
        assertEquals(GenerationOptions.JUNIT4, options.testFormat)
        assertEquals(60, options.searchBudget)
        assertEquals("src/test/generated", options.testFolder)
        assertEquals(root.resolve("app"), ProjectSetupService.baseDir(main))
        buildFixture("maven")
        assertClasspath(main)
        prepare(main, options)
        assertTestRoot(root.resolve("app").resolve(options.testFolder), main)
        importMaven(manager)
        assertTestRoot(
            root.resolve("app").resolve(options.testFolder),
            moduleFor("app/src/main/java/demo/Vault.java"),
        )
        val pom = Files.readString(root.resolve("app/pom.xml"))
        assertTrue(pom, pom.contains("evosuite-setup") && pom.contains("<goal>setup</goal>"))
        val local =
            org.evosuite.intellij.settings.EvoSuiteProjectSettings.getInstance(project).state!!
        local.useProjectSettings = true
        local.values["usePomSettings"] = "false"
        local.values["testFolder"] = "generated-tests/evosuite"
        val changed = background { SettingsResolver.resolve(main) }
        prepare(main, changed)
        importMaven(manager)
        assertTestRoot(
            root.resolve("app/generated-tests/evosuite"),
            moduleFor("app/src/main/java/demo/Vault.java"),
        )
        assertEquals(
            1,
            Regex("<id>evosuite-setup</id>")
                .findAll(Files.readString(root.resolve("app/pom.xml")))
                .count(),
        )
        exerciseGeneration("maven", moduleFor("app/src/main/java/demo/Vault.java"), changed)
    }

    private fun importMaven(manager: MavenProjectsManager) {
        val future =
            CompletableFuture.supplyAsync {
                runBlocking {
                    (manager as MavenProjectsManagerEx).updateAllMavenProjects(
                        MavenSyncSpec.full("EvoSuite acceptance")
                    )
                }
            }
        PlatformTestUtil.waitForFuture(future, 180_000)
    }

    fun testGradleSourceSetImportAndReimportKeepConfiguredTestFolder() {
        fixture("gradle")
        linkGradle()
        importGradle()
        val main = moduleFor("app/src/main/java/demo/Vault.java")
        val tests = moduleFor("app/src/test/java/demo/VaultTest.java")
        assertTrue(main.name, main != tests)
        assertEquals(
            GenerationOptions.JUNIT5,
            background { SettingsResolver.resolve(main) }.testFormat,
        )
        assertEquals(root.resolve("app"), ProjectSetupService.baseDir(main))
        buildFixture("gradle")
        assertClasspath(main)
        val options = GenerationOptions()
        assertEquals(tests, ProjectSetupService.testModule(main))
        assertTrue(
            background {
                ProjectSetupService.hasClass(
                    ProjectSetupService.testModule(main),
                    "org.junit.jupiter.api.Test",
                )
            }
        )
        val questions = prepare(main, options)
        assertTestRoot(
            root.resolve("app").resolve(options.testFolder),
            ProjectSetupService.testModule(main),
        )
        assertTrue(questions.toString(), questions.isEmpty())
        assertTestRoot(root.resolve("app").resolve(options.testFolder), tests)
        options.testFolder = "generated-tests/evosuite"
        val changedQuestions = prepare(main, options)
        assertTrue(changedQuestions.toString(), changedQuestions.isEmpty())
        assertTestRoot(root.resolve("app").resolve(options.testFolder), tests)
        // Apply the exact source-set snippet offered by the plugin, then perform a real re-sync.
        Files.writeString(
            root.resolve("build.gradle"),
            Files.readString(root.resolve("build.gradle")) +
                "\nproject(':app') { sourceSets.test.java.srcDir('" +
                options.testFolder +
                "') }\n",
        )
        LocalFileSystem.getInstance()
            .findFileByNioFile(root.resolve("build.gradle"))!!
            .refresh(false, false)
        importGradle()
        assertTestRoot(
            root.resolve("app").resolve(options.testFolder),
            moduleFor("app/src/test/java/demo/VaultTest.java"),
        )
        exerciseGeneration("gradle", moduleFor("app/src/main/java/demo/Vault.java"), options)
    }

    fun testGradleJUnit4DetectionAndMissingRuntimeDependencyArePersisted() {
        fixture("gradle")
        val build = root.resolve("build.gradle")
        Files.writeString(
            build,
            Files.readString(build)
                .replace("org.junit.jupiter:junit-jupiter:5.14.3", "junit:junit:4.13.2")
                .replace(
                    "        testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.14.3'",
                    "",
                )
                .replace(
                    "        testImplementation 'org.evosuite:evosuite-standalone-runtime:" +
                        EngineJars.VERSION +
                        "'",
                    "",
                )
                .replace("useJUnitPlatform()", "useJUnit()"),
        )
        Files.writeString(
            root.resolve("app/src/test/java/demo/VaultTest.java"),
            Files.readString(
                Path.of(
                    System.getProperty("evosuite.import.fixtures"),
                    "maven/app/src/test/java/demo/VaultTest.java",
                )
            ),
        )
        LocalFileSystem.getInstance().findFileByNioFile(build)!!.refresh(false, false)
        linkGradle()
        importGradle()
        val main = moduleFor("app/src/main/java/demo/Vault.java")
        val options = background { SettingsResolver.resolve(main) }
        assertEquals(GenerationOptions.JUNIT4, options.testFormat)
        val questions = prepare(main, options)
        assertTestRoot(
            root.resolve("app").resolve(options.testFolder),
            ProjectSetupService.testModule(main),
        )
        assertTrue(
            questions.toString(),
            questions.any { it.contains("evosuite-standalone-runtime") },
        )
        importGradle()
        val tests = moduleFor("app/src/test/java/demo/VaultTest.java")
        assertTrue(
            background {
                ProjectSetupService.hasClass(tests, "org.evosuite.runtime.EvoRunnerParameters")
            }
        )
        val updated = Files.readString(build) + Files.readString(root.resolve("app/build.gradle"))
        assertTrue(
            updated,
            updated.contains("evosuite-standalone-runtime") &&
                updated.contains("testImplementation"),
        )
        buildFixture("gradle", true)
    }

    fun testGradleKotlinDslMissingRuntimeDependencyIsPersisted() {
        fixture("gradle")
        for (file in listOf("settings.gradle", "build.gradle", "app/build.gradle")) Files.delete(
            root.resolve(file)
        )
        fixture("gradle-kotlin")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!.refresh(false, true)
        linkGradle()
        importGradle()
        val main = moduleFor("app/src/main/java/demo/Vault.java")
        val options = background { SettingsResolver.resolve(main) }
        assertEquals(GenerationOptions.JUNIT5, options.testFormat)
        val questions = prepare(main, options)
        assertTestRoot(
            root.resolve("app").resolve(options.testFolder),
            ProjectSetupService.testModule(main),
        )
        assertTrue(
            questions.toString(),
            questions.any { it.contains("evosuite-standalone-runtime") },
        )
        importGradle()
        val tests = moduleFor("app/src/test/java/demo/VaultTest.java")
        assertTrue(
            background {
                ProjectSetupService.hasClass(tests, "org.evosuite.runtime.EvoRunnerParameters")
            }
        )
        assertTrue(background { ProjectSetupService.hasClass(tests, "org.junit.jupiter.api.Test") })
        val updated = Files.readString(root.resolve("app/build.gradle.kts"))
        assertTrue(
            updated,
            updated.contains("junit-jupiter") && updated.contains("junit-platform-launcher"),
        )
        assertTrue(
            updated,
            updated.contains("evosuite-standalone-runtime") &&
                updated.contains("testImplementation"),
        )
        buildFixture("gradle", true)
    }

    private fun linkGradle() {
        val home = System.getProperty("evosuite.import.gradleHome").orEmpty()
        require(home.isNotBlank()) { "Pass -Pevosuite.import.gradleHome=/path/to/gradle" }
        val settings =
            GradleProjectSettings().apply {
                externalProjectPath = root.toString()
                gradleHome = home
                distributionType = DistributionType.LOCAL
                gradleJvm = jdkName
                isResolveModulePerSourceSet = true
                delegatedBuild = true
            }
        GradleSettings.getInstance(project).apply {
            serviceDirectoryPath = gradleUserHome.toString()
            linkProject(settings)
        }
    }

    private fun prepare(module: Module, options: GenerationOptions): List<String> {
        val questions = java.util.concurrent.CopyOnWriteArrayList<String>()
        val previous =
            TestDialogManager.setTestDialog { message ->
                questions.add(message)
                com.intellij.openapi.ui.Messages.YES
            }
        try {
            background {
                ProjectSetupService.prepare(module, options, EmptyProgressIndicator(), ::println)
            }
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        return questions
    }

    private fun exerciseGeneration(kind: String, main: Module, options: GenerationOptions) {
        options.searchBudget = 3
        options.clientMemory = 512
        options.criterion = "BRANCH"
        options.pomExtraArguments = ""
        options.extraArguments = "-Dsandbox=false -Dminimization_timeout=3 -Dassertion_timeout=3"
        val cp =
            ReadAction.compute<List<String>, RuntimeException> {
                ClasspathResolver.resolve(main, false)
            }
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val listener =
            object : ProgressParser.Listener {
                override fun line(line: String) {
                    lines.add(line)
                }

                override fun progress(search: Int, coverage: Int) {}
            }
        val installation = background {
            IdeThread.smartRead(project) { ClasspathResolver.java(main, options) }
        }
        val target = GenerationTarget.wholeClass("demo.Vault")
        val result = background {
            GenerationRun.generate(
                target,
                options,
                installation,
                EngineJars.engine(),
                cp,
                root.resolve("app"),
                root.resolve("generation"),
                listener,
                emptyMap(),
            ) {
                false
            }
        }
        assertTrue(lines.joinToString("\n"), result.produced())
        assertEquals(lines.joinToString("\n"), 0, result.exitCode())
        val destination =
            root.resolve("app").resolve(options.testFolder).resolve("demo/Vault_ESTest.java")
        val placed =
            SuitePlacement.place(
                project,
                target,
                options,
                destination,
                result,
                SuiteAction.REPLACE,
                null,
                ::println,
            )
        assertTrue(placed.written())
        IdeThread.flushPendingVfsWrites()
        buildFixture(kind, true)
        // Extend a compiled, handwritten test through the imported test module's full classpath.
        val testModule = moduleFor("app/src/test/java/demo/VaultTest.java")
        val seedRoot = root.resolve("app/src/test/java")
        val original = Files.readString(seedRoot.resolve("demo/VaultTest.java"))
        val extensionCp =
            ReadAction.compute<List<String>, RuntimeException> {
                ClasspathResolver.resolve(testModule, true)
            }
        options.searchBudget = 10
        options.extraArguments = "-Dsandbox=false -Dminimization_timeout=10 -Dassertion_timeout=5"
        val extended = background {
            GenerationRun.generate(
                GenerationTarget.extending(
                    "demo.Vault",
                    listOf(GenerationTarget.Seed("demo.VaultTest", seedRoot.toFile())),
                ),
                options,
                installation,
                EngineJars.engine(),
                extensionCp,
                root.resolve("app"),
                root.resolve("extension"),
                listener,
                emptyMap(),
            ) {
                false
            }
        }
        assertNotNull(extended.seeding())
        assertTrue(lines.joinToString("\n"), extended.seeding().seeded())
        assertTrue(lines.joinToString("\n"), extended.seeding().ranExistingTests())
        assertEquals(original, Files.readString(seedRoot.resolve("demo/VaultTest.java")))
    }

    private fun importGradle() {
        val done = CompletableFuture<Void>()
        ExternalSystemUtil.refreshProject(
            root.toString(),
            ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
                .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
                .withImportProjectData(true)
                .withCallback(done),
        )
        PlatformTestUtil.waitForFuture(done, 180_000)
    }

    private fun buildFixture(kind: String, executeTests: Boolean = false) {
        background {
            val command =
                if (kind == "maven")
                    listOf(
                        "mvn",
                        "-f",
                        root.resolve("pom.xml").toString(),
                        if (executeTests) "test" else "test-compile",
                    )
                else
                    listOf(
                        Path.of(System.getProperty("evosuite.import.gradleHome"), "bin", "gradle")
                            .toString(),
                        "-p",
                        root.toString(),
                        if (executeTests) "test" else "testClasses",
                        "--console=plain",
                    )
            val log = root.resolve("acceptance-build.log")
            val process =
                ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .apply {
                        environment()["JAVA_HOME"] = System.getProperty("evosuite.it.javaHome")
                        environment()["GRADLE_USER_HOME"] = gradleUserHome.toString()
                    }
                    .start()
            try {
                check(process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)) {
                    "Build timed out: " + log
                }
                assertEquals(Files.readString(log), 0, process.exitValue())
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!.refresh(false, true)
    }

    private fun <T> background(action: () -> T): T =
        PlatformTestUtil.waitForFuture(CompletableFuture.supplyAsync(action), 180_000)

    private fun moduleFor(path: String): Module =
        ReadAction.compute<Module, RuntimeException> {
            val file = LocalFileSystem.getInstance().findFileByNioFile(root.resolve(path))!!
            ProjectFileIndex.getInstance(project).getModuleForFile(file)
                ?: error(
                    "No module for " +
                        path +
                        "; modules: " +
                        ModuleManager.getInstance(project).modules.map { it.name }
                )
        }

    private fun assertClasspath(module: Module) {
        val cp =
            ReadAction.compute<List<String>, RuntimeException> {
                ClasspathResolver.resolve(module, false)
            }
        assertTrue(
            cp.toString(),
            cp.any { it.contains("app") && (it.contains("classes") || it.contains("production")) },
        )
        assertTrue(
            cp.toString(),
            cp.any {
                it.contains("library") && (it.contains("classes") || it.contains("production"))
            },
        )
        assertFalse(
            cp.toString(),
            cp.any {
                it.contains("junit") ||
                    it.contains("evosuite-standalone-runtime") ||
                    it.contains("test-classes")
            },
        )
    }

    private fun assertTestRoot(path: Path, owner: Module) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val index = ProjectFileIndex.getInstance(project)
        assertTrue(path.toString(), index.isInTestSourceContent(file))
        assertEquals(owner, index.getModuleForFile(file))
    }
}
