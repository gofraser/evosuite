/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.intellij.run;

import com.intellij.notification.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.*;
import com.intellij.task.ProjectTaskManager;

import org.evosuite.ide.*;
import org.evosuite.intellij.actions.TargetCollector;
import org.evosuite.intellij.actions.TestClassInference;
import org.evosuite.intellij.maven.*;
import org.evosuite.intellij.settings.*;
import org.evosuite.intellij.setup.ProjectSetupService;
import org.evosuite.intellij.ui.*;
import org.jetbrains.annotations.NotNull;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Application-wide run serialization; project closure and disposal cancel the entire process tree.
 */
@Service(Service.Level.APP)
public final class GenerationService implements Disposable {
    private final Semaphore slot = new Semaphore(1, true);
    private final Map<Project, Set<ProgressIndicator>> running = new ConcurrentHashMap<>();
    private volatile boolean disposed;
    private final Map<String, ProgressIndicator> runIndicators = new ConcurrentHashMap<>();
    private final Set<String> cancelledRuns = ConcurrentHashMap.newKeySet();

    public static GenerationService getInstance() {
        return ApplicationManager.getApplication().getService(GenerationService.class);
    }

    public boolean isRunning(Project project) {
        return !running.getOrDefault(project, Set.of()).isEmpty();
    }

    public void cancel(Project project) {
        RunHistory.getInstance(project).getState().runs.stream()
                .filter(RunHistory.Run::active)
                .forEach(r -> cancel(project, r.id));
        running.getOrDefault(project, Set.of()).forEach(ProgressIndicator::cancel);
    }

    public void cancel(Project project, String id) {
        cancelledRuns.add(id);
        var indicator = runIndicators.get(id);
        if (indicator != null) indicator.cancel();
    }

    public void replay(Project project, RunHistory.Run run, RunHistory.Target target) {
        submit(
                project,
                new TargetCollector.Selection(List.of(), List.of(), List.of()),
                TargetCollector.Mode.valueOf(run.mode),
                target);
    }

    @Override
    public void dispose() {
        disposed = true;
        running.values().forEach(set -> set.forEach(ProgressIndicator::cancel));
    }

    private record Request(
            Module module,
            List<GenerationTarget> targets,
            GenerationOptions options,
            Path base,
            Path testDir,
            Map<String, SuitePlacement.Snapshot> consent,
            Map<String, Integer> historyIndices) {}

    public void submit(
            Project project, TargetCollector.Selection selection, TargetCollector.Mode mode) {
        submit(project, selection, mode, null);
    }

    private void submit(
            Project project,
            TargetCollector.Selection selection,
            TargetCollector.Mode mode,
            RunHistory.Target replay) {
        var history = RunHistory.getInstance(project);
        String identity =
                mode
                        + ":"
                        + (replay != null
                                ? replay.module + ":" + replay.className + ":" + replay.seed
                                : ReadAction.compute(
                                        () ->
                                                selection.elements().stream()
                                                                .map(
                                                                        e -> {
                                                                            var file =
                                                                                    e
                                                                                            .getContainingFile();
                                                                            return file != null
                                                                                            && file
                                                                                                            .getVirtualFile()
                                                                                                    != null
                                                                                    ? file.getVirtualFile()
                                                                                                    .getPath()
                                                                                            + ":"
                                                                                            + e.getClass()
                                                                                                    .getSimpleName()
                                                                                            + ":"
                                                                                            + e
                                                                                                    .getTextRange()
                                                                                    : e.toString();
                                                                        })
                                                                .toList()
                                                                .toString()
                                                        + selection.files().stream()
                                                                .map(VirtualFile::getPath)
                                                                .toList()
                                                        + selection.modules().stream()
                                                                .map(Module::getName)
                                                                .toList()));
        if (history.activeSelection(identity) != null) {
            EvoSuiteConsole.getInstance(project)
                    .println(
                            "This selection is already queued or running. Use Runs to inspect or"
                                    + " cancel it.");
            EvoSuiteConsole.getInstance(project).show();
            return;
        }
        if (!GenerationDocuments.saveBeforeGeneration(project)) return;
        EvoSuiteConsole console = EvoSuiteConsole.getInstance(project);
        console.show();
        console.println("EvoSuite: collecting Java targets and checking generation settings…");
        String runId = history.start(mode.name(), identity);
        history.cleanup(false);
        new Task.Backgroundable(project, "EvoSuite: generate tests", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                running.compute(
                        project,
                        (ignored, indicators) -> {
                            Set<ProgressIndicator> next =
                                    indicators == null ? ConcurrentHashMap.newKeySet() : indicators;
                            next.add(indicator);
                            return next;
                        });
                runIndicators.put(runId, indicator);
                if (cancelledRuns.contains(runId)) indicator.cancel();
                boolean acquired = false;
                List<VirtualFile> suites = new ArrayList<>();
                List<SuiteComparison.Pair> comparisons = new ArrayList<>();
                int[] counts = {
                    0, 0, 0, 0
                }; // written, warnings/errors, delegated targets, unchanged
                try {
                    indicator.setIndeterminate(true);
                    indicator.setText("Collecting Java targets");
                    Map<Module, List<GenerationTarget>> targets;
                    if (replay != null) {
                        Module module =
                                com.intellij.openapi.module.ModuleManager.getInstance(project)
                                        .findModuleByName(replay.module);
                        if (module == null)
                            throw new IllegalArgumentException(
                                    "Module no longer exists: " + replay.module);
                        targets =
                                Map.of(
                                        module,
                                        List.of(
                                                new GenerationTarget(
                                                        replay.className,
                                                        replay.methods,
                                                        replay.seeds.stream()
                                                                .map(
                                                                        seed ->
                                                                                new GenerationTarget
                                                                                        .Seed(
                                                                                        seed.className,
                                                                                        new java.io
                                                                                                .File(
                                                                                                seed.root)))
                                                                .toList())));
                    } else {
                        var collected =
                                IdeThread.smartRead(
                                        project,
                                        () -> TargetCollector.collect(project, selection, mode));
                        targets =
                                mode == TargetCollector.Mode.EXTEND
                                                || mode == TargetCollector.Mode.COVERAGE
                                        ? extensions(project, collected.tests())
                                        : collected.targets();
                    }
                    List<Request> requests = new ArrayList<>();
                    List<String> withSuites = new ArrayList<>();
                    int total = 0;
                    for (var entry : targets.entrySet()) {
                        indicator.checkCanceled();
                        Module module = entry.getKey();
                        try {
                            GenerationOptions options =
                                    replay == null
                                            ? SettingsResolver.resolve(module)
                                            : SettingKeys.resolve(replay.settings);
                            if (replay != null) {
                                options.randomSeed = replay.seed;
                                options.llmApiKeyServerId = replay.keyServerId;
                            }
                            if (mode == TargetCollector.Mode.COVERAGE)
                                options.llmApproach = GenerationOptions.APPROACH_SEARCH_ONLY;
                            Path base =
                                    ReadAction.compute(() -> ProjectSetupService.baseDir(module));
                            Path folder =
                                    ReadAction.compute(
                                            () -> ProjectSetupService.testFolder(module, options));
                            List<GenerationTarget> safe = new ArrayList<>();
                            Map<String, SuitePlacement.Snapshot> consent = new HashMap<>();
                            Map<String, Integer> historyIndices = new HashMap<>();
                            for (GenerationTarget target : entry.getValue()) {
                                Path suite =
                                        EvoSuiteCommand.suiteFile(
                                                        folder.toFile(),
                                                        target.className(),
                                                        options.testSuffix)
                                                .toPath();
                                Path displayedOutput =
                                        mode == TargetCollector.Mode.COVERAGE
                                                        && !target.seeds().isEmpty()
                                                ? target.seeds()
                                                        .getFirst()
                                                        .sourceRoot()
                                                        .toPath()
                                                        .resolve(
                                                                target.seeds()
                                                                                .getFirst()
                                                                                .className()
                                                                                .replace('.', '/')
                                                                        + ".java")
                                                : suite;
                                historyIndices.put(
                                        target.className(),
                                        history.target(
                                                runId,
                                                module.getName(),
                                                target,
                                                options,
                                                displayedOutput));
                                SuitePlacement.Snapshot existing =
                                        IdeThread.edt(() -> SuitePlacement.snapshot(suite));
                                if (mode != TargetCollector.Mode.COVERAGE
                                        && existing != null
                                        && !SuitePlacement.generated(
                                                project,
                                                suite,
                                                existing.source(),
                                                suite.getFileName()
                                                        .toString()
                                                        .replaceFirst("\\.java$", ""))) {
                                    console.println(
                                            EvoSuiteCommand.notAnEvoSuiteSuite(
                                                    target.className(), suite.toFile()));
                                    counts[1]++;
                                    history.outcome(runId, historyIndices.get(target.className()), "Skipped", "Existing destination is handwritten or no longer matches its generated-suite ownership marker", -1);
                                    continue;
                                }
                                safe.add(target);
                                console.println("Planned output: " + suite);
                                if (target.extendsTests())
                                    console.println(
                                            "Extending "
                                                    + target.seedNames()
                                                    + ": using existing tests as seeds; only"
                                                    + " additional tests are exported to the output"
                                                    + " suite.");
                                if (mode != TargetCollector.Mode.COVERAGE && existing != null) {
                                    consent.put(target.className(), existing);
                                    withSuites.add(
                                            target.className() + " (" + module.getName() + ")");
                                }
                            }
                            if (!safe.isEmpty()) {
                                requests.add(
                                        new Request(
                                                module,
                                                List.copyOf(safe),
                                                options,
                                                base,
                                                folder,
                                                Map.copyOf(consent),
                                                Map.copyOf(historyIndices)));
                                total += safe.size();
                            }
                        } catch (ProcessCanceledException ex) {
                            throw ex;
                        } catch (Exception ex) {
                            counts[1]++;
                            console.println(
                                    "Cannot prepare "
                                            + module.getName()
                                            + ": "
                                            + mask(String.valueOf(ex.getMessage()))
                                            + ". Continuing with other modules.");
                        }
                    }
                    if (requests.isEmpty()) {
                        history.finish(runId, counts[1] > 0 ? "Could not prepare" : "No eligible targets", "See target details and console for skipped selections or configuration errors");
                        console.println(
                                counts[1] > 0
                                        ? "No targets could be prepared. See the messages above;"
                                                + " existing handwritten suites are kept."
                                        : "No eligible Java targets selected. Choose a production"
                                                + " Java class, package or module; use Extend Tests"
                                                + " for existing test classes. Interfaces and"
                                                + " generated suites are skipped.");
                        if (counts[1] > 0)
                            showNotification(
                                    project,
                                    NotificationType.WARNING,
                                    "EvoSuite could not prepare generation. See the console for"
                                            + " configuration details.");
                        return;
                    }
                    if (total > 5 && replay == null) {
                        int targetCount = total;
                        String summary =
                                requests.stream()
                                        .map(
                                                r ->
                                                        r.module().getName()
                                                                + ": "
                                                                + r.targets().size()
                                                                + " targets, "
                                                                + r.options()
                                                                        .effectiveSearchBudget()
                                                                + "s search each, LLM calls/class: "
                                                                + r.options().effectiveLlmMaxCalls()
                                                                + " (0=unlimited)")
                                        .collect(java.util.stream.Collectors.joining("\n"));
                        boolean proceed =
                                IdeThread.edt(
                                        () ->
                                                Messages.showDialog(
                                                                project,
                                                                GenerationDialogs.message(
                                                                        "Generate for "
                                                                                + targetCount
                                                                                + " targets?\n\n"
                                                                                + summary
                                                                                + "\n\n"
                                                                                + "Build,"
                                                                                + " minimization"
                                                                                + " and validation"
                                                                                + " take additional"
                                                                                + " time. Inspect"
                                                                                + " effective"
                                                                                + " configuration"
                                                                                + " from the"
                                                                                + " EvoSuite view"
                                                                                + " before"
                                                                                + " starting."),
                                                                "EvoSuite: Review Bulk Request",
                                                                new String[] {
                                                                    "Start Generation", "Cancel"
                                                                },
                                                                0,
                                                                Messages.getQuestionIcon())
                                                        == 0);
                        if (!proceed) throw new ProcessCanceledException();
                    }
                    SuiteAction action = SuiteAction.ADD;
                    if (!withSuites.isEmpty()) {
                        var scope =
                                mode == TargetCollector.Mode.CLASSES
                                        ? ExistingSuitesQuestion.Scope.CLASSES
                                        : mode == TargetCollector.Mode.METHODS
                                                ? ExistingSuitesQuestion.Scope.METHODS
                                                : ExistingSuitesQuestion.Scope.EXTENSION;
                        String folder =
                                requests.stream()
                                                        .map(r -> r.testDir().toString())
                                                        .distinct()
                                                        .count()
                                                == 1
                                        ? requests.getFirst().testDir().toString()
                                        : null;
                        var question = ExistingSuitesQuestion.of(withSuites, total, folder, scope);
                        action =
                                IdeThread.edt(
                                        () ->
                                                ExistingSuitesQuestion.action(
                                                        Messages.showDialog(
                                                                project,
                                                                GenerationDialogs.message(
                                                                        question.message
                                                                                + "\n\n"
                                                                                + "In Compare, copy"
                                                                                + " wanted changes"
                                                                                + " from Generated"
                                                                                + " to Existing."
                                                                                + " IntelliJ saves"
                                                                                + " your edits"
                                                                                + " normally;"
                                                                                + " closing the"
                                                                                + " viewer does not"
                                                                                + " undo them. You"
                                                                                + " can reopen"
                                                                                + " comparisons"
                                                                                + " from the result"
                                                                                + " notification.\n\n"
                                                                                + "Cancel stops"
                                                                                + " this request"
                                                                                + " before building"
                                                                                + " or changing"
                                                                                + " project"
                                                                                + " setup."),
                                                                question.title,
                                                                ExistingSuitesQuestion.BUTTONS
                                                                        .toArray(String[]::new),
                                                                question.defaultButton(),
                                                                Messages.getQuestionIcon())));
                        if (action == null) throw new ProcessCanceledException();
                    }
                    indicator.setText("Waiting for other EvoSuite runs");
                    history.phase(runId, "Queued: waiting for engine");
                    if (slot.availablePermits() == 0)
                        console.println(
                                "Waiting for another EvoSuite run. Stop cancels this project's"
                                        + " queued requests.");
                    while (!slot.tryAcquire(200, TimeUnit.MILLISECONDS)) check(project, indicator);
                    acquired = true;
                    check(project, indicator);
                    int done = 0;
                    for (Request request : requests) {
                        check(project, indicator);
                        if (request.module().isDisposed()) continue;
                        try {
                            var options = request.options();
                            console.println("=== Module " + request.module().getName());
                            options.fromPom.forEach(
                                    line -> console.println("From pom: " + mask(line)));
                            for (String property :
                                    EvoSuiteCommand.overriddenUserProperties(options))
                                console.println(
                                        "Ignoring additional "
                                                + property
                                                + "; configure the output folder/suffix in EvoSuite"
                                                + " settings.");
                            indicator.setText("Preparing " + request.module().getName());
                            history.phase(runId, "Preparing " + request.module().getName());
                            var maven = MavenBridge.get(project);
                            Map<String, String> env =
                                    LlmEnvironment.resolve(
                                            options,
                                            () ->
                                                    LlmApiKeyStore.get(
                                                            options.effectiveLlmProvider()),
                                            () ->
                                                    maven == null
                                                            ? ""
                                                            : maven.serverPassword(
                                                                    options.llmApiKeyServerId,
                                                                    console::println),
                                            System.getenv(),
                                            console::println);
                            String provider = options.effectiveLlmProvider();
                            if (!provider.equals("NONE")
                                    && !provider.equals("OLLAMA")
                                    && env.getOrDefault(
                                                    EvoSuiteCommand.API_KEY_VARIABLE,
                                                    System.getenv()
                                                            .getOrDefault(
                                                                    EvoSuiteCommand
                                                                            .API_KEY_VARIABLE,
                                                                    ""))
                                            .isBlank()) {
                                int choice =
                                        IdeThread.edt(
                                                () ->
                                                        Messages.showDialog(
                                                                project,
                                                                GenerationDialogs.message(
                                                                        "No API key is available"
                                                                                + " for "
                                                                                + provider
                                                                                + ". Configure a"
                                                                                + " key before"
                                                                                + " using this"
                                                                                + " provider, or"
                                                                                + " run search only"
                                                                                + " for this"
                                                                                + " request."
                                                                                + " Project"
                                                                                + " settings are"
                                                                                + " kept."),
                                                                "EvoSuite: Missing API Key",
                                                                new String[] {
                                                                    "Configure Key…",
                                                                    "Run Search Only",
                                                                    "Cancel"
                                                                },
                                                                0,
                                                                Messages.getQuestionIcon()));
                                if (choice == 0) {
                                    IdeThread.edt(
                                            () -> {
                                                com.intellij.openapi.options.ShowSettingsUtil
                                                        .getInstance()
                                                        .showSettingsDialog(
                                                                project,
                                                                org.evosuite.intellij.settings
                                                                        .EvoSuiteApplicationConfigurable
                                                                        .class,
                                                                c ->
                                                                        c.selectProviderCredentials(
                                                                                provider));
                                                return null;
                                            });
                                    throw new ProcessCanceledException();
                                }
                                if (choice != 1) throw new ProcessCanceledException();
                                options.llmApproach = GenerationOptions.APPROACH_SEARCH_ONLY;
                                env = Map.of(EvoSuiteCommand.API_KEY_VARIABLE, "");
                                request.historyIndices()
                                        .values()
                                        .forEach(i -> history.settings(runId, i, options));
                            }
                            boolean delegation =
                                    EvoSuiteProjectSettings.getInstance(project)
                                                    .getState()
                                                    .delegateToMaven
                                            && mode == TargetCollector.Mode.CLASSES
                                            && maven != null
                                            && maven.directory(request.module()) != null;
                            // Maven's own export does not implement IDE staging or Search-only
                            // enforcement.
                            // Fall back to the bundled driver whenever either protection is
                            // required.
                            if (delegation
                                    && (replay != null
                                            || !options.randomSeed.isBlank()
                                            || !options.llmContextMode.equals("SIGNATURE_ONLY")
                                            || options.llmMaxTokens != 32768
                                            || options.llmTimeoutSeconds != 60
                                            || options.llmRetryMaxAttempts != 2
                                            || !options.llmTemperature.equals("0.7")
                                            || options.effectiveNoRuntimeDependency()
                                            || options.llmSwitchedOff()
                                            || !request.consent().isEmpty()
                                            || request.targets().stream()
                                                    .anyMatch(
                                                            t ->
                                                                    Files.exists(
                                                                            EvoSuiteCommand
                                                                                    .suiteFile(
                                                                                            request.testDir()
                                                                                                    .toFile(),
                                                                                            t
                                                                                                    .className(),
                                                                                            options.testSuffix)
                                                                                    .toPath())))) {
                                console.println(
                                        "Using the bundled engine for saved/fixed-seed settings,"
                                            + " runtime-free output, Search only, or existing-suite"
                                            + " protection.");
                                delegation = false;
                            }
                            final Map<String, String> environment = env;
                            var java =
                                    delegation
                                            ? null
                                            : ReadAction.compute(
                                                    () -> {
                                                        try {
                                                            return ClasspathResolver.java(
                                                                    request.module(), options);
                                                        } catch (java.io.IOException ex) {
                                                            throw new IllegalArgumentException(
                                                                    ex.getMessage(), ex);
                                                        }
                                                    });
                            if (mode != TargetCollector.Mode.COVERAGE)
                                ProjectSetupService.prepare(
                                        request.module(), options, indicator, console::println);
                            check(project, indicator);
                            // Only the initial save question authorizes saving editor changes.
                            IdeThread.flushPendingVfsWrites();
                            if (delegation) {
                                console.println(
                                        "Delegating whole-class generation to Maven. Maven controls"
                                                + " scheduling/export and uses pom settings; IDE"
                                                + " Add/Compare is unavailable in this mode.");
                                final boolean delegatedOk =
                                        maven.delegate(
                                                request.module(),
                                                request.targets(),
                                                options,
                                                environment,
                                                indicator);
                                if (!delegatedOk) counts[1]++;
                                else counts[2] += request.targets().size();
                                request.historyIndices()
                                        .values()
                                        .forEach(
                                                i ->
                                                        history.outcome(
                                                                runId,
                                                                i,
                                                                delegatedOk
                                                                        ? "Delegated to Maven"
                                                                        : "Maven failed",
                                                                "See Maven output for exported"
                                                                        + " suites and reports",
                                                                -1));
                                done += request.targets().size();
                                continue;
                            }
                            indicator.setText("Building " + request.module().getName());
                            history.phase(runId, "Building " + request.module().getName());
                            console.println(
                                    "Building "
                                            + request.module().getName()
                                            + " before generation…");
                            var build =
                                    IdeThread.await(
                                            IdeThread.edt(
                                                    () ->
                                                            ProjectTaskManager.getInstance(project)
                                                                    .build(request.module())),
                                            indicator);
                            if (build.isAborted() || build.hasErrors()) {
                                console.println(
                                        "Build failed or was cancelled. Fix compilation errors"
                                                + " before generating tests.");
                                counts[1] += request.targets().size();
                                request.historyIndices()
                                        .values()
                                        .forEach(
                                                i ->
                                                        history.outcome(
                                                                runId,
                                                                i,
                                                                "Build failed",
                                                                "Fix compilation errors before"
                                                                        + " generation",
                                                                -1));
                                continue;
                            }
                            IdeThread.flushPendingVfsWrites();
                            Path engine = EngineJars.engine();
                            indicator.setIndeterminate(false);
                            for (GenerationTarget target : request.targets()) {
                                check(project, indicator);
                                int historyIndex = request.historyIndices().get(target.className());
                                int index = done++;
                                int all = total;
                                List<String> cp =
                                        ReadAction.compute(
                                                () ->
                                                        ClasspathResolver.resolve(
                                                                request.module(),
                                                                target.extendsTests()));
                                if (cp.isEmpty())
                                    throw new IllegalArgumentException(
                                            "No production classpath for "
                                                    + request.module().getName());
                                indicator.setText("Generating tests for " + target.label());
                                Path state =
                                        Path.of(PathManager.getSystemPath(), "evosuite", "runs");
                                Files.createDirectories(state);
                                Path staging = Files.createTempDirectory(state, "run-");
                                history.beginTarget(runId, historyIndex, staging);
                                // Resolve custom source layouts from the IDE, not src/main/java
                                // assumptions.
                                GenerationOptions targetOptions = options.copy();
                                targetOptions.llmSourcePath =
                                        IdeThread.smartRead(
                                                project,
                                                () -> {
                                                    var type =
                                                            com.intellij.psi.JavaPsiFacade
                                                                    .getInstance(project)
                                                                    .findClass(
                                                                            target.className(),
                                                                            com.intellij.psi.search
                                                                                    .GlobalSearchScope
                                                                                    .moduleWithDependenciesAndLibrariesScope(
                                                                                            request
                                                                                                    .module(),
                                                                                            false));
                                                    return type == null
                                                                    || type.getContainingFile()
                                                                                    .getVirtualFile()
                                                                            == null
                                                            ? ""
                                                            : type.getContainingFile()
                                                                    .getVirtualFile()
                                                                    .getPath();
                                                });
                                console.println(
                                        "Reports and LLM traces: " + staging.resolve("reports"));
                                String key =
                                        environment.getOrDefault(
                                                EvoSuiteCommand.API_KEY_VARIABLE,
                                                System.getenv(EvoSuiteCommand.API_KEY_VARIABLE));
                                boolean keepSources = false;
                                try {
                                    ProgressParser.Listener listener =
                                            new ProgressParser.Listener() {
                                                public void line(String line) {
                                                    history.line(runId, historyIndex, line);
                                                    String phase = line.toLowerCase(Locale.ROOT);
                                                    if (line.stripLeading().startsWith("*")
                                                            && (phase.contains("minimiz")
                                                                    || phase.contains("assertion")
                                                                    || phase.contains(
                                                                            "junit check"))) {
                                                        indicator.setIndeterminate(true);
                                                        indicator.setText(
                                                                "Post-processing "
                                                                        + target.label());
                                                    }
                                                    console.println(
                                                            key == null || key.isBlank()
                                                                    ? mask(line)
                                                                    : mask(line)
                                                                            .replace(key, "****"));
                                                }

                                                public void progress(int search, int coverage) {
                                                    history.progress(
                                                            runId, historyIndex, search, coverage);
                                                    indicator.setIndeterminate(false);
                                                    indicator.setFraction(
                                                            (index + Math.min(100, search) / 100.0)
                                                                    / all);
                                                    indicator.setText2(
                                                            target.label()
                                                                    + ": search "
                                                                    + search
                                                                    + "%, coverage "
                                                                    + coverage
                                                                    + "%");
                                                }
                                            };
                                    if (mode == TargetCollector.Mode.COVERAGE) {
                                        int exit =
                                                GenerationRun.measureCoverage(
                                                        target,
                                                        targetOptions,
                                                        java,
                                                        engine,
                                                        cp,
                                                        request.base(),
                                                        staging,
                                                        listener,
                                                        environment,
                                                        () ->
                                                                indicator.isCanceled()
                                                                        || project.isDisposed()
                                                                        || disposed);
                                        if (exit == EvoSuiteProcess.CANCELLED)
                                            throw new ProcessCanceledException();
                                        if (exit != 0) counts[1]++;
                                        history.outcome(
                                                runId,
                                                historyIndex,
                                                exit == 0 ? "Coverage measured" : "Coverage failed",
                                                "Open reports for coverage statistics",
                                                -1);
                                        continue;
                                    }
                                    GenerationRun.Result result =
                                            GenerationRun.generate(
                                                    target,
                                                    targetOptions,
                                                    java,
                                                    engine,
                                                    cp,
                                                    request.base(),
                                                    staging,
                                                    listener,
                                                    environment,
                                                    () ->
                                                            indicator.isCanceled()
                                                                    || project.isDisposed()
                                                                    || disposed);
                                    if (result.exitCode() == EvoSuiteProcess.CANCELLED)
                                        throw new ProcessCanceledException();
                                    if (result.seeding() != null) {
                                        console.println(result.seeding().summary());
                                        if (!result.seeding().seeded()
                                                || !result.seeding().ranExistingTests())
                                            counts[1]++;
                                    }
                                    if (!result.produced()) {
                                        console.println(
                                                "No tests generated for "
                                                        + target.label()
                                                        + " (exit "
                                                        + result.exitCode()
                                                        + ").");
                                        counts[1]++;
                                        history.outcome(
                                                runId,
                                                historyIndex,
                                                "No tests generated",
                                                "Engine exit code " + result.exitCode(),
                                                0);
                                        continue;
                                    }
                                    if (result.exitCode() != 0) {
                                        console.println(
                                                "EvoSuite wrote tests but exited with code "
                                                        + result.exitCode()
                                                        + "; the suite may be incomplete.");
                                        counts[1]++;
                                    }
                                    Path destination =
                                            EvoSuiteCommand.suiteFile(
                                                            request.testDir().toFile(),
                                                            target.className(),
                                                            options.testSuffix)
                                                    .toPath();
                                    var placed =
                                            SuitePlacement.place(
                                                    project,
                                                    target,
                                                    targetOptions,
                                                    destination,
                                                    result,
                                                    action,
                                                    request.consent().get(target.className()),
                                                    console::println);
                                    if (placed.skipped()) counts[1]++;
                                    else if (placed.written()) counts[0]++;
                                    else if (placed.comparisons().isEmpty()) counts[3]++;
                                    if (placed.suite() != null && options.openGeneratedTests)
                                        suites.add(placed.suite());
                                    comparisons.addAll(placed.comparisons());
                                    history.comparisons(
                                            runId, historyIndex, placed.comparisons(), staging);
                                    history.outcome(
                                            runId,
                                            historyIndex,
                                            placed.skipped()
                                                    ? "Skipped"
                                                    : !placed.comparisons().isEmpty()
                                                            ? "Pending comparison"
                                                            : placed.written()
                                                                    ? "Written / updated"
                                                                    : "Unchanged",
                                            "",
                                            SuiteMerger.testCount(
                                                    Files.readString(result.suite()),
                                                    destination
                                                            .getFileName()
                                                            .toString()
                                                            .replaceFirst("\\.java$", "")));
                                    if (!placed.comparisons().isEmpty()) {
                                        keepSources = true;
                                        console.println(
                                                "Generated comparison sources retained at "
                                                        + staging.resolve("tests"));
                                    }
                                } catch (ProcessCanceledException | InterruptedException ex) {
                                    throw ex;
                                } catch (Exception ex) {
                                    counts[1]++;
                                    history.outcome(
                                            runId,
                                            historyIndex,
                                            "Failed",
                                            mask(String.valueOf(ex.getMessage())),
                                            -1);
                                    keepSources = true;
                                    console.println(
                                            "Failed for "
                                                    + target.label()
                                                    + ": "
                                                    + mask(String.valueOf(ex.getMessage())));
                                    console.println(
                                            "Generated sources retained at "
                                                    + staging.resolve("tests"));
                                } finally {
                                    // Failed placements and pending comparisons keep recoverable
                                    // generated sources.
                                    try {
                                        if (!keepSources)
                                            GenerationRun.deleteTree(staging.resolve("tests"));
                                        GenerationRun.deleteTree(staging.resolve("seeds"));
                                    } catch (java.io.IOException ex) {
                                        console.println(
                                                "Could not clean staging folder " + staging);
                                    }
                                }
                            }
                        } catch (ProcessCanceledException | InterruptedException ex) {
                            throw ex;
                        } catch (Exception ex) {
                            counts[1]++;
                            request.historyIndices()
                                    .values()
                                    .forEach(
                                            i ->
                                                    history.outcome(
                                                            runId,
                                                            i,
                                                            "Failed",
                                                            mask(String.valueOf(ex.getMessage())),
                                                            -1));
                            console.println(
                                    "Failed module "
                                            + request.module().getName()
                                            + ": "
                                            + mask(String.valueOf(ex.getMessage()))
                                            + ". Continuing with other modules.");
                        }
                    }
                    IdeThread.flushPendingVfsWrites();
                    history.finish(
                            runId,
                            counts[1] == 0 ? "Completed" : "Completed with warnings",
                            comparisons.isEmpty()
                                    ? "Finished"
                                    : "Review pending comparisons in Runs");
                    console.println(
                            "EvoSuite finished: "
                                    + counts[0]
                                    + " suites written/updated, "
                                    + counts[3]
                                    + " unchanged (no additional tests), "
                                    + counts[2]
                                    + " Maven targets processed, "
                                    + comparisons.size()
                                    + " comparisons, "
                                    + counts[1]
                                    + " warnings/errors.");
                    showNotification(
                            project,
                            counts[1] == 0
                                    ? NotificationType.INFORMATION
                                    : NotificationType.WARNING,
                            "EvoSuite finished: "
                                    + counts[0]
                                    + " suites written/updated"
                                    + (counts[3] == 0
                                            ? ""
                                            : ", " + counts[3] + " unchanged (no additional tests)")
                                    + (counts[2] == 0
                                            ? ""
                                            : ", " + counts[2] + " Maven targets processed")
                                    + (comparisons.isEmpty()
                                            ? ""
                                            : ", "
                                                    + comparisons.size()
                                                    + " files to compare (copy changes to keep"
                                                    + " them)")
                                    + (counts[1] == 0
                                            ? "."
                                            : "; see the EvoSuite console for warnings."),
                            comparisons);
                } catch (ProcessCanceledException ex) {
                    history.finish(
                            runId,
                            "Cancelled",
                            "Completed outputs and pending comparisons are kept");
                    console.println("EvoSuite cancelled. Completed suites are kept.");
                    if (!comparisons.isEmpty())
                        showNotification(
                                project,
                                NotificationType.INFORMATION,
                                "EvoSuite cancelled: completed suites are kept; "
                                        + comparisons.size()
                                        + " files are available to compare.",
                                comparisons);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    history.finish(runId, "Cancelled", "Completed outputs are kept");
                    console.println("EvoSuite cancelled.");
                    if (!comparisons.isEmpty())
                        showNotification(
                                project,
                                NotificationType.INFORMATION,
                                "EvoSuite cancelled: completed suites are kept; "
                                        + comparisons.size()
                                        + " files are available to compare.",
                                comparisons);
                } catch (Exception ex) {
                    history.finish(runId, "Failed", mask(String.valueOf(ex.getMessage())));
                    console.println("EvoSuite failed: " + mask(String.valueOf(ex.getMessage())));
                    showNotification(
                            project,
                            NotificationType.ERROR,
                            "EvoSuite failed. Completed results are kept; see the EvoSuite"
                                    + " console.",
                            comparisons);
                } finally {
                    if (history.active(runId))
                        history.finish(
                                runId,
                                "No eligible targets",
                                "See console for skipped selections or configuration errors");
                    runIndicators.remove(runId);
                    cancelledRuns.remove(runId);
                    if (acquired) slot.release();
                    running.computeIfPresent(
                            project,
                            (ignored, indicators) -> {
                                indicators.remove(indicator);
                                return indicators.isEmpty() ? null : indicators;
                            });
                    ApplicationManager.getApplication()
                            .invokeLater(
                                    () -> {
                                        if (project.isDisposed()) return;
                                        if (suites.size() <= 3)
                                            suites.forEach(
                                                    file ->
                                                            FileEditorManager.getInstance(project)
                                                                    .openFile(file, true));
                                        SuiteComparison.show(project, comparisons);
                                    });
                }
            }
        }.queue();
    }

    private void check(Project project, ProgressIndicator indicator) {
        if (disposed || project.isDisposed()) throw new ProcessCanceledException();
        indicator.checkCanceled();
    }

    private static String mask(String text) {
        return text.replaceAll("(-Dllm_api_key=).*", "$1****");
    }

    private static void showNotification(Project project, NotificationType type, String text) {
        showNotification(project, type, text, List.of());
    }

    private static void showNotification(
            Project project,
            NotificationType type,
            String text,
            List<SuiteComparison.Pair> comparisons) {
        if (project.isDisposed()) return;
        var notification =
                NotificationGroupManager.getInstance()
                        .getNotificationGroup("EvoSuite")
                        .createNotification(text, type);
        if (!comparisons.isEmpty()) {
            var review = List.copyOf(comparisons);
            notification.addAction(
                    NotificationAction.createSimple(
                            "Review comparisons", () -> SuiteComparison.show(project, review)));
        }
        notification.addAction(
                NotificationAction.createSimple(
                        "Show console", () -> EvoSuiteConsole.getInstance(project).show()));
        notification.addAction(
                NotificationAction.createSimple(
                        "EvoSuite settings",
                        () -> {
                            if (EvoSuiteProjectSettings.getInstance(project)
                                    .getState()
                                    .useProjectSettings)
                                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                                        .showSettingsDialog(
                                                project,
                                                org.evosuite.intellij.settings
                                                        .EvoSuiteProjectConfigurable.class);
                            else
                                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                                        .showSettingsDialog(
                                                project,
                                                org.evosuite.intellij.settings
                                                        .EvoSuiteApplicationConfigurable.class);
                        }));
        notification.notify(project);
    }

    private Map<Module, List<GenerationTarget>> extensions(
            Project project, List<TargetCollector.TestClass> tests) {
        Map<Module, Map<String, List<GenerationTarget.Seed>>> grouped = new LinkedHashMap<>();
        for (var test : tests) {
            GenerationOptions options = SettingsResolver.resolve(test.module());
            var inference =
                    IdeThread.smartRead(
                            project,
                            () -> TestClassInference.infer(project, test, options.testSuffix));
            List<String> candidates = inference.candidates();
            String cut =
                    candidates.size() == 1
                            ? candidates.getFirst()
                            : ExtensionTargetDialog.choose(project, test, inference);
            grouped.computeIfAbsent(test.module(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(cut.trim(), ignored -> new ArrayList<>())
                    .add(new GenerationTarget.Seed(test.name(), test.root().toNioPath().toFile()));
        }
        Map<Module, List<GenerationTarget>> result = new LinkedHashMap<>();
        grouped.forEach(
                (module, classes) ->
                        result.put(
                                module,
                                classes.entrySet().stream()
                                        .map(
                                                e ->
                                                        GenerationTarget.extending(
                                                                e.getKey(), e.getValue()))
                                        .toList()));
        return result;
    }
}
