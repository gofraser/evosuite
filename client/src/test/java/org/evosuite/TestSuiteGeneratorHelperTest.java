package org.evosuite;

import org.evosuite.Properties.Criterion;
import org.evosuite.instrumentation.LinePool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestSuiteGeneratorHelperTest {

    private String originalTargetClass;

    @Before
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        LinePool.reset();
    }

    @After
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        LinePool.reset();
    }

    @Test
    public void removesLineCriteriaWhenOnlyInvalidLineNumbersExist() {
        Properties.TARGET_CLASS = "com.example.Foo";
        LinePool.addLine("com.example.Foo", "m()V", 0);
        LinePool.addLine("com.example.Foo$Inner", "n()V", -1);

        Criterion[] filtered = TestSuiteGeneratorHelper.removeDebugInfoDependentCriteriaIfMissing(
                new Criterion[]{Criterion.LINE, Criterion.BRANCH, Criterion.ONLYLINE});

        Assert.assertArrayEquals(new Criterion[]{Criterion.BRANCH}, filtered);
    }

    @Test
    public void keepsLineCriteriaWhenValidLineNumbersExist() {
        Properties.TARGET_CLASS = "com.example.Foo";
        LinePool.addLine("com.example.Foo", "m()V", 12);
        LinePool.addLine("com.example.Foo", "m()V", 0);

        Criterion[] filtered = TestSuiteGeneratorHelper.removeDebugInfoDependentCriteriaIfMissing(
                new Criterion[]{Criterion.LINE, Criterion.BRANCH});

        Assert.assertArrayEquals(new Criterion[]{Criterion.LINE, Criterion.BRANCH}, filtered);
    }

    @Test
    public void keepsCriteriaUnchangedWhenNoLineCriterionIsRequested() {
        Properties.TARGET_CLASS = "com.example.Foo";

        Criterion[] filtered = TestSuiteGeneratorHelper.removeDebugInfoDependentCriteriaIfMissing(
                new Criterion[]{Criterion.BRANCH, Criterion.EXCEPTION});

        Assert.assertArrayEquals(new Criterion[]{Criterion.BRANCH, Criterion.EXCEPTION}, filtered);
    }
}
