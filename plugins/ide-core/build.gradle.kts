plugins { java }
repositories { mavenCentral() }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
tasks.withType<JavaCompile>().configureEach { options.release = 17 }
dependencies {
    implementation("org.eclipse.jdt:org.eclipse.jdt.core:3.43.0")
    implementation("org.eclipse.platform:org.eclipse.text:3.14.400")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
