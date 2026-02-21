package org.evosuite.coverage.method;

import org.evosuite.Properties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

public class MethodCoverageFactoryTest {

    @BeforeEach
    public void setUp() {
        Properties.TARGET_CLASS = java.util.ArrayList.class.getName();
    }

    @Test
    public void testMethodCoverageFactory() {
        MethodCoverageFactory factory = new MethodCoverageFactory();
        List<MethodCoverageTestFitness> goals = factory.getCoverageGoals();
        Assertions.assertFalse(goals.isEmpty());

        boolean foundConstructor = false;
        boolean foundAdd = false;

        for (MethodCoverageTestFitness goal : goals) {
            if (goal.getMethod().startsWith("<init>")) foundConstructor = true;
            if (goal.getMethod().startsWith("add")) foundAdd = true;
        }

        Assertions.assertTrue(foundConstructor, "Constructor not found");
        Assertions.assertTrue(foundAdd, "add not found");
    }

    @Test
    public void testMethodNoExceptionCoverageFactory() {
        MethodNoExceptionCoverageFactory factory = new MethodNoExceptionCoverageFactory();
        List<MethodNoExceptionCoverageTestFitness> goals = factory.getCoverageGoals();
        Assertions.assertFalse(goals.isEmpty());

        boolean foundConstructor = false;
        boolean foundAdd = false;

        for (MethodNoExceptionCoverageTestFitness goal : goals) {
            if (goal.getMethod().startsWith("<init>")) foundConstructor = true;
            if (goal.getMethod().startsWith("add")) foundAdd = true;
        }

        Assertions.assertTrue(foundConstructor, "Constructor not found");
        Assertions.assertTrue(foundAdd, "add not found");
    }

    @Test
    public void testMethodTraceCoverageFactory() {
        MethodTraceCoverageFactory factory = new MethodTraceCoverageFactory();
        List<MethodTraceCoverageTestFitness> goals = factory.getCoverageGoals();
        Assertions.assertFalse(goals.isEmpty());

        boolean foundConstructor = false;
        boolean foundAdd = false;

        for (MethodTraceCoverageTestFitness goal : goals) {
            if (goal.getMethod().startsWith("<init>")) foundConstructor = true;
            if (goal.getMethod().startsWith("add")) foundAdd = true;
        }

        Assertions.assertTrue(foundConstructor, "Constructor not found");
        Assertions.assertTrue(foundAdd, "add not found");
    }
}
