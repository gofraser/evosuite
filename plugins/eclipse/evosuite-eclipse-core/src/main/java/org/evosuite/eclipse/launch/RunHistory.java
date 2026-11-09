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

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.*;
import org.evosuite.eclipse.Activator;
import org.evosuite.eclipse.ui.SuiteComparison;

import java.io.*;
import java.nio.file.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Persistent per-target results, diagnostics and replay profiles, outside shareable project
 * settings.
 */
public final class RunHistory {
    public static final class Entry {
        public final String id;
        public final Properties values = new Properties();
        private Job job;

        Entry(String id) {
            this.id = id;
        }

        public String get(String key) {
            return values.getProperty(key, "");
        }

        public long number(String key) {
            try {
                return Long.parseLong(get(key));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        public boolean active() {
            return number("finished") == 0;
        }

        public IJavaProject project() {
            return JavaCore.create(
                    ResourcesPlugin.getWorkspace().getRoot().getProject(get("project")));
        }

        public IFile output() {
            return get("output").isEmpty()
                    ? null
                    : ResourcesPlugin.getWorkspace()
                            .getRoot()
                            .getFile(IPath.fromPortableString(get("output")));
        }

        public GenerationOptions options() {
            Map<String, String> saved = new LinkedHashMap<>();
            SettingKeys.all().forEach(k -> saved.put(k, get("setting." + k)));
            GenerationOptions options = SettingKeys.resolve(saved);
            Map<String, String> pom = new LinkedHashMap<>();
            values.stringPropertyNames().stream()
                    .filter(k -> k.startsWith("pom."))
                    .forEach(k -> pom.put(k.substring(4), get(k)));
            options.pomProperties = Map.copyOf(pom);
            options.pomExtraArguments = get("pomArgs");
            options.llmApiKeyServerId = get("keyServerId");
            return options;
        }

        public GenerationTarget target() {
            List<String> methods =
                    get("methods").isBlank() ? List.of() : List.of(get("methods").split("\n"));
            List<GenerationTarget.Seed> seeds = new ArrayList<>();
            for (int i = 0; !get("seedClass." + i).isEmpty(); i++)
                seeds.add(
                        new GenerationTarget.Seed(
                                get("seedClass." + i), new File(get("seedRoot." + i))));
            return new GenerationTarget(get("class"), methods, seeds);
        }

        public List<SuiteComparison.Pair> comparisons() {
            List<SuiteComparison.Pair> pairs = new ArrayList<>();
            for (int i = 0; !get("comparison." + i).isEmpty(); i++) {
                try {
                    IFile file =
                            ResourcesPlugin.getWorkspace()
                                    .getRoot()
                                    .getFile(IPath.fromPortableString(get("comparison." + i)));
                    Path base = directory(this);
                    Path obsolete = base.resolve("comparison-" + i + ".obsolete");
                    pairs.add(new SuiteComparison.Pair(file,
                            Files.readString(base.resolve("comparison-" + i + ".java")),
                            Files.isRegularFile(obsolete) ? Files.readString(obsolete) : null));
                } catch (IOException e) {
                    Activator.logError("Cannot reopen comparison", e);
                }
            }
            return pairs;
        }

        public void cancel() {
            if (job != null) job.cancel();
        }
    }

    private static final Pattern COVERAGE =
            Pattern.compile("Total coverage:\\s*([0-9]+(?:[.,][0-9]+)?)\\s*%");
    private static final Pattern API_KEY =
            Pattern.compile("(-Dllm_api_key=)(\"[^\"]*\"|'[^']*'|[^\\s]+)");

    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static List<Entry> entries;

    private RunHistory() {}

    private static Path root() {
        return Activator.getDefault().getStateLocation().append("runs").toFile().toPath();
    }

    public static Path directory(Entry entry) {
        return root().resolve(entry.id);
    }

    public static synchronized List<Entry> entries() {
        if (entries == null) {
            entries = new ArrayList<>();
            try {
                Files.createDirectories(root());
                try (var paths = Files.list(root())) {
                    for (Path dir : paths.toList()) {
                        Path record = dir.resolve("run.properties");
                        if (!Files.isRegularFile(record)) continue;
                        Entry entry = new Entry(dir.getFileName().toString());
                        try (InputStream in = Files.newInputStream(record)) {
                            entry.values.load(in);
                        }
                        if (entry.active()) {
                            entry.values.setProperty("status", "Interrupted");
                            entry.values.setProperty(
                                    "finished", Long.toString(System.currentTimeMillis()));
                        }
                        entries.add(entry);
                    }
                }
                entries.sort(Comparator.comparingLong((Entry e) -> e.number("created")).reversed());
            } catch (IOException e) {
                Activator.logError("Cannot read EvoSuite history", e);
            }
        }
        return List.copyOf(entries);
    }

    public static synchronized boolean hasActiveSelection(
            IJavaProject project, List<GenerationTarget> targets, boolean coverage) {
        Map<Job, List<GenerationTarget>> requests = new LinkedHashMap<>();
        for (Entry entry : entries()) {
            if (entry.active()
                    && entry.job != null
                    && entry.get("project").equals(project.getElementName())
                    && Boolean.parseBoolean(entry.get("coverageOnly")) == coverage)
                requests.computeIfAbsent(entry.job, j -> new ArrayList<>()).add(entry.target());
        }
        return requests.values().stream()
                .anyMatch(
                        selected ->
                                new LinkedHashSet<>(selected).equals(new LinkedHashSet<>(targets)));
    }

    public static synchronized Entry create(
            IJavaProject project,
            GenerationTarget target,
            GenerationOptions options,
            Job job,
            boolean coverage) {
        entries();
        Entry entry = new Entry(UUID.randomUUID().toString());
        entry.job = job;
        entry.values.setProperty("created", Long.toString(System.currentTimeMillis()));
        entry.values.setProperty("project", project.getElementName());
        entry.values.setProperty("class", target.className());
        entry.values.setProperty("methods", String.join("\n", target.methods()));
        entry.values.setProperty("coverageOnly", Boolean.toString(coverage));
        for (int i = 0; i < target.seeds().size(); i++) {
            entry.values.setProperty("seedClass." + i, target.seeds().get(i).className());
            entry.values.setProperty(
                    "seedRoot." + i, target.seeds().get(i).sourceRoot().getAbsolutePath());
        }
        entry.values.setProperty("status", "Queued");
        settings(entry, options);
        entries.add(0, entry);
        if (job != null)
            job.addJobChangeListener(
                    new org.eclipse.core.runtime.jobs.JobChangeAdapter() {
                        @Override
                        public void done(org.eclipse.core.runtime.jobs.IJobChangeEvent event) {
                            if (entry.active())
                                RunHistory.finish(
                                        entry,
                                        event.getResult() != null
                                                        && event.getResult().getSeverity()
                                                                == IStatus.CANCEL
                                                ? "Cancelled"
                                                : "Run ended",
                                        event.getResult() == null
                                                ? "Run ended"
                                                : event.getResult().getMessage());
                        }
                    });
        save(entry);
        prune(false);
        return entry;
    }

    public static synchronized void settings(Entry entry, GenerationOptions options) {
        for (String key : SettingKeys.all()) {
            String value = SettingKeys.value(options, key);
            entry.values.setProperty(
                    "setting." + key, key.equals("extraArguments") ? safeArguments(value) : value);
        }
        options.pomProperties.forEach(
                (key, value) -> {
                    if (!key.equals("llm_api_key")) entry.values.setProperty("pom." + key, value);
                });
        entry.values.setProperty("pomArgs", safeArguments(options.pomExtraArguments));
        entry.values.setProperty("keyServerId", options.llmApiKeyServerId);
        save(entry);
    }

    private static String safeArguments(String value) {
        return EvoSuiteCommand.splitArguments(value).stream()
                .filter(a -> !EvoSuiteCommand.credentialArgument(a))
                .map(a -> "\"" + a.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(" "));
    }

    public static synchronized void update(Entry entry, String key, Object value) {
        entry.values.setProperty(key, String.valueOf(value));
        changed();
    }

    public static void line(Entry entry, String line) {
        try {
            Files.createDirectories(directory(entry));
            Files.writeString(
                    directory(entry).resolve("console.log"),
                    API_KEY.matcher(line).replaceAll("$1****") + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            Activator.logError("Cannot retain EvoSuite output", e);
        }
        var total = COVERAGE.matcher(line);
        if (total.find()) update(entry, "coverage", total.group(1).replace(',', '.') + "%");
        if (line.startsWith("* ")) update(entry, "phase", line.substring(2));
    }

    public static synchronized void finish(Entry entry, String status, String detail) {
        update(entry, "status", status);
        update(entry, "detail", detail);
        update(entry, "finished", System.currentTimeMillis());
        save(entry);
    }

    public static synchronized void comparisons(Entry entry, List<SuiteComparison.Pair> pairs) {
        try {
            Files.createDirectories(directory(entry));
            for (int i = 0; i < pairs.size(); i++) {
                entry.values.setProperty(
                        "comparison." + i,
                        pairs.get(i).existing().getFullPath().toPortableString());
                Files.writeString(
                        directory(entry).resolve("comparison-" + i + ".java"),
                        pairs.get(i).generatedSource());
                if (pairs.get(i).obsoleteSource() != null) {
                    Files.writeString(directory(entry).resolve("comparison-" + i + ".obsolete"), pairs.get(i).obsoleteSource());
                }
            }
            save(entry);
        } catch (IOException e) {
            Activator.logError("Cannot retain comparisons", e);
        }
    }

    public static void reviewed(Entry entry) {
        List<SuiteComparison.Pair> pending = entry.comparisons();
        java.util.Set<IFile> acceptedRuntimeFree = new java.util.HashSet<>();
        for (SuiteComparison.Pair pair : pending) {
            try {
                if (pair.existing().exists()) {
                    var buffer =
                            FileBuffers.getTextFileBufferManager()
                                    .getTextFileBuffer(
                                            pair.existing().getFullPath(),
                                            LocationKind.IFILE);
                    String source;
                    if (buffer != null) source = buffer.getDocument().get();
                    else
                        try (var input = pair.existing().getContents()) {
                            source = new String(input.readAllBytes(), pair.existing().getCharset());
                        }
                    String marker = GeneratedSuiteRegistry.marker(source);
                    if (!marker.isEmpty()
                            && marker.equals(GeneratedSuiteRegistry.marker(pair.generatedSource()))) {
                        GeneratedSuiteRegistry.record(pair.existing().getLocation().toFile(), source);
                        if ((buffer == null || !buffer.isDirty()) && source.equals(pair.generatedSource())) {
                            acceptedRuntimeFree.add(pair.existing());
                        }
                    }
                }
            } catch (IOException | CoreException e) {
                Activator.logError("Cannot recognize accepted suite", e);
            }
        }
        for (SuiteComparison.Pair pair : pending) {
            if (pair.obsoleteSource() == null) continue;
            IFile helper = pair.existing();
            IFile suite = helper.getParent().getFile(IPath.fromPortableString(
                    helper.getName().replaceFirst("_scaffolding\\.java$", ".java")));
            if (!acceptedRuntimeFree.contains(suite)) continue;
            try {
                ResourcesPlugin.getWorkspace().run(monitor -> {
                    helper.refreshLocal(IResource.DEPTH_ZERO, monitor);
                    var buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(
                            helper.getFullPath(), LocationKind.IFILE);
                    if (helper.exists() && TestGenerationJob.overwriteProblem(helper, buffer) == null
                            && pair.obsoleteSource().equals(TestGenerationJob.contents(helper))) {
                        helper.delete(IResource.KEEP_HISTORY, monitor);
                    }
                }, helper.getProject(), IWorkspace.AVOID_UPDATE, null);
            } catch (CoreException e) {
                Activator.logError("Cannot remove obsolete scaffolding", e);
            }
        }
        synchronized (RunHistory.class) {
            entry.values.stringPropertyNames().stream()
                    .filter(k -> k.startsWith("comparison."))
                    .toList()
                    .forEach(entry.values::remove);
            save(entry);
        }
    }

    public static int retentionDays() {
        return Activator.getDefault().getPreferenceStore().getInt("runRetentionDays") > 0
                ? Activator.getDefault().getPreferenceStore().getInt("runRetentionDays")
                : 30;
    }

    public static void retentionDays(int days) {
        Activator.getDefault()
                .getPreferenceStore()
                .setValue("runRetentionDays", Math.max(1, Math.min(3650, days)));
        prune(false);
    }

    public static synchronized void prune(boolean clearCompleted) {
        entries();
        long cutoff = System.currentTimeMillis() - retentionDays() * 86_400_000L;
        entries.removeIf(
                entry -> {
                    if (entry.active()
                            || !entry.get("comparison.0").isEmpty()
                            || (!clearCompleted && entry.number("created") >= cutoff)) return false;
                    try (var paths = Files.walk(directory(entry))) {
                        for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                            Files.deleteIfExists(path);
                        return true;
                    } catch (IOException e) {
                        Activator.logError("Cannot remove old run diagnostics", e);
                        return false;
                    }
                });
        changed();
    }

    private static void save(Entry entry) {
        try {
            Files.createDirectories(directory(entry));
            try (OutputStream out =
                    Files.newOutputStream(directory(entry).resolve("run.properties"))) {
                entry.values.store(out, "EvoSuite run (credentials excluded)");
            }
        } catch (IOException e) {
            Activator.logError("Cannot save EvoSuite history", e);
        }
        changed();
    }

    public static void listen(Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void unlisten(Runnable listener) {
        LISTENERS.remove(listener);
    }

    private static void changed() {
        LISTENERS.forEach(Runnable::run);
    }
}
