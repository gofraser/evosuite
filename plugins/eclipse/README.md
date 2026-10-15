# EvoSuite Eclipse Plugin

Generates JUnit test suites with EvoSuite from inside Eclipse.

## Using it

Right-click a class, a package, a source folder or a project in the Package Explorer or the
Project Explorer, or right-click in a Java editor, and choose **Generate Tests with EvoSuite**.
You can bind a key to the command under *Preferences > General > Keys*.

What happens next:

1. The plugin creates the test folder (`src/test/evosuite` by default) as a test source folder.
   If the project does not already have them, it also adds JUnit and the EvoSuite runtime to the
   build path, as test-only libraries.
2. It waits for the workspace build, then runs EvoSuite in a separate JVM, one class at a time.
   The *EvoSuite* console shows the command line and EvoSuite's output, and the progress view
   shows the search progress and coverage of the current class. Cancelling the job stops
   EvoSuite and its client JVM.
3. Each suite goes to `<test folder>/<package>/<Class>_ESTest.java`. If you generated tests for
   up to three classes, their suites open in editors.

Classes in test source folders, interfaces, annotations and EvoSuite's own output are skipped.
Before overwriting an existing suite, the plugin asks for confirmation.

EvoSuite runs on the project's JRE, so it can always read the project's class files. To use a
different JDK, set *Java home* in the settings.

### Settings

*Window > Preferences > Java > EvoSuite* holds the workspace defaults. *Project > Properties >
EvoSuite* can override them per project. Project settings are stored in
`.settings/org.evosuite.plugins.eclipse.core.prefs`, so a team can commit and share them.

| Setting | Default | |
|---|---|---|
| Search budget per class | 60 s | `-Dsearch_budget` |
| Coverage criteria | EvoSuite's default | `-criterion`, e.g. `LINE:BRANCH` |
| Generate assertions / minimize tests | on | `-Dassertions`, `-Dminimize` |
| Test format | JUnit 5 | `-Dtest_format` |
| Test source folder | `src/test/evosuite` | `-Dtest_dir` |
| Test class suffix | `_ESTest` | `-Djunit_suffix` |
| Configure the build path | on | adds the folder, JUnit and the runtime when missing |
| Open generated tests | on | |
| Client heap | EvoSuite's default | `-mem` |
| Java home | the project's JRE | |
| Additional arguments | | passed last, so they override everything above |

The project directory is EvoSuite's base directory, so an `evosuite-files/evosuite.properties`
in the project still applies. EvoSuite's statistics go to the plugin's state area, not into the
project.

### Maven and Gradle projects

m2e and Buildship regenerate the build path from the build file and drop entries that the plugin
adds. To keep the generated tests compiling, declare the dependencies in the build file:

```xml
<dependency>
  <groupId>org.evosuite</groupId>
  <artifactId>evosuite-standalone-runtime</artifactId>
  <version>${evosuite.version}</version>
  <scope>test</scope>
</dependency>
```

Also register `src/test/evosuite` as a test source root, for example with the
`build-helper-maven-plugin`. When the plugin edits the build path of such a project, the console
repeats this advice.

## Building

The plugin is a Tycho build, separate from the EvoSuite reactor because Tycho 5 needs Java 21.
It bundles the engine jars that the reactor builds:

```sh
# repository root, JDK 17
mvn -pl master,standalone_runtime -am package -DskipTests

# plugins/eclipse, JDK 21 or newer
./mvnw verify
```

Use `./mvnw`, which pins Maven 3.9. Maven 3.10 fails on the test bundle with "Invalid Collect
Request", and the enforcer rejects it up front.

The update site is in `evosuite-eclipse-site/target/repository`, with a zipped copy beside it.
To install it, use *Help > Install New Software... > Add... > Local...* or *Archive...*, or run
headlessly:

```sh
eclipse -nosplash -application org.eclipse.equinox.p2.director \
  -repository file:$PWD/evosuite-eclipse-site/target/repository,https://download.eclipse.org/releases/2026-09 \
  -installIU org.evosuite.plugins.eclipse.feature.feature.group
```

The build compiles and tests against Eclipse 2026-09 (`-Declipse-repo.url=...` selects another
release). The plugin requires Java 21, so it only installs into an Eclipse that runs on Java 21
or newer.

### Tests

`evosuite-eclipse-core-tests` runs in a workbench (Tycho's UI harness). It includes an end-to-end
test that generates tests for a small class with the bundled EvoSuite and checks that the project
compiles them. That test takes about a minute. It runs EvoSuite on the JVM that runs the build
unless you name another JDK. To skip it, pass `-Devosuite.it.skip=true`:

```sh
./mvnw verify -Devosuite.it.javaHome=/path/to/jdk-17
```

### Layout

| Module | |
|---|---|
| `evosuite-eclipse-deps` | data bundle with `lib/evosuite.jar` and `lib/evosuite-standalone-runtime.jar`, installed unpacked |
| `evosuite-eclipse-core` | the plugin: command, job, settings, *EvoSuite Runtime* classpath container |
| `evosuite-eclipse-core-tests` | test fragment |
| `evosuite-eclipse-feature`, `evosuite-eclipse-site` | feature and p2 update site |
