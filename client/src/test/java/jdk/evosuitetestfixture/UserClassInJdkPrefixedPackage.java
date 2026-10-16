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
package jdk.evosuitetestfixture;

/**
 * A plain user class that happens to sit in a {@code jdk.}-prefixed package.
 *
 * <p>Unlike {@code java.}-prefixed packages, the JVM does not reserve {@code jdk.}
 * for the platform: a class here loads normally through the application class
 * loader. {@link org.evosuite.setup.TestUsageCheckerPlatformClassTest} uses this
 * fixture to pin that {@code TestUsageChecker.isPlatformClass} is not fooled by
 * the package name alone -- the class lands in an <em>unnamed</em> module, which
 * is what actually tells it apart from a real {@code jdk.*} platform module.
 */
public class UserClassInJdkPrefixedPackage {
}
