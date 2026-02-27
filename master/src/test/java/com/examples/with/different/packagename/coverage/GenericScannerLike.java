/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package com.examples.with.different.packagename.coverage;

import java.util.ArrayList;
import java.util.LinkedList;

public class GenericScannerLike<E extends GenericScannerLike.ExecLexem> {

    public static class ExecLexem {
        public final String token;

        public ExecLexem(String token) {
            this.token = token;
        }
    }

    public static class AdvLexem extends ExecLexem {
        public AdvLexem(String token) {
            super(token);
        }
    }

    public ArrayList<E> fieldScope = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public Class<LinkedList<String>> publicListType = (Class<LinkedList<String>>) (Class<?>) LinkedList.class;

    public int printLexems(ArrayList<ExecLexem> values) {
        if (values == null) {
            return -1;
        }
        return values.isEmpty() ? 0 : 1;
    }

    public int printAdvLexems(ArrayList<AdvLexem> values) {
        if (values == null) {
            return -1;
        }
        return values.isEmpty() ? 0 : 2;
    }

    public int checkStructure(ArrayList<ExecLexem> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        ExecLexem first = values.get(0);
        if (first instanceof AdvLexem) {
            return 1;
        }
        return first.token.length() > 3 ? 2 : 3;
    }
}
