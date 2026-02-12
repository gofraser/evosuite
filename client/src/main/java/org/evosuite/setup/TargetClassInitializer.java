/*
 * Copyright (C) 2010-2024 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.setup;

import org.evosuite.ClientProcess;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.execution.EvosuiteError;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.BooleanPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.generic.GenericMethod;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;

/**
 * Handles the initialization of the target class under test.
 */
public class TargetClassInitializer {

    /**
     * Initializes the target class by generating inheritance tree and call graph,
     * executing a load test case, and analyzing the class.
     *
     * @param targetClass The fully qualified name of the target class.
     * @param onInitializationError A runnable to execute if an initialization error occurs
     *                              (e.g., writing a failed test suite).
     * @throws Throwable if initialization fails.
     */
    public void initializeTargetClass(String targetClass, Runnable onInitializationError) throws Throwable {
        String cp = ClassPathHandler.getInstance().getTargetProjectClasspath();

        // Generate inheritance tree and call graph *before* loading the CUT
        // as these are required for instrumentation for context-sensitive
        // criteria (e.g. ibranch)
        DependencyAnalysis.initInheritanceTree(Arrays.asList(cp.split(File.pathSeparator)));
        DependencyAnalysis.initCallGraph(targetClass);

        // Here is where the <clinit> code should be invoked for the first time
        DefaultTestCase test = buildLoadTargetClassTestCase(targetClass);
        ExecutionResult execResult = TestCaseExecutor.getInstance().execute(test, Integer.MAX_VALUE);

        if (hasThrownInitializerError(execResult)) {
            // create single test suite with Class.forName()
            if (onInitializationError != null) {
                onInitializationError.run();
            }
            ExceptionInInitializerError ex = getInitializerError(execResult);
            throw ex;
        } else if (!execResult.getAllThrownExceptions().isEmpty()) {
            // some other exception has been thrown during initialization
            Throwable t = execResult.getAllThrownExceptions().iterator().next();
            throw t;
        }

        // Analysis has to happen *after* the CUT is loaded since it will cause
        // several other classes to be loaded (including the CUT), but we require
        // the CUT to be loaded first
        DependencyAnalysis.analyzeClass(targetClass, Arrays.asList(cp.split(File.pathSeparator)));
        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                + "Finished analyzing classpath");
    }

    /**
     * Returns true iif the test case execution has thrown an instance of ExceptionInInitializerError.
     *
     * @param execResult of the test case execution
     * @return true if the test case has thrown an ExceptionInInitializerError
     */
    private static boolean hasThrownInitializerError(ExecutionResult execResult) {
        for (Throwable t : execResult.getAllThrownExceptions()) {
            if (t instanceof ExceptionInInitializerError) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the initialized error from the test case execution.
     *
     * @param execResult of the test case execution
     * @return null if there were no thrown instances of ExceptionInInitializerError
     */
    private static ExceptionInInitializerError getInitializerError(ExecutionResult execResult) {
        for (Throwable t : execResult.getAllThrownExceptions()) {
            if (t instanceof ExceptionInInitializerError) {
                return (ExceptionInInitializerError) t;
            }
        }
        return null;
    }

    /**
     * Creates a single Test Case that only loads the target class.
     * <pre>
     * Thread currentThread = Thread.currentThread();
     * ClassLoader classLoader = currentThread.getClassLoader();
     * classLoader.load(className);
     * </pre>
     *
     * @param className the class to be loaded
     * @return the test case
     * @throws EvosuiteError if a reflection error happens while creating the test case
     */
    public static DefaultTestCase buildLoadTargetClassTestCase(String className) throws EvosuiteError {
        DefaultTestCase test = new DefaultTestCase();

        StringPrimitiveStatement stmt0 = new StringPrimitiveStatement(test, className);
        VariableReference string0 = test.addStatement(stmt0);
        try {
            Method currentThreadMethod = Thread.class.getMethod("currentThread");
            Statement currentThreadStmt = new MethodStatement(test,
                    new GenericMethod(currentThreadMethod, currentThreadMethod.getDeclaringClass()), null,
                    Collections.emptyList());
            VariableReference currentThreadVar = test.addStatement(currentThreadStmt);

            Method getContextClassLoaderMethod = Thread.class.getMethod("getContextClassLoader");
            Statement getContextClassLoaderStmt = new MethodStatement(test,
                    new GenericMethod(getContextClassLoaderMethod, getContextClassLoaderMethod.getDeclaringClass()),
                    currentThreadVar, Collections.emptyList());
            VariableReference contextClassLoaderVar = test.addStatement(getContextClassLoaderStmt);

            BooleanPrimitiveStatement stmt1 = new BooleanPrimitiveStatement(test, true);
            VariableReference boolean0 = test.addStatement(stmt1);

            Method forNameMethod = Class.class.getMethod("forName", String.class, boolean.class, ClassLoader.class);
            Statement forNameStmt = new MethodStatement(test,
                    new GenericMethod(forNameMethod, forNameMethod.getDeclaringClass()), null,
                    Arrays.asList(string0, boolean0, contextClassLoaderVar));
            test.addStatement(forNameStmt);

            return test;
        } catch (NoSuchMethodException | SecurityException e) {
            throw new EvosuiteError("Unexpected exception while creating Class Initializer Test Case");
        }
    }
}
