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
package org.evosuite.eclipse.launch;

/**
 * The settings of one generation request, resolved from the project's or the workspace's
 * preferences. Plain data with no Eclipse types, so that building the command line can be tested
 * without a workbench.
 */
public final class GenerationOptions {

    public static final String JUNIT5 = "JUNIT5";
    public static final String JUNIT4 = "JUNIT4";

    /** Seconds of search per class. */
    public int searchBudget = 60;
    /** Colon-separated coverage criteria; empty means EvoSuite's default set. */
    public String criterion = "";
    public boolean assertions = true;
    public boolean minimize = true;
    /** {@link #JUNIT5} or {@link #JUNIT4}. */
    public String testFormat = JUNIT5;
    public String testSuffix = "_ESTest";
    /** Project-relative folder that receives the generated tests. */
    public String testFolder = "src/test/evosuite";
    /** Heap of the EvoSuite client JVM in megabytes; 0 leaves EvoSuite's default. */
    public int clientMemory = 0;
    /** Further EvoSuite arguments, e.g. {@code -Dsandbox=false}, split like a shell would. */
    public String extraArguments = "";
    /** Java installation that runs EvoSuite; empty means the project's JRE. */
    public String javaHome = "";
    public boolean openGeneratedTests = true;
    /** Add the test folder, JUnit and the EvoSuite runtime to the build path when missing. */
    public boolean configureBuildPath = true;
}
