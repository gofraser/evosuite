pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
rootProject.name = "evosuite-intellij"
include(":ide-core")
project(":ide-core").projectDir = file("../ide-core")
