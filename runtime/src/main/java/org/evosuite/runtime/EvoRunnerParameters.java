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
package org.evosuite.runtime;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Annotation needed to pass parameters to EvoRunner.
 *
 * @author arcuri
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface EvoRunnerParameters {

    /**
     * Should JVM non-determinism (e.g., time, random) be mocked.
     *
     * @return true if non-determinism should be mocked
     */
    boolean mockJVMNonDeterminism() default false;

    /**
     * Should a virtual file system be used.
     *
     * @return true if VFS should be used
     */
    boolean useVFS() default false;

    /**
     * Should a virtual network be used.
     *
     * @return true if VNET should be used
     */
    boolean useVNET() default false;

    /**
     * Should the static state be reset after each test.
     *
     * @return true if static state should be reset
     */
    boolean resetStaticState() default false;

    /**
     * Should a separate class loader be used for each test.
     *
     * @return true if separate class loader should be used
     */
    boolean separateClassLoader() default false;

    /**
     * Should JEE support be enabled.
     *
     * @return true if JEE support should be enabled
     */
    boolean useJEE() default false;

    /**
     * Should the GUI be mocked.
     *
     * @return true if GUI should be mocked
     */
    boolean mockGUI() default false;
}
