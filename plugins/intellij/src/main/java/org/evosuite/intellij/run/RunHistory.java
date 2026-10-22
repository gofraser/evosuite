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

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.components.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.*;

import org.evosuite.ide.*;
import org.evosuite.intellij.settings.EffectiveConfiguration;
import org.evosuite.intellij.ui.SuiteComparison;
import org.jetbrains.annotations.NotNull;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Machine-local, redacted run records. Generated comparison sources stay outside project files. */
@Service(Service.Level.PROJECT)
@State(name = "EvoSuiteRunHistory", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class RunHistory implements PersistentStateComponent<RunHistory.State> {
    public static final class Comparison {
        public String destination = "", generated = "";
        public boolean create;
    }

    public static final class Seed {
        public String className = "", root = "";
    }

    public static final class Target {
        public String module = "",
                className = "",
                status = "Queued",
                output = "",
                reports = "",
                seed = "",
                detail = "",
                keyServerId = "",
                replayUnavailable = "";
        public long started, finished;
        public int search = -1, coverage = -1, tests = -1;
        public List<String> methods = new ArrayList<>();
        public List<Seed> seeds = new ArrayList<>();
        public Map<String, String> settings = new TreeMap<>();
        public List<Comparison> comparisons = new ArrayList<>();

        Target copy() {
            Target t = new Target();
            t.module = module;
            t.className = className;
            t.status = status;
            t.output = output;
            t.reports = reports;
            t.seed = seed;
            t.detail = detail;
            t.keyServerId = keyServerId;
            t.replayUnavailable = replayUnavailable;
            t.started = started;
            t.finished = finished;
            t.search = search;
            t.coverage = coverage;
            t.tests = tests;
            t.methods = new ArrayList<>(methods);
            t.seeds = new ArrayList<>(seeds);
            t.settings = new TreeMap<>(settings);
            t.comparisons = new ArrayList<>(comparisons);
            return t;
        }
    }

    public static final class Run {
        public String id = "", mode = "", status = "Queued", phase = "Queued", selection = "";
        public long started, finished;
        public List<Target> targets = new ArrayList<>();

        public boolean active() {
            return finished == 0;
        }

        Run copy() {
            Run r = new Run();
            r.id = id;
            r.mode = mode;
            r.status = status;
            r.phase = phase;
            r.selection = selection;
            r.started = started;
            r.finished = finished;
            r.targets =
                    targets.stream()
                            .map(Target::copy)
                            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            return r;
        }
    }

    public static final class State {
        public int retentionDays = 30;
        public List<Run> runs = new ArrayList<>();
    }

    private State state = new State();
    private final Project project;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public RunHistory(Project project) {
        this.project = project;
    }

    public static RunHistory getInstance(Project project) {
        return project.getService(RunHistory.class);
    }

    public synchronized State getState() {
        State copy = new State();
        copy.retentionDays = state.retentionDays;
        copy.runs =
                state.runs.stream()
                        .map(Run::copy)
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return copy;
    }

    public synchronized void loadState(@NotNull State value) {
        state = value;
        state.retentionDays = Math.max(1, Math.min(3650, state.retentionDays));
        for (Run run : state.runs) {
            for (Target target : run.targets)
                if (target.status.equals("Delegated to Maven")
                        || target.status.equals("Maven failed"))
                    target.replayUnavailable =
                            "Maven controls generation settings and seeds. Repeat this request"
                                    + " through the Maven runner.";
            if (run.active()) {
                run.status = "Interrupted";
                run.phase =
                        "IDE session ended; completed outputs and pending comparisons are"
                                + " retained.";
                run.finished = System.currentTimeMillis();
                for (Target target : run.targets)
                    if (target.finished == 0) {
                        target.status = "Interrupted";
                        target.finished = run.finished;
                    }
            }
        }
    }

    public void listen(Disposable parent, Runnable listener) {
        listeners.add(listener);
        Disposer.register(parent, () -> listeners.remove(listener));
    }

    private final java.util.concurrent.atomic.AtomicBoolean notificationPending =
            new java.util.concurrent.atomic.AtomicBoolean();

    private void changed() {
        if (notificationPending.compareAndSet(false, true))
            ApplicationManager.getApplication()
                    .invokeLater(
                            () -> {
                                notificationPending.set(false);
                                if (!project.isDisposed()) listeners.forEach(Runnable::run);
                            });
    }

    public synchronized void settings(String id, int index, GenerationOptions options) {
        find(id).targets.get(index).settings = EffectiveConfiguration.savedValues(options);
        changed();
    }

    private Run find(String id) {
        return state.runs.stream().filter(r -> r.id.equals(id)).findFirst().orElseThrow();
    }

    public synchronized String start(String mode, String selection) {
        Run run = new Run();
        run.id = UUID.randomUUID().toString();
        run.mode = mode;
        run.selection = selection;
        run.started = System.currentTimeMillis();
        state.runs.add(0, run);
        changed();
        return run.id;
    }

    public synchronized boolean anyActive() {
        return state.runs.stream().anyMatch(Run::active);
    }

    public synchronized String latestId() {
        return state.runs.isEmpty() ? "" : state.runs.getFirst().id;
    }

    public synchronized boolean active(String id) {
        return find(id).active();
    }

    public synchronized String activeSelection(String selection) {
        return state.runs.stream()
                .filter(r -> r.active() && r.selection.equals(selection))
                .map(r -> r.id)
                .findFirst()
                .orElse(null);
    }

    public synchronized void phase(String id, String phase) {
        find(id).phase = phase;
        find(id).status = phase;
        changed();
    }

    public synchronized void finish(String id, String status, String detail) {
        Run run = find(id);
        run.status = status;
        run.phase = detail;
        run.finished = System.currentTimeMillis();
        for (Target t : run.targets)
            if (t.finished == 0) {
                t.status = status;
                t.finished = run.finished;
            }
        changed();
    }

    public synchronized int target(
            String id,
            String module,
            GenerationTarget target,
            GenerationOptions options,
            Path destination) {
        Target t = new Target();
        t.module = module;
        t.className = target.className();
        t.methods = new ArrayList<>(target.methods());
        t.output = destination.toString();
        for (var seed : target.seeds()) {
            Seed s = new Seed();
            s.className = seed.className();
            s.root = seed.sourceRoot().toString();
            t.seeds.add(s);
        }
        t.settings = EffectiveConfiguration.savedValues(options);
        t.keyServerId = options.llmApiKeyServerId;
        find(id).targets.add(t);
        changed();
        return find(id).targets.size() - 1;
    }

    public synchronized void beginTarget(String id, int index, Path staging) {
        Target t = find(id).targets.get(index);
        t.status = find(id).mode.equals("COVERAGE") ? "Measuring coverage" : "Searching";
        t.started = System.currentTimeMillis();
        t.reports = staging.resolve("reports").toString();
        find(id).phase =
                (find(id).mode.equals("COVERAGE") ? "Measuring coverage: " : "Searching: ")
                        + t.className;
        changed();
    }

    public synchronized void line(String id, int index, String line) {
        Target t = find(id).targets.get(index);
        var seed = java.util.regex.Pattern.compile("-Drandom_seed=(-?\\d+)").matcher(line);
        while (seed.find()) t.seed = seed.group(1);
        var totalCoverage =
                java.util.regex.Pattern.compile("Total coverage:\\s*([0-9]+(?:[.,][0-9]+)?)\\s*%")
                        .matcher(line);
        if (totalCoverage.find()) {
            t.coverage =
                    (int) Math.round(Double.parseDouble(totalCoverage.group(1).replace(',', '.')));
            changed();
        }
        if (!line.stripLeading().startsWith("*")) return;
        String lower = line.toLowerCase(Locale.ROOT);
        String phase =
                lower.contains("minimiz")
                        ? "Minimizing"
                        : lower.contains("assertion")
                                ? "Generating assertions"
                                : lower.contains("junit")
                                                && (lower.contains("check")
                                                        || lower.contains("validat"))
                                        ? "Validating tests"
                                        : null;
        if (phase != null && !t.status.equals(phase)) {
            t.status = phase;
            find(id).phase = phase + ": " + t.className;
            changed();
        }
    }

    public synchronized void progress(String id, int index, int search, int coverage) {
        Target t = find(id).targets.get(index);
        t.search = search;
        t.coverage = coverage;
        changed();
    }

    public synchronized void outcome(
            String id, int index, String status, String detail, int tests) {
        Target t = find(id).targets.get(index);
        t.status = status;
        t.detail = detail;
        if (status.equals("Delegated to Maven") || status.equals("Maven failed"))
            t.replayUnavailable =
                    "Maven controls generation settings and seeds. Repeat this request through the"
                            + " Maven runner.";
        t.tests = tests;
        t.finished = System.currentTimeMillis();
        changed();
    }

    public synchronized void failUnfinished(String id, Collection<Integer> indices, String detail) {
        for (int index : indices) {
            Target target = find(id).targets.get(index);
            if (target.finished == 0) outcome(id, index, "Failed", detail, -1);
        }
    }

    public void comparisons(String id, int index, List<SuiteComparison.Pair> pairs, Path staging)
            throws java.io.IOException {
        List<Comparison> saved = new ArrayList<>();
        int number = 0;
        for (var pair : pairs) {
            var file = FileDocumentManager.getInstance().getFile(pair.existing());
            Path destination =
                    pair.destination() != null
                            ? pair.destination()
                            : file == null ? Path.of(pair.name()) : file.toNioPath();
            Path path = staging.resolve("comparisons/" + number++ + ".java");
            Files.createDirectories(path.getParent());
            Files.writeString(path, pair.generated());
            Comparison c = new Comparison();
            c.destination = destination.toString();
            c.generated = path.toString();
            c.create = pair.destination() != null;
            saved.add(c);
        }
        synchronized (this) {
            find(id).targets.get(index).comparisons = saved;
            changed();
        }
    }

    public List<SuiteComparison.Pair> comparisons(Target target) {
        List<SuiteComparison.Pair> pairs = new ArrayList<>();
        for (Comparison c : target.comparisons) {
            try {
                String source = Files.readString(Path.of(c.generated));
                Path destination = Path.of(c.destination);
                var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(destination);
                var doc = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
                if (doc != null)
                    pairs.add(new SuiteComparison.Pair(destination.toString(), doc, source));
                else pairs.add(SuiteComparison.pending(destination, source));
            } catch (java.io.IOException ignored) {
            }
        }
        return pairs;
    }

    public synchronized void reviewed(String id, int index) {
        Target target = find(id).targets.get(index);
        for (var comparison : target.comparisons) {
            try {
                org.evosuite.intellij.settings.GeneratedSuiteRegistry.getInstance(project)
                        .recordAccepted(
                                Path.of(comparison.destination),
                                Files.readString(Path.of(comparison.generated)));
            } catch (java.io.IOException ignored) {
            }
        }
        target.comparisons.clear();
        Run run = find(id);
        if (!run.active() && run.targets.stream().allMatch(t -> t.comparisons.isEmpty()))
            run.phase = "Review complete";
        if (target.status.equals("Pending comparison")) {
            target.status = "Review closed";
            target.detail =
                    "Saved comparisons were closed. Only changes you applied to project files are"
                            + " kept.";
        }
        changed();
    }

    public void cleanup(boolean allCompleted) {
        List<Run> removed = new ArrayList<>();
        synchronized (this) {
            long cutoff =
                    System.currentTimeMillis() - Math.max(1, state.retentionDays) * 86_400_000L;
            state.runs.removeIf(
                    r -> {
                        boolean remove =
                                !r.active()
                                        && (allCompleted || r.finished < cutoff)
                                        && r.targets.stream()
                                                .allMatch(t -> t.comparisons.isEmpty());
                        if (remove) removed.add(r);
                        return remove;
                    });
            changed();
        }
        ApplicationManager.getApplication()
                .executeOnPooledThread(
                        () -> {
                            Path managed =
                                    Path.of(PathManager.getSystemPath(), "evosuite", "runs")
                                            .toAbsolutePath()
                                            .normalize();
                            for (Run r : removed)
                                for (Target t : r.targets)
                                    if (!t.reports.isBlank()) {
                                        Path folder =
                                                Path.of(t.reports)
                                                        .toAbsolutePath()
                                                        .normalize()
                                                        .getParent();
                                        if (folder != null
                                                && folder.startsWith(managed)
                                                && !folder.equals(managed))
                                            try {
                                                GenerationRun.deleteTree(folder);
                                            } catch (java.io.IOException ignored) {
                                            }
                                    }
                        });
    }

    public synchronized void retention(int days) {
        state.retentionDays = Math.max(1, Math.min(3650, days));
        changed();
    }
}
