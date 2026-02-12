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
package org.evosuite.continuous;

import org.evosuite.Properties;
import org.evosuite.Properties.AvailableSchedule;

/**
 * This class contain the starting, fixed configurations for CTG.
 *
 * @author arcuri
 */
public class CtgConfiguration {

    /**
     * To run a job, you need a minimum of RAM.
     * If not enough RAM, then no point in even trying to start
     * a search.
     * Note: this include the memory of both the master and
     * clients together.
     */
    protected final int minimumMemoryPerJobMB = 500;

    /**
     * How much max memory should be used at the same time
     * among all the parallel CTG runs.
     */
    public final int totalMemoryInMB;

    /**
     * Number of cores CTG is allowed to use.
     */
    public final int numberOfCores;

    /**
     * for how long CTG is allowed to run.
     */
    public final int timeInMinutes;

    /**
     * The minimum amount of minutes a search/job should run.
     * Less than that, and there would be no point to even run
     * the search.
     */
    public final int minMinutesPerJob;

    /**
     * Should we call home to upload status/usage statistics.
     */
    public final boolean callHome;

    /**
     * The type of job scheduler CTG will use.
     */
    public final AvailableSchedule schedule;

    /**
     * Extra parameters for the test data generation jobs.
     * Should only be used for experiments/debugging.
     */
    public final String extraArgs;


    /**
     * Constructs a {@link CtgConfiguration} object.
     *
     * @param totalMemoryInMB total memory in MB
     * @param numberOfCores number of cores
     * @param timeInMinutes time in minutes
     * @param minMinutesPerJob minimum minutes per job
     * @param callHome whether to call home
     * @param schedule the schedule type
     */
    public CtgConfiguration(int totalMemoryInMB, int numberOfCores,
                            int timeInMinutes, int minMinutesPerJob, boolean callHome,
                            AvailableSchedule schedule) {
        this(totalMemoryInMB, numberOfCores, timeInMinutes, minMinutesPerJob, callHome, schedule, "");
    }

    /**
     * Constructs a {@link CtgConfiguration} object with extra arguments.
     *
     * @param totalMemoryInMB total memory in MB
     * @param numberOfCores number of cores
     * @param timeInMinutes time in minutes
     * @param minMinutesPerJob minimum minutes per job
     * @param callHome whether to call home
     * @param schedule the schedule type
     * @param extraArgs extra arguments
     */
    public CtgConfiguration(int totalMemoryInMB, int numberOfCores,
                            int timeInMinutes, int minMinutesPerJob, boolean callHome,
                            AvailableSchedule schedule, String extraArgs) {
        super();
        this.totalMemoryInMB = totalMemoryInMB;
        this.numberOfCores = numberOfCores;
        this.timeInMinutes = timeInMinutes;
        this.minMinutesPerJob = minMinutesPerJob;
        this.callHome = callHome;
        this.schedule = schedule;
        this.extraArgs = extraArgs;

        if (totalMemoryInMB < minimumMemoryPerJobMB) {
            throw new IllegalArgumentException("Should use at least " + minimumMemoryPerJobMB + "MB");
        }
        if (numberOfCores < 1) {
            throw new IllegalArgumentException("Need at least one core");
        }


        int requiredMemory = numberOfCores * minimumMemoryPerJobMB;
        if (totalMemoryInMB < requiredMemory) {
            throw new IllegalArgumentException(
                    "Not enough memory assigned. You need at least " + minimumMemoryPerJobMB + "MB per core."
                            + " You are using " + numberOfCores + " cores for a total of " + totalMemoryInMB
                            + "MB of memory."
                            + " Decrease the number of cores or increase the total memory. See documentation."
            );
        }

    }

    /**
     * Get instance based on values in {@link Properties}.
     *
     * @return the configuration from parameters
     */
    public static CtgConfiguration getFromParameters() {

        return new CtgConfiguration(
                Properties.CTG_MEMORY,
                Properties.CTG_CORES,
                Properties.CTG_TIME,
                Properties.CTG_MIN_TIME_PER_JOB,
                false, /* TODO: just for now, as not implemented yet */
                Properties.CTG_SCHEDULE,
                Properties.CTG_EXTRA_ARGS
        );
    }

    /**
     * Get new configuration with budget time proportional to the number of classes (and available cores).
     *
     * @param minutesPerClass minutes per class
     * @param numberOfCUTs number of CUTs
     * @return the new configuration
     * @throws IllegalArgumentException if arguments are invalid
     */
    public CtgConfiguration getWithChangedTime(int minutesPerClass, int numberOfCUTs) throws IllegalArgumentException {

        if (minutesPerClass < 0) {
            throw new IllegalArgumentException("Invalid value for minutesPerClass:" + minutesPerClass);
        }
        if (numberOfCUTs < 0) {
            throw new IllegalArgumentException("Invalid value for numberOfCUTs:" + numberOfCUTs);
        }

        int time = (int) Math.ceil((minutesPerClass * numberOfCUTs) / (double) this.getNumberOfUsableCores());

        return new CtgConfiguration(
                this.totalMemoryInMB,
                this.numberOfCores,
                time,
                this.minMinutesPerJob,
                this.callHome,
                this.schedule,
                this.extraArgs
        );
    }


    /**
     * Returns the number of usable cores.
     *
     * @return number of usable cores
     */
    public int getNumberOfUsableCores() {

        return numberOfCores;

        //shouldn't silently reduce number of cores if not enough memory
        // if(numberOfCores * minimumMemoryPerJobMB <=  totalMemoryInMB) {
        // return numberOfCores;
        // } else {
        // return totalMemoryInMB / minimumMemoryPerJobMB;
        // }
    }

    /**
     * Returns the constant memory per job in MB.
     *
     * @return constant memory per job
     */
    public int getConstantMemoryPerJob() {
        return totalMemoryInMB / getNumberOfUsableCores();
    }
}
