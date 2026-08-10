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
package org.evosuite.utils.generic;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * A top-level class whose identifier legitimately contains '$' (like Gson's
 * {@code com.google.gson.internal.$Gson$Types}) must keep its '$' in the simple
 * name, not have every '$' turned into a nested-class '.' separator.
 */
public class GenericClassImplSimpleNameTest {

    @Test
    public void topLevelDollarNameIsNotMangledIntoDottedName() {
        GenericClassImpl gc = new GenericClassImpl($Weird$Name.class);
        Assertions.assertEquals("$Weird$Name", gc.getSimpleName());
    }

    @Test
    public void ordinaryTopLevelNameIsUnchanged() {
        Assertions.assertEquals("String", new GenericClassImpl(String.class).getSimpleName());
    }

    @Test
    public void memberClassKeepsDottedOuterInnerShortName() {
        // Member classes still use the outer.inner short name.
        Assertions.assertEquals("Map.Entry", new GenericClassImpl(Map.Entry.class).getSimpleName());
    }

    @Test
    public void arraySimpleNameStillRendersBrackets() {
        Assertions.assertEquals("String[]", new GenericClassImpl(String[].class).getSimpleName());
    }
}

/** Top-level (non-nested) class whose name contains '$' characters. */
class $Weird$Name {
}
