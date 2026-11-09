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
package org.evosuite.eclipse.launch;
import org.evosuite.ide.SuiteMerger;
import org.evosuite.ide.LlmEnvironment;
import org.evosuite.ide.SeedingReport;
import org.evosuite.ide.ProgressParser;
import org.evosuite.ide.EvoSuiteProcess;
import org.evosuite.ide.EvoSuiteCommand;
import org.evosuite.ide.SuiteAction;
import org.evosuite.ide.GenerationTarget;
import org.evosuite.ide.GenerationOptions;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IBuildConfiguration;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.evosuite.eclipse.Activator;
import org.evosuite.eclipse.EvoSuiteJars;
import org.evosuite.eclipse.preferences.EvoSuitePreferences;
import org.evosuite.eclipse.maven.MavenSupport;
import org.evosuite.eclipse.maven.MavenGeneration;
import org.evosuite.eclipse.maven.ServerPassword;
import org.evosuite.eclipse.preferences.LlmApiKey;
import org.evosuite.eclipse.ui.EvoSuiteConsole;
import org.evosuite.eclipse.ui.RunsView;
import org.evosuite.eclipse.ui.SuiteComparison;

/** Serializes EvoSuite processes and applies workspace scheduling rules only when writing suites. */
public class TestGenerationJob extends Job {

    public static final Object FAMILY = TestGenerationJob.class;

    private static final Semaphore ONE_RUN_AT_A_TIME = new Semaphore(1);

    /** Where the LLM API key comes from; tests replace it to stay clear of secure storage. */
    static Function<String, String> apiKeySource = LlmApiKey::get;
    /** Receives every line of EvoSuite's output as well; tests replace it to inspect the log. */
    static Consumer<String> outputListener = line -> {
    };

    /** Runs EvoSuite's command line; tests replace it to stand in for EvoSuite. */
    interface Runner {
        /** @return the exit code, or {@link EvoSuiteProcess#CANCELLED} */
        int run(List<String> commandLine, File workingDirectory, Map<String, String> environment,
                ProgressParser.Listener listener, BooleanSupplier cancelled) throws IOException, InterruptedException;
    }

    static Runner runner = EvoSuiteProcess::run;
    private static final int MAX_EDITORS = 3;

    private final IJavaProject project;
    private final List<GenerationTarget> targets;
    private final GenerationOptions options;
    private final SuiteAction action;
    private final boolean coverageOnly;
    private final boolean fixedSeed;
    private volatile RunHistory.Run run;
    private static final Pattern TOTAL_COVERAGE = Pattern.compile("Total coverage:\\s*([0-9]+(?:[.,][0-9]+)?)\\s*%");
    /** The classes whose suites existed when the job was created: those {@link #action} was chosen for. */
    private final Map<String, SuiteSnapshot> decided;

    private record SuiteSnapshot(long suite, long helper) {
        static SuiteSnapshot of(IFile suite, IFile helper) {
            return new SuiteSnapshot(suite.getModificationStamp(), helper.getModificationStamp());
        }
    }
    private final List<IFile> generated = new CopyOnWriteArrayList<>();
    private final List<SuiteComparison.Pair> comparisons = new CopyOnWriteArrayList<>();
    /** How each class's new suite was used, one line each, for the summary. */
    private final List<String> outcomes = new ArrayList<>();
    private int produced;
    /** What each run that extended existing tests produced, one line each, for the job's message. */
    private final List<String> extensions = new ArrayList<>();

    /** With the project's settings as they are now, not when the job eventually runs. */
    public TestGenerationJob(IJavaProject project, List<String> classes) {
        this(project, classes, EvoSuitePreferences.resolve(project.getProject(), null));
    }

    /**
     * @param options the settings the user confirmed: a run can wait behind another for a long
     *                time, and must not pick up a test folder or suffix changed meanwhile
     */
    public TestGenerationJob(IJavaProject project, List<String> classes, GenerationOptions options) {
        this(project, GenerationTarget.wholeClasses(classes), options, SuiteAction.REPLACE);
    }

    /**
     * @param action what to do with the suites that already exist for some of the targets
     */
    public TestGenerationJob(IJavaProject project, List<GenerationTarget> targets, GenerationOptions options,
                             SuiteAction action) {
        this(project, targets, options, action, false);
    }

    public TestGenerationJob(IJavaProject project, List<GenerationTarget> targets, GenerationOptions options,
                             SuiteAction action, boolean coverageOnly) {
        super((coverageOnly ? "Measuring coverage for " : "Generating tests for ") + (targets.size() == 1
                ? targets.get(0).label() : targets.size() + " classes in " + project.getElementName()));
        this.project = project;
        this.targets = List.copyOf(targets);
        this.options = options.copy();
        this.coverageOnly = coverageOnly;
        if (coverageOnly) this.options.llmApproach = GenerationOptions.APPROACH_SEARCH_ONLY;
        fixedSeed = !this.options.effectiveRandomSeed().isBlank();
        if (!fixedSeed) {
            this.options.randomSeed = Long.toString(ThreadLocalRandom.current().nextLong());
        }
        this.action = action;
        this.decided = coverageOnly ? Map.of() : existingSuites(project, this.targets, options);
        addJobChangeListener(new JobChangeAdapter() {
            @Override
            public void scheduled(IJobChangeEvent event) {
                history();
            }

            @Override
            public void done(IJobChangeEvent event) {
                recordResult(event.getResult());
            }
        });
        setUser(true);
        setPriority(LONG);
    }

    private static Map<String, SuiteSnapshot> existingSuites(IJavaProject project, List<GenerationTarget> targets,
                                                             GenerationOptions options) {
        Map<String, SuiteSnapshot> snapshots = new HashMap<>();
        try {
            IFolder folder = ProjectSetup.testFolder(project.getProject(), options);
            for (GenerationTarget target : targets) {
                IFile suite = SuiteOwnership.suite(folder, target.className(), options.testSuffix);
                if (suite.exists()) snapshots.put(target.className(), SuiteSnapshot.of(suite, scaffolding(suite)));
            }
        } catch (CoreException e) {
            // The job reports the problem when it runs.
        }
        return snapshots;
    }

    private static IFile scaffolding(IFile suite) {
        return suite.getParent().getFile(IPath.fromPortableString(
                suite.getName().replaceFirst("\\.java$", "_scaffolding.java")));
    }

    @Override
    public boolean belongsTo(Object family) {
        return family == FAMILY;
    }

    /**
     * The test suites written by this job, new ones and existing ones that it added tests to;
     * complete once the job has finished.
     */
    public List<IFile> getGeneratedSuites() {
        return List.copyOf(generated);
    }

    /** The existing suites the job opened a comparison for; complete once the job has finished. */
    public List<SuiteComparison.Pair> getComparisons() {
        return List.copyOf(comparisons);
    }

    /** How each class's new suite was used, as the console's summary lists it. */
    List<String> outcomes() {
        return List.copyOf(outcomes);
    }

    @Override
    protected IStatus run(IProgressMonitor monitor) {
        history().startedRunning();
        IStatus status = execute(monitor);
        recordResult(status);
        if (coverageOnly) RunsView.show(run.id());
        return status;
    }

    private synchronized RunHistory.Run history() {
        if (run == null) run = RunHistory.queue(this, project.getElementName(), targets, options, coverageOnly);
        return run;
    }

    private void recordResult(IStatus status) {
        RunHistory.State state = switch (status.getSeverity()) {
            case IStatus.OK -> RunHistory.State.COMPLETED;
            case IStatus.CANCEL -> RunHistory.State.CANCELLED;
            case IStatus.ERROR -> RunHistory.State.FAILED;
            default -> RunHistory.State.WARNINGS;
        };
        List<String> details = new ArrayList<>(outcomes);
        if (status.isMultiStatus()) {
            for (IStatus child : status.getChildren()) details.add(child.getMessage());
        } else if (!status.isOK() || details.isEmpty()) {
            details.add(status.isOK() ? generated.size() + " suites written or updated." : status.getMessage());
        }
        if (history().finish(state, String.join("\n", details), generated, comparisons)) RunHistory.prune();
    }

    private IStatus execute(IProgressMonitor monitor) {
        SubMonitor progress = SubMonitor.convert(monitor, getName(), 20 + 100 * targets.size());
        try {
            progress.subTask("Waiting for other EvoSuite runs to finish");
            while (!ONE_RUN_AT_A_TIME.tryAcquire(250, TimeUnit.MILLISECONDS)) {
                if (progress.isCanceled()) {
                    return Status.CANCEL_STATUS;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Status.CANCEL_STATUS;
        }
        try {
            File[] leftovers = Activator.getDefault().getStateLocation().append("generated").toFile().listFiles();
            if (leftovers != null) for (File leftover : leftovers) deleteRecursively(leftover);
            return generate(progress);
        } catch (OperationCanceledException e) {
            return Status.CANCEL_STATUS;
        } catch (CoreException e) {
            return e.getStatus();
        } finally {
            ONE_RUN_AT_A_TIME.release();
        }
    }

    private IStatus generate(SubMonitor progress) throws CoreException {
        EvoSuiteConsole console = EvoSuiteConsole.get();
        console.show();
        if (!options.fromPom.isEmpty()) {
            console.println("Using the EvoSuite configuration in " + project.getElementName() + "'s pom.xml: "
                    + String.join("; ", options.fromPom));
        }
        List<String> overridden = EvoSuiteCommand.overriddenUserProperties(options);
        if (!overridden.isEmpty()) {
            console.println("Ignoring " + overridden.stream().map(p -> "-D" + p).collect(Collectors.joining(", "))
                    + " from the additional arguments or the pom: the plug-in tells EvoSuite where to write the "
                    + "tests and reports, and reads them from there. Set the test folder and suffix in the "
                    + "EvoSuite settings instead.");
        }

        // Everything that can fail on the settings, before the build path changes below.
        if (!coverageOnly) ProjectSetup.testFolder(project.getProject(), options);
        Map<String, String> environment = coverageOnly ? Map.of() : llmEnvironment(console);
        ProjectSetup.JavaInstallation java = ProjectSetup.javaInstallation(project, options);
        File evosuiteJar = EvoSuiteJars.evosuite();

        boolean delegate = canDelegate();

        IFolder testFolder = coverageOnly ? null : prepareTestFolder(progress, console);

        // After the build path changes above, which trigger a build of their own.
        progress.subTask("Waiting for the build");
        waitForBuild(progress.split(10));

        boolean extending = coverageOnly || targets.stream().anyMatch(GenerationTarget::extendsTests);
        List<String> classpath = ProjectSetup.runtimeClasspath(project, extending);
        if (!java.hasCompiler) {
            console.println(java.executable.getParentFile().getParent() + " is a JRE without javac. EvoSuite "
                    + "cannot compile the tests it generates to check them, so some may fail or not compile; "
                    + "set a JDK as Java home in the EvoSuite settings.");
        }
        File projectDir = project.getProject().getLocation().toFile();
        // Kept apart from the test folder until the user's choice has been carried out.
        File outputDir = Activator.getDefault().getStateLocation().append("generated")
                .append(project.getElementName() + "-" + System.nanoTime()).toFile();

        MultiStatus result = new MultiStatus(Activator.PLUGIN_ID, IStatus.OK,
                "EvoSuite reported problems; the EvoSuite console has the details.", null);
        try {
            int index = 0;
            for (GenerationTarget target : targets) {
                index++;
                if (progress.isCanceled()) {
                    console.println("Cancelled.");
                    break;
                }
                String className = target.className();
                if (!coverageOnly) {
                    IFile existingSuite = SuiteOwnership.suite(testFolder, className, options.testSuffix);
                    existingSuite.refreshLocal(IResource.DEPTH_ZERO, null);
                    if (existingSuite.exists() && !SuiteOwnership.generated(existingSuite)) {
                        result.add(Activator.warning(EvoSuiteCommand.notAnEvoSuiteSuite(className,
                                existingSuite.getLocation().toFile())));
                        continue;
                    }
                }
                String label = targets.size() == 1 ? target.label()
                        : target.label() + " (" + index + "/" + targets.size() + ")";
                File reportDir = run.reports().resolve(className).toFile();
                GenerationOptions targetOptions = options;
                if (!coverageOnly && !GenerationOptions.LLM_NONE.equals(options.effectiveLlmProvider())) {
                    var type = project.findType(className);
                    var resource = type == null ? null : type.getResource();
                    if (resource != null && resource.getLocation() != null) {
                        targetOptions = options.copy();
                        targetOptions.llmSourcePath = resource.getLocation().toOSString();
                    }
                }
                EvoSuiteCommand command = new EvoSuiteCommand(java.executable.getAbsolutePath(),
                        evosuiteJar.getAbsolutePath(), className, classpath, projectDir, outputDir, reportDir,
                        targetOptions, target.methods());
                SeedingReport seeding = null;
                if (coverageOnly) {
                    command.seededBy(target.seeds().stream().map(GenerationTarget.Seed::className).toList(),
                            projectDir).measureCoverage();
                } else if (target.extendsTests()) {
                    File seedSources;
                    try {
                        seedSources = seedSourceDir(target, outputDir);
                    } catch (CoreException e) {
                        // Only this target is lost; the others still run, and the suites done so far are placed.
                        result.add(e.getStatus());
                        continue;
                    }
                    command.seededBy(target.seeds().stream().map(GenerationTarget.Seed::className)
                            .collect(Collectors.toList()), seedSources);
                    seeding = new SeedingReport(target.seedNames());
                }
                File suite = EvoSuiteCommand.suiteFile(outputDir, className, options.testSuffix);
                // Compared with the timestamp afterwards rather than with the clock, which a file system
                // with coarse timestamps (FAT: 2 s) could place before the start of a successful run.
                long before = suite.isFile() ? suite.lastModified() : Long.MIN_VALUE;
                // newChild, not split: split throws once cancelled, which would skip the refresh below
                // and leave the suites of the classes already done out of sync with the workspace.
                IStatus status = delegate && !SuiteOwnership.suite(testFolder, className, options.testSuffix).exists()
                        ? generateWithMaven(target, outputDir, environment, suite, progress.newChild(100))
                        : generateOne(command, java, environment, className, label, suite, before,
                                console, seeding, progress.newChild(100));
                if (status.getSeverity() == IStatus.CANCEL) {
                    console.println("Cancelled.");
                    break;
                }
                if (seeding != null) {
                    outcomes.add(target.label() + ": " + seeding.summary());
                    for (String problem : new String[]{seeding.seedingProblem(), seeding.runProblem()}) {
                        if (problem != null) {
                            result.add(Activator.warning(target.label() + ": " + problem));
                        }
                    }
                }
                if (!coverageOnly && suite.isFile() && suite.lastModified() != before) {
                    produced++;
                    try {
                        IWorkspace workspace = ResourcesPlugin.getWorkspace();
                        workspace.run(monitor -> place(target, suite, testFolder), testFolder.getProject(),
                                IWorkspace.AVOID_UPDATE, null);
                    } catch (CoreException e) {
                        result.add(e.getStatus());
                    }
                }
                if (!status.isOK()) {
                    result.add(status);
                }
            }
        } finally {
            deleteRecursively(outputDir);
            deleteRecursively(seedCopy(outputDir));
        }

        if (testFolder != null) testFolder.refreshLocal(IResource.DEPTH_INFINITE, null);
        summarise(console, result);
        if (options.openGeneratedTests && !generated.isEmpty() && generated.size() <= MAX_EDITORS) {
            openEditors(List.copyOf(generated));
        }
        if (!comparisons.isEmpty()) {
            openComparison(List.copyOf(comparisons));
        }
        if (progress.isCanceled()) {
            return Status.CANCEL_STATUS;
        }
        if (!result.isOK()) {
            return result;
        }
        return extensions.isEmpty() ? Status.OK_STATUS
                : new Status(IStatus.OK, Activator.PLUGIN_ID, String.join(" ", extensions));
    }

    private boolean canDelegate() throws CoreException {
        GenerationOptions defaults = new GenerationOptions();
        return options.delegateToMaven && !coverageOnly && !fixedSeed && decided.isEmpty()
                && targets.stream().noneMatch(target -> target.extendsTests() || !target.methods().isEmpty())
                && !options.llmSwitchedOff() && !options.effectiveNoRuntimeDependency()
                && options.extraArguments.isBlank() && defaults.llmContextMode.equals(options.llmContextMode)
                && defaults.llmMaxTokens == options.llmMaxTokens && defaults.llmTimeoutSeconds == options.llmTimeoutSeconds
                && defaults.llmRetryMaxAttempts == options.llmRetryMaxAttempts
                && defaults.llmTemperature.equals(options.llmTemperature) && MavenGeneration.canRun(project, options);
    }

    private IStatus generateWithMaven(GenerationTarget target, File staging, Map<String, String> environment,
                                      File suite, IProgressMonitor monitor) throws CoreException {
        run.delegated();
        run.update("Maven generation for " + target.label());
        try {
            int exit = MavenGeneration.run(project, target.className(), staging.toPath(), environment, monitor);
            outcomes.add(target.label() + ": ran through Maven. See the Maven console for progress and statistics.");
            if (exit == 0 && suite.isFile()) return Status.OK_STATUS;
            return Activator.error("Maven did not export tests for " + target.className() + " (exit code " + exit + ").");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Status.CANCEL_STATUS;
        }
    }

    private IFolder prepareTestFolder(SubMonitor progress, EvoSuiteConsole console) throws CoreException {
        if (options.configureBuildPath && ProjectSetup.isManagedByBuildTool(project.getProject())) {
            ManagedBuildSetup.configure(project, options, progress.split(3));
            GenerationOptions folderOnly = options.copy();
            folderOnly.configureBuildPath = false;
            return ProjectSetup.prepareTestFolder(project, folderOnly, progress.split(1));
        }
        IFolder testFolder = ProjectSetup.prepareTestFolder(project, options, progress.split(2));
        if (options.configureBuildPath) {
            if (!ProjectSetup.separatesTestCode(project)) {
                console.println("The project uses its root as the Java output folder. Choose a separate output "
                        + "folder in Java Build Path to compile the generated tests.");
            }
            List<String> added = ProjectSetup.addMissingLibraries(project, options, progress.split(2));
            if (!added.isEmpty()) console.println("Added to the build path: " + String.join(", ", added));
        }
        return testFolder;
    }

    /**
     * The folder EvoSuite reads the sources of {@code target}'s test classes from: their source
     * folder, or, if they are spread over several, a copy of them all in the temporary folder.
     */
    static File seedSourceDir(GenerationTarget target, File outputDir) throws CoreException {
        Set<File> roots = target.seeds().stream().map(GenerationTarget.Seed::sourceRoot)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (roots.size() == 1) {
            return roots.iterator().next();
        }
        File copy = seedCopy(outputDir);
        try {
            for (GenerationTarget.Seed seed : target.seeds()) {
                Path destination = copy.toPath().resolve(seed.className().replace('.', File.separatorChar) + ".java");
                Files.createDirectories(destination.getParent());
                Files.copy(seed.sourceFile().toPath(), destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new CoreException(Activator.error("Cannot copy the sources of " + target.seedNames(), e));
        }
        return copy;
    }

    private IStatus generateOne(EvoSuiteCommand command, ProjectSetup.JavaInstallation java,
                                Map<String, String> environment, String className, String label, File suite,
                                long before, EvoSuiteConsole console, SeedingReport seeding,
                                SubMonitor classShare) {
        // A child monitor ignores worked() until it knows how much work it stands for: without
        // this, the search's percentages never reached the progress view or the status bar.
        SubMonitor monitor = SubMonitor.convert(classShare, 100);
        List<String> arguments = command.toArguments(java.version);
        Map<String, String> env = new HashMap<>(environment);
        env.put("JAVA_HOME", java.executable.getParentFile().getParent());
        console.println("");
        console.println("=== " + className);
        console.println(EvoSuiteCommand.forDisplay(arguments));

        AtomicInteger percent = new AtomicInteger();
        AtomicReference<String> phase = new AtomicReference<>("Starting EvoSuite");
        AtomicReference<String> totalCoverage = new AtomicReference<>();
        ProgressParser.Listener listener = new ProgressParser.Listener() {
            @Override
            public void line(String line) {
                String key = environment.getOrDefault(EvoSuiteCommand.API_KEY_VARIABLE,
                        System.getenv(EvoSuiteCommand.API_KEY_VARIABLE));
                String safeLine = key == null || key.isEmpty() ? line : line.replace(key, "[redacted]");
                console.println(safeLine);
                outputListener.accept(safeLine);
                var match = TOTAL_COVERAGE.matcher(safeLine);
                if (match.find()) totalCoverage.set(match.group(1) + "%");
                if (seeding != null) {
                    seeding.accept(line);
                }
                if (line.startsWith("* ")) {
                    phase.set(safeLine.substring(2));
                }
            }

            @Override
            public void progress(int searchPercent, int coveragePercent) {
                percent.set(Math.min(100, searchPercent));
                phase.set("search " + searchPercent + "%, coverage " + coveragePercent + "%");
            }
        };

        int exitCode;
        int[] reported = {0};
        String[] shownPhase = {""};
        File argumentFile = Activator.getDefault().getStateLocation().append("evosuite-arguments.txt").toFile();
        try {
            // Java 8 would take "@file" for the main class; a long command line may then fail on
            // Windows, and the console shows the reason.
            List<String> commandLine = EvoSuiteCommand.fitCommandLine(arguments, argumentFile,
                    java.readsArgumentFiles() ? EvoSuiteCommand.MAX_INLINE_LENGTH : Integer.MAX_VALUE);
            exitCode = runner.run(commandLine, command.getWorkingDirectory(), env, listener, () -> {
                // Polled from this job's thread, so the monitor is only ever touched here.
                int now = percent.get();
                if (now > reported[0]) {
                    monitor.worked(now - reported[0]);
                    reported[0] = now;
                }
                String current = phase.get();
                if (!current.equals(shownPhase[0])) {
                    monitor.subTask(label + ": " + current);
                    run.update(label + ": " + current);
                    shownPhase[0] = current;
                }
                return monitor.isCanceled();
            });
        } catch (IOException e) {
            return Activator.error("Cannot start EvoSuite for " + className + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Status.CANCEL_STATUS;
        } finally {
            argumentFile.delete();
        }
        if (exitCode == EvoSuiteProcess.CANCELLED) {
            return Status.CANCEL_STATUS;
        }
        if (totalCoverage.get() != null) outcomes.add(label + ": coverage " + totalCoverage.get() + ".");
        if (coverageOnly) {
            return exitCode == 0 && totalCoverage.get() != null ? Status.OK_STATUS
                    : Activator.error("EvoSuite could not measure coverage for " + className + " (exit code "
                            + exitCode + "). See the EvoSuite console.");
        }
        // EvoSuite rewrites the suite on success, so an unchanged file means this run wrote nothing.
        if (suite.isFile() && suite.lastModified() != before) {
            if (exitCode != 0) {
                return Activator.warning("EvoSuite wrote tests for " + className + " but then exited with code "
                        + exitCode + "; the suite may be incomplete.");
            }
            return Status.OK_STATUS;
        }
        return Activator.error("No tests were generated for " + className + " (EvoSuite exit code " + exitCode
                + "); the EvoSuite console shows why.");
    }

    private Map<String, String> llmEnvironment(EvoSuiteConsole console) throws CoreException {
        String provider = options.effectiveLlmProvider();
        if ((GenerationOptions.LLM_ANTHROPIC.equals(provider) || GenerationOptions.LLM_OLLAMA.equals(provider))
                && options.effectiveLlmModel().isBlank() && System.getenv().getOrDefault("EVOSUITE_LLM_MODEL", "").isBlank()) {
            throw new CoreException(Activator.error("The LLM provider " + provider
                    + " needs a model. Set it in EvoSuite settings or EVOSUITE_LLM_MODEL."));
        }
        Map<String, String> environment = LlmEnvironment.resolve(options, () -> apiKeySource.apply(provider), () -> {
            ServerPassword password = MavenSupport.findServerPassword(options.llmApiKeyServerId);
            if (password.isFound()) return password.password();
            console.println(password.problem());
            return "";
        }, System.getenv(), console::println);
        boolean cloud = GenerationOptions.LLM_OPENAI.equals(provider) || GenerationOptions.LLM_ANTHROPIC.equals(provider);
        if (!cloud || !environment.getOrDefault(EvoSuiteCommand.API_KEY_VARIABLE,
                System.getenv().getOrDefault(EvoSuiteCommand.API_KEY_VARIABLE, "")).isBlank()) {
            return environment;
        }
        int choice = ManagedBuildSetup.prompt("EvoSuite: missing API key", "No API key is available for " + provider + ".",
                new String[]{"Configure key", "Run Search Only", "Cancel"});
        if (choice == 1) {
            options.llmApproach = GenerationOptions.APPROACH_SEARCH_ONLY;
            run.searchOnly();
            return Map.of(EvoSuiteCommand.API_KEY_VARIABLE, "");
        }
        if (choice == 0) EvoSuitePreferences.openLlmSettings();
        throw new OperationCanceledException();
    }

    private void waitForBuild(SubMonitor monitor) throws CoreException {
        try {
            if (ResourcesPlugin.getWorkspace().isAutoBuilding()) {
                Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
            } else {
                // With its references: their output folders are on EvoSuite's classpath too.
                ResourcesPlugin.getWorkspace().build(
                        new IBuildConfiguration[]{project.getProject().getActiveBuildConfig()},
                        IncrementalProjectBuilder.INCREMENTAL_BUILD, true, monitor);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperationCanceledException();
        }
    }

    static File seedCopy(File outputDir) {
        return new File(outputDir.getParentFile(), outputDir.getName() + "-sources");
    }

    /**
     * Carries out the user's choice for a suite generated into the temporary folder: a class
     * without a suite gets the new one, otherwise the existing suite is replaced, has the new
     * tests added or a comparison is queued. A suite that extends existing tests but has no tests
     * is dropped.
     */
    private void place(GenerationTarget target, File staged, IFolder testFolder) throws CoreException {
        String className = target.className();
        String suiteName = simpleName(className) + options.testSuffix;
        IFile existing = SuiteOwnership.suite(testFolder, className, options.testSuffix);
        existing.refreshLocal(IResource.DEPTH_ZERO, null);
        if (existing.exists() && !SuiteOwnership.generated(existing)) {
            // Written while EvoSuite ran: it is never replaced or merged into.
            throw new CoreException(Activator.warning(EvoSuiteCommand.notAnEvoSuiteSuite(className,
                    existing.getLocation().toFile())));
        }
        String charset = existing.exists() ? existing.getCharset() : testFolder.getDefaultCharset();
        String source = read(staged, charset);
        if (options.effectiveNoRuntimeDependency() && !SuiteMerger.isGeneratedSuite(source, suiteName)) {
            source = SuiteOwnership.decorate(existing, source);
        }
        int tests = SuiteMerger.testCount(source, suiteName);
        if (tests < 0) throw new CoreException(Activator.warning("EvoSuite returned invalid Java for " + suiteName + "."));
        if (target.extendsTests() && tests == 0) {
            outcomes.add("No new tests for " + target.label() + ": " + target.seedNames()
                    + " already covers what EvoSuite found.");
            extended(target, "no new tests");
            return;
        }
        // The scaffolding that -Dtest_scaffolding=true writes next to the suite.
        File stagedScaffolding = new File(staged.getParentFile(), suiteName + "_scaffolding.java");
        IFile scaffolding = scaffolding(existing);
        scaffolding.refreshLocal(IResource.DEPTH_ZERO, null);
        String scaffoldingSource = stagedScaffolding.isFile() ? read(stagedScaffolding, charset) : null;
        String existingScaffolding = scaffolding.exists() ? SuiteOwnership.contents(scaffolding) : null;
        boolean obsoleteScaffolding = options.effectiveNoRuntimeDependency() && existingScaffolding != null
                && scaffoldingSource == null && SuiteOwnership.generatedScaffolding(scaffolding, existingScaffolding);
        // Equal scaffolding, or none, needs no decision.
        boolean newScaffolding = scaffoldingSource != null
                && !scaffoldingSource.equals(existingScaffolding);
        if (!existing.exists()) {
            if (newScaffolding && scaffolding.exists()) {
                comparisons.add(new SuiteComparison.Pair(scaffolding, scaffoldingSource));
                comparisons.add(new SuiteComparison.Pair(existing, source));
                outcomes.add(existing.getName() + ": review the existing scaffolding before accepting the new suite.");
                return;
            }
            write(existing, source, charset, false);
            if (newScaffolding) {
                write(scaffolding, scaffoldingSource, scaffolding.exists() ? scaffolding.getCharset() : charset, false);
            }
            generated.add(existing);
            if (target.extendsTests()) {
                outcomes.add("Wrote " + existing.getName() + " with " + SeedingReport.count(tests, "new test") + ".");
            }
            extended(target, SeedingReport.count(tests, "new test") + " in " + existing.getName());
            return;
        }
        SuiteAction chosen = action;
        if (!decided.containsKey(className)) {
            // Nobody was asked about this suite: it appeared while the run waited or ran.
            boolean partial = !target.methods().isEmpty() || target.extendsTests();
            chosen = partial ? SuiteAction.ADD : SuiteAction.COMPARE;
            outcomes.add(existing.getName() + " was written while this run waited or ran, so it is not replaced: "
                    + (partial ? "the new tests are added to it." : "the new suite opens next to it in a compare "
                    + "editor."));
        }
        if (chosen == SuiteAction.REPLACE) {
            String problem = overwriteProblem(existing);
            if (!SuiteSnapshot.of(existing, scaffolding).equals(decided.get(className))) {
                problem = "the suite or its scaffolding changed after the run was requested";
            } else if (newScaffolding && existingScaffolding != null
                    && !SuiteOwnership.generatedScaffolding(scaffolding, existingScaffolding)) {
                problem = "the existing scaffolding is not generated by EvoSuite";
            } else if (problem == null && (newScaffolding || obsoleteScaffolding)) {
                problem = overwriteProblem(scaffolding);
            }
            if (problem == null) {
                try {
                    if (newScaffolding) {
                        write(scaffolding, scaffoldingSource,
                                scaffolding.exists() ? scaffolding.getCharset() : charset, scaffolding.exists());
                    }
                    write(existing, source, charset, true);
                    if (obsoleteScaffolding) scaffolding.delete(IResource.KEEP_HISTORY, null);
                    generated.add(existing);
                    outcomes.add("Replaced " + existing.getName() + " with " + SeedingReport.count(tests, "new test")
                            + "; the previous version is in its local history.");
                    extended(target, SeedingReport.count(tests, "new test") + " replaced " + existing.getName());
                    return;
                } catch (CoreException e) {
                    problem = e.getStatus().getMessage();
                }
            }
            outcomes.add("Cannot replace " + existing.getName() + ": " + problem
                    + ". The new suite opens next to it in a compare editor instead.");
        } else if (chosen == SuiteAction.ADD) {
            String problem = null;
            if (newScaffolding) {
                problem = "the new tests come with a new " + scaffolding.getName()
                        + ", which cannot be merged with the existing one";
            } else {
                try {
                    SuiteMerger.Result merge = EclipseSuiteMerger.mergeInto(existing, source, suiteName, null);
                    if (merge.isMerged()) {
                        added(target, existing, merge);
                        return;
                    }
                    problem = merge.incompatibility;
                } catch (CoreException e) {
                    problem = e.getStatus().getMessage();
                }
            }
            outcomes.add("Cannot add the new tests to " + existing.getName() + ": " + problem
                    + ". The new suite opens next to it in a compare editor instead.");
        }
        comparisons.add(new SuiteComparison.Pair(existing, source));
        if (obsoleteScaffolding) {
            comparisons.add(new SuiteComparison.Pair(scaffolding, "", existingScaffolding));
        } else if (newScaffolding) {
            comparisons.add(new SuiteComparison.Pair(scaffolding, scaffoldingSource));
        }
        extended(target, "the new tests open next to " + existing.getName() + " in a compare editor");
    }

    private void added(GenerationTarget target, IFile existing, SuiteMerger.Result merge) {
        String skipped = merge.duplicates == 0 ? ""
                : "; left out " + merge.duplicates + " that the suite already has";
        if (merge.added.isEmpty()) {
            outcomes.add("No new tests for " + existing.getName() + skipped + ".");
            extended(target, "no new tests");
        } else {
            generated.add(existing);
            outcomes.add("Added " + merge.added.size() + " test" + (merge.added.size() == 1 ? "" : "s")
                    + " to " + existing.getName() + " (" + String.join(", ", merge.added) + ")" + skipped
                    + ".");
            extended(target, SeedingReport.count(merge.added.size(), "new test") + " added to "
                    + existing.getName());
        }
    }

    /** Why {@code file} must not be overwritten now, or {@code null}. */
    static String overwriteProblem(IFile file) {
        ITextFileBuffer buffer = FileBuffers.getTextFileBufferManager()
                .getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
        if (buffer != null && buffer.isDirty()) return file.getName() + " has unsaved changes in an editor";
        var attributes = file.getResourceAttributes();
        return attributes != null && attributes.isReadOnly() ? file.getName() + " is read-only" : null;
    }

    /** Records what a run that extends existing tests produced, for the job's message. */
    private void extended(GenerationTarget target, String result) {
        if (target.extendsTests()) {
            extensions.add("Extended " + target.seedNames() + " for " + target.label() + ": " + result + ".");
        }
    }

    /**
     * Writes {@code source} into the workspace file {@code target}, which keeps its previous
     * contents in the local history.
     */
    private static void write(IFile target, String source, String charset, boolean replace) throws CoreException {
        var in = new ByteArrayInputStream(source.getBytes(Charset.forName(charset)));
        if (replace) {
            target.setContents(in, IResource.KEEP_HISTORY, null);
        } else {
            ProjectSetup.createFolders(target.getParent());
            target.create(in, IResource.NONE, null);
        }
        SuiteOwnership.remember(target, source);
    }

    private static String read(File file, String charset) throws CoreException {
        try {
            return new String(Files.readAllBytes(file.toPath()), Charset.forName(charset));
        } catch (IOException e) {
            throw new CoreException(Activator.error("Cannot read " + file, e));
        }
    }

    private static void deleteRecursively(File directory) {
        if (!directory.exists()) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            Activator.logError("Cannot delete " + directory, e);
        }
    }

    private void summarise(EvoSuiteConsole console, MultiStatus failures) {
        console.println("");
        if (!coverageOnly) {
            console.println("EvoSuite generated " + produced + " of " + targets.size() + " test suite"
                    + (targets.size() == 1 ? "" : "s") + ".");
        }
        for (String outcome : outcomes) {
            console.println("  " + outcome);
        }
        if (!comparisons.isEmpty()) {
            console.println("  " + (comparisons.size() == 1 ? "The new suite for " + comparisons.get(0).existing().getName()
                    + " opens" : comparisons.size() + " new suites open") + " next to the existing "
                    + (comparisons.size() == 1 ? "one" : "ones") + " in a compare editor; copy over the tests "
                    + "you want and save.");
        }
        for (IStatus failure : failures.getChildren()) {
            console.println("  " + failure.getMessage());
        }
    }

    private static void openComparison(List<SuiteComparison.Pair> pairs) {
        Display display = PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench().getDisplay() : null;
        if (display != null) {
            display.asyncExec(() -> SuiteComparison.open(pairs));
        }
    }

    private static void openEditors(List<IFile> files) {
        Display display = PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench().getDisplay() : null;
        if (display == null) {
            return;
        }
        display.asyncExec(() -> {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window == null ? null : window.getActivePage();
            if (page == null) {
                return;
            }
            for (IFile file : files) {
                try {
                    IDE.openEditor(page, file, true);
                } catch (PartInitException e) {
                    Activator.log(e.getStatus());
                }
            }
        });
    }

    static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
