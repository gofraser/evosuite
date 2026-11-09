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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.evosuite.eclipse.Activator;
import org.evosuite.eclipse.ui.SuiteComparison;
import org.evosuite.ide.EvoSuiteCommand;
import org.evosuite.ide.GenerationOptions;
import org.evosuite.ide.GenerationTarget;
import org.evosuite.ide.SettingKeys;

public final class RunHistory {
    private static final int RETAIN = 30;
    private static final ConcurrentHashMap<String, Run> active = new ConcurrentHashMap<>();
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private RunHistory() {}

    public enum State {
        QUEUED, RUNNING, COMPLETED, WARNINGS, FAILED, CANCELLED, INTERRUPTED;

        boolean inProgress() {
            return this == QUEUED || this == RUNNING;
        }
    }

    public static final class Run {
        private final Path dir;
        private final Properties data;
        private final TestGenerationJob job;
        private volatile String progress = "";

        private Run(Path dir, Properties data, TestGenerationJob job) {
            this.dir = dir;
            this.data = data;
            this.job = job;
        }

        public String id() { return dir.getFileName().toString(); }
        public String project() { return data.getProperty("project"); }
        public String started() { return data.getProperty("started"); }
        public String label() { return data.getProperty("label"); }
        public boolean delegatedToMaven() { return Boolean.parseBoolean(data.getProperty("delegated")); }
        public boolean coverageOnly() { return Boolean.parseBoolean(data.getProperty("coverageOnly")); }
        public String summary() { return data.getProperty("summary", ""); }
        public boolean hasReview() { return data.containsKey("compare.0"); }
        public String progress() { return progress; }
        public Path reports() { return dir.resolve("reports"); }
        public boolean canCancel() { return job != null && state().inProgress(); }
        public void cancel() { if (canCancel()) job.cancel(); }

        public State state() {
            State saved = State.valueOf(data.getProperty("state"));
            return job == null && saved.inProgress() ? State.INTERRUPTED : saved;
        }

        public GenerationOptions options() {
            TreeMap<String, String> settings = new TreeMap<>();
            for (String key : SettingKeys.all()) {
                String value = data.getProperty("setting." + key);
                if (value != null) settings.put(key, value);
            }
            GenerationOptions options = SettingKeys.resolve(settings);
            options.llmApiKeyServerId = data.getProperty("serverId", "");
            return options;
        }

        public List<GenerationTarget> targets() {
            List<GenerationTarget> targets = new ArrayList<>();
            for (int i = 0; data.containsKey("target." + i); i++) {
                String key = "target." + i;
                List<String> methods = lines(key + ".methods");
                List<GenerationTarget.Seed> seeds = new ArrayList<>();
                for (int j = 0; data.containsKey(key + ".seed." + j); j++) {
                    String seed = key + ".seed." + j;
                    seeds.add(new GenerationTarget.Seed(data.getProperty(seed),
                            new File(data.getProperty(seed + ".root")), new File(data.getProperty(seed + ".file"))));
                }
                targets.add(new GenerationTarget(data.getProperty(key), methods, seeds));
            }
            return List.copyOf(targets);
        }

        public List<IFile> suites() {
            return lines("suites").stream().map(RunHistory::file).filter(IFile::exists).toList();
        }

        public synchronized List<SuiteComparison.Pair> comparisons() {
            List<SuiteComparison.Pair> pairs = new ArrayList<>();
            for (int i = 0; data.containsKey("compare." + i); i++) {
                String key = "compare." + i;
                pairs.add(new SuiteComparison.Pair(file(data.getProperty(key)), data.getProperty(key + ".source"),
                        data.getProperty(key + ".obsolete")));
            }
            return List.copyOf(pairs);
        }

        public void finishReview() throws CoreException {
            var workspace = ResourcesPlugin.getWorkspace();
            var project = workspace.getRoot().getProject(project());
            List<SuiteComparison.Pair> pending = comparisons();
            var suites = new java.util.LinkedHashSet<>(suites());
            var acceptedRuntimeFree = new java.util.HashSet<IFile>();
            workspace.run(monitor -> {
                for (SuiteComparison.Pair pair : pending) {
                    IFile file = pair.existing();
                    if (!file.exists() || file.getName().endsWith("_scaffolding.java")) continue;
                    if (SuiteOwnership.remember(file, pair.generatedSource())) acceptedRuntimeFree.add(file);
                    if (SuiteOwnership.contents(file).equals(pair.generatedSource()) || SuiteOwnership.generated(file)) {
                        suites.add(file);
                    }
                }
                for (SuiteComparison.Pair pair : pending) {
                    if (pair.obsoleteSource() == null) continue;
                    IFile helper = pair.existing();
                    helper.refreshLocal(IResource.DEPTH_ZERO, monitor);
                    IFile suite = helper.getParent().getFile(IPath.fromPortableString(
                            helper.getName().replaceFirst("_scaffolding\\.java$", ".java")));
                    if (acceptedRuntimeFree.contains(suite) && helper.exists()
                            && TestGenerationJob.overwriteProblem(helper) == null
                            && pair.obsoleteSource().equals(SuiteOwnership.contents(helper))) {
                        helper.delete(IResource.KEEP_HISTORY, monitor);
                    }
                }
            }, project, IWorkspace.AVOID_UPDATE, null);
            synchronized (this) {
                for (int i = 0; data.containsKey("compare." + i); i++) {
                    String key = "compare." + i;
                    data.remove(key);
                    data.remove(key + ".source");
                    data.remove(key + ".obsolete");
                }
                rememberSuites(List.copyOf(suites));
                save();
            }
            changed();
        }

        private void rememberSuites(List<IFile> suites) {
            data.setProperty("suites", suites.stream().map(file -> file.getFullPath().toPortableString())
                    .collect(Collectors.joining("\n")));
        }

        private List<String> lines(String key) {
            return data.getProperty(key, "").lines().toList();
        }

        void update(String message) {
            progress = message;
            changed();
        }

        synchronized boolean finish(State state, String summary, List<IFile> suites,
                                 List<SuiteComparison.Pair> comparisons) {
            if (!state().inProgress()) return false;
            data.setProperty("summary", summary);
            rememberSuites(suites);
            for (int i = 0; i < comparisons.size(); i++) {
                SuiteComparison.Pair pair = comparisons.get(i);
                String key = "compare." + i;
                data.setProperty(key, pair.existing().getFullPath().toPortableString());
                data.setProperty(key + ".source", pair.generatedSource());
                if (pair.obsoleteSource() != null) data.setProperty(key + ".obsolete", pair.obsoleteSource());
            }
            data.setProperty("state", state.name());
            progress = "";
            if (save()) active.remove(id());
            changed();
            return true;
        }

        synchronized void delegated() {
            data.setProperty("delegated", "true");
            save();
        }

        synchronized void searchOnly() {
            data.setProperty("setting.llmApproach", GenerationOptions.APPROACH_SEARCH_ONLY);
            save();
        }

        synchronized void startedRunning() {
            data.setProperty("state", State.RUNNING.name());
            save();
            changed();
        }

        private synchronized boolean save() {
            try {
                Files.createDirectories(dir);
                Path temp = dir.resolve("run.tmp");
                try (var stream = Files.newOutputStream(temp)) {
                    data.store(stream, null);
                }
                Files.move(temp, dir.resolve("run.properties"), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException e) {
                Activator.logError("Cannot save EvoSuite run " + id(), e);
                return false;
            }
        }
    }

    static Run queue(TestGenerationJob job, String project, List<GenerationTarget> targets,
                     GenerationOptions options, boolean coverageOnly) {
        Properties data = new Properties();
        data.setProperty("project", project);
        data.setProperty("started", Instant.now().toString());
        data.setProperty("label", targets.size() == 1 ? targets.getFirst().label() : targets.size() + " classes");
        data.setProperty("coverageOnly", Boolean.toString(coverageOnly));
        data.setProperty("state", State.QUEUED.name());
        data.setProperty("serverId", options.llmApiKeyServerId);
        for (String key : SettingKeys.all()) {
            data.setProperty("setting." + key, SettingKeys.value(options, key));
        }
        data.setProperty("setting.usePomSettings", "false");
        data.setProperty("setting." + SettingKeys.DELEGATE_TO_MAVEN, "false");
        data.setProperty("setting.extraArguments", EvoSuiteCommand.additionalArguments(options).stream()
                .filter(arg -> !arg.matches("-D(?:test_dir|junit_suffix|report_dir|llm_trace_dir)=.*"))
                .filter(arg -> !EvoSuiteCommand.credentialArgument(arg))
                .map(arg -> "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(" ")));
        for (int i = 0; i < targets.size(); i++) {
            GenerationTarget target = targets.get(i);
            String key = "target." + i;
            data.setProperty(key, target.className());
            data.setProperty(key + ".methods", String.join("\n", target.methods()));
            for (int j = 0; j < target.seeds().size(); j++) {
                GenerationTarget.Seed seed = target.seeds().get(j);
                String name = key + ".seed." + j;
                data.setProperty(name, seed.className());
                data.setProperty(name + ".root", seed.sourceRoot().getAbsolutePath());
                data.setProperty(name + ".file", seed.sourceFile().getAbsolutePath());
            }
        }
        Run run = new Run(directory().resolve(UUID.randomUUID().toString()), data, job);
        active.put(run.id(), run);
        run.save();
        changed();
        return run;
    }

    public static List<Run> runs() {
        var runs = new java.util.HashMap<>(active);
        if (Files.isDirectory(directory())) {
            try (var folders = Files.list(directory())) {
                folders.forEach(dir -> {
                    if (!runs.containsKey(dir.getFileName().toString())) {
                        try (var stream = Files.newInputStream(dir.resolve("run.properties"))) {
                            Properties data = new Properties();
                            data.load(stream);
                            Run run = new Run(dir, data, null);
                            run.state();
                            Instant.parse(run.started());
                            runs.put(run.id(), run);
                        } catch (IOException | RuntimeException ignored) {
                            // An incomplete or invalid record is not a saved run.
                        }
                    }
                });
            } catch (IOException e) {
                Activator.logError("Cannot read EvoSuite run history", e);
            }
        }
        return runs.values().stream().sorted(Comparator.comparing((Run run) -> Instant.parse(run.started())).reversed())
                .toList();
    }

    public static void clearCompleted() {
        runs().stream().filter(RunHistory::canRemove).forEach(RunHistory::remove);
        changed();
    }

    static void prune() {
        runs().stream().filter(RunHistory::canRemove).skip(RETAIN).forEach(RunHistory::remove);
    }

    private static boolean canRemove(Run run) {
        return !run.state().inProgress() && !run.hasReview();
    }

    private static void remove(Run run) {
        try (var paths = Files.walk(run.dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            active.remove(run.id(), run);
        } catch (IOException e) {
            Activator.logError("Cannot remove EvoSuite run " + run.id(), e);
        }
    }

    public static void listen(Runnable listener) { listeners.add(listener); }
    public static void unlisten(Runnable listener) { listeners.remove(listener); }
    private static void changed() { listeners.forEach(Runnable::run); }

    private static Path directory() {
        return Activator.getDefault().getStateLocation().append("runs").toFile().toPath();
    }

    private static IFile file(String path) {
        return ResourcesPlugin.getWorkspace().getRoot().getFile(IPath.fromPortableString(path));
    }
}
