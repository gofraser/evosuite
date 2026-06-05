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
package org.evosuite.llm.search;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProblemCardBuilderTest {

    @Test
    void builderRejectsEmptyCard() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ProblemCard.builder(ProblemCardType.UNREACHED_METHOD).build());
        assertTrue(error.getMessage().contains("title")
                        && error.getMessage().contains("evidence")
                        && error.getMessage().contains("related goals"),
                "Empty-card error message must explain which axes were missing: " + error.getMessage());
    }

    @Test
    void builderClampsImpactBlockageAndConfidenceTo01() {
        ProblemCard card = ProblemCard.builder(ProblemCardType.EXCEPTION_BARRIER)
                .title("clamp test")
                .evidence(Collections.singletonList("ev"))
                .impact(-42.0)
                .blockage(99.0)
                .confidence(Double.NaN)
                .build();
        assertEquals(0.0, card.getImpact(), 1e-9, "Negative impact must clamp to 0");
        assertEquals(1.0, card.getBlockage(), 1e-9, "Out-of-range blockage must clamp to 1");
        assertEquals(0.0, card.getConfidence(), 1e-9, "NaN confidence must clamp to 0");
    }

    @Test
    void builderDefaultsFamilyFromCardType() {
        ProblemCard card = ProblemCard.builder(ProblemCardType.UNINSTANTIABLE_TYPE)
                .title("structural default")
                .evidence(Collections.singletonList("ev"))
                .impact(0.5).blockage(0.5).confidence(0.5)
                .build();
        assertSame(ProblemCardFamily.STRUCTURAL, card.getFamily(),
                "Builder must default family from the card type when unset");
    }

    @Test
    void builderRespectsExplicitFamilyOverride() {
        ProblemCard card = ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                .title("override")
                .evidence(Collections.singletonList("ev"))
                .family(ProblemCardFamily.LOCAL)
                .impact(0.5).blockage(0.5).confidence(0.5)
                .build();
        assertSame(ProblemCardFamily.LOCAL, card.getFamily());
    }
}
