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

EvoSuite runs on the project's JRE, so it can always read the project's class files. To use a
different JDK, set *Java home* on the *Advanced* settings page.

### Existing test suites

If some of the classes already have a suite, the plugin asks once, before generating, what to do
with all of them:

- **Replace** overwrites the existing suites, as EvoSuite does on the command line.
- **Add New Tests** keeps the existing tests and adds the new ones after them. A new test whose
  body equals that of an existing test is left out. Clashing names are renamed: numbered tests
  continue the existing numbering (`test0` becomes `test5` if the suite ends at `test4`), other
  names get a suffix (`_1`). The imports the new tests need are added, and for JUnit 4 suites the
  classes they initialise and reset are too. The previous version stays in the file's local
  history.
- **Compare** opens the existing suite and the new one in a compare editor: the existing file is
  editable, the new suite is read-only. Copy over what you want and save. Several classes share
  one compare editor, which lists them.

With Add New Tests and Compare, EvoSuite writes into the plugin's state area, so the existing
suites stay as they are until generation has finished. Classes without a suite get the new one.
Adding needs two suites that EvoSuite generated the same way: both with `@EvoRunnerParameters`,
for the same version of JUnit, and with the same runner settings and superclass. Otherwise, for
example for a hand-written suite or one with unsaved changes in an editor, the plugin opens the
comparison instead and the console says why.

### Tests for single methods

To test one method, right-click it in the Package Explorer or the Outline, or right-click in the
Java editor while the caret is in it, and choose **Generate Tests for Method with EvoSuite**. The
command is also in the *Source* menu. Select several methods to test them together; the methods
of one class share one EvoSuite run.

The tests call only the selected methods, plus constructors to create objects
(`-Dtarget_method_list`, with each method given by name and JVM descriptor, as in
`sign(I)Ljava/lang/String;`). Because such a suite covers only part of the class, **Add New
Tests** is preselected when the class already has a suite. Constructors can be selected as well.
The command is not offered for private and abstract methods, which tests cannot call directly,
for constructors of enums and abstract classes, and for methods of nested classes: EvoSuite
tests top-level classes, so generate tests for the class that contains them instead.

### Settings

*Window > Preferences > Java > EvoSuite* holds the workspace defaults, on three pages:

- **EvoSuite**: the search (budget, coverage criteria, assertions, minimization) and the
  generated tests (format, folder, names, comments, time limit, opening them in an editor).
- **Large Language Model**: EvoSuite's LLM support, see below.
- **Advanced**: the JVM that runs EvoSuite, additional arguments, taking settings from
  `pom.xml`, and adding the test folder and libraries to the build path.

*Project > Properties > EvoSuite* has the same pages and can override the workspace defaults per
project. *Enable project-specific settings* switches all three pages at once, and *Configure
Workspace Settings...* opens the workspace pages. Project settings are stored in
`.settings/org.evosuite.plugins.eclipse.core.prefs`, so a team can commit and share them.
Settings a project leaves unchanged take EvoSuite's defaults, not the workspace's.

An empty field shows what it stands for, usually EvoSuite's default. Settings that have no
effect given the others, such as which assertions to keep when no assertions are generated, are
disabled but keep their values.

| Setting | Default | |
|---|---|---|
| Search budget per class | 60 s | `-Dsearch_budget` |
| Coverage criteria | EvoSuite's default: all eight boxes ticked | `-criterion`, e.g. `LINE:BRANCH`; criteria without a box that were entered before, such as `STRONGMUTATION`, are kept |
| Generate assertions / minimize tests | on | `-Dassertions`, `-Dminimize` |
| Assertions kept | those that detect faults | `-Dassertion_strategy` (MUTATION, or ALL observed values) |
| Test format | JUnit 5 | `-Dtest_format` |
| Test source folder | `src/test/evosuite` | `-Dtest_dir` |
| Test class suffix | `_ESTest` | `-Djunit_suffix` |
| Test names | numbered (`test0`, ...) | `-Dtest_naming_strategy` (COVERAGE: descriptive, from what each test covers) |
| Variable names | from the type (`int0`, ...) | `-Dvariable_naming_strategy` (HEURISTICS_BASED: from how values are used) |
| Comment tests with the goals they cover | off | `-Dtest_comments` |
| Time limit per test | empty: EvoSuite's 3000 ms | `-Dtimeout` |
| Open generated tests | on | |
| Java home (*Advanced*) | empty: the project's JRE | |
| Client heap (*Advanced*) | empty: EvoSuite's default | `-mem` |
| Additional arguments (*Advanced*) | | passed last, so they override everything else |
| Take settings from pom.xml (*Advanced*) | on | see *Maven and Gradle projects* below |
| Add to the build path (*Advanced*) | on | adds the folder, JUnit and the runtime when missing |

### LLM support

The *Large Language Model* page configures EvoSuite's LLM integration. Choosing a provider
switches it on. Without a provider, or with *Search only*, the other LLM settings are disabled,
and the LLM-only approaches disable the settings that only apply to the search:

| Setting | |
|---|---|
| Provider | None, OpenAI (or any OpenAI-compatible server, via the base URL), Anthropic, Ollama |
| Model | required except for OpenAI, whose default EvoSuite knows; the hint suggests one for the provider |
| Base URL | empty for the provider's endpoint; Ollama defaults to `http://localhost:11434` |
| API key | OpenAI and Anthropic; workspace preferences only, kept in Eclipse's secure storage |
| Approach | search only (the LLM is off, the settings are kept), search assisted by the LLM, or LLM-only generation with one prompt or iterating on uncovered goals (`-Dstrategy=LLMSTRATEGY`) |
| Seed the search | `llm_seeding_profile`: off, minimal, standard or full; search approach only |
| When the search stagnates | `llm_on_stagnation`; search approach only |
| Mutation and crossover | `llm_operator_enabled`; search approach only |
| Improve names, comments and assertions | `llm_postprocessing_enabled` |
| LLM calls per class | `llm_max_calls`; empty for no limit |

*Search only* is absolute: it also overrides an LLM that the pom or the additional arguments
switch on, so it can be relied on to keep the project's code from being sent to a model.

The API key never goes into the project's `.settings` file, onto the command line, or into
the console. EvoSuite receives it as the `EVOSUITE_LLM_API_KEY` environment variable. If no key
is stored, an `EVOSUITE_LLM_API_KEY` set in the environment Eclipse starts in still works.
Without any key, EvoSuite runs without the LLM, and the console says so. The remaining `llm_*`
options can go into *Advanced > Additional arguments*.

The project directory is EvoSuite's base directory, so an `evosuite-files/evosuite.properties`
in the project still applies. EvoSuite's statistics go to the plugin's state area, not into the
project.

### Maven and Gradle projects

If a Maven project configures the EvoSuite Maven plugin
(`org.evosuite.plugins:evosuite-maven-plugin`), the plugin takes these settings from the pom.
That way a class gets the same tests in Eclipse as with `mvn evosuite:generate`:

| pom parameter | becomes | if unset |
|---|---|---|
| `timeInMinutesPerClass` | search budget | 2 minutes, the Maven plugin's default |
| `criterion` | coverage criteria | the Maven plugin's default set |
| `testFolder` | test source folder | `src/test/evosuite` |
| `evosuiteProperties` | `-D` arguments after the Eclipse ones (values may contain spaces) | |
| `extraArgs` | arguments after those, so they win, as with Maven | |
| `llmApiKeyServerId` | the LLM API key: the password of that `<server>` in Maven's `settings.xml`, decrypted if need be | the key in the Eclipse preferences |
| `memoryInMB` / `numberOfCores` (or `cores`) | client heap | the Eclipse setting; the Maven default of 800 MB is a whole-module budget |

If the pom switches the LLM on through `evosuiteProperties` or `extraArgs`, the Eclipse plugin
supplies the API key and checks the model just as for its own LLM settings. Wherever an API key
does end up on a command line, the console masks it.

The values come from m2e's effective model, so parent poms, `pluginManagement` and `${...}`
properties count. Each parameter is resolved as Maven resolves it for the `generate` goal. The
order is a `generate` execution's configuration, the plugin's configuration, a pom property
`evosuite.<name>`, then the deprecated unprefixed property. `testFolder` also comes from a
`setup` execution, which registers the folder for Maven builds. The project's EvoSuite
page lists what the pom overrides, and so does the console at each run. Parameters for
whole-module runs (`timeInMinutesPerProject`, `schedule`, `cuts`, class filters) do not apply to
single classes. To use the Eclipse settings anyway, clear *Advanced > Take settings from the
EvoSuite Maven plugin in pom.xml*. Without m2e installed, the plugin works as for any other project.

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

`evosuite-eclipse-core-tests` runs in a workbench (Tycho's UI harness). It includes end-to-end
tests that generate tests for a small class with the bundled EvoSuite and check that the project
compiles them. They take about two minutes, and run EvoSuite on the JVM that runs the build
unless you name another JDK. To skip them, pass `-Devosuite.it.skip=true`:

```sh
./mvnw verify -Devosuite.it.javaHome=/path/to/jdk-17
```

The settings tests open the pages in the workbench's dialogs, and the tests of the existing-suites
dialog and the compare editor open those. To look at them, name a directory in
`EVOSUITE_SCREENSHOTS`, and each page or dialog they open is saved there as a PNG.

`MavenPluginLifecycleMappingTest` checks that m2e runs the EvoSuite Maven plugin's `setup` goal
and keeps the generated-tests folder as a test source folder. It needs the Maven plugin
(1.2.1-SNAPSHOT) in a local repository that m2e can use offline, named by a settings file. Keep
that repository separate, so that installing a work-in-progress Maven plugin does not replace the
snapshot in `~/.m2`, for example as an APFS clone (`cp -cR ~/.m2/repository /tmp/m2`):

```sh
mvn -o -Dmaven.repo.local=/tmp/m2 -pl plugins/maven -am install -DskipTests   # repository root, JDK 17
./mvnw verify -Devosuite.it.mavenSettings=/tmp/m2-settings.xml                # <localRepository>/tmp/m2</localRepository><offline>true</offline>
```

### Layout

| Module | |
|---|---|
| `evosuite-eclipse-deps` | data bundle with `lib/evosuite.jar` and `lib/evosuite-standalone-runtime.jar`, installed unpacked |
| `evosuite-eclipse-core` | the plugin: command, job, settings, *EvoSuite Runtime* classpath container |
| `evosuite-eclipse-core-tests` | test fragment |
| `evosuite-eclipse-feature`, `evosuite-eclipse-site` | feature and p2 update site |
