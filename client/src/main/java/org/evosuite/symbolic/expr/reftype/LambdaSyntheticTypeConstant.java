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
package org.evosuite.symbolic.expr.reftype;

import org.evosuite.symbolic.expr.ExpressionVisitor;
import org.objectweb.asm.Type;

/**
 * General expression for anonymous jvm-created lambda classes (usually after invokedynamic is used).
 *
 * <p>TODO: Lambdas may be closures so a lot of fields may be attached to them.
 *
 * @author Ignacio Lebrero
 */
public final class LambdaSyntheticTypeConstant extends NonNullReferenceTypeConstant {

    private static final long serialVersionUID = -1234567890123456789L;

    /**
     * Whether this lambda is called from non instrumented sources.
     */
    private boolean callsNonInstrumentedCode;

    /**
     * Constructs a {@link LambdaSyntheticTypeConstant} with the given type, non-instrumented code flag and id.
     *
     * @param concreteValue the type of the lambda
     * @param callsNonInstrumentedCode whether it calls non-instrumented code
     * @param referenceTypeId the unique id of the reference type
     */
    public LambdaSyntheticTypeConstant(Type concreteValue, boolean callsNonInstrumentedCode, int referenceTypeId) {
        super(concreteValue, referenceTypeId);

        this.callsNonInstrumentedCode = callsNonInstrumentedCode;
    }

    public boolean callsNonInstrumentedCode() {
        return callsNonInstrumentedCode;
    }

    @Override
    public <K, V> K accept(ExpressionVisitor<K, V> v, V arg) {
        return v.visit(this, arg);
    }
}
