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
package org.evosuite.symbolic.vm.string.buffer;

import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;

/**
 * Symbolic function implementation for StringBuffer constructors.
 *
 * @author galeotti
 */
public abstract class StringBuffer_Init extends SymbolicFunction {

    private static final String INIT = "<init>";

    /**
     * Constructs a StringBuffer_Init.
     *
     * @param env  the symbolic environment
     * @param desc the method descriptor
     */
    public StringBuffer_Init(SymbolicEnvironment env, String desc) {
        super(env, Types.JAVA_LANG_STRING_BUFFER, INIT, desc);
    }

    /**
     * Symbolic function implementation for StringBuffer(String) constructor.
     */
    public static final class StringBufferInit_S extends StringBuffer_Init {

        /**
         * Constructs a StringBufferInit_S.
         *
         * @param env the symbolic environment
         */
        public StringBufferInit_S(SymbolicEnvironment env) {
            super(env, Types.STR_TO_VOID_DESCRIPTOR);

        }

        /**
         * new StringBuffer(String).
         */
        @Override
        public Object executeFunction() {
            ReferenceConstant symbStrBuffer = this.getSymbReceiver();
            ReferenceConstant symbString = (ReferenceConstant) this
                    .getSymbArgument(0);
            String concString = (String) this.getConcArgument(0);

            // get symbolic value for string argument
            StringValue stringValue = this.env.heap.getField(
                    Types.JAVA_LANG_STRING, SymbolicHeap.$STRING_VALUE,
                    concString, symbString, concString);

            // update symbolic heap
            this.env.heap.putField(Types.JAVA_LANG_STRING_BUFFER,
                    SymbolicHeap.$STRING_BUFFER_CONTENTS, null,
                    symbStrBuffer, stringValue);

            // return void
            return null;
        }

    }

}
