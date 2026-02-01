package org.evosuite.coverage.method;

import org.evosuite.Properties;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

public class MethodCoverageFactoryTest {

    @Before
    public void setUp() {
        Properties.TARGET_CLASS = java.util.ArrayList.class.getName();
    }

    @Test
    public void testMethodCoverageFactory() {
        MethodCoverageFactory factory = new MethodCoverageFactory();
        List<MethodCoverageTestFitness> goals = factory.getCoverageGoals();
        Assert.assertFalse(goals.isEmpty());

        boolean foundConstructor = false;
        boolean foundAdd = false;

        for (MethodCoverageTestFitness goal : goals) {
            if (goal.getMethod().startsWith("<init>")) foundConstructor = true;
            if (goal.getMethod().startsWith("add")) foundAdd = true;
        }

        Assert.assertTrue("Constructor not found", foundConstructor);
        Assert.assertTrue("add not found", foundAdd);
    }

    @Test
    public void testMethodNoExceptionCoverageFactory() {
        MethodNoExceptionCoverageFactory factory = new MethodNoExceptionCoverageFactory();
        List<MethodNoExceptionCoverageTestFitness> goals = factory.getCoverageGoals();
        Assert.assertFalse(goals.isEmpty());

        boolean foundConstructor = false;
        boolean foundAdd = false;

        for (MethodNoExceptionCoverageTestFitness goal : goals) {
            if (goal.getMethod().startsWith("<init>")) foundConstructor = true;
            if (goal.getMethod().startsWith("add")) foundAdd = true;
        }

        Assert.assertTrue("Constructor not found", foundConstructor);
        Assert.assertTrue("add not found", foundAdd);
    }

    @Test
    public void testMethodTraceCoverageFactory() {
        MethodTraceCoverageFactory factory = new MethodTraceCoverageFactory();
        List<MethodTraceCoverageTestFitness> goals = factory.getCoverageGoals();
        Assert.assertFalse(goals.isEmpty());

        boolean foundConstructor = false;
        boolean foundAdd = false;

        for (MethodTraceCoverageTestFitness goal : goals) {
            if (goal.getMethod().startsWith("<init>")) foundConstructor = true;
            if (goal.getMethod().startsWith("add")) foundAdd = true;
        }

        Assert.assertTrue("Constructor not found", foundConstructor);
        Assert.assertTrue("add not found", foundAdd);
    }
}
