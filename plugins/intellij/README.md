# EvoSuite for IntelliJ IDEA

Requires IntelliJ IDEA 2025.3 or newer and a Java project with a full JDK. The plugin bundles
EvoSuite and its test runtime; generation runs in a separate JVM on the module's JDK.

## Generate tests

Right-click a Java class, package, source directory or module in the Project view, or use the
Java editor's **Generate Tests with EvoSuite** action. It is also available in the Structure
view and Generate menu. Test sources, interfaces, annotations and generated suites are skipped.
Requests wait for one another, build their module, then generate one class at a time.

**Generate Tests for Method with EvoSuite** restricts generation to selected methods or the
method at the caret. Private/abstract methods, nested-class methods and enum/abstract-class
constructors are excluded. Overloads use their JVM descriptors, so they stay distinct.

**Extend Tests with EvoSuite** accepts existing test classes. The plugin infers their production
class from the package and `Test`, `Tests`, `TestCase`, `IT`, `ITCase`, `TestFoo` or the configured
suite suffix; ambiguous names open a class-name prompt with inline production-class validation.
Cancel stops the entire request, including other selected test classes. A longer name such as
`VaultEdgeCasesTest` offers the longest matching production-class prefix for confirmation. Select several test classes to seed the
same CUT together. EvoSuite parses their saved source, executes their compiled tests to exclude
covered goals, and generates additional tests. Carving stays disabled. The console reports how
many tests were parsed and executed. Extension deliberately includes test outputs and test
libraries on the engine classpath; ordinary generation excludes them. Parser limitations are
the same as in Eclipse: parameterized tests and statements depending on fixtures/helpers may
be dropped.

The **EvoSuite** sidebar opens a tool window with actions to generate tests for the open Java
file/current method, extend the open test file, and open the EvoSuite settings scope the project uses. Before the first run,
it shows instructions and labeled action buttons. When generation starts it switches to commands
(with keys masked), progress and results; generation/settings actions remain in the toolbar. Its Stop
action cancels this project's requests and kills the engine and client JVMs. Completed suites
are kept. Up to three resulting suites open automatically; comparisons open afterward so they remain visible.

Generation actions use EvoSuite-logo badges: a green class symbol for class/file generation,
a purple diamond for method generation, and a green plus for extension. Light and dark variants
also distinguish the corresponding context-menu entries.

## Existing suites

The plugin asks once per request: **Replace**, **Add New Tests**, **Compare**, or **Cancel**.
Add is preselected for method and extension requests. Every target writes to fresh staging
outside the project before the choice is applied. The question identifies the classes and modules,
shows their output folder when shared, and wraps long names and paths to fit smaller displays.
Cancel stops the request before project setup or building.

- Replace is undoable and retains IntelliJ local history. Unsaved or subsequently changed
  suites or scaffolding open in a comparison instead. This includes saved scaffolding edits made
  while generation was running.
- Add merges into the current editor document as one undoable command, including unsaved
  edits. Matching test bodies are skipped, clashing test names are renamed, padded test
  numbering is continued, imports and compatible JUnit 4 initialization/reset lists are united.
  If the editor was already dirty, merged edits stay unsaved and the console says so.
- Compare shows the existing document editable when writable and the generated source read-only.
  Several suites share a comparison chain, with full paths to distinguish identical file names.
  Copy wanted changes from Generated to Existing; IntelliJ saves edits normally, and closing the
  viewer does not undo edits. The result notification lists pending comparisons and provides
  **Review comparisons** to reopen them during the current IDE session, including after a cancelled
  run with completed comparisons. Generated comparison sources are also retained on disk; the
  console prints their location for recovery after a restart. Read-only or unavailable destinations
  are labeled explicitly.

A handwritten suite is never replaced or merged into: an actual `@EvoRunnerParameters`
annotation is required; a marker in a comment is insufficient. An incompatible merge or
conflicting scaffolding opens a comparison. Whole-class generation also compares a suite
that appeared while the run was queued. Partial generation can add to it after checking
compatibility. Extension writes nothing when no new tests were found. If a new suite's scaffolding
conflicts with an existing file, the suite is kept and the scaffolding opens for comparison.
Resolve that comparison before running the suite.

For JDK 18–23, bundled generation starts the engine with `-Djava.security.manager=allow` and
forwards SecurityManager support to its client JVM. The client uses the selected engine JDK,
including when the IDE inherited a different `JAVA_HOME`. JDK 8–17 need no opt-in; JDK 24 and
later removed this sandbox mechanism, so the launcher omits the unsupported flag there. Explicit
`sandbox=false` still disables EvoSuite sandboxing.

**Remove redundant tests and statements** controls suite/test minimization (`minimize`, default
`true`). Value minimization is separate: the engine's `minimize_values` default is `false`,
unchanged by this plugin. To simplify constants, clear **No additional arguments** under
**Advanced → Additional arguments** and add `-Dminimize_values=true`. Keep test minimization
selected. Values needed to preserve coverage can still remain large.

Bundled-engine launches check the selected JDK before offering dependency changes or starting a build.
Runs identify planned output paths and extension seed classes before generation. Result summaries
distinguish written/updated suites, unchanged suites with no additional tests, pending comparisons,
and warnings; skipped handwritten destinations count as warnings.
Configuration failures link back to the active EvoSuite settings. The console shows collection,
queueing and build progress immediately, and the welcome panel scrolls in a small tool window.
The launch asks to **Save and Generate** or **Cancel** when this project has modified files;
it leaves unrelated project edits unsaved. Edits made while queued remain dirty, so
replacement protection and Add continue to use the current editor contents. Failures in one
target or module do not discard work for other targets; generated sources from a failed placement
remain in the run's staging directory, whose path is printed in the console.

## Settings

**Settings > Tools > EvoSuite** holds IDE defaults; **EvoSuite (Project)** holds project
overrides. Both have EvoSuite, Large Language Model and Advanced tabs.
IDE defaults apply unless **Use project-specific settings** is enabled. Project settings are
stored in `.idea/evosuite.xml`, suitable for version control. Unset project fields use EvoSuite
defaults, independently of IDE defaults. While project settings are off, the disabled fields preview
the active IDE defaults. Switching scope preserves unapplied project edits. **Copy IDE defaults**
starts a project profile from those defaults; **Restore EvoSuite defaults** resets the profile and
Maven delegation without deleting API keys. Neither action is saved until Apply.

The settings match Eclipse: search budget (60 seconds), coverage criteria, assertions and
minimization, assertion strategy, JUnit format, source folder (`src/test/evosuite`), suffix
(`_ESTest`), test/variable names, comments, per-test timeout, editor opening, Java home, client
heap, extra arguments, pom overrides and build-path configuration. Criteria checkboxes preserve
custom criteria. Empty test format detects JUnit 4-only projects; otherwise it uses JUnit 5.
Explicit settings or pom/extra-argument `test_format` override detection.
Text fields show their actual default values, such as `60`, `src/test/evosuite`, and `_ESTest`.
Automatic checkboxes distinguish module-JDK/provider/environment defaults and no additional
arguments from explicit custom values. Clear a numeric/text override and Apply to restore its
displayed default. Combo boxes show named **Default (…)** entries; **Automatic (JUnit 4 or JUnit 5)**
retains project-based framework detection. Simply viewing/applying defaults does not store them
as explicit overrides. Settings tabs wrap explanatory text and scroll vertically
within a compact dialog. Labels include time/memory units and dropdowns use descriptive names.
Unknown stored choices stay visible until corrected; validation identifies the affected field and
selects its tab. Maven-specific controls and imported overrides are grouped under Advanced.

Cloud credentials sit directly below the provider selector. LLM settings include provider,
model, endpoint, approach, seeding, stagnation, mutation/crossover,
postprocessing and call budget. **Search only** overrides every other provider setting, including
pom and extra arguments. The API key is application-wide in PasswordSafe, loaded off the UI
thread only when cloud LLM features are enabled or **Load stored API key** is clicked. The project
LLM tab links to shared API-key management; a loaded key can also be removed with LLM features off.
Keys are sent through `EVOSUITE_LLM_API_KEY`; an inherited environment variable also works.
Provider model defaults and `EVOSUITE_LLM_MODEL` are honored. Local Ollama generation does not
read the shared cloud-provider key. Put keys in secure storage rather than Additional arguments. A failed secure-store read
preserves the saved key unless the user explicitly enters a replacement. A failed key save keeps
the settings dialog open so Apply can be retried.

Output locations (`test_dir`, `junit_suffix`, `report_dir`, `llm_trace_dir`) are pinned after user
arguments, so generated suites and traces are found reliably. Reports and LLM traces stay under
IntelliJ's system directory, in `evosuite/runs/`; successful source staging and argument files are
removed. Failed source placement retains its generated files for recovery.

Legacy plugin budget/folder preferences are imported once; obsolete CTG/Maven/JAR-location
settings are not reused.

## Maven and Gradle

When enabled, the imported effective EvoSuite Maven-plugin configuration takes precedence.
The shared reader follows `default-cli`, plugin configuration, `evosuite.*` properties and the
Maven plugin's legacy-property rules. Other `generate` executions do not count. `timePerClass`
accepts durations such as `90s` or `1m30s`; `timeInMinutesPerClass` remains supported. The console
lists the applied overrides. Without an imported Maven model, regular module settings apply.

`llmApiKeyServerId` selects a server password from the configured Maven user settings. Plaintext,
environment placeholders and Maven 3 decorated encryption are supported. Unsupported encryption
or a missing server produces a diagnostic and falls back to PasswordSafe/environment; ciphertext
is never passed as a key.

When build-path configuration is enabled, the generated folder is registered as a test root.
Missing JUnit/runtime dependencies are offered after confirmation. Maven gets exact, test-scoped
POM dependencies and waits for reimport; Gradle uses the build-file dependency modifier and waits
for re-sync. Maven dependency edits update an existing declaration rather than duplicating it,
and retain explicitly configured existing scopes. Gradle
source-set imports register generated roots and inspect test dependencies in the test module
linked to the selected production module. JUnit 5 additions include the Jupiter engine and
matching Platform launcher. If a Gradle build cannot be modified, the console prints exact
dependency declarations to add manually. Plain modules use the bundled runtime as a test-scoped
module library. Missing bundled-library paths are repaired after
plugin upgrades. Legacy production roots are converted to test roots without duplicate entries.
The dependency version is generated from the same build metadata as the bundled engine.
Imported projects using a snapshot runtime need
that artifact available in their Maven repositories (for a development build, install the reactor).

For Maven, a single **Configure Maven** prompt offers the missing test dependencies together
with adding or updating the `setup` execution. **Dependencies Only** skips the execution. When only
an execution is missing, **Skip Maven Setup** explicitly continues without that POM change.
**Cancel** stops generation and leaves the pom unchanged. Gradle/plain-project dependency prompts
also distinguish **Skip Setup** from **Cancel**. The setup goal requires JUnit/runtime dependencies; it does not add
them. Both edits are one undoable command followed by one awaited reimport. Existing execution
IDs and other configuration are preserved. It saves that pom and waits for reimport before
building. Its import configurator keeps that execution's folder as a test root on reimport. Gradle projects receive the exact
source-set snippet in the console; add it to the build script so re-sync retains the root:

```groovy
sourceSets.test.java.srcDir("src/test/evosuite")
```

The project settings offer **Run whole-class requests through the Maven plugin**. This invokes
IntelliJ's Maven runner with `compile` and `generate` (which already exports tests), uses pom
settings and Maven scheduling/export, and has no per-class
progress or IDE Add/Compare workflow. Requests requiring Search-only enforcement or protection
of existing suites use the bundled engine instead. Methods and extension always use the bundled
engine. The default mode is the bundled engine.

## Build and test

The engine reactor uses JDK 17; the standalone IntelliJ Gradle build uses JDK 21.

```sh
# repository root, JDK 17
mvn -pl master,standalone_runtime,plugins/ide-core -am package -DskipTests
mvn -f plugins/ide-core/pom.xml verify

# plugins/intellij, JDK 21
./gradlew test buildPlugin verifyPlugin \
  -Pevosuite.it.javaHome=/path/to/jdk-17
```

The Gradle wrapper and its distribution checksum are pinned. `copyEngineJars` rejects missing
jars or a shaded engine older than its compiled inputs. `-Pevosuite.root=/path/to/checkout`
selects already-built engine/runtime artifacts; ide-core always builds from this checkout's
sources. `-PlocalIde=/path/to/IDE/Contents` selects a local SDK (run Gradle with its supported JDK).

The ZIP is `build/distributions/evosuite-intellij-1.2.1-SNAPSHOT.zip`. Install through
**Settings > Plugins > gear > Install Plugin from Disk**. `runIde` launches a development sandbox.
`-PverificationIde=/path/to/IDE/Contents` reuses an installed IDE as the second verification target.
Engine jars are under `evosuite-intellij/engine/`, separate from the plugin libraries. Both the minimum
2025.3.2 and 2026.2.3 are configured for Plugin Verifier. The verifier's new-ID naming rule
`TemplateWordInPluginId` is muted because this plugin retains its previously released
`org.evosuite.plugin.intellij` ID; API compatibility checks remain enabled. Verification uses the supplied IDEs and their bundled
runtimes/dependencies, without fetching unrelated optional Marketplace plugins. The Maven folder
import hook is a public experimental API, so both IDE versions remain in the release checks.

Tests include shared command/merger/POM/progress/process behavior, IntelliJ PSI selection and
method descriptors, settings serialization/precedence, test roots/classpaths, protected suite
placement, Maven imported-model mapping and a real engine run whose generated suite is compiled.
`-Pevosuite.it.skip=true` skips the standalone real-engine smoke explicitly.

The opt-in import acceptance suite loads physical multi-module projects through IntelliJ's real
Maven and Gradle importers. It checks source-set ownership, custom-folder reimport, existing and
missing dependencies, JUnit 4-only detection, dependent-module classpaths, generation, generated
suite compilation/execution and parsed seed execution. Groovy and Kotlin DSL dependency edits
are exercised. CI runs these checks; locally use:

```sh
# repository root, JDK 17: install the snapshot artifacts used by imported builds
mvn -pl master,standalone_runtime,plugins/ide-core,plugins/maven -am install -DskipTests
# plugins/intellij, JDK 21
./gradlew :test --tests org.evosuite.intellij.ImportAcceptanceTest \
  -Pevosuite.import.it=true -Pevosuite.it.javaHome=/path/to/jdk-17
```

The tests use the wrapper's Gradle installation. Override it with
`-Pevosuite.import.gradleHome=/path/to/gradle` when needed. These are automated imports and
builds; they do not require desktop control or alter existing IDE projects.

`signPlugin` reads `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`; `publishPlugin`
reads `PUBLISH_TOKEN`. Publishing/listing ownership and release credentials remain maintainer
steps; no Marketplace upload is part of the build.
