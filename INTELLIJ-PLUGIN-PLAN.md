# EvoSuite IntelliJ plugin — revised implementation plan

Revised 2026-10-21 against `7786356eb963cae05f3da63cbd10013a0b46ff90`.
Implementation branch: `codex/intellij-plugin`. This supersedes the speculative 2026-10-15 plan.

## Investigation and corrections

The current Eclipse plugin, rather than historical worktree names, is the parity baseline.
Its README and tests describe class/package/root/project selection, method selection,
parsed JUnit extension, build setup, Maven precedence, LLM configuration, cancellation,
and Replace/Add/Compare. Parsed seeding, `timePerClass`, JUnit detection, staging for
**all** suite actions, handwritten-suite protection, unexpected-suite races, placeholder
removal, padded numbering, import clashes and scaffolding comparisons are already implemented.
They must be preserved; they are not future engine work.

The legacy IntelliJ implementation uses obsolete components and a manually configured IDEA
SDK, delegates to CTG or Maven, and lacks tests. Replace it in place, retaining the plugin id
`org.evosuite.plugin.intellij` for an eventual Marketplace update. Do not publish in this task.

Platform documentation was rechecked on 2026-10-21:
- IntelliJ Platform Gradle plugin 2.19.0 requires Gradle 9; use wrapper-pinned 9.8.2 and checksum.
- Compile against IDEA 2025.3.2 (253), Java 21, the agreed minimum. Verify also on 2026.2.3.
- Declare Java and JUnit plugin dependencies; isolate Maven behind an optional descriptor.
- Keep the shaded engine and runtime in `engine/`, never on the IDE classloader's classpath.

## Decisions

1. Share the existing command/options/targets/progress/process/POM/seeding/question code in
   `plugins/ide-core`. Use Java 17 bytecode, a standalone Maven build and a Gradle subproject.
   Both builds compile the same sources. The reactor includes the Maven module.
2. Resolve the open parser question by preserving the tested **headless JDT merger**, not
   rewriting it on JavaParser. JDT and text edits run without an Eclipse workbench; IntelliJ
   bundles those libraries. Eclipse uses its existing JDT bundles and embeds only ide-core.
   File buffers, local history and document undo remain IDE adapters. Preserve the existing
   merger tests, and keep Eclipse-specific file/history tests in its workbench suite.
3. Introduce reusable filesystem Java validation, settings keys/validation, LLM environment
   resolution, and an isolated per-target generation driver. Never infer success from an old
   suite's mtime: fresh staging directories are stronger evidence and avoid timestamp resolution
   problems. Keep Eclipse's orchestration when extracting it would add unrelated risk.
4. Use Java for services/actions and Kotlin for settings pages, with IDE defaults and a shared
   `.idea/evosuite.xml` override. Enabled project settings start from engine defaults, not IDE
   defaults. PasswordSafe holds the application API key; no key in serialized state.
5. Detect JUnit 4-only projects, support the same settings and pom precedence as Eclipse,
   make Search only absolute, and lock output folders/suffix/reports after additional args.
6. Include the agreed optional Maven delegation mode. Explain its limits (whole classes,
   Maven scheduling, no IDE per-class staging/merge/progress). Invoke IntelliJ's Maven runner;
   never pretend it offers method or extension parity.
7. Offer Maven setup/dependency edits explicitly. Use IntelliJ's project-model dependency API
   for imported projects and test-scoped module libraries for plain modules. Maven setup must
   persist the generated root over reimport. For Gradle source sets, print the exact snippet
   instead of silently rewriting build scripts.
8. ADD merges current unsaved document contents as one undoable command. REPLACE compares
   instead if there are unsaved changes, the suite changed since consent, or a suite appeared
   while queued. A handwritten suite is never overwritten. Incompatible ADD and differing
   scaffolding become editable-existing/read-only-generated comparisons.
9. Extension accepts several seed classes/roots for one CUT, copies sources to a common staging
   root, includes compiled test outputs/libraries deliberately, disables carving after user
   arguments, and reports parsed/executed coverage. Save documents before compilation.
10. Marketplace upload/signing credentials are outside this task. Produce an installable unsigned
    ZIP with signing configuration available for the maintainer.

## Implementation order and acceptance checks

- [x] Extract core and wire Eclipse to it; run pure tests and the Eclipse workbench suite.
- [x] Replace legacy plugin with Gradle wrapper, descriptor, optional Maven support, console.
- [x] Implement selection/filtering/descriptors, serialized runs, build-before-run and cancellation.
- [x] Resolve module Java/classpath; register test roots and add missing dependencies.
- [x] Add settings pages, defaults/overrides, enablement, secure key handling and legacy migration.
- [x] Read effective imported Maven model, server credentials, optional delegation and setup.
- [x] Implement suite decisions, undoable ADD, diff fallback, safe replacement and editor opening.
- [x] Implement parsed extension and useful end-of-run summaries.
- [x] Platform tests for selection, settings, setup/classpaths, merge/diff and actions; shared launch
      tests including a real bundled-engine smoke, cancellation and LLM environment behavior.
- [x] Build ZIP, inspect contents, run Plugin Verifier on baseline and current IDE; document results
      and any checks blocked by the environment rather than claiming unexecuted checks passed.

## Validation commands

```sh
# JDK 17
mvn -pl master,standalone_runtime,plugins/ide-core -am package -DskipTests
mvn -f plugins/ide-core/pom.xml test
# JDK 21
cd plugins/intellij
./gradlew test buildPlugin verifyPlugin
# Set -Pevosuite.root=/other/checkout to reuse already built engine jars.
# For a local SDK: -PlocalIde=/path/to/IDE/Contents (use that IDE's required JVM).
# Optional real engine tests: -Pevosuite.it.javaHome=/path/to/jdk-17
cd ../eclipse
./mvnw verify -Devosuite.it.javaHome=/path/to/jdk-17
```

## Sources

- [Platform Gradle plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
- [SDK dependencies](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html)
- [Build numbers and Java versions](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html)
- [Sensitive data](https://plugins.jetbrains.com/docs/intellij/persisting-sensitive-data.html)
- [Persistent state](https://plugins.jetbrains.com/docs/intellij/persisting-state-of-components.html)
- [Verification](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html)
- Repository baseline: `plugins/eclipse/README.md`, `evosuite-eclipse-core`, its test fragment,
  `plugins/maven`, and engine `PARSED_JUNIT` implementation.

## Validation record (2026-10-21)

- Fresh engine/runtime artifacts built successfully from this branch with JDK 17.
- Shared core: 77 tests pass in Maven and Gradle, including process-tree cancellation and key transport.
- IntelliJ: the full 15-test suite passed against IDEA 2025.3.2, followed by six focused
  settings/path tests on the final code (16 distinct tests in total). The real-engine smoke generates class and
  method suites, compiles the generated suite, parses a handwritten seed and executes its tests.
- Eclipse: workbench verification passes (145 tests, 8 optional tests skipped). A separate
  full run passed all seven real-engine tests; its unrelated menu-fixture race was corrected
  by building the recreated test project before looking up its type. The workbench suite was
  then rerun successfully. The m2e lifecycle test needs a separately installed Maven plugin
  and its settings file and remains optional.
- ZIP inspection found and corrected a display-name/project-directory mismatch. Every sandbox
  now receives both engine jars under `evosuite-intellij/engine`; the ZIP build asserts their
  presence and rejects putting them in `lib`. A platform test resolves the installed paths.
- PasswordSafe is never accessed by headless settings indexing or unit tests. The legacy
  Marketplace-ID naming check alone is muted (`TemplateWordInPluginId`); API checks remain active.
- Plugin Verifier passes for baseline 253.30387.90 and current 262.10968.63. Both verdicts are
  compatible, with no internal API usages, missing dependencies, or binary compatibility errors.
  Baseline reports one public deprecated API usage; current reports six. Both report eight usages
  of the same public experimental Maven folder-import API. These warnings are retained and
  documented rather than muted. Plugin paths use the public class-location API; optional Maven
  support resolves through its service registration, avoiding descriptor lookups marked internal.
- Final `:test --tests org.evosuite.intellij.SettingsTest :buildPlugin :verifyPlugin` passes.
  The verifier uses the supplied IDEs' bundled runtimes/dependencies in offline mode. The only
  muted check is the previously released plugin ID's naming rule. The complete ZIP is ~56 MiB.
- Signing and Marketplace publishing remain release activities. The later import acceptance
  follow-up is recorded below; this task does not publish or require credentials.

## Implementation review (2026-10-21)

Reviewed the IntelliJ launch, placement, project setup, settings and Maven adapters against
Eclipse's corresponding handlers/jobs and fixtures. Corrected:

- Index-dependent background reads now wait for smart mode, including indexing begun after
  action invocation. Model writes use the caller's default modality.
- Pending VFS writes are flushed before external execution on 2026.2; the public API is
  detected reflectively because 2025.3 completes writes synchronously and lacks this method.
  See [Virtual Files](https://plugins.jetbrains.com/docs/intellij/virtual-file.html) and
  [Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html).
- Launch/setup no longer save unrelated edits made after initial consent. Add retains dirty
  buffers; replacement still falls back to Compare.
- Existing legacy main roots become one test-root entry, preserving the package prefix.
  Broken bundled runtime library paths are rebound after upgrades. Dependency versions come
  from generated engine metadata instead of a hardcoded Java constant.
- Extension inference uses Eclipse's convention ordering and longest production-class prefix
  as a confirmed guess. Interfaces and other ineligible types cannot become guesses.
- A scaffolding conflict no longer discards a newly generated suite. Failed updates fall back
  to Compare; failed placements retain source staging, and target/module failures allow the
  rest of a batch to continue. Cancellation still propagates.
- Maven setup updates preserve execution IDs (including inherited IDs), other goals and
  configuration, save only the pom, and await the update promise before compilation.
  Delegation uses generate's built-in export and reports delegated targets separately.
- Invalid stored overrides can be disabled. Empty coverage selections report validation errors.
  API-key save failures keep Apply pending and retryable instead of clearing modified state.

Regression fixtures cover root conversion, library repair, inference conventions/ambiguity,
new-suite scaffolding conflicts, read-only comparisons, settings recovery, secure-store retries,
bundled-version consistency and Maven execution updates.

Review validation: `test buildPlugin verifyPlugin --continue` passes with JDK 21 and the
real-engine test using JDK 17. All 26 IntelliJ tests and 77 shared-core tests pass with no
skips. The rebuilt ZIP passes CRC inspection and includes both isolated engine jars.
Plugin Verifier reports Compatible on 253.30387.90 (one deprecated API usage) and
262.10968.63 (three deprecated usages), with eight experimental Maven-import API usages
each and no internal-API or binary compatibility errors. At this review stage, real imported
Maven/Gradle acceptance was still outstanding; the follow-up below supersedes that limitation.

## Real import acceptance follow-up (2026-10-22)

Desktop control repeatedly timed out reading IntelliJ's accessibility interface. The user
explicitly authorized automated import checks instead. The acceptance suite therefore uses
IntelliJ's actual Maven/Gradle importers and physical multi-module projects, not constructed
models or a claim of successful UI testing.

The imports reproduced three functional gaps:

1. Gradle imports main/test source sets as separate modules. Registering generated tests against
   the selected main module failed with “Test folder has no module content root”. JUnit detection
   and dependency lookup also missed the linked test module's libraries. Resolve that module
   through `TestModuleProperties`; register roots there, including custom folders requiring an
   additional content entry, and apply dependencies in its scope. Register the generated root
   after dependency-driven imports finish, so the final module model owns it.
2. `JavaProjectModelModificationService` fell back to an IDEA-library download dialog for Gradle.
   It did not persist Gradle dependencies. Use the public `DependencyModifierService`, save only
   build documents changed by the operation, flush pending VFS writes and await a real re-sync.
   Add Jupiter's aggregate artifact and matching launcher when JUnit 5 is absent. Unsupported
   build files get exact manual declarations; they never receive a transient IDEA-library fallback.
3. Maven's model-modification version search substituted `RELEASE` when its repository index
   did not list the local runtime snapshot. Repeated setup added duplicate declarations. Use
   exact test-scoped POM edits, update existing coordinates idempotently, preserve existing scopes,
   save only the changed pom and flush pending VFS writes before the awaited reimport.

Acceptance coverage:

- Maven multi-module/JUnit 4: import, effective options, missing-runtime dependency insertion,
  setup execution creation/update, custom output-folder persistence after full reimport,
  production/dependent-module classpaths, generation, compilation/execution and parsed seeding.
- Gradle multi-project/JUnit 5: separate source-set ownership, no redundant dependency prompts,
  custom generated root, apply the offered source-set snippet, real re-sync, generation,
  compilation/execution and parsed seeding using compiled test outputs.
- Gradle Groovy/JUnit 4: detect the framework from the linked test module, persist a missing
  runtime dependency in the build script, re-sync and execute the fixture tests.
- Gradle Kotlin DSL: add missing Jupiter/Platform-launcher/runtime dependencies, re-sync and
  execute the fixture tests.
- Acceptance Gradle daemons/caches use an isolated user home. This avoids reusing the user's
  daemon from another installation, which can expose unexpected distribution paths in Kotlin
  DSL script classpaths. The IntelliJ test harness explicitly permits its JDK/distribution/cache
  roots without disabling VFS access checks globally.

`ImportAcceptanceTest` is opt-in with `-Pevosuite.import.it=true` and a supplied JDK 17. It uses
the wrapper's Gradle home by default. CI installs the snapshot Maven plugin/runtime and runs
this suite alongside the existing tests. Fixture file changes are tracked as task inputs.

Final validation (2026-10-22): the complete `test buildPlugin verifyPlugin --continue` run passes
with all 31 IntelliJ tests (including four real-import acceptance cases), 77 shared-core tests,
and no skips or failures. Normal tests also pass with the optional import suite excluded.
The real imports run against IDEA 2025.3.2 using JDK 17 project/engine processes and a JDK 21
IDE/test build. Plugin Verifier reports Compatible on 253.30387.90 and 262.10968.63, with one/four
deprecated API usages respectively and twelve public experimental API usages each. No internal
API or binary compatibility errors are reported. The final unsigned ZIP passes CRC inspection
and includes both engine jars outside `lib/`. CI installs the required snapshots and enables
the real-import acceptance suite.


## Settings and first-run usability follow-up (2026-10-22)

Manual feedback identified missing branding, an oversized settings dialog, invisible defaults,
and two successive Maven setup prompts. The plugin now uses vector paths of the Eclipse logo
for the plugin listing, tool window and all generation actions, including dark-theme variants.
Settings prefer a compact 620 by 520 layout, wrap notes, use a two-column criteria grid, and
track the viewport width while scrolling vertically. Text fields show default descriptions;
combo boxes render named Default entries and an Automatic JUnit choice while retaining unset
values internally so detection and settings precedence are unchanged.

Maven setup now offers missing JUnit/runtime dependencies and the setup execution together.
Configure Maven applies both in one undoable POM edit and awaits one reimport. Dependencies
Only preserves the ability to skip the execution; Cancel leaves the POM untouched. The prompt
explains that the setup goal registers test roots/configures the agent, but requires rather
than inserts the test dependencies. Matching existing setup does not prompt again.

Regression checks cover visible defaults, reset/apply and automatic-JUnit round trips, logo
loading and action dimensions, all tabs fitting a compact viewport, and actual Maven imports
for combined setup, dependency-only setup, cancellation and repeated setup. The settings
fixture also renders each tab for visual inspection without desktop automation.

Validation: `test buildPlugin verifyPlugin --continue` passes with 35 IntelliJ tests (including
five real-import acceptance cases) and 77 shared-core tests, no failures/errors/skips. Captures
of the three compact settings tabs were visually inspected. Plugin Verifier reports Compatible
on IDEA 2025.3.2 and 2026.2.3, with the unchanged one/four deprecated and twelve experimental
API usages. The rebuilt ZIP passes CRC inspection and contains the plugin/action logo assets
as well as both isolated engine jars.


## Explicit defaults and useful tool window (2026-10-22)

Further manual feedback found that default descriptions beside empty text fields still looked
like missing settings. Text editors now display their default values directly. Unedited default
values retain their original unset/explicit state; edits become overrides, and clearing an override
and applying restores its displayed default. Automatic checkboxes cover the module JDK,
provider/environment model, provider endpoint and no extra arguments. Their descriptive values
are read-only until the automatic option is disabled, and are never passed to the engine as literal
paths/models/arguments. JUnit format retains its explicit Automatic choice.

The EvoSuite sidebar remains the generation/progress tool window. Its initial view now explains
how to start, with labeled actions for the open Java file, current method, existing tests and
settings. The first console output switches to the console; output from an earlier run is retained
when the window opens. The toolbar keeps generation/settings available alongside console actions,
and Stop is disabled when no requests are running. Tool-window generation resolves the selected
text editor explicitly, so moving focus into the console does not lose the generation target.

Regression checks exercise default/edit/clear/apply round trips, explicit values equal to defaults,
automatic extra-argument controls, the initial-to-output transition, reopening a populated console,
and forwarding the open editor/caret to the existing generation actions. The real method action's
enablement is checked as well. Tool-window buttons and delegation use public ActionUtil dispatch
helpers; direct calls to override-only action methods were caught and removed by Plugin Verifier.
The settings shortcut selects the project's active settings scope. Settings and tool-window
captures support visual layout checks without desktop control.

Validation: the full regression run passed all 38 IntelliJ tests (including five real Maven/Gradle
imports) and all 77 shared-core tests with no failures/errors/skips. After the action-dispatch
correction, all 15 focused settings/tool-window tests passed again, and `buildPlugin verifyPlugin`
completed successfully. Plugin Verifier reports Compatible on IDEA 2025.3.2 and 2026.2.3, with
unchanged one/four deprecated and twelve experimental API usages, and no override-only violations.
The final ZIP passes CRC inspection and includes the new tool-window classes and isolated engine jars.


## Remaining settings and first-run usability review (2026-10-22)

Reviewed the settings editors, inherited/project scope, secure-storage lifecycle, setup dialogs,
preflight/build sequencing, notifications and small tool-window behavior. Fixed the following:

- Descriptive labels include units; enum choices and coverage criteria have readable names and
  checkboxes have clickable captions. Unknown persisted choices remain visible instead of falling
  back silently. Validation identifies the field, selects its tab, and explains numeric/URL errors.
- A disabled project profile previews active IDE values. Scope changes retain draft project values;
  explicit copy/restore actions take effect only on Apply. Restore also resets Maven delegation.
  Criteria ordering and explicit default selections no longer mark an untouched page modified.
- Maven controls/override summaries live under Advanced, with a precedence hint on the main page.
  Wrapped helper text reserves vertical space even when GridBagLayout uses minimum sizes; field
  descriptions must not overlap inputs, and criteria captions fit the compact viewport.
- PasswordSafe is read lazily for active cloud features or explicit key management. Search-only/local
  settings do not open it. A project page links to the shared credentials, and loaded keys remain
  removable when LLM generation is disabled. The shared launch adapter now honors engine model
  defaults/environment overrides and avoids a cloud-key lookup for local Ollama.
- Cancel stops Maven/Gradle setup and the generation request. Explicit Skip/Dependencies Only choices
  retain opt-out behavior. Real Maven import tests exposed invokeAndWait consuming cancellation;
  IdeThread now transports UI callback failures explicitly back to the worker, preserving their types.
- Missing-JDK failure occurs before dependency prompts/build-file edits. Preparation/build/queue
  progress appears promptly, and configuration notifications link to settings. The welcome panel
  scrolls when docked into a small area, keeping its generation/settings controls reachable.

Regression coverage includes scope drafts/copy/reset, malformed and unsupported values, explicit
criteria order, lazy/manual credentials and removal, cloud/local model defaults, a real missing-JDK
launch with settings recovery, UI-to-worker cancellation/exception propagation, compact field-hint
layout, and actual imported Maven/Gradle setup cancellation without build-file changes.

Validation: the full `test buildPlugin verifyPlugin --continue` run passed 45 IntelliJ tests
(including five physical Maven/Gradle imports) and 79 shared-core tests, with no failures/errors/
skips. After the final presentation refinements, all 22 focused settings/first-run/tool-window
checks passed again and the installable ZIP was rebuilt and verified. Compact settings and
welcome/console captures were visually inspected; cloud credentials now sit directly below the
provider selector with explicit first-use instructions. Plugin Verifier reports Compatible on
IDEA 2025.3.2 and 2026.2.3 with unchanged one/four deprecated and twelve experimental API usages.
The final ZIP passes CRC inspection and keeps both engine jars isolated outside the IDE classloader.


## Generation and extension usability review (2026-10-22)

Reviewed target preparation, save/build sequencing, extension inference, the existing-suite question,
placement, comparison viewers and completion/cancellation feedback. Fixed:

- Saving is scoped to this project's modified documents with explicit Save and Generate/Cancel
  choices. A failed save stops generation instead of using stale sources; unrelated editor edits
  stay dirty.
- Extension class-name prompts validate production classes inline and explain the output suite.
  Cancel now aborts the entire request rather than silently dropping one selected seed. Ambiguous
  prompts list inferred candidates and identify the seed's module. The console identifies seed
  classes and planned output paths before generation.
- Replace/Add/Compare questions identify class/module and shared output location. Long messages,
  including unbroken names and paths, wrap for smaller screens. Compare instructions explain
  copying changes, IntelliJ autosave and the fact that closing does not undo edits. Cancel reports
  cancellation and stops before build or setup.
- Replacement consent snapshots now cover scaffolding as well as the suite. Saved, unsaved,
  newly appeared or removed scaffolding changes force comparison instead of overwriting concurrent
  work. Read-only fallback is explained in the console.
- Comparison titles include the full file path; existing content stays linked to the live editor
  document. Read-only and unavailable destinations are labeled and protected. Comparisons open
  after generated editors, and completion notifications count them and offer Review comparisons
  to reopen them in the current session, also when a run is cancelled or fails after producing
  comparisons. Generated comparison sources are retained on disk with their location in the console
  so dismissing a notification or restarting the IDE does not lose the new tests.
- Add reports when preexisting unsaved edits remain dirty. Duplicate-only and empty-extension
  outcomes are reported as unchanged, without claiming that all coverage goals were reached.
  Skipped handwritten destinations count as warnings instead of implying an empty selection or
  successful generation.

New regression coverage exercises physical project-scoped saving and cancellation, production-class
validation, extension cancellation, an actual generation request cancelled at Replace/Add/Compare
before preflight/setup, compact questions, concurrent saved scaffolding edits, live-document and
read-only comparisons, duplicate-only Add and no-additional-tests extension placement.

Validation: the full regression/package/verifier run passed all 52 IntelliJ tests, including five
real Maven/Gradle import acceptance cases, and 79 shared-core tests with no failures/errors/skips.
After the final comparison-source retention/error-recovery adjustment, all 14 focused generation,
extension, placement and first-run tests passed again, and the installable ZIP was rebuilt.
Plugin Verifier reports Compatible on IDEA 2025.3.2 and 2026.2.3 with unchanged one/four deprecated
and twelve experimental API usages. ZIP CRC inspection passed and both engine jars remain isolated
outside the IDE classloader. Dialog acceptance is automated; native desktop interaction remains
outside this validation, as requested earlier in the session.


## Sandbox launch flags, action badges and minimization clarification (2026-10-22)

- The IDE command builder adds `-Djava.security.manager=allow` as a JVM option before `-jar`
  only for the selected JDK 18–23. Explicit JavaInstallation metadata drives IntelliJ launches;
  the shared Eclipse-style adapter can read the selected JDK's release metadata. Unknown
  versions do not inherit a guess from the IDE's JVM.
- Engine client compatibility arguments also opt in on JDK 18–23: setting the parent flag alone
  does not affect a spawned JVM. Explicit parent `disallow` is preserved. No `allow` is sent to
  JDK 24+, which rejects it. GenerationRun pins JAVA_HOME to the selected engine installation
  so a stale IDE environment cannot select a different client JDK.
- Class, method and extension actions have distinct vector badges on the existing EvoSuite logo,
  with light/dark assets shared by the context menus and tool-window delegates.
- The settings checkbox now says Remove redundant tests and statements, with visible help
  distinguishing value minimization. `MINIMIZE_VALUES=false` is the engine default (Git blame
  traces it back to 2012), rather than a new IDE override. The settings and README explain
  enabling `-Dminimize_values=true` without changing the engine default for other users.

Regression coverage includes JDK 8/11/17/18/21/23/24/25/27 command boundaries, option placement
before -jar, selected-JDK release detection, child flag forwarding and explicit disallow,
light/dark icon rendering, and real JDK 21 engine/client generation with sandboxing left enabled
and a deliberately conflicting inherited JAVA_HOME.

Validation: rebuilt and installed engine/runtime/core/Maven snapshots with JDK 17. All six focused
master JVM-forwarding tests passed. The full IntelliJ `test buildPlugin verifyPlugin --continue`
run passed 54 IntelliJ tests (including actual sandbox-enabled JDK 21 generation and five physical
Maven/Gradle import acceptance cases) and 81 shared-core tests, with no failures/errors/skips.
Light/dark badge renders and the compact settings page were visually inspected. Plugin Verifier
reports Compatible on IDEA 2025.3.2 and 2026.2.3 with unchanged one/four deprecated and twelve
experimental API usages. ZIP CRC passed and both freshly built engine jars remain isolated
outside the IDE classloader.
