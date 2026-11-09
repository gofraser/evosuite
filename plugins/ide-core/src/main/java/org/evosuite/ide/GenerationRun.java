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
package org.evosuite.ide;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * Runs a target into an isolated directory: a previous suite can never be mistaken for new output.
 */
public final class GenerationRun {
    private GenerationRun() {}

    public record Result(int exitCode, Path suite, Path scaffolding, SeedingReport seeding) {
        public boolean produced() {
            return Files.isRegularFile(suite);
        }
    }

    public static Result generate(
            GenerationTarget target,
            GenerationOptions options,
            JavaInstallation java,
            Path engine,
            List<String> classpath,
            Path basedir,
            Path staging,
            ProgressParser.Listener listener,
            Map<String, String> environment,
            BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        options = options.copy();
        if (options.randomSeed.isBlank())
            options.randomSeed = Long.toString(ThreadLocalRandom.current().nextLong());
        Files.createDirectories(staging);
        Path output = staging.resolve("tests"), reports = staging.resolve("reports");
        Files.createDirectories(output);
        Files.createDirectories(reports);
        EvoSuiteCommand command =
                new EvoSuiteCommand(
                        java.executable().toString(),
                        engine.toString(),
                        target.className(),
                        classpath,
                        basedir.toFile(),
                        output.toFile(),
                        reports.toFile(),
                        options,
                        target.methods());
        SeedingReport seeding =
                target.extendsTests() ? new SeedingReport(target.seedNames()) : null;
        if (target.extendsTests()) {
            Path seeds = staging.resolve("seeds");
            for (GenerationTarget.Seed seed : target.seeds()) {
                Path copy = seeds.resolve(seed.className().replace('.', '/') + ".java");
                Files.createDirectories(copy.getParent());
                if (Files.exists(copy)) {
                    if (Files.mismatch(seed.sourceFile().toPath(), copy) != -1)
                        throw new IOException("Selected tests have the same class name: " + seed.className());
                } else {
                    Files.copy(seed.sourceFile().toPath(), copy);
                }
            }
            command.seededBy(
                    target.seeds().stream().map(GenerationTarget.Seed::className).toList(),
                    seeds.toFile());
        }
        List<String> args = command.toArguments(java.version());
        // EvoSuite's client launcher consults JAVA_HOME. Keep it on the selected engine JDK
        // instead of inheriting a possibly different JDK from the IDE's environment.
        Map<String, String> launchEnvironment = new HashMap<>(environment);
        launchEnvironment.put("JAVA_HOME", java.executable().getParent().getParent().toString());
        listener.line(EvoSuiteCommand.forDisplay(args));
        Path argfile = staging.resolve("arguments.txt");
        int exit;
        try {
            List<String> launch =
                    EvoSuiteCommand.fitCommandLine(
                            args,
                            argfile.toFile(),
                            java.readsArgumentFiles()
                                    ? EvoSuiteCommand.MAX_INLINE_LENGTH
                                    : Integer.MAX_VALUE);
            exit =
                    EvoSuiteProcess.run(
                            launch,
                            basedir.toFile(),
                            launchEnvironment,
                            new ProgressParser.Listener() {
                                public void line(String line) {
                                    if (seeding != null) seeding.accept(line);
                                    listener.line(line);
                                }

                                public void progress(int search, int coverage) {
                                    listener.progress(search, coverage);
                                }
                            },
                            cancelled);
        } finally {
            Files.deleteIfExists(argfile);
        }
        return output(exit, output, target, options.testSuffix, seeding);
    }

    public static Result output(int exitCode, Path folder, GenerationTarget target, String suffix,
                                SeedingReport seeding) {
        Path suite = EvoSuiteCommand.suiteFile(folder.toFile(), target.className(), suffix).toPath();
        String name = suite.getFileName().toString();
        Path scaffold = suite.resolveSibling(name.substring(0, name.length() - ".java".length())
                + "_scaffolding.java");
        return new Result(exitCode, suite, scaffold, seeding);
    }

    public static int measureCoverage(
            GenerationTarget target,
            GenerationOptions options,
            JavaInstallation java,
            Path engine,
            List<String> classpath,
            Path basedir,
            Path staging,
            ProgressParser.Listener listener,
            Map<String, String> environment,
            BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Files.createDirectories(staging.resolve("reports"));
        var command =
                new EvoSuiteCommand(
                                java.executable().toString(),
                                engine.toString(),
                                target.className(),
                                classpath,
                                basedir.toFile(),
                                staging.resolve("tests").toFile(),
                                staging.resolve("reports").toFile(),
                                options)
                        .seededBy(
                                target.seeds().stream()
                                        .map(GenerationTarget.Seed::className)
                                        .toList(),
                                basedir.toFile())
                        .measureCoverage();
        List<String> args = command.toArguments(java.version());
        listener.line(EvoSuiteCommand.forDisplay(args));
        Map<String, String> env = new HashMap<>(environment);
        env.put("JAVA_HOME", java.executable().getParent().getParent().toString());
        Path argfile = staging.resolve("arguments.txt");
        try {
            return EvoSuiteProcess.run(
                    EvoSuiteCommand.fitCommandLine(
                            args,
                            argfile.toFile(),
                            java.readsArgumentFiles()
                                    ? EvoSuiteCommand.MAX_INLINE_LENGTH
                                    : Integer.MAX_VALUE),
                    basedir.toFile(),
                    env,
                    listener,
                    cancelled);
        } finally {
            Files.deleteIfExists(argfile);
        }
    }

    public static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var files = Files.walk(path)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(file);
        }
    }
}
