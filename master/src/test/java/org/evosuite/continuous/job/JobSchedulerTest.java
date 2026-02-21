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
package org.evosuite.continuous.job;

import java.util.List;
import java.util.Set;

import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.continuous.CtgConfiguration;
import org.evosuite.Properties.AvailableSchedule;
import org.evosuite.continuous.project.ProjectAnalyzer;
import org.evosuite.continuous.project.ProjectStaticData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.examples.with.different.packagename.continuous.BaseForSeeding;
import com.examples.with.different.packagename.continuous.MoreBranches;
import com.examples.with.different.packagename.continuous.NoBranches;
import com.examples.with.different.packagename.continuous.OnlyAbstract;
import com.examples.with.different.packagename.continuous.OnlyAbstractImpl;
import com.examples.with.different.packagename.continuous.Simple;
import com.examples.with.different.packagename.continuous.SomeBranches;
import com.examples.with.different.packagename.continuous.SomeInterface;
import com.examples.with.different.packagename.continuous.SomeInterfaceImpl;
import com.examples.with.different.packagename.continuous.Trivial;
import com.examples.with.different.packagename.continuous.UsingSimpleAndTrivial;

public class JobSchedulerTest {

    @BeforeAll
    public static void initClass() {
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
    }

    @Test
    public void testBudget() {

        String[] cuts = new String[]{SomeInterface.class.getName(),
                NoBranches.class.getName(), SomeBranches.class.getName(),
                MoreBranches.class.getName()};

        ProjectAnalyzer analyzer = new ProjectAnalyzer(cuts);
        ProjectStaticData data = analyzer.analyze();

        int cores = 2;
        int memory = 1400;
        int budget = 2;

        CtgConfiguration conf = new CtgConfiguration(memory, cores, budget, 1, false, AvailableSchedule.BUDGET);

        JobScheduler scheduler = new JobScheduler(data, conf);

        List<JobDefinition> jobs = scheduler.createNewSchedule();
        Assertions.assertNotNull(jobs);
        //we have 4 classes, but one is an interface
        Assertions.assertEquals(3, jobs.size());

        for (JobDefinition job : jobs) {
            Assertions.assertEquals(700, job.memoryInMB);
        }

        Assertions.assertEquals(MoreBranches.class.getName(), jobs.get(0).cut);
        Assertions.assertEquals(SomeBranches.class.getName(), jobs.get(1).cut);
        Assertions.assertEquals(NoBranches.class.getName(), jobs.get(2).cut);

        long dif01 = jobs.get(0).seconds - jobs.get(1).seconds;
        long dif12 = jobs.get(1).seconds - jobs.get(2).seconds;

        Assertions.assertTrue(dif01 > 0, "" + dif01);
        Assertions.assertTrue(dif12 > 0, "" + dif12);

        int sum = jobs.get(0).seconds + jobs.get(1).seconds + jobs.get(2).seconds;
        Assertions.assertTrue(sum <= (cores * budget * 60), "wrong value " + sum);
    }

    @Test
    public void testNonExceedingBudget() {

        String[] cuts = new String[]{
                NoBranches.class.getName(),
                Trivial.class.getName(),
                MoreBranches.class.getName()};

        ProjectAnalyzer analyzer = new ProjectAnalyzer(cuts);
        ProjectStaticData data = analyzer.analyze();

        int cores = 2;
        int memory = 1400;
        int budget = 10;

        CtgConfiguration conf = new CtgConfiguration(memory, cores, budget, 1, false, AvailableSchedule.BUDGET);

        JobScheduler scheduler = new JobScheduler(data, conf);

        List<JobDefinition> jobs = scheduler.createNewSchedule();
        Assertions.assertNotNull(jobs);
        Assertions.assertEquals(3, jobs.size());

        for (JobDefinition job : jobs) {
            Assertions.assertEquals(700, job.memoryInMB);
        }

        Assertions.assertEquals(MoreBranches.class.getName(), jobs.get(0).cut);
        Assertions.assertEquals(Trivial.class.getName(), jobs.get(1).cut);
        Assertions.assertEquals(NoBranches.class.getName(), jobs.get(2).cut);

        long dif01 = jobs.get(0).seconds - jobs.get(1).seconds;
        long dif12 = jobs.get(1).seconds - jobs.get(2).seconds;

        Assertions.assertTrue(dif01 > 0, "" + dif01);
        Assertions.assertTrue(dif12 > 0, "" + dif12);

        int sum = jobs.get(0).seconds + jobs.get(1).seconds + jobs.get(2).seconds;
        Assertions.assertTrue(sum <= (cores * budget * 60), "wrong value " + sum);

        for (JobDefinition job : jobs) {
            Assertions.assertTrue(job.seconds <= budget * 60, "wrong " + job.seconds);
        }
    }

    @Test
    public void testSimple() {

        String[] cuts = new String[]{SomeInterface.class.getName(),
                NoBranches.class.getName(), SomeBranches.class.getName(),
                MoreBranches.class.getName()};

        ProjectAnalyzer analyzer = new ProjectAnalyzer(cuts);
        ProjectStaticData data = analyzer.analyze();

        int cores = 2;
        int memory = 1400;
        int budget = 2;

        CtgConfiguration conf = new CtgConfiguration(memory, cores, budget, 1, false, AvailableSchedule.SIMPLE);
        JobScheduler scheduler = new JobScheduler(data, conf);


        List<JobDefinition> jobs = scheduler.createNewSchedule();
        Assertions.assertNotNull(jobs);
        //we have 4 classes, but one is an interface
        Assertions.assertEquals(3, jobs.size());

        for (JobDefinition job : jobs) {
            Assertions.assertEquals(700, job.memoryInMB);
        }

        Assertions.assertEquals(jobs.get(0).seconds, jobs.get(1).seconds);
        Assertions.assertEquals(jobs.get(2).seconds, jobs.get(1).seconds);

        int sum = jobs.get(0).seconds + jobs.get(1).seconds + jobs.get(2).seconds;
        Assertions.assertTrue(sum <= (cores * budget * 60), "wrong value " + sum);
    }

    @Test
    public void testSeeding() {

        String[] cuts = new String[]{BaseForSeeding.class.getName(),
                NoBranches.class.getName(), MoreBranches.class.getName(),
                SomeInterface.class.getName(), SomeInterfaceImpl.class.getName(),
                SomeBranches.class.getName(), OnlyAbstract.class.getName(),
                OnlyAbstractImpl.class.getName(), Trivial.class.getName()};

        ProjectAnalyzer analyzer = new ProjectAnalyzer(cuts);
        ProjectStaticData data = analyzer.analyze();

        int cores = 3;
        int memory = 1800;
        int budget = 3;

        CtgConfiguration conf = new CtgConfiguration(memory, cores, budget, 1, false, AvailableSchedule.SEEDING);
        JobScheduler scheduler = new JobScheduler(data, conf);

        List<JobDefinition> jobs = scheduler.createNewSchedule();
        Assertions.assertNotNull(jobs);

        for (JobDefinition job : jobs) {
            Assertions.assertEquals(600, job.memoryInMB);
        }

        /*
         * FIXME: in the long run, abstract class with no code should be skipped.
         * at the moment, they are not, because there is default constructor that
         * is automatically added
         */

        //we have 9 classes, but 2 have no code
        Assertions.assertEquals(8, jobs.size(), "Wrong number of jobs: " + jobs.toString()); //FIXME should be 7

        JobDefinition seeding = null;
        for (JobDefinition job : jobs) {
            if (job.cut.equals(BaseForSeeding.class.getName())) {
                seeding = job;
                break;
            }
        }
        Assertions.assertNotNull(seeding);

        Set<String> in = seeding.inputClasses;
        Assertions.assertNotNull(in);
        System.out.println(in.toString());
        Assertions.assertTrue(in.contains(NoBranches.class.getName()));
        Assertions.assertTrue(in.contains(SomeBranches.class.getName()));
        Assertions.assertTrue(in.contains(SomeInterfaceImpl.class.getName()));
        Assertions.assertTrue(in.contains(OnlyAbstractImpl.class.getName()));
        Assertions.assertEquals(5, in.size()); //FIXME should be 4
    }

    @Test
    public void testSeedingOrder() {

        String[] cuts = new String[]{
                Simple.class.getName(),
                UsingSimpleAndTrivial.class.getName(),
                Trivial.class.getName(),
        };

        ProjectAnalyzer analyzer = new ProjectAnalyzer(cuts);
        ProjectStaticData data = analyzer.analyze();

        int cores = 3;
        int memory = 1800;
        int budget = 2;

        CtgConfiguration conf = new CtgConfiguration(memory, cores, budget, 1, false, AvailableSchedule.SEEDING);
        JobScheduler scheduler = new JobScheduler(data, conf);

        List<JobDefinition> jobs = scheduler.createNewSchedule();
        Assertions.assertNotNull(jobs);

        Assertions.assertEquals(3, jobs.size(), "Wrong number of jobs: " + jobs.toString());

        //UsingSimpleAndTrivial should be the last in the schedule, as it depends on the first 2
        JobDefinition seeding = jobs.get(2);
        Assertions.assertNotNull(seeding);
        Assertions.assertEquals(UsingSimpleAndTrivial.class.getName(), seeding.cut);

        Set<String> in = seeding.inputClasses;
        Assertions.assertNotNull(in);
        System.out.println(in.toString());
        Assertions.assertTrue(in.contains(Simple.class.getName()));
        Assertions.assertTrue(in.contains(Trivial.class.getName()));
        Assertions.assertEquals(2, in.size());
    }


    @Test
    public void testSeedingAndBudget() {

        String[] cuts = new String[]{
                Trivial.class.getName(),
                UsingSimpleAndTrivial.class.getName(),
                Simple.class.getName(),
        };

        ProjectAnalyzer analyzer = new ProjectAnalyzer(cuts);
        ProjectStaticData data = analyzer.analyze();

        int cores = 2;
        int memory = 1800;
        int budget = 3;

        CtgConfiguration conf = new CtgConfiguration(memory, cores, budget, 1, false, AvailableSchedule.BUDGET_AND_SEEDING);
        JobScheduler scheduler = new JobScheduler(data, conf);

        List<JobDefinition> jobs = scheduler.createNewSchedule();
        Assertions.assertNotNull(jobs);

        Assertions.assertEquals(3, jobs.size(), "Wrong number of jobs: " + jobs.toString());

        //UsingSimpleAndTrivial should be the last in the schedule, as it depends on the other 2
        JobDefinition seeding = jobs.get(2);
        Assertions.assertNotNull(seeding);
        Assertions.assertEquals(UsingSimpleAndTrivial.class.getName(), seeding.cut);

        Set<String> in = seeding.inputClasses;
        Assertions.assertNotNull(in);
        System.out.println(in.toString());
        Assertions.assertTrue(in.contains(Simple.class.getName()));
        Assertions.assertTrue(in.contains(Trivial.class.getName()));
        Assertions.assertEquals(2, in.size());


        JobDefinition simple = jobs.get(0); //should be the first, as it has the highest number of branches among the jobs with no depencencies
        Assertions.assertNotNull(simple);
        Assertions.assertEquals(Simple.class.getName(), simple.cut);

        int simpleTime = jobs.get(0).seconds;
        int trivialTime = jobs.get(1).seconds;
        int seedingTime = jobs.get(2).seconds;

        System.out.println("Ordered times: " + simpleTime + ", " + trivialTime + ", " + seedingTime);

        Assertions.assertTrue(simpleTime > trivialTime);
        Assertions.assertTrue(simpleTime < seedingTime);  //seeding, even if last, it should have more time, as it has most branches
        Assertions.assertTrue(trivialTime < seedingTime);
    }


}
