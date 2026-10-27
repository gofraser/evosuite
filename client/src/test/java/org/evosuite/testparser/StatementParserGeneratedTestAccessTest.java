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
package org.evosuite.testparser;

import org.evosuite.Properties;
import org.evosuite.testcase.TestCodeVisitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A type the generated test cannot name is downgraded to Object in the typed-null fallback. The
 * decision follows the package the generated test is written to (the class prefix), not the
 * package of the target class.
 */
class StatementParserGeneratedTestAccessTest {

    /** Package-private class. */
    static class PackageLocal {
    }

    private String savedTargetClass;
    private String savedClassPrefix;

    @BeforeEach
    void setUp() {
        savedTargetClass = Properties.TARGET_CLASS;
        savedClassPrefix = Properties.CLASS_PREFIX;
        Properties.TARGET_CLASS = StatementParserGeneratedTestAccessTest.class.getName();
    }

    @AfterEach
    void tearDown() {
        Properties.TARGET_CLASS = savedTargetClass;
        Properties.CLASS_PREFIX = savedClassPrefix;
    }

    private String parseDeclaration(String declaration) {
        TestParser parser = new TestParser(getClass().getClassLoader());
        parser.setMarkParsedFromLlm(true);
        ParseResult result = parser.parseTestMethodBody(declaration, List.of());
        TestCodeVisitor visitor = new TestCodeVisitor();
        result.getTestCase().accept(visitor);
        return visitor.getCode();
    }

    @Test
    void packageLocalTypeIsNameableOnlyInItsOwnPackage() {
        String declaration =
                "org.evosuite.testparser.StatementParserGeneratedTestAccessTest.PackageLocal value = null;";

        Properties.CLASS_PREFIX = "org.evosuite.testparser";
        String samePackage = parseDeclaration(declaration);
        assertTrue(samePackage.contains("PackageLocal value = null;"), samePackage);

        Properties.CLASS_PREFIX = "com.example.other";
        String otherPackage = parseDeclaration(declaration);
        assertFalse(otherPackage.contains("PackageLocal value = null;"), otherPackage);
    }
}
