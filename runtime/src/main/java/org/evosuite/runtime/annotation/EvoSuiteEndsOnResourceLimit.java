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
package org.evosuite.runtime.annotation;

import java.lang.annotation.*;


/**
 * Marks a generated test whose execution ended on one of EvoSuite's resource limits (loop
 * iterations, invocations, allocations), so the test deliberately stops at the statement that
 * hit it.
 *
 * <p>Code that recurses without bound ends on whichever guard fires first: the limit during the
 * search, but a {@link StackOverflowError} in a cold JVM with larger frames. The non-functional
 * requirement rule and extension therefore also accept a {@code StackOverflowError} from a test
 * carrying this annotation. They do not accept it from other tests, where it would be a genuine
 * infinite-recursion failure.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface EvoSuiteEndsOnResourceLimit {

}
