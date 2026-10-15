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
package org.evosuite.continuous;

import com.examples.with.different.packagename.continuous.MoreBranches;
import com.examples.with.different.packagename.continuous.SomeBranches;
import org.evosuite.Properties.AvailableSchedule;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.continuous.job.JobDefinition;
import org.evosuite.continuous.job.JobScheduler;
import org.evosuite.continuous.project.ProjectAnalyzer;
import org.evosuite.continuous.project.ProjectStaticData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CtgTimeTest {

    @BeforeAll
    public static void initClass() {
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
    }

    @Test
    public void minutesAreConvertedToSeconds() {
        CtgConfiguration conf = new CtgConfiguration(1000, 1, 3, 1, false, AvailableSchedule.BUDGET);
        assertEquals(180, conf.timeInSeconds);
        assertEquals(60, conf.minSecondsPerJob);

        CtgConfiguration perClass = conf.getWithChangedTime(2, 3);
        assertEquals(360, perClass.timeInSeconds);
        assertEquals(60, perClass.minSecondsPerJob);
    }

    @Test
    public void aBudgetPerClassBelowTheMinimumLowersTheMinimum() {
        CtgConfiguration conf = new CtgConfiguration(2000, 2, 3, 1, false, AvailableSchedule.BUDGET)
                .getWithChangedTimeInSeconds(30, 4);

        assertEquals(60, conf.timeInSeconds, "4 classes of 30s on 2 cores");
        assertEquals(30, conf.minSecondsPerJob);
    }

    @Test
    public void scheduledJobsGetTheSecondsPerClass() {
        ProjectStaticData data = new ProjectAnalyzer(new String[]{
                SomeBranches.class.getName(), MoreBranches.class.getName()}).analyze();
        CtgConfiguration conf = new CtgConfiguration(1000, 1, 3, 1, false, AvailableSchedule.SIMPLE)
                .getWithChangedTimeInSeconds(30, 2);

        List<JobDefinition> jobs = new JobScheduler(data, conf).createNewSchedule();

        assertEquals(2, jobs.size());
        for (JobDefinition job : jobs) {
            assertEquals(30, job.seconds);
        }
    }
}
