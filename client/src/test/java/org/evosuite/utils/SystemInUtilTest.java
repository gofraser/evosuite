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
package org.evosuite.utils;

import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.util.SystemInUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Scanner;
import java.util.concurrent.TimeUnit;

public class SystemInUtilTest {

    @AfterEach
    public void tearDown() {
        SystemInUtil.resetSingleton();
    }

    @Test
    @Timeout(value = 3000, unit = TimeUnit.MILLISECONDS)
    public void testDoubleExecution() {

        RuntimeSettings.mockSystemIn = true;

        String data = "Hello World!";
        SystemInUtil.getInstance().initForTestCase();
        SystemInUtil.addInputLine(data);
        Scanner scanner = new Scanner(System.in);
        String first = scanner.nextLine();
        scanner.close();
        Assertions.assertEquals(data, first);

        //now add the same again
        SystemInUtil.addInputLine(data);
        scanner = new Scanner(System.in);
        String second = scanner.nextLine();
        scanner.close();
        Assertions.assertEquals(data, second);
    }

}
