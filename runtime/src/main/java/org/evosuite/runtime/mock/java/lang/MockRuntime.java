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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.runtime.mock.java.lang;

import org.evosuite.runtime.System.SystemExitException;
import org.evosuite.runtime.jvm.ShutdownHookHandler;
import org.evosuite.runtime.mock.StaticReplacementMock;
import org.evosuite.runtime.mock.java.io.MockIOException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.StringTokenizer;

/**
 * Custom mock implementation of {@link java.lang.Runtime}.
 */
public class MockRuntime implements StaticReplacementMock {

    public String getMockedClassName() {
        return java.lang.Runtime.class.getName();
    }

    // ---- static methods -------

    /**
     * Returns the runtime object associated with the current Java application.
     *
     * @return the {@code Runtime} object associated with the current Java application.
     */
    public static Runtime getRuntime() {
        /*
         * return actual instance, because we cannot instantiate a new one,
         * and anyway will never been used directly in an unsafe mode
         */
        return java.lang.Runtime.getRuntime();
    }

    /**
     * Enable or disable finalization on exit.
     *
     * @param value true to enable, false to disable
     */
    public static void runFinalizersOnExit(boolean value) {
        //Shutdown.setRunFinalizersOnExit(value);
        //nothing to do
    }

    // ----- instance replacement methods -------------

    /**
     * Terminates the currently running Java virtual machine by throwing a SystemExitException.
     *
     * @param runtime the runtime instance
     * @param status  termination status.
     * @throws SystemExitException unconditionally
     */
    public static void exit(Runtime runtime, int status) {
        /*
         * TODO: move this exception class here once we remove old System mock
         */
        throw new SystemExitException();
    }

    /**
     * Registers a new virtual-machine shutdown hook.
     *
     * @param runtime the runtime instance
     * @param hook    an initialized but unstarted {@code Thread} object
     */
    public static void addShutdownHook(Runtime runtime, Thread hook) {
        /*
         * this is going to be handled specially by ShutdownHookHandler.
         * The mocking is implemented in this special way to handle all cases in which
         * addShutdownHook is called by API that we do not mock and that cannot
         * be instrumented
         */
        runtime.addShutdownHook(hook);
    }

    /**
     * De-registers a previously-registered virtual-machine shutdown hook.
     *
     * @param runtime the runtime instance
     * @param hook    the hook to remove
     * @return true if the specified hook was previously registered and successfully de-registered, false otherwise.
     */
    public static boolean removeShutdownHook(Runtime runtime, Thread hook) {
        /*
         * this is going to be handled specially by ShutdownHookHandler
         */
        return runtime.removeShutdownHook(hook);
    }

    /**
     * Forcibly terminates the currently running Java virtual machine.
     *
     * @param runtime the runtime instance
     * @param status  termination status.
     * @throws SystemExitException unconditionally
     */
    public static void halt(Runtime runtime, int status) {
        ShutdownHookHandler.getInstance().processWasHalted();
        throw new SystemExitException();
    }

    /**
     * Executes the specified string command in a separate process.
     *
     * @param runtime the runtime instance
     * @param command a specified system command.
     * @return A new {@link Process} object for managing the subprocess
     * @throws IOException if an I/O error occurs
     */
    public static Process exec(Runtime runtime, String command) throws IOException {
        return exec(runtime, command, null, null);
    }

    /**
     * Executes the specified string command in a separate process with the specified environment.
     *
     * @param runtime the runtime instance
     * @param command a specified system command.
     * @param envp    array of strings, each element of which has environment variable
     *                settings in the format name=value, or null if the subprocess should
     *                inherit the environment of the current process.
     * @return A new {@link Process} object for managing the subprocess
     * @throws IOException if an I/O error occurs
     */
    public static Process exec(Runtime runtime, String command, String[] envp) throws IOException {
        return exec(runtime, command, envp, null);
    }

    /**
     * Executes the specified string command in a separate process with the specified
     * environment and working directory.
     *
     * @param runtime the runtime instance
     * @param command a specified system command.
     * @param envp    array of strings, each element of which has environment variable
     *                settings in the format name=value, or null if the subprocess should
     *                inherit the environment of the current process.
     * @param dir     the working directory of the subprocess, or null if the subprocess
     *                should inherit the working directory of the current process.
     * @return A new {@link Process} object for managing the subprocess
     * @throws IOException if an I/O error occurs
     */
    public static Process exec(Runtime runtime, String command, String[] envp, File dir) throws IOException {

        if (command.length() == 0) {
            throw new MockIllegalArgumentException("Empty command");
        }

        StringTokenizer st = new StringTokenizer(command);
        String[] cmdarray = new String[st.countTokens()];
        for (int i = 0; st.hasMoreTokens(); i++) {
            cmdarray[i] = st.nextToken();
        }
        return exec(runtime, cmdarray, envp, dir);
    }

    /**
     * Executes the specified command and arguments in a separate process.
     *
     * @param runtime  the runtime instance
     * @param cmdarray array containing the command to call and its arguments.
     * @return A new {@link Process} object for managing the subprocess
     * @throws IOException if an I/O error occurs
     */
    public static Process exec(Runtime runtime, String[] cmdarray) throws IOException {
        return exec(runtime, cmdarray, null, null);
    }

    /**
     * Executes the specified command and arguments in a separate process with the
     * specified environment.
     *
     * @param runtime  the runtime instance
     * @param cmdarray array containing the command to call and its arguments.
     * @param envp     array of strings, each element of which has environment variable
     *                 settings in the format name=value, or null if the subprocess should
     *                 inherit the environment of the current process.
     * @return A new {@link Process} object for managing the subprocess
     * @throws IOException if an I/O error occurs
     */
    public static Process exec(Runtime runtime, String[] cmdarray, String[] envp) throws IOException {
        return exec(runtime, cmdarray, envp, null);
    }

    /**
     * Executes the specified command and arguments in a separate process with the
     * specified environment and working directory.
     *
     * @param runtime  the runtime instance
     * @param cmdarray array containing the command to call and its arguments.
     * @param envp     array of strings, each element of which has environment variable
     *                 settings in the format name=value, or null if the subprocess should
     *                 inherit the environment of the current process.
     * @param dir      the working directory of the subprocess, or null if the subprocess
     *                 should inherit the working directory of the current process.
     * @return A new {@link Process} object for managing the subprocess
     * @throws IOException if an I/O error occurs
     */
    public static Process exec(Runtime runtime, String[] cmdarray, String[] envp, File dir)
            throws IOException {
        /*
        return new ProcessBuilder(cmdarray)
        .environment(envp)
        .directory(dir)
        .start();
        */
        //TODO mock ProcessBuilder
        throw new MockIOException("Cannot start processes in a unit test");
    }

    /**
     * Runs the garbage collector.
     *
     * @param runtime the runtime instance
     */
    public static void gc(Runtime runtime) {
        //do nothing
    }

    /**
     * Runs the finalization methods of any objects pending finalization.
     *
     * @param runtime the runtime instance
     */
    public static void runFinalization(Runtime runtime) {
        //runFinalization0();
        //do nothing
    }

    /**
     * Enables/disables tracing of instructions.
     *
     * @param runtime the runtime instance
     * @param on      true to enable, false to disable
     */
    public static void traceInstructions(Runtime runtime, boolean on) {
        //do nothing
    }

    /**
     * Enables/disables tracing of method calls.
     *
     * @param runtime the runtime instance
     * @param on      true to enable, false to disable
     */
    public static void traceMethodCalls(Runtime runtime, boolean on) {
        //do nothing
    }

    /**
     * Loads the native library specified by the filename argument.
     *
     * @param runtime  the runtime instance
     * @param filename the file to load.
     */
    public static void load(Runtime runtime, String filename) {
        //load0(Reflection.getCallerClass(), filename);
        runtime.load(filename); // we need to load the actuall stuff
    }

    /**
     * Loads the native library specified by the libname argument.
     *
     * @param runtime the runtime instance
     * @param libname the name of the library.
     */
    public static void loadLibrary(Runtime runtime, String libname) {
        //loadLibrary0(Reflection.getCallerClass(), libname);
        runtime.loadLibrary(libname); // we need to load the actuall stuff
    }

    /**
     * Creates a localized version of an input stream.
     *
     * @param runtime the runtime instance
     * @param in      an input stream
     * @return a localized input stream
     */
    public static InputStream getLocalizedInputStream(Runtime runtime, InputStream in) {
        // inlined runtime.getLocalizedInputStream for Java 11 compatibility.
        return in;
    }

    /**
     * Creates a localized version of an output stream.
     *
     * @param runtime the runtime instance
     * @param out     an output stream
     * @return a localized output stream
     */
    public static OutputStream getLocalizedOutputStream(Runtime runtime, OutputStream out) {
        // inlined runtime.getLocalizedOutputStream for Java 11 compatibility.
        return out;
    }

    //-------------------------------------------------
    // for the following methods, we return reasonable values. Technically those returned values could
    // be part of the search. But most likely it would be not useful to increase coverage in typical cases
    // Note: we still need them to be deterministic, and not based on actual Runtime

    /**
     * Returns the number of processors available to the Java virtual machine.
     *
     * @param runtime the runtime instance
     * @return the number of processors
     */
    public static int availableProcessors(Runtime runtime) {
        return 1;
    }

    /**
     * Returns the amount of free memory in the Java Virtual Machine.
     *
     * @param runtime the runtime instance
     * @return an approximation to the total amount of memory currently available for
     *         future allocated objects, in bytes.
     */
    public static long freeMemory(Runtime runtime) {
        return 200;
    }

    /**
     * Returns the total amount of memory in the Java virtual machine.
     *
     * @param runtime the runtime instance
     * @return the total amount of memory, in bytes.
     */
    public static long totalMemory(Runtime runtime) {
        return 400;
    }

    /**
     * Returns the maximum amount of memory that the Java virtual machine will attempt to use.
     *
     * @param runtime the runtime instance
     * @return the maximum amount of memory, in bytes.
     */
    public static long maxMemory(Runtime runtime) {
        return 500;
    }

    //-------------------------------------------------
}
