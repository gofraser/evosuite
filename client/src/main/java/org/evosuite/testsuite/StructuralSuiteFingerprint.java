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
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.testsuite;

import org.evosuite.testcase.TestPresentationMetadata;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestCodeVisitor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes a stable digest of the executable structure of a suite. Assertions
 * and LLM-only rendering metadata are deliberately excluded so that the digest
 * can be compared before and after oracle materialization.
 */
public final class StructuralSuiteFingerprint {

    private StructuralSuiteFingerprint() {
        // utility class
    }

    public static String compute(TestSuiteChromosome suite) {
        if (suite == null) {
            throw new IllegalArgumentException("suite must not be null");
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int index = 0;
            for (TestCase original : suite.getTests()) {
                TestCase structuralCopy = original.clone();
                structuralCopy.removeAssertions();
                TestPresentationMetadata.clear(structuralCopy);

                update(digest, "test:");
                update(digest, Integer.toString(index++));
                update(digest, "\nstatements:");
                update(digest, Integer.toString(structuralCopy.size()));
                update(digest, "\n");
                update(digest, renderStructure(structuralCopy));
                update(digest, "\n--end-test--\n");
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * TestCodeVisitor uses Properties.ASSERTIONS even when a test contains no
     * assertions (for example, to decide whether unused return values need a
     * declaration). Pin it to the assertion-free export setting so replay arm
     * selection cannot change the structural digest.
     */
    private static String renderStructure(TestCase test) {
        TestCodeVisitor visitor = new TestCodeVisitor();
        visitor.setEmitAssertions(false);
        visitor.setAssertionsEnabledForRendering(false);
        test.accept(visitor);
        return visitor.getCode();
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
