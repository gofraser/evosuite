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

import org.evosuite.Properties;
import org.evosuite.Properties.AvailableSchedule;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHacker;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.classpath.ResourceList;
import org.evosuite.continuous.job.schedule.HistorySchedule;
import org.evosuite.continuous.persistency.ClassFingerprints;
import org.evosuite.continuous.project.ProjectStaticData.ClassInfo;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.junit.CoverageAnalysis;
import org.evosuite.runtime.sandbox.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.function.Predicate;

/**
 * <p>This class is used to analyze and gather all the
 * static information of the target project.</p>
 *
 * <p>To be useful, this analysis does not need to be 100% precise,
 * as we use the generated statistics <i>only</i> for heuristics</p>
 *
 * <p>Note: this class assumes the classpath is properly set.</p>
 *
 * @author arcuri
 */
public class ProjectAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(ProjectAnalyzer.class);

    /**
     * the folder/jar where to find the .class files used as CUTs.
     */
    private final String target;

    /**
     * package prefix to select a subset of classes on classpath/target to define
     * what to run CTG on.
     */
    private final String prefix;

    private final transient Set<String> cutsToAnalyze;

    /**
     * When specifying a set of CUTs, still check if they do exist (eg scan folder to search for
     * them), instead of justing using them directly (and get errors later).
     */
    private final boolean validateCutsToAnalyze;

    /**
     * Fingerprints from previous runs, to tell which classes changed; {@code null} treats all as changed.
     */
    private ClassFingerprints fingerprints = null;

    /**
     * Tells whether a class already has generated tests from a previous run.
     */
    private Predicate<String> hasTests = className -> false;

    /**
     * Main constructor.
     *
     * @param target target folder
     * @param prefix package prefix
     * @param cuts classes to analyze
     */
    public ProjectAnalyzer(String target, String prefix, String[] cuts) {
        super();
        this.target = target;
        this.prefix = prefix == null ? "" : prefix;
        this.validateCutsToAnalyze = true;

        if (cuts == null) {
            this.cutsToAnalyze = null;
        } else {
            this.cutsToAnalyze = new LinkedHashSet<>();
            for (String s : cuts) {
                if (s != null && !s.isEmpty()) {
                    cutsToAnalyze.add(s.trim());
                }
            }
        }
    }

    /**
     * Instead of scanning for classes in the given target, directly specify
     * the class names the project is composed by.
     *
     * <p>Note: this constructor is mainly meant for unit tests.</p>
     *
     * @param cuts classes to analyze
     */
    public ProjectAnalyzer(String[] cuts) throws NullPointerException {
        super();
        if (cuts == null) {
            throw new NullPointerException("Input array cannot be null");
        }
        this.target = null;
        this.prefix = null;
        this.validateCutsToAnalyze = false;
        this.cutsToAnalyze = new LinkedHashSet<>();
        cutsToAnalyze.addAll(Arrays.asList(cuts));
    }

    /**
     * Detect which classes changed since their tests were generated. In an incremental run
     * (see {@link Properties#CTG_INCREMENTAL}) only those, and classes without tests, are tested.
     *
     * @param fingerprints the fingerprints recorded in previous runs
     * @param hasTests     whether a class already has generated tests
     */
    public void useChangeDetection(ClassFingerprints fingerprints, Predicate<String> hasTests) {
        this.fingerprints = fingerprints;
        this.hasTests = hasTests;
    }

    /**
     * Classes the user selected explicitly are tested even if they did not change, unless the
     * selection is just a filter on the project's classes (see {@link Properties#CTG_SELECTED_CUTS_ARE_FILTER}).
     */
    private boolean cutsSelectedExplicitly() {
        return cutsToAnalyze != null && validateCutsToAnalyze && !Properties.CTG_SELECTED_CUTS_ARE_FILTER;
    }

    private Collection<String> getCutsToAnalyze() {

        if (cutsToAnalyze != null && !validateCutsToAnalyze) {
            // this is mainly in test cases
            return cutsToAnalyze;
        }

        Set<String> suts = null;

        if (target != null) {
            if (!target.contains(File.pathSeparator)) {
                suts = ResourceList.getInstance(TestGenerationContext.getInstance()
                        .getClassLoaderForSUT())
                        .getAllClasses(target, prefix, false);
            } else {
                suts = new LinkedHashSet<>();
                for (String element : target.split(File.pathSeparator)) {
                    suts.addAll(ResourceList.getInstance(TestGenerationContext.getInstance()
                            .getClassLoaderForSUT())
                            .getAllClasses(element, prefix, false));
                }
            }
        } else {
            /*
             * if no target specified, just grab everything on SUT classpath
             */
            suts = ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                    .getAllClasses(ClassPathHandler.getInstance().getTargetProjectClasspath(),
                            prefix, false);
        }

        List<String> cuts = new LinkedList<>();

        for (String className : suts) {

            if (cutsToAnalyze != null && !cutsToAnalyze.contains(className)) {
                /*
                 * Note: if this is happens, it is not necessarily an error.
                 * For example, this will happen when CTG is run on a multi-module
                 * maven project
                 */
                continue;
            }

            try {
                Class<?> clazz = ClassPathHacker.getContinuousClassLoader().loadClass(className);
                if (!CoverageAnalysis.isTest(clazz)) {
                    cuts.add(className);
                }
            } catch (ClassNotFoundException e) {
                logger.error("" + e, e);
            } catch (ExceptionInInitializerError | NoClassDefFoundError | UnsatisfiedLinkError e) {
                /*
                 * TODO: for now we skip it, but at a certain point
                 * we should able to handle it, especially if it
                 * is due to static state initialization
                 */
                logger.warn("Cannot initialize class: " + className);
            }

        }
        return cuts;

    }

    /**
     * Analyze the classes in the given target.
     *
     * @return project data
     */
    public ProjectStaticData analyze() {

        ProjectStaticData data = new ProjectStaticData();
        if (Properties.CTG_SCHEDULE.equals(AvailableSchedule.HISTORY)) {
            data.loadPreviousResults();
        }
        if (Properties.CTG_HISTORY_FILE != null) {
            data.initializeLocalHistory();
        }

        for (String className : getCutsToAnalyze()) {
            Class<?> theClass = null;
            int numberOfBranches = -1;
            boolean hasCode = false;

            Properties.TARGET_CLASS = className;
            InstrumentingClassLoader instrumenting = new InstrumentingClassLoader();

            BranchPool.getInstance(instrumenting).reset();

            try {
                /*
                 * to access number of branches, we need to use
                 * instrumenting class loader. But loading a class would
                 * execute its static code, and so we need to
                 * use a security manager.
                 */
                Sandbox.goingToExecuteUnsafeCodeOnSameThread();
                instrumenting.loadClass(className);

                numberOfBranches = BranchPool.getInstance(instrumenting).getBranchCounter();
                hasCode = (numberOfBranches > 0)
                        || (BranchPool.getInstance(instrumenting).getBranchlessMethods().size() > 0);

                /*
                 * just to avoid possible issues with instrumenting classloader
                 */
                theClass = ClassPathHacker.getContinuousClassLoader().loadClass(className);

                //TODO kind
                //if(theClass.isInterface()){
                //    kind = ClassKind.INTERFACE;
                //} else if(theClass.is  Modifier.isAbstract( someClass.getModifiers() );

            } catch (Exception e) {
                logger.warn("Cannot handle " + className + " due to: " + e.getClass() + " " + e.getMessage());
                continue;
            } finally {
                Sandbox.doneWithExecutingUnsafeCodeOnSameThread();
                BranchPool.getInstance(instrumenting).reset();
                Properties.TARGET_CLASS = "";
            }

            ClassInfo ci = new ClassInfo(theClass, numberOfBranches, hasCode);
            data.addNewClass(ci);

            detectChange(data, ci);
        }

        return data;
    }

    private void detectChange(ProjectStaticData data, ClassInfo ci) {
        String fingerprint = null;
        if (fingerprints != null) {
            // recorded once the class's tests are generated, whatever decides what changed
            fingerprint = fingerprints.fingerprint(ci.theClass);
            ci.setFingerprint(fingerprint);
        }

        // one source decides what changed: a list of changed source files if one is given
        // (eg from version control), otherwise the fingerprints
        boolean changed;
        if (data.hasHistoryFile()) {
            changed = data.hasChanged(sourceFileOf(ci.theClass));
        } else if (fingerprints != null) {
            changed = fingerprints.hasChanged(ci.getClassName(), fingerprint);
        } else {
            changed = true;
        }

        if (Properties.CTG_SCHEDULE == AvailableSchedule.HISTORY) {
            ci.setChanged(changed);
            ci.isToTest(changed || data.isToTest(ci.getClassName(), HistorySchedule.COMMIT_IMPROVEMENT));
            return;
        }

        // "changed" also decides whether a new suite replaces the old one without comparing coverage.
        // Outside HISTORY a class that is tested always gets its new suite, so it counts as changed;
        // change detection only decides which classes are tested at all.
        ci.setChanged(true);
        if (Properties.CTG_INCREMENTAL && fingerprints != null && !cutsSelectedExplicitly()) {
            ci.isToTest(changed || !hasTests.test(ci.getClassName()));
        }
    }

    /**
     * The source file a class is declared in, relative to its source root, eg {@code a.b.C.java} for
     * {@code a.b.C} and for its nested classes {@code a.b.C$D}.
     */
    static String sourceFileOf(Class<?> theClass) {
        String name = theClass.getName();
        int nested = name.indexOf('$');
        if (nested >= 0) {
            name = name.substring(0, nested);
        }
        return name + ".java";
    }
}
