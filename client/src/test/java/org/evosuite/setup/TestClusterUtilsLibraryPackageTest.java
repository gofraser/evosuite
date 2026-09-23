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
package org.evosuite.setup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * com.sun is the JDK's package as much as rome's (com.sun.syndication), JavaMail's or
 * Mojarra's. Excluding the prefix outright removed every method that mentions such a type -
 * 108_liferay's RSSUtil returns ObjectValuePair&lt;String, SyndFeed&gt; and lost its only
 * method, so it could reach 0.5 branch coverage at best.
 */
public class TestClusterUtilsLibraryPackageTest {

    /** Stands in for a library class that happens to live under com.sun. */
    public static class PretendLibraryClass {
    }

    @Test
    public void jdkInternalsUnderComSunStayExcluded() throws Exception {
        Class<?> jdkClass = Class.forName("com.sun.net.httpserver.HttpServer");
        assertTrue(jdkClass.getClassLoader() == null
                        || jdkClass.getModule().isNamed(),
                "precondition: this class comes from the JDK");
        assertFalse(TestClusterUtils.checkIfCanUse(jdkClass.getName(), jdkClass));
        assertFalse(TestClusterUtils.checkIfCanUse(jdkClass.getName()),
                "without the loaded class the conservative answer is kept");
    }

    @Test
    public void classpathLibrariesUnderComSunAreUsable() {
        // the test class itself stands in for the library: loaded from the classpath, not the JDK
        Class<?> library = PretendLibraryClass.class;
        assertTrue(TestClusterUtils.checkIfCanUse("com.sun.syndication.feed.synd.SyndFeed", library),
                "a com.sun class loaded from the project classpath is an ordinary dependency");
    }

    @Test
    public void unrelatedPackagesAreUnaffected() {
        assertTrue(TestClusterUtils.checkIfCanUse("org.example.Foo"));
        assertFalse(TestClusterUtils.checkIfCanUse("sun.misc.Unsafe"));
        assertFalse(TestClusterUtils.checkIfCanUse("apple.laf.JRSUIUtils"));
    }
}
