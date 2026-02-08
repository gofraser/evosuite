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

import org.junit.Assert;
import org.junit.Test;

/**
 * Created by arcuri on 6/14/14.
 */
public class InheritanceTreeGeneratorTest {

    @Test
    public void canFindJDKData() {
        InheritanceTree it = InheritanceTreeGenerator.readJDKData();
        Assert.assertNotNull(it);
    }

    @Test
    public void mapToSupportedLts() {
        Assert.assertEquals(8, InheritanceTreeGenerator.mapToSupportedLts(8));
        Assert.assertEquals(8, InheritanceTreeGenerator.mapToSupportedLts(9));
        Assert.assertEquals(11, InheritanceTreeGenerator.mapToSupportedLts(11));
        Assert.assertEquals(11, InheritanceTreeGenerator.mapToSupportedLts(15));
        Assert.assertEquals(17, InheritanceTreeGenerator.mapToSupportedLts(17));
        Assert.assertEquals(17, InheritanceTreeGenerator.mapToSupportedLts(20));
        Assert.assertEquals(21, InheritanceTreeGenerator.mapToSupportedLts(21));
        Assert.assertEquals(21, InheritanceTreeGenerator.mapToSupportedLts(24));
        Assert.assertEquals(25, InheritanceTreeGenerator.mapToSupportedLts(25));
        Assert.assertEquals(25, InheritanceTreeGenerator.mapToSupportedLts(26));
    }

}
