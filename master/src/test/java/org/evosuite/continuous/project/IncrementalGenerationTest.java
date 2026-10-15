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
package org.evosuite.continuous.project;

import com.examples.with.different.packagename.continuous.ExtendingNoBranches;
import com.examples.with.different.packagename.continuous.ImplementsDefaultMethod;
import com.examples.with.different.packagename.continuous.MoreBranches;
import com.examples.with.different.packagename.continuous.NoBranches;
import com.examples.with.different.packagename.continuous.SomeBranches;
import com.examples.with.different.packagename.continuous.WithDefaultMethod;
import com.examples.with.different.packagename.continuous.WithNestedClasses;
import org.apache.commons.io.IOUtils;
import org.evosuite.Properties;
import org.evosuite.Properties.AvailableSchedule;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.continuous.CtgConfiguration;
import org.evosuite.continuous.job.JobDefinition;
import org.evosuite.continuous.job.JobScheduler;
import org.evosuite.continuous.persistency.ClassFingerprints;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IncrementalGenerationTest {

    @TempDir
    Path tmp;

    @BeforeAll
    public static void initClass() {
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
    }

    @AfterEach
    public void reset() {
        Properties.CTG_INCREMENTAL = true;
        Properties.CTG_SCHEDULE = AvailableSchedule.BUDGET;
        Properties.CTG_HISTORY_FILE = null;
        Properties.CTG_SELECTED_CUTS_ARE_FILTER = false;
    }

    @Test
    public void fingerprintDependsOnTheEvoSuiteBuild() {
        ClassFingerprints a = new ClassFingerprints(tmp.toFile(), "1.0@abc");
        ClassFingerprints b = new ClassFingerprints(tmp.toFile(), "1.1@def");

        String first = a.fingerprint(SomeBranches.class);
        assertNotNull(first);
        assertEquals(first, a.fingerprint(SomeBranches.class));
        assertNotEquals(first, b.fingerprint(SomeBranches.class));
        assertNotEquals(first, a.fingerprint(MoreBranches.class));
    }

    @Test
    public void fingerprintsSurviveASaveAndForgetRemovedClasses() throws Exception {
        ClassFingerprints fingerprints = new ClassFingerprints(tmp.toFile(), "v");
        fingerprints.record(SomeBranches.class.getName(), "aaa");
        fingerprints.record(MoreBranches.class.getName(), "bbb");
        fingerprints.retainOnly(Collections.singletonList(SomeBranches.class.getName()));
        fingerprints.save();

        ClassFingerprints loaded = new ClassFingerprints(tmp.toFile(), "v");
        assertFalse(loaded.hasChanged(SomeBranches.class.getName(), "aaa"));
        assertTrue(loaded.hasChanged(SomeBranches.class.getName(), "zzz"));
        assertTrue(loaded.hasChanged(MoreBranches.class.getName(), "bbb"));
        assertTrue(loaded.hasChanged(SomeBranches.class.getName(), null));
    }

    @Test
    public void unchangedClassesWithTestsAreNotTestedAgain() {
        ClassFingerprints fingerprints = new ClassFingerprints(tmp.toFile(), "v");
        fingerprints.record(SomeBranches.class.getName(), fingerprints.fingerprint(SomeBranches.class));
        fingerprints.record(NoBranches.class.getName(), fingerprints.fingerprint(NoBranches.class));
        fingerprints.record(MoreBranches.class.getName(), "outdated");

        // NoBranches is unchanged too, but its tests were lost
        ProjectStaticData data = analyze(fingerprints, SomeBranches.class.getName()::equals,
                SomeBranches.class, NoBranches.class, MoreBranches.class);

        assertFalse(data.getClassInfo(SomeBranches.class.getName()).isToTest());
        assertTrue(data.getClassInfo(NoBranches.class.getName()).isToTest());
        assertTrue(data.getClassInfo(MoreBranches.class.getName()).isToTest());
        assertEquals(2, data.getTotalNumberOfCUTsToTest());
        assertEquals(3, data.getTotalNumberOfTestableCUTs());
    }

    @Test
    public void withoutIncrementalEveryClassIsTested() {
        Properties.CTG_INCREMENTAL = false;
        ClassFingerprints fingerprints = new ClassFingerprints(tmp.toFile(), "v");
        fingerprints.record(SomeBranches.class.getName(), fingerprints.fingerprint(SomeBranches.class));

        ProjectStaticData data = analyze(fingerprints, name -> true, SomeBranches.class);

        assertTrue(data.getClassInfo(SomeBranches.class.getName()).isToTest());
        // "changed" lets the new suite replace the old one without a coverage comparison, as it always did
        // outside HISTORY; an unchanged class that is tested anyway must still get its new suite
        assertTrue(data.getClassInfo(SomeBranches.class.getName()).hasChanged());
    }

    @Test
    public void aClassFilterKeepsIncrementalGenerationButAnExplicitSelectionDoesNot() throws Exception {
        ClassFingerprints fingerprints = new ClassFingerprints(tmp.toFile(), "v");
        fingerprints.record(SomeBranches.class.getName(), fingerprints.fingerprint(SomeBranches.class));
        String target = new File(SomeBranches.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getAbsolutePath();
        String[] cuts = {SomeBranches.class.getName()};

        Properties.CTG_SELECTED_CUTS_ARE_FILTER = true;
        ProjectAnalyzer filtered = new ProjectAnalyzer(target, SomeBranches.class.getPackage().getName(), cuts);
        filtered.useChangeDetection(fingerprints, name -> true);
        assertFalse(filtered.analyze().getClassInfo(SomeBranches.class.getName()).isToTest(),
                "classes selected by classIncludes/classExcludes were regenerated although unchanged");

        Properties.CTG_SELECTED_CUTS_ARE_FILTER = false;
        ProjectAnalyzer explicit = new ProjectAnalyzer(target, SomeBranches.class.getPackage().getName(), cuts);
        explicit.useChangeDetection(fingerprints, name -> true);
        assertTrue(explicit.analyze().getClassInfo(SomeBranches.class.getName()).isToTest());
    }

    @Test
    public void aHistoryFileDecidesWhatChanged() throws Exception {
        Path history = tmp.resolve("history");
        Files.write(history, Collections.singletonList("M\tsrc/main/java/org/other/Unrelated.java"),
                StandardCharsets.UTF_8);
        Properties.CTG_HISTORY_FILE = history.toString();
        Properties.CTG_SCHEDULE = AvailableSchedule.HISTORY;

        // no fingerprints recorded, so by its fingerprint the class would be new
        ProjectStaticData data = analyze(new ClassFingerprints(tmp.toFile(), "v"), name -> true,
                SomeBranches.class);

        assertTrue(data.hasHistoryFile(), "history file not loaded");
        assertFalse(data.getClassInfo(SomeBranches.class.getName()).hasChanged(),
                "schedule=" + Properties.CTG_SCHEDULE + " historyFile=" + Properties.CTG_HISTORY_FILE);
    }

    @Test
    public void editingANestedOrAnonymousClassChangesTheOuterClass() throws Exception {
        Path classes = copyClassFiles(WithNestedClasses.class, "WithNestedClasses", "WithNestedClasses$1",
                "WithNestedClasses$Helper");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, null)) {
            Class<?> outer = loader.loadClass(WithNestedClasses.class.getName());
            ClassFingerprints fingerprints = new ClassFingerprints(tmp.toFile(), "v");
            String before = fingerprints.fingerprint(outer);

            touch(classes, WithNestedClasses.class, "WithNestedClasses$1");
            String anonymousEdited = fingerprints.fingerprint(outer);
            assertNotEquals(before, anonymousEdited);

            touch(classes, WithNestedClasses.class, "WithNestedClasses$Helper");
            assertNotEquals(anonymousEdited, fingerprints.fingerprint(outer));
        }
    }

    @Test
    public void editingADefaultMethodChangesTheImplementingClass() throws Exception {
        Path classes = copyClassFiles(WithDefaultMethod.class, "WithDefaultMethod", "ImplementsDefaultMethod");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, null)) {
            Class<?> implementing = loader.loadClass(ImplementsDefaultMethod.class.getName());
            ClassFingerprints fingerprints = new ClassFingerprints(tmp.toFile(), "v");
            String before = fingerprints.fingerprint(implementing);

            touch(classes, WithDefaultMethod.class, "WithDefaultMethod");
            assertNotEquals(before, fingerprints.fingerprint(implementing));
        }
    }

    private Path copyClassFiles(Class<?> anyInPackage, String... simpleNames) throws Exception {
        Path source = Paths.get(anyInPackage.getProtectionDomain().getCodeSource().getLocation().toURI());
        String packagePath = anyInPackage.getPackage().getName().replace('.', '/');
        Path target = tmp.resolve("classes");
        Files.createDirectories(target.resolve(packagePath));
        for (String name : simpleNames) {
            Files.copy(source.resolve(packagePath).resolve(name + ".class"),
                    target.resolve(packagePath).resolve(name + ".class"));
        }
        return target;
    }

    /**
     * Stand-in for recompiling an edited class: its class file changes, while the loaded classes do not.
     */
    private static void touch(Path classes, Class<?> anyInPackage, String simpleName) throws Exception {
        Path file = classes.resolve(anyInPackage.getPackage().getName().replace('.', '/'))
                .resolve(simpleName + ".class");
        Files.write(file, new byte[]{0}, StandardOpenOption.APPEND);
    }

    @Test
    public void fingerprintCoversTheSuperclass() throws Exception {
        String version = "v";
        String fingerprint = new ClassFingerprints(tmp.toFile(), version).fingerprint(ExtendingNoBranches.class);

        // the same digest over the subclass alone, which is what a change to NoBranches would not affect
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(version.getBytes(StandardCharsets.UTF_8));
        digest.update(ExtendingNoBranches.class.getName().getBytes(StandardCharsets.UTF_8));
        String resource = ExtendingNoBranches.class.getName().replace('.', '/') + ".class";
        try (InputStream in = ExtendingNoBranches.class.getClassLoader().getResourceAsStream(resource)) {
            digest.update(IOUtils.toByteArray(in));
        }
        StringBuilder subclassOnly = new StringBuilder();
        for (byte b : digest.digest()) {
            subclassOnly.append(String.format("%02x", b));
        }

        assertNotEquals(subclassOnly.toString(), fingerprint);
    }

    @Test
    public void scheduleOnlyHasJobsForClassesToTest() {
        ClassFingerprints fingerprints = new ClassFingerprints(tmp.toFile(), "v");
        fingerprints.record(MoreBranches.class.getName(), fingerprints.fingerprint(MoreBranches.class));

        ProjectStaticData data = analyze(fingerprints, name -> true,
                SomeBranches.class, NoBranches.class, MoreBranches.class);

        CtgConfiguration conf = new CtgConfiguration(1400, 2, 2, 1, false, AvailableSchedule.BUDGET);
        List<JobDefinition> jobs = new JobScheduler(data, conf).createNewSchedule();

        List<String> cuts = jobs.stream().map(job -> job.cut).collect(Collectors.toList());
        assertEquals(2, cuts.size());
        assertFalse(cuts.contains(MoreBranches.class.getName()));
        int sum = jobs.stream().mapToInt(job -> job.seconds).sum();
        assertTrue(sum <= 2 * 2 * 60, "budget exceeded: " + sum);
    }

    @Test
    public void historyFileMatchesWholePackageSegments() throws Exception {
        Path history = tmp.resolve("history");
        Files.write(history, Arrays.asList(
                "M\t/work/src/main/java/org/abar/Foo.java",
                "A\tsrc/main/java/com/example/Outer.java"), StandardCharsets.UTF_8);
        Properties.CTG_HISTORY_FILE = history.toString();

        ProjectStaticData data = new ProjectStaticData();
        data.initializeLocalHistory();

        assertTrue(data.hasHistoryFile());
        assertTrue(data.hasChanged("org.abar.Foo.java"));
        assertFalse(data.hasChanged("bar.Foo.java"), "a change to abar/Foo.java is not one to bar/Foo.java");
        assertTrue(data.hasChanged("com.example.Outer.java"));
    }

    @Test
    public void nestedClassesBelongToTheSourceFileOfTheirOuterClass() {
        assertEquals(IncrementalGenerationTest.class.getName() + ".java",
                ProjectAnalyzer.sourceFileOf(Nested.class));
    }

    private static class Nested {
    }

    private ProjectStaticData analyze(ClassFingerprints fingerprints,
                                      java.util.function.Predicate<String> hasTests, Class<?>... classes) {
        String[] cuts = Arrays.stream(classes).map(Class::getName).toArray(String[]::new);
        ProjectAnalyzer analyzer = new ProjectAnalyzer(cuts);
        analyzer.useChangeDetection(fingerprints, hasTests);
        return analyzer.analyze();
    }
}
