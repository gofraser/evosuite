/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors
 */
package org.evosuite.testcase.synthesizer;

/**
 * Fixed construction-policy limits shared by eligibility and runtime repair.
 */
public final class ObjectRulePolicy {

    public static final int MAX_FAILED_ATTEMPTS = 3;
    public static final int MAX_EXTRA_EXECUTIONS = 1;

    private ObjectRulePolicy() {
    }
}
