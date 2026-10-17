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
package org.evosuite.setup;

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A public method taking a member class that reflects as public but is private in source (see
 * {@link SourcePrivateMemberFixture}): no test can write an argument for it.
 */
public class SourcePrivateParameterMethodTest {

    private String originalClassPrefix;
    private SourcePrivateMemberFixture fixture;

    @BeforeEach
    public void setUp() throws Exception {
        originalClassPrefix = Properties.CLASS_PREFIX;
        Properties.CLASS_PREFIX = SourcePrivateMemberFixture.PACKAGE;
        fixture = new SourcePrivateMemberFixture();
    }

    @AfterEach
    public void tearDown() {
        Properties.CLASS_PREFIX = originalClassPrefix;
    }

    @Test
    public void reflectionDisagreesWithTheEnclosingTable() {
        assertTrue(Modifier.isPublic(fixture.inner.getModifiers()));
        assertTrue(Modifier.isPrivate(TestUsageChecker.getSourceModifiers(fixture.inner)));
    }

    @Test
    public void methodTakingTheSourcePrivateTypeIsNotUsable() throws Exception {
        Method use = fixture.outer.getMethod("use", fixture.inner);
        assertTrue(TestUsageChecker.isNotCallableFromSource(use));
        assertFalse(TestUsageChecker.canUse(use, fixture.outer),
                "canUse must agree with isNotCallableFromSource: callers that only ask canUse"
                        + " would otherwise render a call no test can compile");
    }
}
