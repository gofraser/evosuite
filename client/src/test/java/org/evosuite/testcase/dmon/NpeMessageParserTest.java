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
package org.evosuite.testcase.dmon;

import org.junit.Assert;
import org.junit.Test;

public class NpeMessageParserTest {

    @Test
    public void parsesHelpfulInvokeMessage() {
        NpeMessageParser parser = new NpeMessageParser();
        String msg = "Cannot invoke \"example.Service.get()\" because \"this.service\" is null";

        NpeMessageParser.ParseResult result = parser.parse(msg);

        Assert.assertEquals(NpeMessageParser.ParseStrength.EXACT_MATCH, result.getStrength());
        Assert.assertTrue(result.getNullExpression().isPresent());
        Assert.assertEquals("this.service", result.getNullExpression().get());
        Assert.assertTrue(result.getOwnerToken().isPresent());
        Assert.assertEquals("this", result.getOwnerToken().get());
    }

    @Test
    public void returnsNoMatchForEmptyMessage() {
        NpeMessageParser parser = new NpeMessageParser();
        NpeMessageParser.ParseResult result = parser.parse("");

        Assert.assertEquals(NpeMessageParser.ParseStrength.NO_MATCH, result.getStrength());
        Assert.assertFalse(result.getNullExpression().isPresent());
        Assert.assertFalse(result.getOwnerToken().isPresent());
        Assert.assertFalse(result.getMemberToken().isPresent());
    }
}

