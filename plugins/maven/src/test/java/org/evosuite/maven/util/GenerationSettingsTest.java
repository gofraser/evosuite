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
package org.evosuite.maven.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class GenerationSettingsTest {

    @Test
    public void durationsNeedUnits() {
        assertEquals(30, GenerationSettings.parseSeconds("30s"));
        assertEquals(120, GenerationSettings.parseSeconds("2m"));
        assertEquals(90, GenerationSettings.parseSeconds("1m30s"));
        assertEquals(3600, GenerationSettings.parseSeconds(" 1H "));
        for (String invalid : Arrays.asList("90", "", "1.5m", "m", "30 s")) {
            assertThrows(IllegalArgumentException.class, () -> GenerationSettings.parseSeconds(invalid), invalid);
        }
    }
}
