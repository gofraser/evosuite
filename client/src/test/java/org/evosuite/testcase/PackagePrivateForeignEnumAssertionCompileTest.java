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
package org.evosuite.testcase;

import com.examples.with.different.packagename.hiddenpkg.HiddenStates;
import org.evosuite.Properties;
import org.evosuite.assertion.EnumAssertionCodeRenderer;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observed on liferay: an assertion on {@code com.caucho.network.listen.SocketLinkState.REQUEST_ACTIVE_KA},
 * a package-private enum of another package, which javac rejects with 'is not public in ...'.
 * Such a constant has to be compared by name.
 */
public class PackagePrivateForeignEnumAssertionCompileTest {

    private String originalTargetClass;
    private String originalClassPrefix;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        originalClassPrefix = Properties.CLASS_PREFIX;
        Properties.TARGET_CLASS = PackagePrivateForeignEnumAssertionCompileTest.class.getName();
        Properties.resetTargetClass();
        Properties.CLASS_PREFIX = PackagePrivateForeignEnumAssertionCompileTest.class.getPackage().getName();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
        Properties.CLASS_PREFIX = originalClassPrefix;
    }

    @Test
    public void constantOfPackagePrivateEnumOfAnotherPackageIsComparedByName() {
        Object constant = HiddenStates.holder().getState();
        TestCodeVisitor visitor = new TestCodeVisitor();
        String assertion = EnumAssertionCodeRenderer.getEnumAssertionCode(constant, "state0", visitor);

        String source = "package org.evosuite.testcase;\n"
                + "import static org.junit.jupiter.api.Assertions.*;\n"
                + "public class EnumAssertionProbe {\n"
                + "  public static void run() throws Throwable {\n"
                + "    Object state0 = "
                + HiddenStates.class.getName().replace('$', '.') + ".holder().getState();\n"
                + "    " + assertion + "\n"
                + "  }\n}\n";
        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-foreign-enum", "EnumAssertionProbe", source);
        assertTrue(result.success, "Rendered assertion did not compile:\n" + source + "\n" + result.stderr);
    }
}
