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

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IBuildConfiguration;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
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
import org.evosuite.eclipse.preferences.LlmApiKey;
import org.evosuite.eclipse.ui.EvoSuiteConsole;

/**
 * Generates tests for a list of classes of one project, one EvoSuite process per class.
 *
 * <p>The job holds no scheduling rule: a run takes minutes, and a workspace rule would block
 * builds and edits for all of that time. Runs are serialised by a semaphore instead, because
 * each one already occupies a CPU core per JVM and parallel runs only slow each other down.
 */
public class TestGenerationJob extends Job {

    public static final Object FAMILY = TestGenerationJob.class;

    private static final Semaphore ONE_RUN_AT_A_TIME = new Semaphore(1);

    /** Where the LLM API key comes from; tests replace it to stay clear of secure storage. */
    static Supplier<String> apiKeySource = LlmApiKey::get;
    private static final int MAX_EDITORS = 3;

    private final IJavaProject project;
    private final List<String> classes;
    private final GenerationOptions options;
    private final List<IFile> generated = new CopyOnWriteArrayList<>();

    /** With the project's settings as they are now, not when the job eventually runs. */
    public TestGenerationJob(IJavaProject project, List<String> classes) {
        this(project, classes, EvoSuitePreferences.resolve(project.getProject(), null));
    }

    /**
     * @param options the settings the user confirmed: a run can wait behind another for a long
     *                time, and must not pick up a test folder or suffix changed meanwhile
     */
    public TestGenerationJob(IJavaProject project, List<String> classes, GenerationOptions options) {
        super(classes.size() == 1
                ? "Generating tests for " + simpleName(classes.get(0))
                : "Generating tests for " + classes.size() + " classes in " + project.getElementName());
        this.project = project;
        this.classes = List.copyOf(classes);
        this.options = options;
        setUser(true);
        setPriority(LONG);
    }

    @Override
    public boolean belongsTo(Object family) {
        return family == FAMILY;
    }

    /** The test suites written by this job; complete once the job has finished. */
    public List<IFile> getGeneratedSuites() {
        return List.copyOf(generated);
    }

    @Override
    protected IStatus run(IProgressMonitor monitor) {
        SubMonitor progress = SubMonitor.convert(monitor, getName(), 20 + 100 * classes.size());
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

        IFolder testFolder = ProjectSetup.prepareTestFolder(project, options, progress.split(2));
        if (options.configureBuildPath && !ProjectSetup.separatesTestCode(project)) {
            console.println(project.getElementName() + " keeps its sources and classes in the project folder "
                    + "itself, where Eclipse allows no further source folder. " + options.testFolder
                    + " is excluded from the build; to compile the generated tests, give the project separate "
                    + "source and output folders under Build Path > Configure Build Path.");
        }
        if (options.configureBuildPath) {
            List<String> added = ProjectSetup.addMissingLibraries(project, options, progress.split(2));
            if (!added.isEmpty()) {
                console.println("Added to the build path of " + project.getElementName() + ": "
                        + String.join(", ", added));
                if (!options.fromPom.isEmpty()) {
                    console.println("Maven will drop these entries the next time it updates the project. "
                            + "Declare test dependencies on JUnit and org.evosuite:evosuite-standalone-runtime; "
                            + "the EvoSuite plugin's setup goal adds " + options.testFolder + " to Maven builds.");
                } else if (ProjectSetup.isManagedByBuildTool(project.getProject())) {
                    console.println("This project is built by Maven or Gradle, which will drop these entries "
                            + "the next time it updates the project. Declare test dependencies on JUnit and "
                            + "org.evosuite:evosuite-standalone-runtime, and add " + options.testFolder
                            + " as a test source folder, to keep the generated tests compiling.");
                }
            }
        } else {
            progress.split(2);
        }

        // After the build path changes above, which trigger a build of their own.
        progress.subTask("Waiting for the build");
        waitForBuild(progress.split(10));

        List<String> classpath = ProjectSetup.runtimeClasspath(project);
        Map<String, String> environment = llmEnvironment(console);
        ProjectSetup.JavaInstallation java = ProjectSetup.javaInstallation(project, options);
        if (!java.hasCompiler) {
            console.println(java.executable.getParentFile().getParent() + " is a JRE without javac. EvoSuite "
                    + "cannot compile the tests it generates to check them, so some may fail or not compile; "
                    + "set a JDK as Java home in the EvoSuite settings.");
        }
        File evosuiteJar = EvoSuiteJars.evosuite();
        File projectDir = project.getProject().getLocation().toFile();
        File testDir = testFolder.getLocation().toFile();
        File reportDir = Activator.getDefault().getStateLocation()
                .append("reports").append(project.getElementName()).toFile();

        MultiStatus result = new MultiStatus(Activator.PLUGIN_ID, IStatus.OK,
                "EvoSuite reported problems; the EvoSuite console has the details.", null);
        int index = 0;
        for (String className : classes) {
            index++;
            if (progress.isCanceled()) {
                console.println("Cancelled.");
                break;
            }
            String label = classes.size() == 1 ? simpleName(className)
                    : simpleName(className) + " (" + index + "/" + classes.size() + ")";
            EvoSuiteCommand command = new EvoSuiteCommand(java.executable.getAbsolutePath(), evosuiteJar.getAbsolutePath(),
                    className, classpath, projectDir, testDir, reportDir, options);
            File suite = EvoSuiteCommand.suiteFile(testDir, className, options.testSuffix);
            // newChild, not split: split throws once cancelled, which would skip the refresh below
            // and leave the suites of the classes already done out of sync with the workspace.
            IStatus status = generateOne(command, java, environment, className, label, suite, console,
                    progress.newChild(100));
            if (status.getSeverity() == IStatus.CANCEL) {
                console.println("Cancelled.");
                break;
            }
            if (!status.isOK()) {
                result.add(status);
            }
        }

        testFolder.refreshLocal(IResource.DEPTH_INFINITE, null);
        summarise(console, result);
        if (options.openGeneratedTests && !generated.isEmpty() && generated.size() <= MAX_EDITORS) {
            openEditors(List.copyOf(generated));
        }
        if (progress.isCanceled()) {
            return Status.CANCEL_STATUS;
        }
        return result.isOK() ? Status.OK_STATUS : result;
    }

    private IStatus generateOne(EvoSuiteCommand command, ProjectSetup.JavaInstallation java,
                                Map<String, String> environment, String className, String label, File suite,
                                EvoSuiteConsole console, SubMonitor classShare) {
        // A child monitor ignores worked() until it knows how much work it stands for: without
        // this, the search's percentages never reached the progress view or the status bar.
        SubMonitor monitor = SubMonitor.convert(classShare, 100);
        // Compared with the timestamp afterwards rather than with the clock, which a file system
        // with coarse timestamps (FAT: 2 s) could place before the start of a successful run.
        long before = suite.isFile() ? suite.lastModified() : Long.MIN_VALUE;
        List<String> arguments = command.toArguments();
        console.println("");
        console.println("=== " + className);
        console.println(EvoSuiteCommand.forDisplay(arguments));

        AtomicInteger percent = new AtomicInteger();
        AtomicReference<String> phase = new AtomicReference<>("Starting EvoSuite");
        ProgressParser.Listener listener = new ProgressParser.Listener() {
            @Override
            public void line(String line) {
                console.println(line);
                if (line.startsWith("* ")) {
                    phase.set(line.substring(2));
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
            exitCode = EvoSuiteProcess.run(commandLine, command.getWorkingDirectory(), environment, listener, () -> {
                // Polled from this job's thread, so the monitor is only ever touched here.
                int now = percent.get();
                if (now > reported[0]) {
                    monitor.worked(now - reported[0]);
                    reported[0] = now;
                }
                String current = phase.get();
                if (!current.equals(shownPhase[0])) {
                    monitor.subTask(label + ": " + current);
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
        // EvoSuite rewrites the suite on success, so an unchanged file means this run wrote nothing.
        if (suite.isFile() && suite.lastModified() != before) {
            IFile file = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(IPath.fromFile(suite));
            if (file != null) {
                generated.add(file);
            }
            if (exitCode != 0) {
                return Activator.warning("EvoSuite wrote tests for " + className + " but then exited with code "
                        + exitCode + "; the suite may be incomplete.");
            }
            return Status.OK_STATUS;
        }
        return Activator.error("No tests were generated for " + className + " (EvoSuite exit code " + exitCode
                + "); the EvoSuite console shows why.");
    }

    /**
     * Checks the LLM settings, and returns the environment that carries the API key: on the
     * command line it would be visible to other users and echoed to the console.
     */
    private Map<String, String> llmEnvironment(EvoSuiteConsole console) throws CoreException {
        // Decided on what EvoSuite will see, since the pom or the arguments can switch the LLM on.
        String provider = options.effectiveLlmProvider();
        if (provider.isEmpty() || GenerationOptions.LLM_NONE.equals(provider)) {
            return Map.of();
        }
        if (options.effectiveLlmModel().isEmpty() && !GenerationOptions.LLM_OPENAI.equals(provider)) {
            throw new CoreException(Activator.error("The LLM provider " + provider + " needs a model. "
                    + "Set one in the EvoSuite settings, under Large language model."));
        }
        if (!options.llmApiKeyServerId.isEmpty()) {
            // As mvn evosuite:generate does: the password of a server in Maven's settings.xml.
            String key = MavenSupport.serverPassword(options.llmApiKeyServerId);
            if (key != null) {
                return Map.of(EvoSuiteCommand.API_KEY_VARIABLE, key);
            }
            console.println("The pom names the server " + options.llmApiKeyServerId + " for the LLM API key, "
                    + "but Maven's settings.xml has no such server with a password; using the key from the "
                    + "EvoSuite preferences instead.");
        }
        String key = apiKeySource.get();
        if (!key.isEmpty()) {
            return Map.of(EvoSuiteCommand.API_KEY_VARIABLE, key);
        }
        if (!GenerationOptions.LLM_OLLAMA.equals(provider)
                && System.getenv(EvoSuiteCommand.API_KEY_VARIABLE) == null) {
            console.println("No LLM API key is set. Enter one under Preferences > Java > EvoSuite, or set "
                    + EvoSuiteCommand.API_KEY_VARIABLE + " in the environment Eclipse starts in.");
        }
        return Map.of();
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

    private void summarise(EvoSuiteConsole console, MultiStatus failures) {
        console.println("");
        console.println("EvoSuite generated " + generated.size() + " of " + classes.size() + " test suite"
                + (classes.size() == 1 ? "" : "s") + ".");
        for (IStatus failure : failures.getChildren()) {
            console.println("  " + failure.getMessage());
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
