/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.utils;

import org.evosuite.ClientProcess;
import org.evosuite.ConsoleProgressBar;
import org.evosuite.Properties;
import org.evosuite.result.TestGenerationResult;
import org.evosuite.result.TestGenerationResultBuilder;
import org.evosuite.rmi.MasterServices;
import org.evosuite.rmi.service.ClientNodeRemote;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.statistics.SearchStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.rmi.ConnectException;
import java.rmi.RemoteException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;

/*
 * this code should be used by the main master process.
 *
 * FIXME: once RMI is stable tested, we ll need to remove all the TCP stuff, and refactor
 */

public class ExternalProcessGroupHandler {
    /**
     * Constant <code>logger</code>.
     */
    protected static final Logger logger = LoggerFactory.getLogger(ExternalProcessGroupHandler.class);

    protected Process[] processGroup;
    protected String[][] lastCommands;

    protected Thread[] outputPrinters;
    protected Thread[] errorPrinters;
    protected Thread[] messageHandlers;

    protected ObjectInputStream in;

    protected Object finalResult;
    /**
     * Constant <code>WAITING_FOR_DATA</code>.
     */
    protected static final Object WAITING_FOR_DATA = "waiting_for_data_"
            + System.currentTimeMillis();

    protected Thread[] processKillHooks;
    protected Thread clientRunningOnThread;

    protected volatile CountDownLatch[] latches;

    protected String baseDir = System.getProperty("user.dir");

    private final String[] hsErrFiles;

    /**
     * For execution modes that do not require test generation results,
     * allow DONE to be treated as terminal to avoid waiting for FINISHED.
     */
    private boolean allowDoneAsFinished = false;

    public ExternalProcessGroupHandler() {
        this(1);
    }

    /**
     * <p>
     * Constructor for ExternalProcessGroupHandler.
     * </p>
     */
    public ExternalProcessGroupHandler(final int nrOfProcesses) {
        this.processGroup = new Process[nrOfProcesses];
        this.lastCommands = new String[nrOfProcesses][];

        this.outputPrinters = new Thread[nrOfProcesses];
        this.errorPrinters = new Thread[nrOfProcesses];
        this.messageHandlers = new Thread[nrOfProcesses];

        this.processKillHooks = new Thread[nrOfProcesses];
        this.latches = new CountDownLatch[nrOfProcesses];
        this.hsErrFiles = new String[nrOfProcesses];
    }

    /**
     * Only for debug reasons.
     *
     * @param ms time to wait in milliseconds
     */
    public void stopAndWaitForClientOnThread(long ms) {

        if (clientRunningOnThread != null && clientRunningOnThread.isAlive()) {
            clientRunningOnThread.interrupt();
        }

        long start = System.currentTimeMillis();
        while ((System.currentTimeMillis() - start) < ms) { //to avoid miss it in case of interrupt
            if (clientRunningOnThread != null && clientRunningOnThread.isAlive()) {
                try {
                    clientRunningOnThread.join(ms - (System.currentTimeMillis() - start));
                    break;
                } catch (InterruptedException e) {
                    // ignored
                }
            } else {
                break;
            }
        }

        if (clientRunningOnThread != null && clientRunningOnThread.isAlive()) {
            throw new AssertionError("clientRunningOnThread is alive even after waiting " + ms + "ms");
        }
    }


    /**
     * Sets the base directory.
     *
     * @param baseDir the base directory
     */
    public void setBaseDir(String baseDir) {
        this.baseDir = baseDir;
    }

    public void setAllowDoneAsFinished(boolean allow) {
        this.allowDoneAsFinished = allow;
    }

    /**
     * <p>
     * startProcess.
     * </p>
     *
     * @param commands an array of {@link java.lang.String} objects.
     * @return a boolean.
     */
    public boolean startProcess(String[] commands) {
        List<String[]> commandsList = new ArrayList<>();
        commandsList.add(commands);
        return this.startProcessGroup(commandsList);
    }

    /**
     * Starts a process for each command array of the given list. If one process fails to start, all already started
     * processes are killed.
     *
     * @param commands a list of arrays of commands to start
     * @return true iff all processes have started correctly, false otherwise
     */
    public boolean startProcessGroup(List<String[]> commands) {
        int rollbackToI = 0;

        for (int i = 0; i < commands.size(); i++) {
            String[] command = commands.get(i);

            if (!Properties.IS_RUNNING_A_SYSTEM_TEST) {
                logger.debug("Going to start process with command: " + Arrays.toString(command).replace(",", " "));
            }

            List<String> formatted = new LinkedList<>();
            for (String s : command) {
                String token = s.trim();
                if (!token.isEmpty()) {
                    formatted.add(token);
                }
            }

            hsErrFiles[i] = "hs_err_EvoSuite_client_p" + getServerPort() + "_t" + System.currentTimeMillis();
            String option = "-XX:ErrorFile=" + hsErrFiles[i];
            formatted.add(1, option); // add it after the first "java" command

            if (!startProcess(formatted.toArray(new String[0]), i, null)) {
                rollbackToI = i;
                break;
            }
        }

        if (rollbackToI > 0) {
            for (int i = 0; i < rollbackToI; i++) {
                killProcess(i);
            }
        }

        return rollbackToI == 0;
    }

    protected boolean didClientJVMCrash(final int processIndex) {
        return new File(hsErrFiles[processIndex]).exists();
    }

    protected String getAndDeleteHsErrFile(final int processIndex) {
        if (!didClientJVMCrash(processIndex)) {
            return null;
        }

        StringBuilder builder = new StringBuilder();

        File file = new File(hsErrFiles[processIndex]);
        file.deleteOnExit();

        try (Scanner in = new Scanner(file)) {
            while (in.hasNextLine()) {
                String row = in.nextLine();
                //do not read the full file, just the header
                if (row.startsWith("#")) {
                    builder.append(row).append("\n");
                } else {
                    break; //end of the header
                }
            }
        } catch (FileNotFoundException e) {
            //shouldn't really happen
            logger.error("Error while reading " + file.getAbsolutePath() + ": " + e.getMessage());
            return null;
        }

        return builder.toString();
    }

    /**
     * Returns a string representation of the states of all processes in the group.
     *
     * @return a string describing the states of the processes
     */
    public String getProcessStates() {
        if (processGroup == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < processGroup.length; i++) {
            builder.append("process nr. ");
            builder.append(i);
            builder.append(": ");

            if (processGroup[i] == null) {
                builder.append("null\n");
            } else {
                try {
                    int exitValue = processGroup[i].exitValue();
                    builder.append("Terminated with exit status ");
                    builder.append(exitValue);
                    builder.append("\n");
                } catch (IllegalThreadStateException e) {
                    builder.append("Still running\n");
                }
            }
        }
        return builder.toString();
    }

    /**
     * <p>
     * startProcess.
     * </p>
     *
     * @param command         an array of {@link java.lang.String} objects.
     * @param processIndex    index of process
     * @param populationData a {@link java.lang.Object} object.
     * @return a boolean.
     */
    protected boolean startProcess(String[] command, int processIndex, Object populationData) {
        if (processGroup[processIndex] != null) {
            logger.warn("Already running an external process");
            return false;
        }

        latches[processIndex] = new CountDownLatch(1);
        finalResult = WAITING_FOR_DATA;


        //the following thread is important to make sure that the external process is killed
        //when current process ends

        processKillHooks[processIndex] = new Thread() {
            @Override
            public void run() {
                killProcess(processIndex);
                closeServer();
            }
        };

        Runtime.getRuntime().addShutdownHook(processKillHooks[processIndex]);
        // now start the process

        if (!Properties.CLIENT_ON_THREAD) {
            File dir = new File(baseDir);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(dir);
            builder.redirectErrorStream(false);

            try {
                processGroup[processIndex] = builder.start();
            } catch (IOException e) {
                logger.error("Failed to start external process", e);
                return false;
            }

            startExternalProcessPrinter(processIndex);
        } else {
            /*
             * Here we run client on a thread instead of process.
             * NOTE: this should only be done for debugging, ie in
             * JUnit files created for testing EvoSuite.
             */
            clientRunningOnThread = new Thread() {
                @Override
                public void run() {
                    /*
                     * NOTE: the handling of the parameters "-D" should be handled
                     * directly in JUnit by setting the different values in Properties
                     */
                    ClientProcess.main(new String[0]);
                }
            };
            clientRunningOnThread.setName("client");
            clientRunningOnThread.start();
            Sandbox.addPrivilegedThread(clientRunningOnThread);
        }

        startSignalHandler(processIndex);
        lastCommands[processIndex] = command;

        return true;
    }

    /**
     * <p>
     * killProcess.
     * </p>
     */
    public void killProcess() {
        this.killProcess(0);
    }

    /**
     * Terminates the process with given index.
     *
     * @param processIndex index of process to kill
     */
    public void killProcess(final int processIndex) {
        if (processGroup[processIndex] == null) {
            return;
        }

        try {
            Runtime.getRuntime().removeShutdownHook(processKillHooks[processIndex]);
        } catch (Exception e) { /* do nothing. this can happen if shutdown is in progress */
        }


        /*
         * TODO: use RMI to 'gracefully' stop the client
         */

        if (processGroup[processIndex] != null) {
            try {
                //be sure streamers are closed, otherwise process might hang on Windows
                processGroup[processIndex].getOutputStream().close();
                processGroup[processIndex].getInputStream().close();
                processGroup[processIndex].getErrorStream().close();
            } catch (Exception t) {
                logger.error("Failed to close process stream: " + t);
            }
            processGroup[processIndex].destroy();
        }
        processGroup[processIndex] = null;

        if (clientRunningOnThread != null && clientRunningOnThread.isAlive()) {
            clientRunningOnThread.interrupt();
        }
        clientRunningOnThread = null;

        if (outputPrinters[processIndex] != null && outputPrinters[processIndex].isAlive()) {
            outputPrinters[processIndex].interrupt();
        }
        outputPrinters[processIndex] = null;

        if (errorPrinters[processIndex] != null && errorPrinters[processIndex].isAlive()) {
            errorPrinters[processIndex].interrupt();
        }
        errorPrinters[processIndex] = null;

        if (messageHandlers[processIndex] != null && messageHandlers[processIndex].isAlive()) {
            messageHandlers[processIndex].interrupt();
        }
        messageHandlers[processIndex] = null;
    }

    /**
     * Terminates all running processes.
     */
    public void killAllProcesses() {
        for (int i = 0; i < processGroup.length; i++) {
            killProcess(i);
        }
    }

    /**
     * <p>
     * getServerPort.
     * </p>
     *
     * @return a int.
     */
    public int getServerPort() {
        return MasterServices.getInstance().getRegistryPort();
    }

    /**
     * <p>
     * openServer.
     * </p>
     *
     * @return a int.
     */
    public int openServer() {
        boolean started = MasterServices.getInstance().startRegistry();
        if (!started) {
            logger.error("Not possible to start RMI registry");
            return -1;
        }

        try {
            MasterServices.getInstance().registerServices();
        } catch (RemoteException e) {
            logger.error("Failed to start RMI services", e);
            return -1;
        }

        return MasterServices.getInstance().getRegistryPort();

        /*
        if (server == null) {
            try {
                server = new ServerSocket();
                server.setSoTimeout(10000);
                server.bind(null);
                return server.getLocalPort();
            } catch (Exception e) {
                logger.error("Not possible to start TCP server", e);
            }
        }
        return -1;
         */
    }

    /**
     * <p>
     * closeServer.
     * </p>
     */
    public void closeServer() {
        MasterServices.getInstance().stopServices();
    }

    /**
     * <p>
     * startExternalProcessPrinter.
     * </p>
     *
     * @param processIndex index of process
     */
    protected void startExternalProcessPrinter(final int processIndex) {

        if (outputPrinters[processIndex] == null || !outputPrinters[processIndex].isAlive()) {
            outputPrinters[processIndex] = new Thread() {
                @Override
                public void run() {
                    try {
                        BufferedReader procIn = new BufferedReader(
                                new InputStreamReader(processGroup[processIndex].getInputStream()));

                        int data = 0;
                        while (data != -1 && !isInterrupted()) {
                            data = procIn.read();
                            if (data != -1 && Properties.PRINT_TO_SYSTEM) {
                                System.out.print((char) data);
                            }
                        }

                    } catch (Exception e) {
                        if (MasterServices.getInstance().getMasterNode() == null) {
                            return;
                        }

                        boolean finished = true;
                        for (ClientState state : MasterServices.getInstance().getMasterNode().getCurrentState()) {
                            if (state != ClientState.DONE) {
                                finished = false;
                                break;
                            }
                        }
                        if (!finished) {
                            logger.error("Exception while reading output of client process. "
                                    + e.getMessage());
                        } else {
                            logger.debug("Exception while reading output of client process. "
                                    + e.getMessage());
                        }
                    }
                }
            };

            outputPrinters[processIndex].start();
        }

        if (errorPrinters[processIndex] == null || !errorPrinters[processIndex].isAlive()) {
            errorPrinters[processIndex] = new Thread() {
                @Override
                public void run() {
                    try {
                        BufferedReader procIn = new BufferedReader(
                                new InputStreamReader(processGroup[processIndex].getErrorStream()));

                        int data = 0;
                        String errorLine = "";
                        while (data != -1 && !isInterrupted()) {
                            data = procIn.read();
                            if (data != -1 && Properties.PRINT_TO_SYSTEM) {
                                System.err.print((char) data);

                                errorLine += (char) data;
                                if ((char) data == '\n') {
                                    logger.error(errorLine);
                                    errorLine = "";
                                }
                            }
                        }

                    } catch (Exception e) {
                        if (MasterServices.getInstance().getMasterNode() == null) {
                            return;
                        }

                        boolean finished = true;
                        for (ClientState state : MasterServices.getInstance().getMasterNode().getCurrentState()) {
                            if (state != ClientState.DONE) {
                                finished = false;
                                break;
                            }
                        }
                        if (!finished) {
                            logger.error("Exception while reading output of client process. "
                                    + e.getMessage());
                        } else {
                            logger.debug("Exception while reading output of client process. "
                                    + e.getMessage());
                        }
                    }
                }
            };

            errorPrinters[processIndex].start();
        }

        if (Properties.SHOW_PROGRESS
                && (Properties.LOG_LEVEL == null
                || (!Properties.LOG_LEVEL.equals("info")
                && !Properties.LOG_LEVEL.equals("debug")
                && !Properties.LOG_LEVEL.equals("trace"))
                )
        ) {
            ConsoleProgressBar.startProgressBar();
        }

    }

    /**
     * <p>
     * startExternalProcessMessageHandler.
     * </p>
     *
     * @param processIndex index of process
     */
    protected void startExternalProcessMessageHandler(final int processIndex) {
        if (messageHandlers[processIndex] != null && messageHandlers[processIndex].isAlive()) {
            return;
        }

        messageHandlers[processIndex] = new Thread() {
            @Override
            public void run() {
                boolean read = true;
                while (read && !isInterrupted()) {
                    String message = null;
                    Object data = null;

                    try {
                        message = (String) in.readObject();
                        data = in.readObject();
                        logger.debug("Received msg: " + message);
                        logger.debug("Received data: " + data);
                    } catch (Exception e) {
                        /*
                         * TODO: this parts need to be improved.
                         * An exception here is most likely due to the client crashing.
                         * If there is still enough budget (this might not be trivial to check,
                         * eg it could be fine for time, but not number of fitness evaluations), then
                         * we should try to re-start based on the partial info received so far, eg
                         * the best solutions found so far which was sent to master
                         */
                        logger.error("Class "
                                + Properties.TARGET_CLASS
                                + ". Error when reading message. Likely the client has crashed. Error message: "
                                + e.getMessage());
                        message = Messages.FINISHED_COMPUTATION;
                        data = null;
                    }

                    if (message.equals(Messages.FINISHED_COMPUTATION)) {
                        LoggingUtils.getEvoLogger().info("* Computation finished");
                        read = false;
                        killProcess(processIndex);
                        finalResult = data;
                        latches[processIndex].countDown();
                    } else if (message.equals(Messages.NEED_RESTART)) {
                        //now data represent the current generation
                        LoggingUtils.getEvoLogger().info("* Restarting client process");
                        killProcess(processIndex);
                        /*
                         * TODO: this will need to be changed, to take into account
                         * a possible reduced budget
                         */
                        startProcess(lastCommands[processIndex], processIndex, data);
                    } else {
                        killProcess(processIndex);
                        logger.error("Class " + Properties.TARGET_CLASS
                                + ". Error, received invalid message: ", message);
                        return;
                    }
                }
            }
        };
        messageHandlers[processIndex].start();
    }

    /**
     * Starts the signal handler for process with given index.
     *
     * @param processIndex index of process
     */
    protected void startSignalHandler(final int processIndex) {
        try {
            Class<?> signalClass = Class.forName("sun.misc.Signal");
            Class<?> signalHandlerClass = Class.forName("sun.misc.SignalHandler");

            Object signalInt = signalClass.getConstructor(String.class).newInstance("INT");

            Object handler = java.lang.reflect.Proxy.newProxyInstance(
                    signalHandlerClass.getClassLoader(),
                    new Class<?>[]{signalHandlerClass},
                    new java.lang.reflect.InvocationHandler() {
                        private boolean interrupted = false;

                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                                throws Throwable {
                            if (method.getName().equals("handle")) {
                                if (interrupted) {
                                    System.exit(0);
                                }
                                try {
                                    interrupted = true;
                                    if (processGroup[processIndex] != null) {
                                        processGroup[processIndex].waitFor();
                                    }
                                } catch (InterruptedException e) {
                                    logger.warn("", e);
                                }
                                return null;
                            }
                            return null;
                        }
                    }
            );

            signalClass.getMethod("handle", signalClass, signalHandlerClass).invoke(null, signalInt, handler);

        } catch (Throwable e) {
            logger.debug("Could not register signal handler: " + e.getMessage());
        }
    }

    /**
     * <p>
     * waitForResult.
     * </p>
     *
     * @param timeout a int.
     * @return a {@link java.lang.Object} object.
     */
    public TestGenerationResult waitForResult(int timeout) {
        try {
            long start = System.currentTimeMillis();
            Map<String, ClientNodeRemote> clients = MasterServices.getInstance()
                    .getMasterNode().getClientsOnceAllConnected(timeout);
            if (clients == null) {
                logger.error("Could not access client process");
                return TestGenerationResultBuilder.buildErrorResult("Could not access client process");
            }

            for (Entry<String, ClientNodeRemote> entry : clients.entrySet()) {
                long passed = System.currentTimeMillis() - start;
                long remaining = timeout - passed;
                if (remaining <= 0) {
                    remaining = 1;
                }
                boolean finished = false;
                ClientState clientState = MasterServices.getInstance().getMasterNode().getCurrentState(entry.getKey());
                if (clientState == ClientState.DONE || clientState == ClientState.FINISHED) {
                    finished = true;
                } else if (clientState == null || !clientState.equals(ClientState.FINISHED)) {
                    try {
                        finished = waitUntilFinishedWithTimeout(entry.getValue(), remaining, entry.getKey());
                    } catch (ConnectException e) {
                        logger.warn("Failed to connect to client. Client with id " + entry.getKey()
                                + " is already finished.");
                        finished = true;
                    }
                    if (!finished) {
                        /*
                         * Grace period: clients can legitimately finish right after the wait timeout,
                         * especially under load. Re-check state briefly before declaring a timeout.
                         */
                        finished = waitForClientStateToComplete(entry.getKey(), 5_000L);
                    }
                } else {
                    finished = true;
                }

                if (!finished) {
                    /*
                     * TODO what to do here? Try to stop the client through RMI?
                     * Or check in which state it is, and based on that decide if giving more time?
                     */
                    logger.error("Class " + Properties.TARGET_CLASS
                            + ". Clients have not finished yet, although a timeout occurred.\n"
                            + MasterServices.getInstance().getMasterNode().getSummaryOfClientStatuses());
                }
            }
        } catch (InterruptedException e) {
            // ignored
        } catch (RemoteException e) {

            String msg = "Class " + Properties.TARGET_CLASS + ". Lost connection with clients.\n"
                    + MasterServices.getInstance().getMasterNode().getSummaryOfClientStatuses();

            boolean crashOccurred = false;
            for (int i = 0; i < processGroup.length; i++) {
                if (didClientJVMCrash(i)) {
                    String err = getAndDeleteHsErrFile(i);
                    String clientMsg = "The JVM of the client process crashed:\n" + err;
                    logger.error(clientMsg);
                    crashOccurred = true;
                }
            }

            if (crashOccurred) {
                logger.error(msg, e);
            }
        }

        for (int i = 0; i < processGroup.length; i++) {
            killProcess(i);
        }
        LoggingUtils.getEvoLogger().info("* Computation finished");
        return null; //TODO refactoring
        /*
        try {
            latch.await(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            logger.warn("Class "
                                + Properties.TARGET_CLASS
                                + ". Thread interrupted while waiting for results from client process",
                        e);
        }

        return finalResult;
         */
    }

    private boolean waitUntilFinishedWithTimeout(ClientNodeRemote client, long timeoutMs, String clientId)
            throws RemoteException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        final long pollMs = 250L;
        while (System.currentTimeMillis() < deadline) {
            ClientState state = MasterServices.getInstance().getMasterNode().getCurrentState(clientId);
            if (state == ClientState.FINISHED) {
                return true;
            }
            if (clientRunningOnThread != null && !clientRunningOnThread.isAlive()) {
                return true;
            }
            if (allowDoneAsFinished && state == ClientState.DONE) {
                return true;
            }
            if (state == ClientState.DONE) {
                SearchStatistics stats = SearchStatistics.getInstance(clientId);
                if (!stats.getTestGenerationResults().isEmpty() && stats.hasEssentialOutputVariables()) {
                    return true;
                }
            }
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        logger.error("Timeout while waiting for client " + clientId + " to finish.");
        return false;
    }

    private boolean waitForClientStateToComplete(String clientId, long graceMs) {
        final long deadline = System.currentTimeMillis() + graceMs;
        while (System.currentTimeMillis() < deadline) {
            ClientState state = MasterServices.getInstance().getMasterNode().getCurrentState(clientId);
            if (state == ClientState.DONE || state == ClientState.FINISHED) {
                return true;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
