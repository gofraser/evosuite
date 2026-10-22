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
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
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

    public static GenerationService getInstance() {
        return ApplicationManager.getApplication().getService(GenerationService.class);
    }

    public boolean isRunning(Project project) {
        return !running.getOrDefault(project, Set.of()).isEmpty();
    }

    public void cancel(Project project) {
        running.getOrDefault(project, Set.of()).forEach(ProgressIndicator::cancel);
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
            Map<String, SuitePlacement.Snapshot> consent) {}

    public void submit(
            Project project, TargetCollector.Selection selection, TargetCollector.Mode mode) {
        if (FileDocumentManager.getInstance().getUnsavedDocuments().length > 0) {
            if (Messages.showYesNoDialog(
                            project,
                            "Save modified documents before building and generating tests? EvoSuite"
                                    + " reads saved sources and compiled classes.",
                            "EvoSuite",
                            Messages.getQuestionIcon())
                    != Messages.YES) return;
            FileDocumentManager.getInstance().saveAllDocuments();
        }
        EvoSuiteConsole console = EvoSuiteConsole.getInstance(project);
        console.show();
        console.println("EvoSuite: collecting Java targets and checking generation settings…");
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
                boolean acquired = false;
                List<VirtualFile> suites = new ArrayList<>();
                List<SuiteComparison.Pair> comparisons = new ArrayList<>();
                int[] counts = {0, 0, 0}; // written, warnings/errors, delegated targets
                try {
                    indicator.setIndeterminate(true);
                    indicator.setText("Collecting Java targets");
                    var collected =
                            IdeThread.smartRead(
                                    project,
                                    () -> TargetCollector.collect(project, selection, mode));
                    Map<Module, List<GenerationTarget>> targets =
                            mode == TargetCollector.Mode.EXTEND
                                    ? extensions(project, collected.tests())
                                    : collected.targets();
                    List<Request> requests = new ArrayList<>();
                    List<String> withSuites = new ArrayList<>();
                    int total = 0;
                    for (var entry : targets.entrySet()) {
                        indicator.checkCanceled();
                        Module module = entry.getKey();
                        try {
                            GenerationOptions options = SettingsResolver.resolve(module);
                            Path base =
                                    ReadAction.compute(() -> ProjectSetupService.baseDir(module));
                            Path folder =
                                    ReadAction.compute(
                                            () -> ProjectSetupService.testFolder(module, options));
                            List<GenerationTarget> safe = new ArrayList<>();
                            Map<String, SuitePlacement.Snapshot> consent = new HashMap<>();
                            for (GenerationTarget target : entry.getValue()) {
                                Path suite =
                                        EvoSuiteCommand.suiteFile(
                                                        folder.toFile(),
                                                        target.className(),
                                                        options.testSuffix)
                                                .toPath();
                                SuitePlacement.Snapshot existing =
                                        IdeThread.edt(() -> SuitePlacement.snapshot(suite));
                                if (existing != null
                                        && !SuitePlacement.generated(
                                                existing.source(),
                                                suite.getFileName()
                                                        .toString()
                                                        .replaceFirst("\\.java$", ""))) {
                                    console.println(
                                            EvoSuiteCommand.notAnEvoSuiteSuite(
                                                    target.className(), suite.toFile()));
                                    continue;
                                }
                                safe.add(target);
                                if (existing != null) {
                                    consent.put(target.className(), existing);
                                    withSuites.add(target.label());
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
                                                Map.copyOf(consent)));
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
                        console.println("No eligible Java targets selected. Choose a production Java class, package or module; use Extend Tests for existing test classes. Interfaces and generated suites are skipped.");
                        if (counts[1] > 0) showNotification(project, NotificationType.WARNING,
                                "EvoSuite could not prepare generation. See the console for configuration details.");
                        return;
                    }
                    SuiteAction action =
                            mode == TargetCollector.Mode.CLASSES
                                    ? SuiteAction.REPLACE
                                    : SuiteAction.ADD;
                    if (!withSuites.isEmpty()) {
                        var scope =
                                mode == TargetCollector.Mode.CLASSES
                                        ? ExistingSuitesQuestion.Scope.CLASSES
                                        : mode == TargetCollector.Mode.METHODS
                                                ? ExistingSuitesQuestion.Scope.METHODS
                                                : ExistingSuitesQuestion.Scope.EXTENSION;
                        var question = ExistingSuitesQuestion.of(withSuites, total, null, scope);
                        action =
                                IdeThread.edt(
                                        () ->
                                                ExistingSuitesQuestion.action(
                                                        Messages.showDialog(
                                                                project,
                                                                question.message,
                                                                question.title,
                                                                ExistingSuitesQuestion.BUTTONS
                                                                        .toArray(String[]::new),
                                                                question.defaultButton(),
                                                                Messages.getQuestionIcon())));
                        if (action == null) return;
                    }
                    indicator.setText("Waiting for other EvoSuite runs");
                    if (slot.availablePermits() == 0) console.println("Waiting for another EvoSuite run. Stop cancels this project's queued requests.");
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
                            var maven = MavenBridge.get(project);
                            Map<String, String> env =
                                    LlmEnvironment.resolve(
                                            options,
                                            LlmApiKeyStore::get,
                                            () ->
                                                    maven == null
                                                            ? ""
                                                            : maven.serverPassword(
                                                                    options.llmApiKeyServerId,
                                                                    console::println),
                                            System.getenv(),
                                            console::println);
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
                                    && (options.llmSwitchedOff()
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
                                        "Using the bundled engine to enforce Search only or protect"
                                                + " existing suites.");
                                delegation = false;
                            }
                            var java = delegation ? null :
                                    ReadAction.compute(
                                            () -> {
                                                try {
                                                    return ClasspathResolver.java(
                                                            request.module(), options);
                                                } catch (java.io.IOException ex) {
                                                    throw new IllegalArgumentException(
                                                            ex.getMessage(), ex);
                                                }
                                            });
                            ProjectSetupService.prepare(request.module(), options, indicator, console::println);
                            check(project, indicator);
                            // Only the initial save question authorizes saving editor changes.
                            IdeThread.flushPendingVfsWrites();
                            if (delegation) {
                                console.println(
                                        "Delegating whole-class generation to Maven. Maven controls"
                                                + " scheduling/export and uses pom settings; IDE"
                                                + " Add/Compare is unavailable in this mode.");
                                if (!maven.delegate(
                                        request.module(),
                                        request.targets(),
                                        options,
                                        env,
                                        indicator)) counts[1]++;
                                else counts[2] += request.targets().size();
                                done += request.targets().size();
                                continue;
                            }
                            indicator.setText("Building " + request.module().getName());
                            console.println("Building " + request.module().getName() + " before generation…");
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
                                continue;
                            }
                            IdeThread.flushPendingVfsWrites();
                            Path engine = EngineJars.engine();
                            indicator.setIndeterminate(false);
                            for (GenerationTarget target : request.targets()) {
                                check(project, indicator);
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
                                console.println(
                                        "Reports and LLM traces: " + staging.resolve("reports"));
                                String key =
                                        env.getOrDefault(
                                                EvoSuiteCommand.API_KEY_VARIABLE,
                                                System.getenv(EvoSuiteCommand.API_KEY_VARIABLE));
                                boolean keepSources = false;
                                try {
                                    GenerationRun.Result result =
                                            GenerationRun.generate(
                                                    target,
                                                    options,
                                                    java,
                                                    engine,
                                                    cp,
                                                    request.base(),
                                                    staging,
                                                    new ProgressParser.Listener() {
                                                        public void line(String line) {
                                                            console.println(
                                                                    key == null || key.isBlank()
                                                                            ? mask(line)
                                                                            : mask(line)
                                                                                    .replace(
                                                                                            key,
                                                                                            "****"));
                                                        }

                                                        public void progress(
                                                                int search, int coverage) {
                                                            indicator.setFraction(
                                                                    (index
                                                                                    + Math.min(
                                                                                                    100,
                                                                                                    search)
                                                                                            / 100.0)
                                                                            / all);
                                                            indicator.setText2(
                                                                    target.label()
                                                                            + ": search "
                                                                            + search
                                                                            + "%, coverage "
                                                                            + coverage
                                                                            + "%");
                                                        }
                                                    },
                                                    env,
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
                                                    options,
                                                    destination,
                                                    result,
                                                    action,
                                                    request.consent().get(target.className()),
                                                    console::println);
                                    if (placed.written()) counts[0]++;
                                    if (placed.suite() != null && options.openGeneratedTests)
                                        suites.add(placed.suite());
                                    comparisons.addAll(placed.comparisons());
                                } catch (ProcessCanceledException | InterruptedException ex) {
                                    throw ex;
                                } catch (Exception ex) {
                                    counts[1]++;
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
                                    // A failed placement must leave recoverable generated sources.
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
                            console.println(
                                    "Failed module "
                                            + request.module().getName()
                                            + ": "
                                            + mask(String.valueOf(ex.getMessage()))
                                            + ". Continuing with other modules.");
                        }
                    }
                    IdeThread.flushPendingVfsWrites();
                    console.println(
                            "EvoSuite finished: "
                                    + counts[0]
                                    + " suites written/updated, "
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
                                    + (counts[2] == 0
                                            ? ""
                                            : ", " + counts[2] + " Maven targets processed")
                                    + (counts[1] == 0
                                            ? "."
                                            : "; see the EvoSuite console for warnings."));
                } catch (ProcessCanceledException ex) {
                    console.println("EvoSuite cancelled. Completed suites are kept.");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    console.println("EvoSuite cancelled.");
                } catch (Exception ex) {
                    console.println("EvoSuite failed: " + mask(String.valueOf(ex.getMessage())));
                    showNotification(
                            project,
                            NotificationType.ERROR,
                            "EvoSuite failed. See the EvoSuite console.");
                } finally {
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
                                        SuiteComparison.show(project, comparisons);
                                        if (suites.size() <= 3)
                                            suites.forEach(
                                                    file ->
                                                            FileEditorManager.getInstance(project)
                                                                    .openFile(file, true));
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
        if (project.isDisposed()) return;
        var notification =
                NotificationGroupManager.getInstance()
                        .getNotificationGroup("EvoSuite")
                        .createNotification(text, type);
        notification.addAction(
                NotificationAction.createSimple(
                        "Show console", () -> EvoSuiteConsole.getInstance(project).show()));
        notification.addAction(NotificationAction.createSimple("EvoSuite settings", () -> {
            if (EvoSuiteProjectSettings.getInstance(project).getState().useProjectSettings)
                com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, org.evosuite.intellij.settings.EvoSuiteProjectConfigurable.class);
            else
                com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, org.evosuite.intellij.settings.EvoSuiteApplicationConfigurable.class);
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
                            : IdeThread.edt(
                                    () ->
                                            Messages.showInputDialog(
                                                    project,
                                                    "Class under test for "
                                                            + test.name()
                                                            + " (fully qualified name):",
                                                    "Extend Tests with EvoSuite",
                                                    Messages.getQuestionIcon(),
                                                    inference.guess(),
                                                    null));
            if (cut == null || cut.isBlank()) continue;
            boolean valid =
                    IdeThread.smartRead(
                            project,
                            () -> {
                                PsiClass type =
                                        JavaPsiFacade.getInstance(project)
                                                .findClass(
                                                        cut.trim(),
                                                        GlobalSearchScope
                                                                .moduleWithDependenciesAndLibrariesScope(
                                                                        test.module(), false));
                                return TestClassInference.isCut(project, type);
                            });
            if (!valid) throw new IllegalArgumentException("No top-level production class " + cut);
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
