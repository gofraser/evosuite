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
package org.evosuite.determinism;

import com.examples.with.different.packagename.localsearch.IsstaFoo;
import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.SystemTestBase;
import org.evosuite.utils.LoggingUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

import static org.junit.Assert.assertEquals;

/**
 * Created by Andrea Arcuri on 19/10/15.
 */
public class BaseDeterminismSystemTest {

    @BeforeClass
    public static void initClass() {
        LoggingUtils.changeLogbackFile("logback_for_determinism_check.xml");
        Properties.IS_RUNNING_A_SYSTEM_TEST = true;
    }

    @AfterClass
    public static void tearDownClass() {
        LoggingUtils.changeLogbackFile("logback.xml");
        Properties.IS_RUNNING_A_SYSTEM_TEST = false;
    }


    @Test
    public void testBase() {
        checkDeterminism(com.examples.with.different.packagename.TrivialInt.class);
    }

    @Test
    public void testLS() {
        checkDeterminism(IsstaFoo.class, () -> {
            Properties.DSE_PROBABILITY = 0.0;
            Properties.LOCAL_SEARCH_PROBABILITY = 1.0;
            Properties.LOCAL_SEARCH_RATE = 1;
            Properties.LOCAL_SEARCH_BUDGET_TYPE = Properties.LocalSearchBudgetType.TESTS;
            Properties.LOCAL_SEARCH_BUDGET = 100;
            Properties.SEARCH_BUDGET = 5000;
        });
    }


    @Test
    public void testDSE() {
        checkDeterminism(IsstaFoo.class, () -> {
            Properties.DSE_PROBABILITY = 1.0;
            Properties.LOCAL_SEARCH_PROBABILITY = 1.0;
            Properties.LOCAL_SEARCH_RATE = 1;
            Properties.LOCAL_SEARCH_BUDGET_TYPE = Properties.LocalSearchBudgetType.TESTS;
            Properties.LOCAL_SEARCH_BUDGET = 100;
            Properties.SEARCH_BUDGET = 5000;
        });
    }

    public static void checkDeterminism(Class<?> target) {
        checkDeterminism(target, null);
    }

    public static void checkDeterminism(Class<?> target, Runnable initializer) {

        //dry run, needed to avoid logs of static initializers that are called only once
        run(target, initializer);

        String first = run(target, initializer);
        String second = run(target, initializer);

        if (!first.equals(second)) {
            System.err.println("Determinism check failed for target: " + target.getCanonicalName());
            System.err.println(diffSummary(first, second));
        }
        assertEquals(first, second);
    }

    private static String run(Class<?> target) {
        return run(target, null);
    }

    private static String run(Class<?> target, Runnable initializer) {

        SystemTestBase scaffolding = new SystemTestBase();

        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        PrintStream outStream = new PrintStream(byteStream);
        PrintStream latestOut = System.out;
        System.setOut(outStream);


        scaffolding.setDefaultPropertiesForTestCases(); //@Before
        // Avoid time-based statistics output that breaks determinism checks
        Properties.NEW_STATISTICS = false;
        Properties.STATISTICS_BACKEND = Properties.StatisticsBackend.NONE;
        Properties.CRITERION = new Properties.Criterion[]{
                Properties.Criterion.LINE, Properties.Criterion.BRANCH,
                Properties.Criterion.EXCEPTION, Properties.Criterion.WEAKMUTATION,
                Properties.Criterion.OUTPUT, Properties.Criterion.METHOD,
                Properties.Criterion.METHODNOEXCEPTION, Properties.Criterion.CBRANCH};

        if (initializer != null) {
            initializer.run();
        }


        boolean defaultPrint = Properties.PRINT_TO_SYSTEM;
        Properties.PRINT_TO_SYSTEM = true;

        try {
            EvoSuite evosuite = new EvoSuite();
            String targetClass = target.getCanonicalName();

            Properties.TARGET_CLASS = targetClass;
            String[] command = new String[]{"-generateSuite", "-class", targetClass};

            evosuite.parseCommandLine(command);
        } finally {
            scaffolding.resetStaticVariables();//@After
            System.setOut(latestOut);
            Properties.PRINT_TO_SYSTEM = defaultPrint;
        }

        return filter(byteStream.toString());
    }

    private static String filter(String s) {

        List<String> skip = Arrays.asList("sun.reflect.GeneratedMethodAccessor");

        StringBuffer buffer = new StringBuffer(s.length());
        Scanner scanner = new Scanner(s);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            if (skip.stream().anyMatch(k -> line.contains(k))) {
                continue;
            }
            buffer.append(line);
            buffer.append("\n");
        }

        return buffer.toString();
    }

    private static String diffSummary(String first, String second) {
        String[] a = first.split("\n", -1);
        String[] b = second.split("\n", -1);
        int min = Math.min(a.length, b.length);
        int idx = -1;
        for (int i = 0; i < min; i++) {
            if (!a[i].equals(b[i])) {
                idx = i;
                break;
            }
        }
        if (idx == -1 && a.length != b.length) {
            idx = min;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("First length: ").append(a.length).append(" lines, ");
        sb.append("Second length: ").append(b.length).append(" lines\n");
        if (idx == -1) {
            sb.append("No differing line found, but strings are not equal.\n");
            return sb.toString();
        }
        sb.append("First differing line index: ").append(idx + 1).append("\n");
        sb.append("First:  ").append(idx < a.length ? a[idx] : "<no line>").append("\n");
        sb.append("Second: ").append(idx < b.length ? b[idx] : "<no line>").append("\n");
        sb.append("Context:\n");
        int from = Math.max(0, idx - 2);
        int to = Math.min(Math.max(a.length, b.length), idx + 3);
        for (int i = from; i < to; i++) {
            String left = i < a.length ? a[i] : "<no line>";
            String right = i < b.length ? b[i] : "<no line>";
            sb.append("  ").append(i + 1).append(": ").append(left).append("\n");
            sb.append("  ").append(i + 1).append(": ").append(right).append("\n");
        }
        return sb.toString();
    }
}
