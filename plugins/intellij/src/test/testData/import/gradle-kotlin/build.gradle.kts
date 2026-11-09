subprojects {
    apply(plugin = "java")
    repositories { mavenCentral(); mavenLocal() }
    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(17) }
    }
}
