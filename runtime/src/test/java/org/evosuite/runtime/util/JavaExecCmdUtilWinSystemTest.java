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
package org.evosuite.runtime.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Optional;

public class JavaExecCmdUtilWinSystemTest {

    @Test
    public void winNeverNull() {
        Assertions.assertNotNull(JavaExecCmdUtil.getJavaBinExecutablePath());
        Assertions.assertNotNull(JavaExecCmdUtil.getJavaBinExecutablePath(true));
        Assertions.assertNotNull(JavaExecCmdUtil.getJavaBinExecutablePath(false));
    }

    @Test
    public void winFallbackToJavaWhenFullPathNotRequiredAndEnvPathUnavailable() {
        String originalOs = System.getProperty("os.name");
        String originalSep = System.getProperty("file.separator");
        String originalJavaHome = System.getProperty("java.home");
        try {
            System.setProperty("os.name", "Windows 10");
            System.setProperty("file.separator", "\\");
            System.setProperty("java.home", "c:\\evosuite\\fake-jdk");

            String expected = expectedPath(false, Optional.of("Windows 10"), "\\", "c:\\evosuite\\fake-jdk");
            Assertions.assertEquals(expected, JavaExecCmdUtil.getJavaBinExecutablePath(false));
        } finally {
            restoreProperty("os.name", originalOs);
            restoreProperty("file.separator", originalSep);
            restoreProperty("java.home", originalJavaHome);
        }
    }

    @Test
    public void winFallbackToJavaCmdWhenFullPathRequiredAndEnvPathUnavailable() {
        String originalOs = System.getProperty("os.name");
        String originalSep = System.getProperty("file.separator");
        String originalJavaHome = System.getProperty("java.home");
        try {
            System.setProperty("os.name", "Windows 10");
            System.setProperty("file.separator", "\\");
            System.setProperty("java.home", "c:\\evosuite\\fake-jdk");

            String expected = expectedPath(true, Optional.of("Windows 10"), "\\", "c:\\evosuite\\fake-jdk");
            Assertions.assertEquals(expected, JavaExecCmdUtil.getJavaBinExecutablePath(true));
        } finally {
            restoreProperty("os.name", originalOs);
            restoreProperty("file.separator", originalSep);
            restoreProperty("java.home", originalJavaHome);
        }
    }

    private static String expectedPath(boolean fullRequired,
                                       Optional<String> osName,
                                       String sep,
                                       String javaHomeProperty) {
        String javaHomeEnv = System.getenv("JAVA_HOME");
        if (javaHomeEnv != null) {
            String exeSuffix = osName.filter(name -> name.toLowerCase().contains("windows"))
                    .map(name -> ".exe")
                    .orElse("");
            File fromEnv = new File(javaHomeEnv + sep + "bin" + sep + "java" + exeSuffix);
            if (fromEnv.exists()) {
                return fromEnv.getPath();
            }
        }
        String javaCmd = javaHomeProperty + sep + "bin" + sep + "java";
        return fullRequired ? javaCmd : "java";
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
