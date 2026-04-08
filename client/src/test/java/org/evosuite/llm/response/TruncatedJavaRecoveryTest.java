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
package org.evosuite.llm.response;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TruncatedJavaRecoveryTest {

    private final TruncatedJavaRecovery recovery = new TruncatedJavaRecovery();

    @Test
    void recoversWhenMethodBodyIsTruncated() {
        String source = "public class T {\n"
                + "  @org.junit.Test\n"
                + "  public void ok(){ int x = 1; }\n"
                + "  @org.junit.Test\n"
                + "  public void broken(){ int y =\n";

        TruncatedJavaRecovery.RecoveryResult result = recovery.recover(source);

        assertTrue(result.isRecovered());
        assertTrue(result.getSource().contains("public void ok()"));
        assertFalse(result.getSource().contains("public void broken()"));
        assertTrue(result.getSource().trim().endsWith("}"));
    }

    @Test
    void recoversWhenTruncatedInsideStringLiteral() {
        String source = "public class T {\n"
                + "  @org.junit.Test\n"
                + "  public void ok(){ int x = 1; }\n"
                + "  @org.junit.Test\n"
                + "  public void broken(){ String s = \"unterminated\n";

        TruncatedJavaRecovery.RecoveryResult result = recovery.recover(source);

        assertTrue(result.isRecovered());
        assertFalse(result.getSource().contains("unterminated"));
    }

    @Test
    void recoversWhenTruncatedInsideComment() {
        String source = "public class T {\n"
                + "  @org.junit.Test\n"
                + "  public void ok(){ int x = 1; }\n"
                + "  @org.junit.Test\n"
                + "  public void broken(){ /* open comment\n";

        TruncatedJavaRecovery.RecoveryResult result = recovery.recover(source);

        assertTrue(result.isRecovered());
        assertTrue(result.getSource().contains("public void ok()"));
    }

    @Test
    void doesNotChangeAlreadyParseableSource() {
        String source = "public class T {\n"
                + "  @org.junit.Test\n"
                + "  public void ok(){ int x = 1; }\n"
                + "}\n";

        TruncatedJavaRecovery.RecoveryResult result = recovery.recover(source);

        assertFalse(result.isRecovered());
        assertEquals(source, result.getSource());
    }
}
