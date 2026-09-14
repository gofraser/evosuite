/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.testcase.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestCaseExecutorTimeoutRecoveryTest {

    @Test
    public void recognizesGroovyDynamicBootstrapWithoutMatchingApplicationCode() {
        StackTraceElement[] bootstrap = {
                new StackTraceElement("org.codehaus.groovy.vmplugin.v8.CacheableCallSite",
                        "getAndPut", "CacheableCallSite.java", 74),
                new StackTraceElement("groovy.text.markup.MarkupTemplateEngine",
                        "<init>", "MarkupTemplateEngine.java", 94)
        };
        StackTraceElement[] application = {
                new StackTraceElement("example.MyGroovyApplication",
                        "render", "MyGroovyApplication.groovy", 12)
        };

        assertTrue(TestCaseExecutor.isRuntimeBootstrapStack(bootstrap));
        assertFalse(TestCaseExecutor.isRuntimeBootstrapStack(application));
    }

    @Test
    public void liveWorkerStillRequiresCleanupAfterFutureCancellation() {
        assertTrue(TestCaseExecutor.shouldForceStopAfterCancellation(false, true));
        assertTrue(TestCaseExecutor.shouldForceStopAfterCancellation(true, false));
        assertFalse(TestCaseExecutor.shouldForceStopAfterCancellation(false, false));
    }

    @Test
    public void eitherRotationLimitStopsReplacementExecutors() {
        assertTrue(TestCaseExecutor.reachedExecutorRotationLimit(50, 0, 50, 10));
        assertTrue(TestCaseExecutor.reachedExecutorRotationLimit(1, 10, 50, 10));
        assertFalse(TestCaseExecutor.reachedExecutorRotationLimit(1, 1, 50, 10));
    }

    @Test
    public void fastEscalatesOnlyWhenStillRunningWithManyProbesOutsideProtectedBootstrap() {
        // still running, well past the threshold, not in <clinit>/bootstrap: escalate
        assertTrue(TestCaseExecutor.shouldFastEscalateAfterKillSwitch(false, true, 1500, 1000));

        // inside <clinit> or the runtime-bootstrap grace path: never force it early,
        // no matter how many probes have been seen
        assertFalse(TestCaseExecutor.shouldFastEscalateAfterKillSwitch(true, true, 1_000_000, 1000));

        // already finished by the time we checked: nothing to escalate
        assertFalse(TestCaseExecutor.shouldFastEscalateAfterKillSwitch(false, false, 1500, 1000));

        // below the threshold: could just be genuinely slow, give it the normal chain
        assertFalse(TestCaseExecutor.shouldFastEscalateAfterKillSwitch(false, true, 500, 1000));

        // exactly at the threshold is not yet "exceeds"
        assertFalse(TestCaseExecutor.shouldFastEscalateAfterKillSwitch(false, true, 1000, 1000));
        assertTrue(TestCaseExecutor.shouldFastEscalateAfterKillSwitch(false, true, 1001, 1000));
    }
}
