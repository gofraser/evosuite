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

import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.task.ProjectTaskManager;

import org.evosuite.ide.EvoSuiteCommand;
import org.evosuite.ide.ExistingSuitesQuestion;
import org.evosuite.ide.GenerationOptions;
import org.evosuite.ide.GenerationRun;
import org.evosuite.ide.GenerationTarget;
import org.evosuite.ide.JavaInstallation;
import org.evosuite.ide.LlmEnvironment;
import org.evosuite.ide.ProgressParser;
import org.evosuite.ide.SettingKeys;
import org.evosuite.ide.SuiteAction;
import org.evosuite.intellij.actions.TargetCollector;
import org.evosuite.intellij.actions.TargetCollector.Mode;
import org.evosuite.intellij.actions.TestClassInference;
import org.evosuite.intellij.maven.MavenBridge;
import org.evosuite.intellij.maven.MavenIntegration;
import org.evosuite.intellij.settings.EvoSuiteApplicationConfigurable;
import org.evosuite.intellij.settings.EvoSuiteProjectConfigurable;
import org.evosuite.intellij.settings.EvoSuiteProjectSettings;
import org.evosuite.intellij.settings.LlmApiKeyStore;
import org.evosuite.intellij.settings.SettingsResolver;
import org.evosuite.intellij.setup.ProjectSetupService;
import org.evosuite.intellij.ui.EvoSuiteConsole;
import org.evosuite.intellij.ui.EvoSuiteToolWindowFactory;
import org.evosuite.intellij.ui.SuiteComparison;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service(Service.Level.APP)
public final class GenerationService implements Disposable {
    private static final Pattern API_KEY = Pattern.compile(
            "(-Dllm_api_key=)(?:\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\S+)");
    private final Semaphore slot = new Semaphore(1, true);
    private final Map<String, GenerationJob> jobs = new ConcurrentHashMap<>();
    private volatile boolean disposed;

    public static GenerationService getInstance() {
        return ApplicationManager.getApplication().getService(GenerationService.class);
    }

    public boolean isRunning(Project project) {
        return jobs.values().stream().anyMatch(job -> job.project == project);
    }

    public void cancel(Project project) {
        jobs.values().stream().filter(job -> job.project == project).forEach(GenerationJob::cancel);
    }

    public void cancel(Project project, String id) {
        GenerationJob job = jobs.get(id);
        if (job != null && job.project == project) job.cancel();
    }

    @Override
    public void dispose() {
        disposed = true;
        jobs.values().forEach(GenerationJob::cancel);
    }

    public void replay(Project project, RunHistory.Run run, RunHistory.Target target) {
        if (!target.canReplay()) {
            EvoSuiteConsole console = EvoSuiteConsole.getInstance(project);
            console.println("Repeat this request through the Maven runner.");
            console.show();
            return;
        }
        submit(project, null, Mode.valueOf(run.mode), target);
    }

    public void submit(Project project, TargetCollector.Selection selection, Mode mode) {
        submit(project, selection, mode, null);
    }

    private void submit(Project project, TargetCollector.Selection selection, Mode mode,
                        RunHistory.Target replay) {
        if (disposed || project.isDisposed()) return;
        RunHistory history = RunHistory.getInstance(project);
        EvoSuiteConsole console = EvoSuiteConsole.getInstance(project);
        String identity = mode + ":" + (replay != null
                ? List.of(replay.module, replay.className, replay.methods, replay.seed, replay.settings.hashCode())
                : ReadAction.compute(() -> selectionIdentity(selection)));
        if (history.hasActiveSelection(identity)) {
            console.println("This selection is already queued or running. Inspect or cancel it in Runs.");
            console.show();
            return;
        }
        if (!GenerationDocuments.saveBeforeGeneration(project)) return;

        console.show();
        String id = history.start(mode.name(), identity);
        history.cleanup(false);
        var job = new GenerationJob(project, mode, selection, replay, history, console, id);
        jobs.put(id, job);
        try {
            job.queue();
        } catch (RuntimeException ex) {
            jobs.remove(id);
            history.finish(id, "Could not start", mask(String.valueOf(ex.getMessage())));
            throw ex;
        }
    }

    private static List<List<String>> selectionIdentity(TargetCollector.Selection selection) {
        return List.of(selection.elements().stream().map(GenerationService::elementIdentity)
                        .distinct().sorted().toList(),
                selection.files().stream().map(VirtualFile::getPath).distinct().sorted().toList(),
                selection.modules().stream().map(Module::getName).distinct().sorted().toList());
    }

    private static String elementIdentity(PsiElement element) {
        var file = element.getContainingFile();
        VirtualFile source = file == null ? null : file.getVirtualFile();
        return source == null ? element.toString()
                : source.getPath() + ":" + element.getClass().getSimpleName() + ":" + element.getTextRange();
    }

    private record PlannedTarget(GenerationTarget target, Path destination,
                                 SuitePlacement.Snapshot consent, int historyIndex) {}

    private record ModulePlan(Module module, GenerationOptions options, Path base, Path testDir,
                              List<PlannedTarget> targets) {
        List<GenerationTarget> generationTargets() {
            return targets.stream().map(PlannedTarget::target).toList();
        }
    }

    private final class GenerationJob extends Task.Backgroundable {
        private final Project project;
        private final Mode mode;
        private final TargetCollector.Selection selection;
        private final RunHistory.Target replay;
        private final RunHistory history;
        private final EvoSuiteConsole console;
        private final String id;
        private final Set<VirtualFile> suites = new LinkedHashSet<>();
        private final List<SuiteComparison.Pair> comparisons = new ArrayList<>();
        private volatile ProgressIndicator indicator;
        private volatile boolean cancelled;
        private SuiteAction suiteAction = SuiteAction.ADD;
        private int total, done, written, unchanged, delegated, warnings, measured;
        private String coverageSummary;

        GenerationJob(Project project, Mode mode, TargetCollector.Selection selection,
                      RunHistory.Target replay, RunHistory history, EvoSuiteConsole console, String id) {
            super(project, mode == Mode.COVERAGE ? "EvoSuite: measure coverage" : "EvoSuite: generate tests",
                    true);
            this.project = project;
            this.mode = mode;
            this.selection = selection;
            this.replay = replay;
            this.history = history;
            this.console = console;
            this.id = id;
        }

        void cancel() {
            cancelled = true;
            ProgressIndicator current = indicator;
            if (current != null) current.cancel();
        }

        private boolean cancelled() {
            return cancelled || disposed || project.isDisposed() || indicator.isCanceled();
        }

        private void checkCancelled() {
            if (cancelled()) throw new ProcessCanceledException();
        }

        @Override
        public void run(@NotNull ProgressIndicator progress) {
            indicator = progress;
            if (cancelled) progress.cancel();
            boolean acquired = false;
            try {
                checkCancelled();
                progress.setIndeterminate(true);
                progress.setText("Collecting Java targets");
                console.println("Collecting Java targets and checking EvoSuite settings…");
                List<ModulePlan> plans = planTargets();
                if (plans.isEmpty()) {
                    String status = warnings == 0 ? "No eligible targets" : "Could not prepare";
                    history.finish(id, status, "See the EvoSuite console for details");
                    console.println(warnings == 0 ? "No eligible Java targets selected."
                            : "No targets could be prepared. See the preceding messages.");
                    if (warnings != 0) notifyResult(NotificationType.WARNING, "EvoSuite could not prepare generation.");
                    return;
                }
                total = plans.stream().mapToInt(plan -> plan.targets().size()).sum();
                confirm(plans);
                progress.setText("Waiting for other EvoSuite runs");
                history.phase(id, "Queued: waiting for engine");
                while (!slot.tryAcquire(200, TimeUnit.MILLISECONDS)) checkCancelled();
                acquired = true;
                for (ModulePlan plan : plans) {
                    checkCancelled();
                    runModule(plan);
                }
                checkCancelled();
                IdeThread.flushPendingVfsWrites();
                finish();
            } catch (ProcessCanceledException ex) {
                finishCancelled();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                finishCancelled();
            } catch (Exception ex) {
                String detail = mask(String.valueOf(ex.getMessage()));
                history.finish(id, "Failed", detail);
                console.println("EvoSuite failed: " + detail);
                notifyResult(NotificationType.ERROR, "EvoSuite failed. See the console for details.");
            } finally {
                if (acquired) slot.release();
                jobs.remove(id);
                if (!project.isDisposed()) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (project.isDisposed()) return;
                        if (suites.size() <= 3) {
                            var editors = FileEditorManager.getInstance(project);
                            suites.stream().filter(VirtualFile::isValid).forEach(file -> editors.openFile(file, true));
                        }
                        SuiteComparison.show(project, comparisons);
                    });
                }
            }
        }

        @Override
        public void onFinished() {
            // IntelliJ can cancel a task before it enters run().
            jobs.remove(id);
            if (history.active(id)) finishCancelled();
        }

        private List<ModulePlan> planTargets() {
            List<ModulePlan> plans = new ArrayList<>();
            for (var entry : collectTargets().entrySet()) {
                checkCancelled();
                Module module = entry.getKey();
                List<Integer> indices = new ArrayList<>();
                try {
                    GenerationOptions options = replay == null
                            ? SettingsResolver.resolve(module) : SettingKeys.resolve(replay.settings);
                    if (replay != null) {
                        options.randomSeed = replay.seed;
                        options.llmApiKeyServerId = replay.keyServerId;
                    }
                    boolean coverage = mode == Mode.COVERAGE;
                    if (coverage) options.llmApproach = GenerationOptions.APPROACH_SEARCH_ONLY;
                    Path base = ProjectSetupService.baseDir(module);
                    Path folder = coverage ? null : ProjectSetupService.testFolder(module, options);
                    List<PlannedTarget> targets = new ArrayList<>();
                    for (var target : entry.getValue()) {
                        checkCancelled();
                        Path output = coverage ? target.seeds().getFirst().sourceFile().toPath()
                                : EvoSuiteCommand.suiteFile(folder.toFile(), target.className(), options.testSuffix)
                                        .toPath();
                        int index = history.target(id, module.getName(), target, options, output);
                        indices.add(index);
                        SuitePlacement.Snapshot consent = coverage ? null
                                : IdeThread.edt(() -> SuitePlacement.snapshot(output));
                        if (consent != null && !SuitePlacement.generated(project, output,
                                consent.source(), SuitePlacement.suiteName(output))) {
                            console.println(EvoSuiteCommand.notAnEvoSuiteSuite(target.className(), output.toFile()));
                            history.outcome(id, index, "Skipped", "Destination is not an EvoSuite suite", -1);
                            warnings++;
                            continue;
                        }
                        targets.add(new PlannedTarget(target, output, consent, index));
                        if (!coverage) console.println("Planned output: " + output);
                    }
                    if (!targets.isEmpty()) plans.add(new ModulePlan(module, options, base, folder, targets));
                } catch (ProcessCanceledException ex) {
                    throw ex;
                } catch (Exception ex) {
                    warnings++;
                    String detail = mask(String.valueOf(ex.getMessage()));
                    history.failUnfinished(id, indices, detail);
                    console.println("Cannot prepare " + module.getName() + ": " + detail);
                }
            }
            return plans;
        }

        private Map<Module, List<GenerationTarget>> collectTargets() {
            if (replay != null) {
                Module module = ReadAction.compute(() -> ModuleManager.getInstance(project)
                        .findModuleByName(replay.module));
                if (module == null) throw new IllegalArgumentException("Module no longer exists: " + replay.module);
                var seeds = replay.seeds.stream().map(seed -> seed.source.isBlank()
                        ? new GenerationTarget.Seed(seed.className, new File(seed.root))
                        : new GenerationTarget.Seed(seed.className, new File(seed.root), new File(seed.source)))
                        .toList();
                return Map.of(module, List.of(new GenerationTarget(replay.className, replay.methods, seeds)));
            }
            var collected = IdeThread.smartRead(project, () -> TargetCollector.collect(project, selection, mode));
            return mode == Mode.EXTEND || mode == Mode.COVERAGE
                    ? extensions(project, collected.tests()) : collected.targets();
        }

        private void confirm(List<ModulePlan> plans) {
            if (total > 5 && replay == null) {
                String summary = plans.stream().map(plan -> {
                    String label = plan.module().getName() + ": " + plan.targets().size() + " targets";
                    if (mode == Mode.COVERAGE) return label;
                    return label + ", " + plan.options().effectiveSearchBudget()
                            + "s search each, LLM calls/class: " + plan.options().effectiveLlmMaxCalls()
                            + " (0 = unlimited)";
                }).collect(Collectors.joining("\n"));
                String operation = mode == Mode.COVERAGE ? "Measure coverage" : "Generate tests";
                int answer = question(operation + " for " + total + " targets?\n\n" + summary,
                        "EvoSuite: Review Bulk Request", new String[] {"Start", "Cancel"}, 0);
                if (answer != 0) throw new ProcessCanceledException();
            }
            List<String> existing = plans.stream().flatMap(plan -> plan.targets().stream()
                    .filter(target -> target.consent() != null)
                    .map(target -> target.target().className() + " (" + plan.module().getName() + ")"))
                    .toList();
            if (existing.isEmpty()) return;
            var scope = switch (mode) {
                case CLASSES -> ExistingSuitesQuestion.Scope.CLASSES;
                case METHODS -> ExistingSuitesQuestion.Scope.METHODS;
                default -> ExistingSuitesQuestion.Scope.EXTENSION;
            };
            var folders = plans.stream().map(ModulePlan::testDir).distinct().toList();
            var question = ExistingSuitesQuestion.of(existing, total,
                    folders.size() == 1 ? folders.getFirst().toString() : null, scope);
            String runtimeFree = plans.stream().anyMatch(plan -> plan.options().effectiveNoRuntimeDependency())
                    ? "\n\nReplace can also remove obsolete scaffolding. Add opens Compare if the runtime setups differ."
                    : "";
            suiteAction = ExistingSuitesQuestion.action(question(question.message + runtimeFree
                            + "\n\nIn Compare, copy changes into Existing to keep them. Cancel stops this request before setup.",
                    question.title, ExistingSuitesQuestion.BUTTONS.toArray(String[]::new), question.defaultButton()));
            if (suiteAction == null) throw new ProcessCanceledException();
        }

        private int question(String message, String title, String[] buttons, int defaultButton) {
            return IdeThread.edt(() -> {
                checkCancelled();
                return Messages.showDialog(project, GenerationDialogs.message(message), title, buttons,
                        defaultButton, Messages.getQuestionIcon());
            });
        }

        private void runModule(ModulePlan plan) throws InterruptedException {
            Module module = plan.module();
            GenerationOptions options = plan.options();
            try {
                if (module.isDisposed()) throw new IllegalStateException("Module was removed");
                indicator.setIndeterminate(true);
                indicator.setText("Preparing " + module.getName());
                history.phase(id, "Preparing " + module.getName());
                console.println("=== Module " + module.getName());
                options.fromPom.forEach(line -> console.println("From pom: " + mask(line)));
                for (String property : EvoSuiteCommand.overriddenUserProperties(options))
                    console.println("Ignoring additional " + property + "; EvoSuite controls output locations.");

                MavenIntegration maven = MavenBridge.get(project);
                Map<String, String> environment = environment(plan, maven);
                boolean delegation = canDelegate(plan, maven);
                JavaInstallation java = delegation ? null : ClasspathResolver.java(module, options);
                if (mode != Mode.COVERAGE)
                    ProjectSetupService.prepare(module, options, indicator, console::println);
                checkCancelled();
                IdeThread.flushPendingVfsWrites();
                if (delegation) {
                    delegate(plan, maven, environment);
                    return;
                }
                indicator.setText("Building " + module.getName());
                history.phase(id, "Building " + module.getName());
                var modules = IdeThread.smartRead(project, () -> buildModules(module, plan.generationTargets()));
                var build = IdeThread.await(IdeThread.edt(() -> {
                    checkCancelled();
                    return ProjectTaskManager.getInstance(project).build(modules);
                }), indicator);
                if (build.isAborted() || build.hasErrors()) {
                    warnings += plan.targets().size();
                    console.println("Build failed or was cancelled for " + module.getName() + ".");
                    plan.targets().forEach(target ->
                            outcome(target, "Build failed", "Fix compilation errors before generation", -1));
                    return;
                }
                IdeThread.flushPendingVfsWrites();
                Path engine = EngineJars.engine();
                for (PlannedTarget target : plan.targets()) {
                    checkCancelled();
                    runTarget(plan, target, java, engine, environment);
                }
            } catch (ProcessCanceledException | InterruptedException ex) {
                throw ex;
            } catch (Exception ex) {
                warnings++;
                String detail = mask(String.valueOf(ex.getMessage()));
                history.failUnfinished(id, plan.targets().stream().map(PlannedTarget::historyIndex).toList(), detail);
                console.println("Failed module " + module.getName() + ": " + detail);
            }
        }

        private Map<String, String> environment(ModulePlan plan, MavenIntegration maven) {
            GenerationOptions options = plan.options();
            Map<String, String> env = LlmEnvironment.resolve(options,
                    () -> LlmApiKeyStore.get(options.effectiveLlmProvider()),
                    () -> maven == null ? "" : maven.serverPassword(options.llmApiKeyServerId, console::println),
                    System.getenv(), console::println);
            String provider = options.effectiveLlmProvider();
            if (provider.equals("NONE") || provider.equals("OLLAMA")
                    || !env.getOrDefault(EvoSuiteCommand.API_KEY_VARIABLE,
                            System.getenv().getOrDefault(EvoSuiteCommand.API_KEY_VARIABLE, "")).isBlank()) return env;

            int choice = question("No API key is available for " + provider + ". Configure a key or run search only.",
                    "EvoSuite: Missing API Key", new String[] {"Configure Key…", "Run Search Only", "Cancel"}, 0);
            if (choice == 0) {
                IdeThread.edt(() -> {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project,
                            EvoSuiteApplicationConfigurable.class, c -> c.selectProviderCredentials(provider));
                    return null;
                });
            }
            if (choice != 1) throw new ProcessCanceledException();
            options.llmApproach = GenerationOptions.APPROACH_SEARCH_ONLY;
            plan.targets().forEach(target -> history.settings(id, target.historyIndex(), options));
            return Map.of(EvoSuiteCommand.API_KEY_VARIABLE, "");
        }

        private boolean canDelegate(ModulePlan plan, MavenIntegration maven) throws IOException {
            if (mode != Mode.CLASSES || maven == null || maven.directory(plan.module()) == null
                    || !EvoSuiteProjectSettings.getInstance(project).getState().delegateToMaven) return false;
            GenerationOptions options = plan.options();
            if (replay != null || !options.usePomSettings || !options.effectiveRandomSeed().isBlank()
                    || !options.llmContextMode.equals("SIGNATURE_ONLY") || options.llmMaxTokens != 32768
                    || options.llmTimeoutSeconds != 60 || options.llmRetryMaxAttempts != 2
                    || !options.llmTemperature.equals("0.7") || options.effectiveNoRuntimeDependency()
                    || options.llmSwitchedOff() || plan.targets().stream().anyMatch(t -> t.consent() != null)
                    || !maven.canDelegate(plan.module(), options)) {
                console.println("Using the bundled engine to preserve run settings or protect existing suites.");
                return false;
            }
            return true;
        }

        private void delegate(ModulePlan plan, MavenIntegration maven, Map<String, String> environment)
                throws IOException {
            console.println("Running whole-class generation through Maven with its POM settings.");
            Path staging = newStaging();
            Path export = staging.resolve("export");
            try {
                history.phase(id, "Running Maven");
                for (PlannedTarget target : plan.targets()) {
                    history.delegated(id, target.historyIndex(), staging);
                }
                boolean ok = maven.delegate(plan.module(), plan.generationTargets(), export, environment, indicator);
                checkCancelled();
                done += plan.targets().size();
                if (!ok) {
                    warnings++;
                    plan.targets().forEach(target -> outcome(target, "Maven failed", "See Maven output", -1));
                    return;
                }
                for (PlannedTarget target : plan.targets()) {
                    GenerationRun.Result result = GenerationRun.output(0, export,
                            target.target(), plan.options().testSuffix, null);
                    if (!result.produced()) {
                        warnings++;
                        outcome(target, "No tests exported", "See Maven output", -1);
                        continue;
                    }
                    try {
                        place(plan, target, plan.options(), result, staging, null);
                        delegated++;
                    } catch (ProcessCanceledException ex) {
                        throw ex;
                    } catch (Exception ex) {
                        warnings++;
                        String detail = mask(String.valueOf(ex.getMessage()));
                        console.println("Failed for " + target.target().label() + ": " + detail);
                        outcome(target, "Failed", detail, -1);
                    }
                }
            } finally {
                GenerationRun.deleteTree(export);
            }
        }

        private void runTarget(ModulePlan plan, PlannedTarget planned, JavaInstallation java,
                               Path engine, Map<String, String> environment) throws InterruptedException {
            int position = done++;
            GenerationTarget target = planned.target();
            Path staging = null;
            boolean keepSources = false;
            String key = environment.getOrDefault(EvoSuiteCommand.API_KEY_VARIABLE,
                    System.getenv(EvoSuiteCommand.API_KEY_VARIABLE));
            try {
                var cp = ReadAction.compute(() -> ClasspathResolver.resolve(plan.module(), target));
                if (cp.isEmpty()) throw new IllegalArgumentException("No production classpath");
                indicator.setText((mode == Mode.COVERAGE ? "Measuring coverage for " : "Generating tests for ")
                        + target.label());
                staging = newStaging();
                history.beginTarget(id, planned.historyIndex(), staging);
                GenerationOptions options = plan.options().copy();
                if (mode != Mode.COVERAGE)
                    options.llmSourcePath = IdeThread.smartRead(project, () -> sourcePath(plan.module(), target));
                console.println("Reports and LLM traces: " + staging.resolve("reports"));
                ProgressParser.Listener listener = listener(planned, position, key);
                if (mode == Mode.COVERAGE) {
                    int exit = GenerationRun.measureCoverage(target, options, java, engine, cp, plan.base(),
                            staging, listener, environment, this::cancelled);
                    checkCancelled();
                    if (exit == 0) {
                        measured++;
                        int coverage = history.coverage(id, planned.historyIndex());
                        if (coverage >= 0) coverageSummary = coverage + "% coverage for " + target.className();
                        outcome(planned, "Coverage measured", "", -1);
                    } else {
                        warnings++;
                        outcome(planned, "Coverage failed", "Engine exit code " + exit + ". See the EvoSuite console.", -1);
                    }
                    return;
                }
                GenerationRun.Result result = GenerationRun.generate(target, options, java, engine, cp, plan.base(),
                        staging, listener, environment, this::cancelled);
                checkCancelled();
                if (result.seeding() != null) {
                    console.println(redact(result.seeding().summary(), key));
                    if (!result.seeding().seeded() || !result.seeding().ranExistingTests()) warnings++;
                }
                if (!result.produced()) {
                    warnings++;
                    String detail = "Engine exit code " + result.exitCode();
                    if (result.seeding() != null) detail += ". " + redact(result.seeding().summary(), key);
                    outcome(planned, "No tests generated", detail, 0);
                    console.println("No tests generated for " + target.label() + " (" + detail + ").");
                    return;
                }
                if (result.exitCode() != 0) {
                    warnings++;
                    console.println("EvoSuite wrote tests but exited with code " + result.exitCode()
                            + "; the suite may be incomplete.");
                }
                keepSources = place(plan, planned, options, result, staging, key);
            } catch (ProcessCanceledException | InterruptedException ex) {
                throw ex;
            } catch (Exception ex) {
                warnings++;
                String detail = redact(String.valueOf(ex.getMessage()), key);
                outcome(planned, "Failed", detail, -1);
                console.println("Failed for " + target.label() + ": " + detail);
                keepSources = true;
            } finally {
                if (staging != null) {
                    if (keepSources && Files.isDirectory(staging.resolve("tests")))
                        console.println("Generated sources retained at " + staging.resolve("tests"));
                    try {
                        if (!keepSources) GenerationRun.deleteTree(staging.resolve("tests"));
                        GenerationRun.deleteTree(staging.resolve("seeds"));
                    } catch (IOException ex) {
                        console.println("Could not clean staging folder " + staging);
                    }
                }
            }
        }

        private boolean place(ModulePlan plan, PlannedTarget target, GenerationOptions options,
                              GenerationRun.Result result, Path staging, String key) throws IOException {
            Path destination = target.destination();
            ProjectSetupService.verifyOutputPath(plan.base(), destination);
            ProjectSetupService.verifyOutputPath(plan.base(), SuitePlacement.scaffoldingPath(destination));
            var placed = SuitePlacement.place(project, target.target(), options, destination, result,
                    suiteAction, target.consent(), console::println);
            if (placed.skipped()) warnings++;
            else if (placed.written()) written++;
            else if (placed.comparisons().isEmpty()) unchanged++;
            if (placed.suite() != null && options.openGeneratedTests) suites.add(placed.suite());
            comparisons.addAll(placed.comparisons());
            history.comparisons(id, target.historyIndex(), placed.comparisons(), staging);
            String status;
            if (placed.skipped()) status = "Skipped";
            else if (!placed.comparisons().isEmpty()) status = "Pending comparison";
            else status = placed.written() ? "Written / updated" : "Unchanged";
            outcome(target, status, result.seeding() == null ? "" : redact(result.seeding().summary(), key),
                    placed.tests());
            return !placed.comparisons().isEmpty();
        }

        private ProgressParser.Listener listener(PlannedTarget planned, int position, String key) {
            int index = planned.historyIndex();
            String label = planned.target().label();
            return new ProgressParser.Listener() {
                @Override
                public void line(String line) {
                    String safe = redact(line, key);
                    String phase = history.line(id, index, safe);
                    if (phase != null) {
                        indicator.setIndeterminate(true);
                        indicator.setText(phase + " for " + label);
                    }
                    console.println(safe);
                }

                @Override
                public void progress(int search, int coverage) {
                    history.progress(id, index, search, coverage);
                    indicator.setIndeterminate(false);
                    indicator.setFraction((position + Math.min(100, search) / 100.0) / total);
                    indicator.setText2(label + ": search " + search + "%, coverage " + coverage + "%");
                }
            };
        }

        private void outcome(PlannedTarget target, String status, String detail, int tests) {
            history.outcome(id, target.historyIndex(), status, detail, tests);
        }

        private void finish() {
            history.finish(id, warnings == 0 ? "Completed" : "Completed with warnings",
                    comparisons.isEmpty() ? "Finished" : "Review pending comparisons in Runs");
            List<String> parts = new ArrayList<>();
            if (mode == Mode.COVERAGE) {
                parts.add(measured == 1 && coverageSummary != null
                        ? coverageSummary : measured + " targets measured");
            } else {
                parts.add(written + " suites written/updated");
                if (unchanged != 0) parts.add(unchanged + " unchanged");
                if (delegated != 0) parts.add(delegated + " generated through Maven");
                if (!comparisons.isEmpty()) parts.add(comparisons.size() + " comparisons");
            }
            if (warnings != 0) parts.add(warnings + " warnings/errors");
            String message = "EvoSuite finished: " + String.join(", ", parts) + ".";
            console.println(message);
            notifyResult(warnings == 0 ? NotificationType.INFORMATION : NotificationType.WARNING, message);
        }

        private void finishCancelled() {
            history.finish(id, "Cancelled", "Completed outputs and pending comparisons are kept");
            console.println("EvoSuite cancelled.");
            if (!comparisons.isEmpty())
                notifyResult(NotificationType.INFORMATION, "EvoSuite cancelled. Comparisons are available in Runs.");
        }

        private void notifyResult(NotificationType type, String message) {
            if (project.isDisposed()) return;
            var notification = NotificationGroupManager.getInstance().getNotificationGroup("EvoSuite")
                    .createNotification(message, type);
            if (!comparisons.isEmpty()) {
                var review = List.copyOf(comparisons);
                notification.addAction(NotificationAction.createSimple("Review comparisons",
                        () -> SuiteComparison.show(project, review)));
            }
            if (mode == Mode.COVERAGE)
                notification.addAction(NotificationAction.createSimple("View coverage",
                        () -> EvoSuiteToolWindowFactory.showRun(project, id)));
            else notification.addAction(NotificationAction.createSimple("Show console", console::show));
            notification.addAction(NotificationAction.createSimple("EvoSuite settings", () -> {
                if (EvoSuiteProjectSettings.getInstance(project).getState().useProjectSettings)
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, EvoSuiteProjectConfigurable.class);
                else ShowSettingsUtil.getInstance().showSettingsDialog(project, EvoSuiteApplicationConfigurable.class);
            }));
            notification.notify(project);
        }
    }

    public static String mask(String text) {
        return API_KEY.matcher(text).replaceAll("$1****");
    }

    private static String redact(String text, String key) {
        String masked = mask(text);
        return key == null || key.isBlank() ? masked : masked.replace(key, "****");
    }

    private static Path newStaging() throws IOException {
        Path state = RunHistory.stagingRoot();
        Files.createDirectories(state);
        return Files.createTempDirectory(state, "run-");
    }

    private static String sourcePath(Module module, GenerationTarget target) {
        var type = JavaPsiFacade.getInstance(module.getProject()).findClass(target.className(),
                GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false));
        var file = type == null ? null : type.getContainingFile();
        var source = file == null ? null : file.getVirtualFile();
        return source == null ? "" : source.getPath();
    }

    public static Module[] buildModules(Module production, List<GenerationTarget> targets) {
        return ReadAction.compute(() -> {
            Set<Module> modules = new LinkedHashSet<>();
            modules.add(production);
            modules.addAll(ClasspathResolver.seedModules(production, targets));
            return modules.toArray(Module[]::new);
        });
    }

    private static Map<Module, List<GenerationTarget>> extensions(Project project,
                                                               List<TargetCollector.TestClass> tests) {
        Map<Module, Map<String, List<GenerationTarget.Seed>>> grouped = new LinkedHashMap<>();
        for (var test : tests) {
            com.intellij.openapi.progress.ProgressManager.checkCanceled();
            Module testOwner = ProjectSetupService.productionModule(test.module());
            String suffix = SettingsResolver.resolve(testOwner).testSuffix;
            var inference = IdeThread.smartRead(project, () -> TestClassInference.infer(project, test, suffix));
            String cut = inference.candidates().size() == 1 ? inference.candidates().getFirst()
                    : ExtensionTargetDialog.choose(project, test, inference);
            Module owner = IdeThread.smartRead(project, () -> {
                var type = JavaPsiFacade.getInstance(project).findClass(cut,
                        GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(test.module(), false));
                return type == null ? null : ProjectFileIndex.getInstance(project)
                        .getModuleForFile(type.getContainingFile().getVirtualFile());
            });
            if (owner == null) throw new IllegalArgumentException("Production class is no longer available: " + cut);
            var seed = test.file() == null
                    ? new GenerationTarget.Seed(test.name(), test.root().toNioPath().toFile())
                    : new GenerationTarget.Seed(test.name(), test.root().toNioPath().toFile(),
                            test.file().toNioPath().toFile());
            grouped.computeIfAbsent(owner, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(cut, ignored -> new ArrayList<>()).add(seed);
        }
        Map<Module, List<GenerationTarget>> result = new LinkedHashMap<>();
        grouped.forEach((module, classes) -> result.put(module, classes.entrySet().stream()
                .map(entry -> GenerationTarget.extending(entry.getKey(), entry.getValue())).toList()));
        return result;
    }
}
