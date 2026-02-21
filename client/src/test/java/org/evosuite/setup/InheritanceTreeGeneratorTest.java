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
package org.evosuite.setup;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Created by arcuri on 6/14/14.
 */
public class InheritanceTreeGeneratorTest {

    @Test
    public void canFindJDKData() {
        InheritanceTree it = InheritanceTreeGenerator.readJDKData();
        Assertions.assertNotNull(it);
    }

    @Test
    public void mapToSupportedLts() {
        Assertions.assertEquals(8, InheritanceTreeGenerator.mapToSupportedLts(8));
        Assertions.assertEquals(8, InheritanceTreeGenerator.mapToSupportedLts(9));
        Assertions.assertEquals(11, InheritanceTreeGenerator.mapToSupportedLts(11));
        Assertions.assertEquals(11, InheritanceTreeGenerator.mapToSupportedLts(15));
        Assertions.assertEquals(17, InheritanceTreeGenerator.mapToSupportedLts(17));
        Assertions.assertEquals(17, InheritanceTreeGenerator.mapToSupportedLts(20));
        Assertions.assertEquals(21, InheritanceTreeGenerator.mapToSupportedLts(21));
        Assertions.assertEquals(21, InheritanceTreeGenerator.mapToSupportedLts(24));
        Assertions.assertEquals(25, InheritanceTreeGenerator.mapToSupportedLts(25));
        Assertions.assertEquals(25, InheritanceTreeGenerator.mapToSupportedLts(26));
    }

}
