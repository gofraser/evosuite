/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.runtime;

import org.evosuite.runtime.agent.InstrumentingAgent;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * When running tests from a build tool (eg  "mvn test" when using Maven)
 * we need to use this listener (eg, in Maven configured from pom.xml).
 * Reason is to address the following two issues:
 * <p/>
 * <ul>
 * <li>
 * "manual" tests executed before EvoSuite ones that
 * lead to load system under test classes while the Java Agent is not active.
 * </li>
 * <li>
 *     Even if no manual test is run, still JUnit could still load some system
 *     under test classes before Java Agent is activated, messing up with
 *     the internal bytecode instrumentation used for environment mocking (eg
 *     file system and networking)
 * </li>
 * </ul>
 * <p/>
 * <p/>
 * Note: bytecode re-instrumenting is not really an option,
 * as it has its limitations (eg don't change signature of classes/methods)
 *
 * @author arcuri
 */
public class InitializingListener extends RunListener {

    /**
     * Name of the method that is used to initialize the SUT classes.
     */
    public static final String INITIALIZE_CLASSES_METHOD = "initializeClasses";

    /**
     * File name of list of scaffolding files to use for initialization.
     */
    public static final String SCAFFOLDING_LIST_FILE_STRING = ".scaffolding_list.tmp";
    /**
     * Property used for example in Ant to specify where the EvoSuite tests have been compiled.
     */
    public static final String COMPILED_TESTS_FOLDER_PROPERTY = "EvoSuiteCompiledTestFolder";


    //TODO: need to move out of this class, as to avoid dependency on JUnit RunListener
    public static String getScaffoldingListFilePath() {
        //we could use a system property here if we want to change location
        return SCAFFOLDING_LIST_FILE_STRING;
    }

    @Override
    public void testRunStarted(Description description) throws Exception {
        try {
            java.lang.System.out.println("Executing " + InitializingListener.class.getName());

            /*
                Here we cannot trust what passed as "Description", as it could had
                been not initialized. This is for example the case for Maven, and
                who knows what would be in Ant and Gradle.
             */


            /*
                This is not 100% correct, but anyway this is done only when running tests with "mvn test"
                by the final users, not really in the experiments.
                So, activating everything should be fine
             */
            RuntimeSettings.activateAllMocking();
            RuntimeSettings.mockSystemIn = true;
            RuntimeSettings.resetStaticState = true;

            List<String> list;
            String compiledTestsFolder = java.lang.System.getProperty(COMPILED_TESTS_FOLDER_PROPERTY);

            /*
                We have 2 different approaches based on Maven and Ant.
                TODO: we ll need to handle also Gradle, and possibly find a simpler, unified way
             */
            if (compiledTestsFolder == null) {
                list = classesToInitFromScaffoldingFile();
                if (list.isEmpty()) {
                    File defaultCompiledTests = new File("target" + File.separator + "test-classes");
                    if (defaultCompiledTests.exists()) {
                        list = InitializingListenerUtils.scanClassesToInit(defaultCompiledTests);
                    }
                }
            } else {
                list = InitializingListenerUtils.scanClassesToInit(new File(compiledTestsFolder));
                if (list.isEmpty()) {
                    list = classesToInitFromScaffoldingFile();
                }
            }
            list = deduplicate(list);
            java.lang.System.out.println("Initializing " + list.size() + " scaffolding classes");

            try {
                InstrumentingAgent.initialize();
            } catch (Throwable t) {
                java.lang.System.out.println("WARN: Failed to initialize InstrumentingAgent in InitializingListener: "
                        + t.getClass().getName() + ": " + t.getMessage());
            }

            for (String name : list) {
                Method m = null;
                try {
                    //reflection might load some SUT class
                    InstrumentingAgent.activate();
                    ClassLoader testClassLoader = Thread.currentThread().getContextClassLoader();
                    if (testClassLoader == null) {
                        testClassLoader = InitializingListener.class.getClassLoader();
                    }
                    Class<?> test = testClassLoader.loadClass(name);
                    m = test.getDeclaredMethod(INITIALIZE_CLASSES_METHOD);
                    m.setAccessible(true);
                } catch (NoSuchMethodException e) {
                    /*
                     * this is ok.
                     * Note: we could skip the test based on some pattern on the
                     * name, but not really so important in the end
                     */
                } catch (Exception e) {
                    java.lang.System.out.println("Exception while loading class " + name + ": " + e.getMessage());
                } finally {
                    try {
                        InstrumentingAgent.deactivate();
                    } catch (Throwable t) {
                        java.lang.System.out.println("WARN: Failed to deactivate InstrumentingAgent in "
                                + "InitializingListener: " + t.getClass().getName() + ": " + t.getMessage());
                    }
                }

                if (m == null) {
                    continue;
                }

                try {
                    m.invoke(null);
                } catch (Exception e) {
                    Throwable root = e;
                    if (e instanceof InvocationTargetException && ((InvocationTargetException) e).getCause() != null) {
                        root = ((InvocationTargetException) e).getCause();
                    }
                    java.lang.System.out.println("Exception while calling " + name + "." + INITIALIZE_CLASSES_METHOD
                            + "(): " + root.getClass().getName() + ": " + root.getMessage());
                    root.printStackTrace(java.lang.System.out);
                }
            }

            // Keep transformer active for classes loaded after listener startup.
            // The per-class initialize/deactivate block above turns it off again.
            try {
                InstrumentingAgent.getTransformer().activate();
            } catch (Throwable t) {
                java.lang.System.out.println("WARN: Failed to activate transformer in InitializingListener: "
                        + t.getClass().getName() + ": " + t.getMessage());
            }
        } catch (Throwable t) {
            java.lang.System.out.println("WARN: InitializingListener failed during startup: "
                    + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(java.lang.System.out);
        }
    }

    @Override
    public void testRunFinished(Result result) throws Exception {
        // Ensure no instrumentation side effects leak across runs.
        try {
            InstrumentingAgent.getTransformer().deactivate();
        } catch (Throwable t) {
            java.lang.System.out.println("WARN: Failed to deactivate transformer in InitializingListener: "
                    + t.getClass().getName() + ": " + t.getMessage());
        }
    }


    private List<String> classesToInitFromScaffoldingFile() {
        File scaffolding = new File(SCAFFOLDING_LIST_FILE_STRING);
        return readInitializationClasses(scaffolding);
    }

    static List<String> readInitializationClasses(File scaffolding) {
        if (scaffolding.exists()) {
            return InitializingListenerUtils.readInitializationClassList(scaffolding);
        }
        java.lang.System.out.println(
                "WARN: initialization scaffolding list file not found. If this module has legacy EvoSuite tests, "
                        + "recall to call the preparation step before executing the tests. For example, in Maven "
                        + "you need to make sure that 'evosuite:prepare' is called. See documentation at "
                        + "www.evosuite.org for further details.");
        return new ArrayList<>();
    }

    private static List<String> deduplicate(List<String> classNames) {
        Set<String> unique = new LinkedHashSet<>(classNames);
        return new ArrayList<>(unique);
    }
}
