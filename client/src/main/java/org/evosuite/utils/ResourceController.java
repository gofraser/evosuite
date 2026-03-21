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
package org.evosuite.utils;

import org.evosuite.Properties;
import org.evosuite.ga.Chromosome;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.ga.metaheuristics.SearchListener;
import org.evosuite.ga.stoppingconditions.StoppingCondition;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.evosuite.ga.archive.Archive;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.execution.MethodCall;

import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;

/**
 * EvoSuite can run out of resources: eg out of memory, or too many threads that
 * are stalled and cannot be killed.
 *
 * <p>There can be several ways to handle these cases. The simplest is to to just
 * stop the search. Note: stopping the search when EvoSuite is close to run of
 * memory is important because, if it does actually run out of memory, when it
 * will not be able to write down the results obtained so far!
 *
 * @author Gordon Fraser
 */
public class ResourceController<T extends Chromosome<T>> implements SearchListener<T>,
        StoppingCondition<T>, Serializable {

    private static final long serialVersionUID = -4459807323163275506L;

    private static final Logger logger = LoggerFactory.getLogger(ResourceController.class);

    private GeneticAlgorithm<T> ga;
    private boolean stopComputation;

    public ResourceController() {
        // empty default constructor
    }

    public ResourceController(ResourceController<T> that) {
        this.ga = that.ga; // no deep copy
        this.stopComputation = that.stopComputation;
    }

    @Override
    public ResourceController<T> clone() {
        return new ResourceController<>(this);
    }

    private String exceededResource() {

        int totalRotations = TestCaseExecutor.getInstance().getTotalRotationCount();
        if (totalRotations >= Properties.MAX_TOTAL_ROTATIONS) {
            return "too many executor rotations: " + totalRotations
                    + " (limit " + Properties.MAX_TOTAL_ROTATIONS + ")"
                    + " — SUT likely causes immediate hangs";
        }

        int stalledThreads = TestCaseExecutor.getInstance().getNumStalledThreads();
        if (stalledThreads >= Properties.MAX_STALLED_THREADS) {
            return "too many stalled threads: " + stalledThreads
                    + " (limit " + Properties.MAX_STALLED_THREADS + ")";
        }

        Runtime runtime = Runtime.getRuntime();

        long freeMem = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();

        if (freeMem < Properties.MIN_FREE_MEM) {
            logger.trace("* Running out of memory, calling GC with memory left: "
                    + freeMem + " / " + runtime.maxMemory());
            System.gc();
            freeMem = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();

            if (freeMem < Properties.MIN_FREE_MEM) {
                logMemoryDiagnostics(runtime, freeMem);
                return "low memory: " + (freeMem / 1024 / 1024) + " MB free of "
                        + (runtime.maxMemory() / 1024 / 1024) + " MB (need "
                        + (Properties.MIN_FREE_MEM / 1024 / 1024) + " MB)";
            } else {
                logger.trace("* Garbage collection recovered sufficient memory: "
                        + freeMem + " / " + runtime.maxMemory());
            }
        }

        return null;
    }

    private void logMemoryDiagnostics(Runtime runtime, long freeMem) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Memory Diagnostics ===\n");

            // Heap summary
            long totalMem = runtime.totalMemory();
            long maxMem = runtime.maxMemory();
            long usedMem = totalMem - runtime.freeMemory();
            sb.append(String.format("  Heap: used=%d MB, committed=%d MB, max=%d MB, free=%d MB%n",
                    usedMem / 1024 / 1024, totalMem / 1024 / 1024,
                    maxMem / 1024 / 1024, freeMem / 1024 / 1024));

            // Memory pool breakdown (shows Old Gen, Eden, Survivor, Metaspace, etc.)
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                MemoryUsage usage = pool.getUsage();
                if (usage != null && usage.getUsed() > 1024 * 1024) { // only pools > 1 MB
                    sb.append(String.format("  Pool %-30s: used=%d MB, committed=%d MB, max=%d MB%n",
                            pool.getName(),
                            usage.getUsed() / 1024 / 1024,
                            usage.getCommitted() / 1024 / 1024,
                            usage.getMax() > 0 ? usage.getMax() / 1024 / 1024 : -1));
                }
            }

            // Thread info
            int threadCount = Thread.activeCount();
            int stalledThreads = TestCaseExecutor.getInstance().getNumStalledThreads();
            int totalRotations = TestCaseExecutor.getInstance().getTotalRotationCount();
            sb.append(String.format("  Threads: active=%d, stalled=%d, total executor rotations=%d%n",
                    threadCount, stalledThreads, totalRotations));

            // Loaded classes (high count suggests ByteBuddy mock class accumulation)
            long loadedClasses = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
            long totalLoadedClasses = ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount();
            long unloadedClasses = ManagementFactory.getClassLoadingMXBean().getUnloadedClassCount();
            sb.append(String.format("  Classes: loaded=%d, total ever loaded=%d, unloaded=%d%n",
                    loadedClasses, totalLoadedClasses, unloadedClasses));

            // Heap class histogram — shows top classes by retained bytes (like jmap -histo)
            try {
                javax.management.MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
                String histogram = (String) mbs.invoke(
                        new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"),
                        "gcClassHistogram",
                        new Object[]{null},
                        new String[]{"[Ljava.lang.String;"});
                // Include only the top 30 entries (header + 30 data lines)
                String[] lines = histogram.split("\n");
                sb.append("  === Heap Class Histogram (top 30) ===\n");
                int count = 0;
                for (String line : lines) {
                    sb.append("  ").append(line).append('\n');
                    if (line.trim().startsWith("1:")) count = 1;
                    else if (count > 0) count++;
                    if (count > 30) break;
                }
            } catch (Exception e) {
                sb.append("  Heap histogram: unavailable (").append(e.getMessage()).append(")\n");
            }

            // Population and archive stats
            if (ga != null) {
                try {
                    List<T> pop = ga.getPopulation();
                    int popSize = pop.size();
                    int totalStatements = 0;
                    int maxStatements = 0;
                    for (T individual : pop) {
                        int s = individual.size();
                        totalStatements += s;
                        if (s > maxStatements) maxStatements = s;
                    }
                    sb.append(String.format("  Population: size=%d, total statements=%d, max statements=%d, avg=%.1f%n",
                            popSize, totalStatements, maxStatements,
                            popSize > 0 ? (double) totalStatements / popSize : 0.0));
                } catch (Exception e) {
                    sb.append("  Population: unavailable\n");
                }

                try {
                    Archive archive = Archive.getArchiveInstance();
                    sb.append(String.format("  Archive: covered=%d, uncovered=%d, total targets=%d%n",
                            archive.getNumberOfCoveredTargets(),
                            archive.getNumberOfUncoveredTargets(),
                            archive.getNumberOfTargets()));
                } catch (Exception e) {
                    sb.append("  Archive: unavailable\n");
                }

                sb.append(String.format("  Search: generation=%d%n", ga.getAge()));
            }

            // Execution trace diagnostics — the traces are the primary memory consumer
            try {
                if (ga != null) {
                    List<T> pop = ga.getPopulation();
                    int withResults = 0;
                    long totalMethodCalls = 0;
                    long totalBranchEntries = 0;
                    long totalLineEntries = 0;
                    long maxMethodCallsInOne = 0;
                    int totalOutputTraces = 0;

                    for (T individual : pop) {
                        if (individual instanceof TestChromosome) {
                            TestChromosome tc = (TestChromosome) individual;
                            ExecutionResult result = tc.getLastExecutionResult();
                            if (result != null) {
                                withResults++;
                                ExecutionTrace trace = result.getTrace();
                                if (trace != null) {
                                    List<MethodCall> calls = trace.getMethodCalls();
                                    if (calls != null) {
                                        long callCount = calls.size();
                                        totalMethodCalls += callCount;
                                        if (callCount > maxMethodCallsInOne) {
                                            maxMethodCallsInOne = callCount;
                                        }
                                        for (MethodCall mc : calls) {
                                            if (mc.branchTrace != null) totalBranchEntries += mc.branchTrace.size();
                                            if (mc.lineTrace != null) totalLineEntries += mc.lineTrace.size();
                                        }
                                    }
                                }
                                totalOutputTraces += result.getTraces().size();
                            }
                        }
                    }
                    sb.append(String.format("  Cached execution results: %d / %d individuals%n",
                            withResults, pop.size()));
                    sb.append(String.format("  Traces (population): method calls=%d (max in one=%d), " +
                                    "branch entries=%d, line entries=%d, output traces=%d%n",
                            totalMethodCalls, maxMethodCallsInOne,
                            totalBranchEntries, totalLineEntries, totalOutputTraces));

                    // Also check archive solutions
                    try {
                        Archive archive = Archive.getArchiveInstance();
                        java.util.Set<TestChromosome> archiveSolutions = archive.getSolutions();
                        if (archiveSolutions != null) {
                            long archiveMethodCalls = 0;
                            long archiveBranchEntries = 0;
                            long archiveMaxMethodCalls = 0;
                            for (TestChromosome atc : archiveSolutions) {
                                ExecutionResult ar = atc.getLastExecutionResult();
                                if (ar != null && ar.getTrace() != null) {
                                    List<MethodCall> calls = ar.getTrace().getMethodCalls();
                                    if (calls != null) {
                                        archiveMethodCalls += calls.size();
                                        if (calls.size() > archiveMaxMethodCalls) {
                                            archiveMaxMethodCalls = calls.size();
                                        }
                                        for (MethodCall mc : calls) {
                                            if (mc.branchTrace != null) archiveBranchEntries += mc.branchTrace.size();
                                        }
                                    }
                                }
                            }
                            sb.append(String.format("  Traces (archive, %d solutions): method calls=%d (max in one=%d), branch entries=%d%n",
                                    archiveSolutions.size(), archiveMethodCalls, archiveMaxMethodCalls, archiveBranchEntries));
                        }
                    } catch (Exception e) {
                        // archive diagnostics unavailable
                    }
                }
            } catch (Exception e) {
                // ignore
            }

            logger.warn(sb.toString());
        } catch (Exception e) {
            logger.warn("Failed to collect memory diagnostics: {}", e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void searchStarted(GeneticAlgorithm<T> algorithm) {
        ga = algorithm;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void iteration(GeneticAlgorithm<T> algorithm) {
        // TODO Auto-generated method stub

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void searchFinished(GeneticAlgorithm<T> algorithm) {
        // TODO Auto-generated method stub

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void fitnessEvaluation(T individual) {
        String reason = exceededResource();
        if (reason != null) {
            /*
             * TODO: for now, we just stop the search. in case of running out of memory, other options could
             * be to reduce the population size, eg by using "removeWorstIndividuals". but before that,
             * "calculateFitness" need to be-refactored
             */
            stopComputation = true;
            ga.addStoppingCondition(this);
            logger.warn("Shutting down the search due to resource limit: {}", reason);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void modification(T individual) {
        // TODO Auto-generated method stub

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void forceCurrentValue(long value) {
        // TODO Auto-generated method stub

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getCurrentValue() {
        // TODO Auto-generated method stub
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getLimit() {
        // TODO Auto-generated method stub
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isFinished() {
        return stopComputation;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() {
        stopComputation = false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setLimit(long limit) {
        // TODO Auto-generated method stub

    }

}
