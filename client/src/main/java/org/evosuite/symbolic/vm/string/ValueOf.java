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
package org.evosuite.symbolic.vm.string;

import org.evosuite.symbolic.expr.bv.IntegerValue;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.ref.ReferenceExpression;
import org.evosuite.symbolic.expr.str.IntegerToStringCast;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;

/**
 * Symbolic function implementation for String.valueOf.
 *
 * @author galeotti
 */
public abstract class ValueOf extends SymbolicFunction {

    private static final String VALUE_OF = "valueOf";

    /**
     * Base class for ValueOf implementations that take an integer-like argument.
     */
    public abstract static class ValueOf_Int extends ValueOf {

        /**
         * Constructs a ValueOf_Int.
         *
         * @param env  the symbolic environment
         * @param desc the method descriptor
         */
        public ValueOf_Int(SymbolicEnvironment env, String desc) {
            super(env, desc);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public final Object executeFunction() {

            IntegerValue symbArg = this.getSymbIntegerArgument(0);

            ReferenceExpression symbRetVal = this.getSymbRetVal();
            String concRetVal = (String) this.getConcRetVal();

            if (symbArg.containsSymbolicVariable()) {
                StringValue symbExpr = new IntegerToStringCast(symbArg,
                        concRetVal);

                ReferenceConstant symbNonNullRetVal = (ReferenceConstant) symbRetVal;

                env.heap.putField(Types.JAVA_LANG_STRING,
                        SymbolicHeap.$STRING_VALUE, concRetVal,
                        symbNonNullRetVal, symbExpr);
            }
            return this.getSymbRetVal();

        }

    }

    /**
     * Constructs a ValueOf.
     *
     * @param env  the symbolic environment
     * @param desc the method descriptor
     */
    public ValueOf(SymbolicEnvironment env, String desc) {
        super(env, Types.JAVA_LANG_STRING, VALUE_OF, desc);
    }

    /**
     * Symbolic function implementation for String.valueOf(Object).
     */
    public static final class ValueOf_O extends ValueOf {

        /**
         * Constructs a ValueOf_O.
         *
         * @param env the symbolic environment
         */
        public ValueOf_O(SymbolicEnvironment env) {
            super(env, Types.OBJECT_TO_STR_DESCRIPTOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object executeFunction() {

            ReferenceExpression symbArg = this.getSymbArgument(0);
            Object concArg = this.getConcArgument(0);

            ReferenceExpression symbRetVal = this.getSymbRetVal();
            String concRetVal = (String) this.getConcRetVal();

            if (concArg != null && concArg instanceof String) {

                String concStrArg = (String) concArg;
                ReferenceConstant symbNonNullStr = (ReferenceConstant) symbArg;

                StringValue strExpr = env.heap.getField(Types.JAVA_LANG_STRING,
                        SymbolicHeap.$STRING_VALUE, concStrArg,
                        symbNonNullStr, concStrArg);

                ReferenceConstant symbNonNullRetVal = (ReferenceConstant) symbRetVal;

                env.heap.putField(Types.JAVA_LANG_STRING,
                        SymbolicHeap.$STRING_VALUE, concRetVal,
                        symbNonNullRetVal, strExpr);
            }

            return this.getSymbRetVal();
        }

    }

    /**
     * Symbolic function implementation for String.valueOf(long).
     */
    public static final class ValueOf_J extends ValueOf_Int {

        /**
         * Constructs a ValueOf_J.
         *
         * @param env the symbolic environment
         */
        public ValueOf_J(SymbolicEnvironment env) {
            super(env, Types.LONG_TO_STR_DESCRIPTOR);
        }
    }

    /**
     * Symbolic function implementation for String.valueOf(int).
     */
    public static final class ValueOf_I extends ValueOf_Int {

        /**
         * Constructs a ValueOf_I.
         *
         * @param env the symbolic environment
         */
        public ValueOf_I(SymbolicEnvironment env) {
            super(env, Types.INT_TO_STR_DESCRIPTOR);
        }
    }

    /**
     * Symbolic function implementation for String.valueOf(char).
     */
    public static final class ValueOf_C extends ValueOf_Int {

        /**
         * Constructs a ValueOf_C.
         *
         * @param env the symbolic environment
         */
        public ValueOf_C(SymbolicEnvironment env) {
            super(env, Types.CHAR_TO_STR_DESCRIPTOR);
        }
    }

    /**
     * Symbolic function implementation for String.valueOf(boolean).
     */
    public static final class ValueOf_B extends ValueOf_Int {

        /**
         * Constructs a ValueOf_B.
         *
         * @param env the symbolic environment
         */
        public ValueOf_B(SymbolicEnvironment env) {
            super(env, Types.BOOLEAN_TO_STR_DESCRIPTOR);
        }
    }

}
