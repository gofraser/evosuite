plugins { java }
dependencies {
    implementation(project(":library"))
}
tasks.test { useJUnitPlatform() }
