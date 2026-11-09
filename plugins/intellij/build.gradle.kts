import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    java
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}
group = "org.evosuite.plugins"
val engineRoot = providers.gradleProperty("evosuite.root").map { file(it) }.getOrElse(file("../.."))
val enginePom = engineRoot.resolve("pom.xml")
val engineVersion = DocumentBuilderFactory.newInstance().apply {
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
}.newDocumentBuilder().parse(enginePom).documentElement.let { root ->
    fun org.w3c.dom.Node.child(name: String): org.w3c.dom.Node? =
        (0 until childNodes.length).asSequence().map(childNodes::item).firstOrNull { it.nodeName == name }

    val declared = root.child("version")?.textContent?.trim() ?: error("No project version in $enginePom")
    if (declared == "\${revision}") {
        providers.gradleProperty("revision").orNull
            ?: root.child("properties")?.child("revision")?.textContent?.trim()
            ?: error("No revision in $enginePom")
    } else {
        declared
    }
}
version = engineVersion
repositories { mavenCentral(); intellijPlatform { defaultRepositories() } }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":ide-core"))
    implementation("org.codehaus.plexus:plexus-cipher:2.0")
    intellijPlatform {
        val localIde = providers.gradleProperty("localIde")
        if (localIde.isPresent) local(localIde.get()) else intellijIdea("2025.3.2")
        bundledPlugins("com.intellij.java", "org.jetbrains.idea.maven", "JUnit", "org.jetbrains.plugins.gradle")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
        pluginVerifier()
        zipSigner()
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.platform:junit-platform-console-standalone:1.14.3")
}
intellijPlatform {
    pluginConfiguration {
        name = "EvoSuite"
        version = project.version.toString()
        ideaVersion { sinceBuild = "253"; untilBuild = provider { null } }
        vendor { name = "EvoSuite"; url = "https://www.evosuite.org" }
    }
    pluginVerification {
        ides {
            current()
            val verificationIde = providers.gradleProperty("verificationIde")
            if (verificationIde.isPresent) local(verificationIde.get())
            else create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }
    signing { certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN"); privateKey = providers.environmentVariable("PRIVATE_KEY"); password = providers.environmentVariable("PRIVATE_KEY_PASSWORD") }
    publishing { token = providers.environmentVariable("PUBLISH_TOKEN") }
}
// Dependency additions must use the same version as the bundled engine, including release bumps.
val engineMetadata = layout.buildDirectory.file("generated/engine-metadata/evosuite-engine.properties")
val generateEngineMetadata = tasks.register("generateEngineMetadata") {
    inputs.property("engineVersion", engineVersion)
    outputs.file(engineMetadata)
    doLast {
        engineMetadata.get().asFile.apply {
            parentFile.mkdirs()
            writeText("version=$engineVersion\n")
        }
    }
}
tasks.processResources {
    dependsOn(generateEngineMetadata)
    from(engineMetadata)
}
val engineJar = engineRoot.resolve("master/target/evosuite-master-$engineVersion.jar")
val runtimeJar = engineRoot.resolve("standalone_runtime/target/evosuite-standalone-runtime-$engineVersion.jar")
val validateEngineJars = tasks.register("validateEngineJars") {
    doLast {
        listOf(engineJar, runtimeJar).forEach { require(it.isFile) { "Build EvoSuite jars first: mvn -pl master,standalone_runtime -am package -DskipTests (missing $it)" } }
        listOf("master", "client", "runtime").forEach { module ->
            val latest = engineRoot.resolve("$module/target/classes").walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: 0L
            require(engineJar.lastModified() >= latest) { "EvoSuite jar is older than $module classes. Rebuild the shaded engine jar." }
        }
    }
}
val engineFiles = tasks.register<Sync>("copyEngineJars") {
    dependsOn(validateEngineJars)
    from(engineJar) { rename { "evosuite.jar" } }
    from(runtimeJar) { rename { "evosuite-standalone-runtime.jar" } }
    into(layout.buildDirectory.dir("engine"))
}
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>().configureEach {
    dependsOn(engineFiles)
    from(engineFiles) { into("${rootProject.name}/engine") }
}
tasks.buildPlugin {
    doLast {
        ZipFile(archiveFile.get().asFile).use { zip ->
            listOf("evosuite.jar", "evosuite-standalone-runtime.jar").forEach { name ->
                require(zip.getEntry("${rootProject.name}/engine/$name") != null) { "Plugin ZIP is missing engine/$name" }
                require(zip.getEntry("${rootProject.name}/lib/$name") == null) { "Engine jars must not be on the IDE classloader" }
            }
        }
    }
}
tasks.test {
    // These JUnit 3 platform fixtures cannot use JUnit 4 assumptions to skip setup.
    // Exclude the expensive, prerequisite-dependent imports unless explicitly enabled.
    if (!providers.gradleProperty("evosuite.import.it").map(String::toBoolean).getOrElse(false)) {
        exclude("**/ImportAcceptanceTest*")
    }
    systemProperty("evosuite.it.javaHome", providers.gradleProperty("evosuite.it.javaHome").getOrElse(""))
    systemProperty("evosuite.it.skip", providers.gradleProperty("evosuite.it.skip").getOrElse("false"))
    systemProperty("evosuite.engine", engineJar.absolutePath)
    systemProperty("evosuite.runtime", runtimeJar.absolutePath)
    systemProperty("evosuite.import.it", providers.gradleProperty("evosuite.import.it").getOrElse("false"))
    systemProperty("evosuite.import.gradleHome", providers.gradleProperty("evosuite.import.gradleHome").getOrElse(gradle.gradleHomeDir?.absolutePath.orEmpty()))
    systemProperty("evosuite.import.fixtures", file("src/test/testData/import").absolutePath)
    inputs.dir("src/test/testData/import")
    // JDK 21 has the Java desktop module; tests run through the platform's normal harness.
    maxHeapSize = "2g"
}

// Preserve the previously released Marketplace ID for users' update path. This naming rule
// only applies to new IDs; keep all API and dependency compatibility checks enabled.
tasks.verifyPlugin {
    freeArgs.addAll("-mute", "TemplateWordInPluginId")
    // Java, JUnit and optional Maven are supplied by each IDE. Do not fetch unrelated
    // transitive optional Marketplace plugins (Python, Go, Android, etc.).
    offline = true
    useBundledRuntime = true
}
