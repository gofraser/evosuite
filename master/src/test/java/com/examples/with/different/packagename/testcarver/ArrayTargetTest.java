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
package com.examples.with.different.packagename.testcarver;

import org.junit.Assert;
import org.junit.Test;

public class ArrayTargetTest {

    @Test
    public void testArrayOps() {
        int[] data = new int[2];
        data[0] = 3;
        data[1] = 5;

        ArrayTarget target = new ArrayTarget();
        target.set(data);
        Assert.assertEquals(5, target.getAt(1));

        Assert.assertEquals(8, ArrayTarget.sumFirstTwo(data));
    }
}
