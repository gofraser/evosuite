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
package org.evosuite.symbolic.solver.smt;

import org.evosuite.symbolic.solver.SmtSort;

public final class SmtConstantDeclaration {

    private final String name;
    private final SmtSort[] sorts;

    /**
     * Constructor.
     *
     * @param constantName a {@link java.lang.String} object.
     * @param constantSorts an array of {@link org.evosuite.symbolic.solver.SmtSort} objects.
     */
    public SmtConstantDeclaration(String constantName, SmtSort... constantSorts) {
        this.name = constantName;
        this.sorts = constantSorts;
    }

    /**
     * Returns the constant name.
     *
     * @return a {@link java.lang.String} object.
     */
    public String getConstantName() {
        return name;
    }

    /**
     * Returns the constant sorts.
     *
     * @return an array of {@link org.evosuite.symbolic.solver.SmtSort} objects.
     */
    public SmtSort[] getConstantSorts() {
        return sorts;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        SmtQueryPrinter printer = new SmtQueryPrinter();
        String str = printer.print(this);
        return str;
    }
}
