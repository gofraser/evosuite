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
package org.evosuite.testcase.accessfixture.other;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Public API whose generic results mention private nested classes. */
public class Exposer {
    private static class Hidden {
    }

    private static class HiddenChild extends java.util.Date {
    }

    public LinkedHashMap<String, Hidden> dict;

    public Map<Integer, LinkedHashMap<String, Hidden>> m;

    public LinkedHashMap<Integer, LinkedHashMap<String, Hidden>> hiddenNestedMap() {
        return new LinkedHashMap<>();
    }

    public int takeDict(LinkedHashMap<String, Hidden> argument) {
        return argument.size();
    }

    protected int protectedCount() {
        return 1;
    }

    public LinkedHashMap<String, Hidden> hiddenMap() {
        return new LinkedHashMap<>();
    }

    public ArrayList<HiddenChild> hiddenList() {
        return new ArrayList<>();
    }

    public List<Map<String, Hidden>> nestedHidden() {
        return new ArrayList<>();
    }
}
