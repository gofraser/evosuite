# Eclipse / modern IntelliJ functionality audit

Reference: the modern IntelliJ implementation at `codex/intellij-plugin`, commit `42e6324192`,
plus the working-tree improvements present on 2026-10-22. The legacy `plugins/intellij` in this
checkout predates that implementation and was not the comparison baseline. Reference sources
were read without modifying that worktree.

| IntelliJ addition beyond the previous Eclipse implementation | Eclipse implementation |
|---|---|
| Value minimization distinct from suite minimization | General settings, persistence and `minimize_values` launch argument; dependent enablement |
| Runtime-free JUnit output | General setting, `no_runtime_dependency`, dependency setup, machine-local marker/ownership checks and compatible merging |
| Random seed and repeatable requests | Signed 64-bit setting; fresh explicit seeds, saved per-target settings and Repeat saved run |
| All engine criteria | Existing common checkboxes plus multi-selection of additional criteria and validation |
| Automatic framework detection | JUnit 4-only project detection, otherwise JUnit 5; explicit overrides retain precedence |
| Advanced LLM context and request controls | Source/signatures/decompiled/bytecode context, actual JDT source path, token limit, timeout, retries and temperature |
| Provider credentials and connection check | Separate lazy secure keys, explicit legacy import, save/read failure handling, metadata-only cancellable HTTP check, missing-key choices |
| Effective settings and setup inspection | Project property-page check showing applied profile/Maven values, JDK, output and dependencies |
| Settings usability | Scrollable pages, workspace preview, copy defaults, retained project drafts, visible invalid choices and domain validation |
| Existing-suite defaults and edit protection | Add preselected; merges preserve dirty documents; saved changes during generation protected; scaffolding conflicts compared |
| Durable results workspace | EvoSuite Runs view, per-target status/coverage/tests/elapsed/phase/seed, saved settings, reports, persistent comparisons, replay and cancellation |
| Run generated suites | Eclipse's native JUnit launch configuration from Runs |
| Existing-test coverage | Explorer/editor/view action, recursive test-directory selection, same CUT chooser, coverage-only launch without setup or LLM |
| Request workflow | Project-scoped save, batch budget confirmation, exact active-selection duplicate suppression, chooser cancellation aborts entire selection |
| Persistent build-tool setup | Confirmed Maven dependencies/setup execution or Gradle dependencies/test root/JUnit Platform, followed by m2e/Buildship sync |
| Optional Maven execution mode | Project setting, Eclipse Maven runner, protected/partial/advanced requests kept on bundled engine path |
| JDK launch behavior | Full-JDK validation, selected JAVA_HOME for clients, SecurityManager opt-in only on JDK 18–23 |
| Diagnostics lifecycle | Per-target report/staging folders, recovery sources retained on failure, configurable retention, pending comparisons protected |

Existing Eclipse class/method selection, source-root handling, PARSED_JUNIT extension, Maven
precedence/server-password decryption, progress parsing, process-tree cancellation and compare
editor integration were already present and remain in place. Platform-specific presentation
(Swing versus SWT, sidebar versus view, badges, local-history/undo UI) follows the host IDE.

Validation includes the existing workbench suite and additional persistence/command/ownership,
Maven/Gradle idempotence, metadata protocol, real runtime-free generation and coverage tests.
The full workbench run passed 204 tests (the optional m2e test skipped). A separate offline m2e
lifecycle and open-editor action run passed all 7 tests, including m2e. External Maven repositories
and a live Gradle import were not exercised; Gradle build-script changes have idempotence tests.
The final provider-key removal, enablement and layout run passed all 13 tests. The m2e test can be repeated with `evosuite.it.mavenSettings` as documented in README.

## External-review follow-up (2026-11-01)

Compared the confirmed IntelliJ fixes recorded in its `REVIEW-20261101.md` against Eclipse.

| Review issue | Eclipse disposition |
|---|---|
| Missing Gradle subproject script / wrong DSL | Ported. Setup creates the subproject script after confirmation, infers the nearest build's Kotlin/Groovy DSL, and preserves an existing local script. A file appearing or changing during confirmation aborts setup. |
| JUnit 4 under inherited JUnit Platform tasks | Ported. Both DSLs explicitly configure `useJUnit()` for JUnit 4; JUnit 5 continues to use `useJUnitPlatform()`. |
| Delegated Maven output missing from results | Ported. History records suite destinations; workspace refresh runs after success, failure or cancellation, imports partial output, counts tests, and honors the existing editor-opening preference and limit. Open/Run tests can use those suites. Runtime-based output is already recognized by its real runner annotation. |
| Credential-token masking / repeated log regex compilation | Ported. Mask quoted or unquoted key values without dropping following flags; compile log patterns once. |
| Maven built-in / nested expressions | Already resolved by m2e's effective model. Added an integration regression for nested custom properties referencing build directory, artifact ID, group ID and version. |
| Unresolved method descriptors | Already returns null and skips the selected method rather than throwing or guessing a type. |
| Editor references / multi-class open-file targeting | Already collects the editor input compilation unit for class actions, and uses the caret only for method actions. JDT handles remain stable across saving. |
| Source-set ownership / background read actions | IntelliJ workspace-model concerns. Eclipse uses the owning Java project and JDT/workspace APIs, including test roots on that project's classpath. |
| Settings modality / empty explicit automatic fields | Eclipse already uses native JFace dialogs; its optional fields expose blank-as-default directly rather than a separate automatic checkbox. |
| JDK validation / version metadata | Filesystem-only JDK validation already exists; Maven/Tycho uses the project POM version rather than a regex over a hardcoded root. |
| Maven credential decryption/security relocation | Eclipse delegates to m2e's native Maven `SettingsDecrypter`, rather than maintaining a second settings-security parser. |
| JUnit runner / JDT dependencies | Native Eclipse JUnit launches and platform-provided JDT remain appropriate. |
| Queued destination snapshots | Preserved in both plugins: intervening edits require comparison and never grant fresh overwrite consent. |

Follow-up validation: the complete Eclipse 2026-09 / Tycho 5.0.4 workbench suite ran
214 tests with zero failures/errors (one optional Maven lifecycle test skipped). A final focused
parity/Maven run passed all 25 tests, and `verify` rebuilt the update-site ZIP from the final
sources. Tests used JDK 21 for Eclipse and JDK 17 for the bundled engine. The new Maven output
regression simulates an external write; live Eclipse Maven-runner/Buildship imports were not
exercised in this follow-up. `git diff --check` passed.
