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
package org.evosuite.symbolic.expr.ref.array;

import org.evosuite.symbolic.expr.ExpressionVisitor;
import org.evosuite.symbolic.expr.ref.NonNullReferenceConstant;
import org.objectweb.asm.Type;

import java.util.Objects;

/**
 * Represents an instant of an array.
 *
 * @author Ignacio Lebrero
 */
public abstract class ArrayConstant extends NonNullReferenceConstant implements ArrayValue {

    private static final long serialVersionUID = -4837586943538263455L;

    public ArrayConstant(Type arrayType, int instanceId) {
        super(arrayType, instanceId);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        ArrayConstant other = (ArrayConstant) obj;
        return concreteValue != null && concreteValue.equals(other.concreteValue);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(concreteValue);
    }

    public static final class IntegerArrayConstant extends ArrayConstant implements ArrayValue.IntegerArrayValue {

        private static final long serialVersionUID = 8782417009903552164L;

        public IntegerArrayConstant(Type arrayType, int instanceId) {
            super(arrayType, instanceId);
        }

        @Override
        public <K, V> K accept(ExpressionVisitor<K, V> v, V arg) {
            return v.visit(this, arg);
        }
    }

    public static final class RealArrayConstant extends ArrayConstant implements ArrayValue.RealArrayValue {

        private static final long serialVersionUID = 4245124775439103610L;

        public RealArrayConstant(Type arrayType, int instanceId) {
            super(arrayType, instanceId);
        }

        @Override
        public <K, V> K accept(ExpressionVisitor<K, V> v, V arg) {
            return v.visit(this, arg);
        }
    }

    public static final class StringArrayConstant extends ArrayConstant implements ArrayValue.StringArrayValue {

        private static final long serialVersionUID = -431480209150834944L;

        public StringArrayConstant(int instanceId) {
            super(Type.getType(String[].class), instanceId);
        }

        @Override
        public <K, V> K accept(ExpressionVisitor<K, V> v, V arg) {
            return v.visit(this, arg);
        }
    }

    public static final class ReferenceArrayConstant extends ArrayConstant implements ArrayValue.ReferenceArrayValue {

        private static final long serialVersionUID = -8345808433788765844L;

        public ReferenceArrayConstant(Type arrayType, int instanceId) {
            super(arrayType, instanceId);
        }

        @Override
        public <K, V> K accept(ExpressionVisitor<K, V> v, V arg) {
            return v.visit(this, arg);
        }
    }
}

