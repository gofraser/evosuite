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
- Signing, Marketplace publishing and an interactive imported Maven/Gradle project acceptance
  run remain release activities; this task does not publish or require credentials.
