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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.executionmode;

import org.evosuite.runtime.ReflectionAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client must learn which of the module opens on its command line are EvoSuite's own
 * (hidden from the SUT) and which the user gave EvoSuite's JVM (meant for the SUT).
 */
public class ModuleAccessForwardingTest {

    @AfterEach
    public void clearInherited() {
        System.clearProperty(ReflectionAccess.PRIVATE_ACCESS_PROPERTY);
    }

    private static String privateAccessValue(List<String> cmdLine) {
        String prefix = "-D" + ReflectionAccess.PRIVATE_ACCESS_PROPERTY + "=";
        for (String arg : cmdLine) {
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }

    @Test
    public void everyEvoSuiteOpenNotGrantedByTheUserIsPrivate() {
        Assumptions.assumeTrue(Runtime.version().feature() >= 9);
        List<String> cmdLine = new ArrayList<>();
        ExecutionModeUtils.addCommonModuleOpens(cmdLine);

        String value = privateAccessValue(cmdLine);
        assertTrue(value != null, "the client must be told which opens are EvoSuite's own");
        Set<String> privateAccess = new HashSet<>(Arrays.asList(value.split(",")));
        Set<String> userGranted = ExecutionModeUtils.userGrantedModuleAccess();

        for (int i = 0; i + 1 < cmdLine.size(); i++) {
            if ("--add-opens".equals(cmdLine.get(i)) || "--add-exports".equals(cmdLine.get(i))) {
                String pair = cmdLine.get(i + 1).substring(0, cmdLine.get(i + 1).indexOf('='));
                assertEquals(!userGranted.contains(pair), privateAccess.contains(pair), pair);
            }
        }
        assertTrue(privateAccess.contains("java.base/java.util") || userGranted.contains("java.base/java.util"));
    }

    @Test
    public void userOpensOfThisJvmAreForwardedToTheClient() {
        Assumptions.assumeTrue(Runtime.version().feature() >= 9);
        List<String> input = ManagementFactory.getRuntimeMXBean().getInputArguments();
        Assumptions.assumeTrue(input.stream().anyMatch(a -> a.startsWith("--add-opens")),
                "surefire starts this JVM with --add-opens");

        List<String> forwarded = ExecutionModeUtils.userModuleAccessArguments();
        assertFalse(forwarded.isEmpty());
        for (int i = 0; i < forwarded.size(); i += 2) {
            assertTrue(forwarded.get(i).startsWith("--add-"), forwarded.get(i));
            assertTrue(forwarded.get(i + 1).endsWith("=ALL-UNNAMED"), forwarded.get(i + 1));
        }
        List<String> cmdLine = new ArrayList<>();
        ExecutionModeUtils.addCommonModuleOpens(cmdLine);
        assertTrue(cmdLine.containsAll(forwarded));
    }

    @Test
    public void inheritedPrivateListIsNotMistakenForUserGrants() {
        Assumptions.assumeTrue(Runtime.version().feature() >= 9);
        Set<String> before = ExecutionModeUtils.userGrantedModuleAccess();
        Assumptions.assumeFalse(before.isEmpty());
        String one = before.iterator().next();

        // a master spawned by EvoSuite inherits the list of what was EvoSuite's, not the user's
        System.setProperty(ReflectionAccess.PRIVATE_ACCESS_PROPERTY, one);
        Set<String> after = ExecutionModeUtils.userGrantedModuleAccess();
        assertFalse(after.contains(one));
        assertEquals(before.size() - 1, after.size());
    }
}
