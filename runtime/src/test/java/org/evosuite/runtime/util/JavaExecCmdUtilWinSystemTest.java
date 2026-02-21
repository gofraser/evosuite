/**
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 * <p>
 * This file is part of EvoSuite.
 * <p>
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 * <p>
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 * <p>
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.runtime.util;

import org.apache.commons.lang3.StringUtils;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringStartsWith;
import org.hamcrest.junit.MatcherAssume;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.MethodName;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;
import uk.org.webcompere.systemstubs.properties.SystemProperties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(SystemStubsExtension.class)
@TestMethodOrder(MethodName.class)
public class JavaExecCmdUtilWinSystemTest {

    private static final String SEPARATOR = "\\";
    private static final String JAVA_HOME_SYSTEM = System.getenv("JAVA_HOME");
    private static final String JAVA_HOME_MOCK_PATH =
            "c:" + SEPARATOR + "sample" + SEPARATOR + "windows path" + SEPARATOR + "jdk_8";
    private static final String WIN_MOCK_OS = "Windows 10";
    private static final String ORIG_OS = System.getProperty("os.name");

    @SystemStub
    public EnvironmentVariables environmentVariables = new EnvironmentVariables();

    @SystemStub
    public SystemProperties systemProperties = new SystemProperties("os.name", WIN_MOCK_OS,
                    "file.separator", SEPARATOR,
                    "java.home", JAVA_HOME_MOCK_PATH);

    @BeforeEach
    public void initTestEnvironment() {
        environmentVariables.set("JAVA_HOME", JAVA_HOME_MOCK_PATH);
    }

    @org.junit.jupiter.api.Test
    public void winNeverNull() {
        assertNotNull(JavaExecCmdUtil.getJavaBinExecutablePath());
        assertNotNull(JavaExecCmdUtil.getJavaBinExecutablePath(true));
        assertNotNull(JavaExecCmdUtil.getJavaBinExecutablePath(false));
    }

    @org.junit.jupiter.api.Test
    public void winMockEnvIsOk() {
        // run test only on windows build
        MatcherAssume.assumeThat(ORIG_OS.toLowerCase(), StringStartsWith.startsWith("win"));

        assertThat(System.getenv("JAVA_HOME"), IsEqual.equalTo(JAVA_HOME_MOCK_PATH));
        assertThat(System.getProperty("os.name"), IsEqual.equalTo(WIN_MOCK_OS));
        assertFalse(StringUtils.isEmpty(JAVA_HOME_SYSTEM));
    }

    @org.junit.jupiter.api.Test
    public void winOldBehaviorJava() {
        // return "java" value
        assertThat(JavaExecCmdUtil.getJavaBinExecutablePath(), IsEqual.equalTo("java"));
    }

    @org.junit.jupiter.api.Test
    public void winOldBehaviorJavaCmd() {
        // return JAVA_CMD value
        assertThat(JavaExecCmdUtil.getJavaBinExecutablePath(true),
                IsEqual.equalTo(
                        JAVA_HOME_MOCK_PATH + SEPARATOR + "bin" + SEPARATOR + "java"));
    }

    @org.junit.jupiter.api.Test
    public void winNewBehavior() {
        // run test only on windows build
        MatcherAssume.assumeThat(ORIG_OS.toLowerCase(), StringStartsWith.startsWith("win"));

        JavaExecCmdUtil.getOsName().filter(osName -> osName.startsWith("Windows")).ifPresent(os ->
                {
                    // set correct java_home and get real path to java.exe
                    environmentVariables.set("JAVA_HOME", JAVA_HOME_SYSTEM);
                    assertThat(JavaExecCmdUtil.getJavaBinExecutablePath(),
                            IsEqual.equalTo(
                                    JAVA_HOME_SYSTEM + SEPARATOR + "bin" + SEPARATOR + "java.exe"));
                }
        );
    }
}
