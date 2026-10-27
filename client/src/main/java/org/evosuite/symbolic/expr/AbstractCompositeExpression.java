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
package org.evosuite.symbolic.expr;

import org.evosuite.Properties;
import org.evosuite.symbolic.ConstraintTooLongException;
import org.evosuite.symbolic.dse.DSEStatistics;

/**
 * Base class of the expressions built from other expressions. The constructor
 * rejects expressions that exceed {@link Properties#DSE_CONSTRAINT_LENGTH}.
 *
 * @param <T> the type of the concrete value
 */
public abstract class AbstractCompositeExpression<T> extends AbstractExpression<T> {

    private static final long serialVersionUID = 4180562310934215672L;

    protected AbstractCompositeExpression(T concreteValue, int size,
                                          boolean containsSymbolicVariable) {
        super(concreteValue, size, containsSymbolicVariable);
        if (getSize() > Properties.DSE_CONSTRAINT_LENGTH) {
            DSEStatistics.getInstance().reportConstraintTooLong(getSize());
            throw new ConstraintTooLongException(getSize());
        }
    }
}
