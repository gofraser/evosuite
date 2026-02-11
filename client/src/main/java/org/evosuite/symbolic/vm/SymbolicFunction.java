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
package org.evosuite.symbolic.vm;

import org.evosuite.symbolic.expr.bv.IntegerValue;
import org.evosuite.symbolic.expr.constraint.IntegerConstraint;
import org.evosuite.symbolic.expr.fp.RealValue;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.ref.ReferenceExpression;
import org.objectweb.asm.Type;

/**
 * This class represents the execution of a concrete method (Math.abs(), new
 * Integer(), etc.) at the symbolic level.
 *
 * @author galeotti
 */
public abstract class SymbolicFunction {

    /**
     * Builds a new SymbolicFunction.
     *
     * @param env the symbolic environment
     * @param owner the owner class
     * @param name the method name
     * @param desc the method descriptor
     */
    public SymbolicFunction(SymbolicEnvironment env, String owner,
                            String name, String desc) {
        super();
        this.env = env;
        this.owner = owner;
        this.name = name;
        this.desc = desc;
        this.symbArgs = new Object[Type.getArgumentTypes(desc).length];
        this.concArgs = new Object[Type.getArgumentTypes(desc).length];
    }

    /* non-assignable references */
    protected final SymbolicEnvironment env;
    private final String owner;
    private final String name;
    private final String desc;
    private final Object[] symbArgs;
    private final Object[] concArgs;

    /* assignable references */
    private Object concReceiver;
    private ReferenceExpression symbReceiver;

    private Object concRetVal;
    private Object symbRetVal;

    public final String getOwner() {
        return owner;
    }

    public final String getName() {
        return name;
    }

    void setReceiver(Object concReceiver, ReferenceExpression symbReceiver) {
        this.concReceiver = concReceiver;
        this.symbReceiver = symbReceiver;
    }

    // IntegerExpression parameters
    void setParam(int i, int concArg, IntegerValue symbArg) {
        this.concArgs[i] = concArg;
        this.symbArgs[i] = symbArg;
    }

    void setParam(int i, char concArg, IntegerValue symbArg) {
        this.concArgs[i] = concArg;
        this.symbArgs[i] = symbArg;
    }

    void setParam(int i, byte concArg, IntegerValue symbArg) {
        this.concArgs[i] = concArg;
        this.symbArgs[i] = symbArg;
    }

    void setParam(int i, short concArg, IntegerValue symbArg) {
        this.concArgs[i] = concArg;
        this.symbArgs[i] = symbArg;
    }

    void setParam(int i, boolean concArg, IntegerValue symbArg) {
        this.concArgs[i] = concArg;
        this.symbArgs[i] = symbArg;
    }

    void setParam(int i, long concArg, IntegerValue symbArg) {
        this.concArgs[i] = concArg;
        this.symbArgs[i] = symbArg;
    }

    // RealExpression params

    void setParam(int i, float concArg, RealValue symbArg) {
        this.concArgs[i] = concArg;
        this.symbArgs[i] = symbArg;
    }

    void setParam(int i, double concArg, RealValue symbArg) {
        this.concArgs[i] = concArg;
        this.symbArgs[i] = symbArg;
    }

    // Reference params

    void setParam(int i, Object concArg, ReferenceExpression symbArg) {
        this.concArgs[i] = concArg;
        this.symbArgs[i] = symbArg;
    }

    void setReturnValue(int concRetVal, IntegerValue symbRetVal) {
        this.concRetVal = concRetVal;
        this.symbRetVal = symbRetVal;
    }

    void setReturnValue(boolean concRetVal, IntegerValue symbRetVal) {
        this.concRetVal = concRetVal;
        this.symbRetVal = symbRetVal;
    }

    void setReturnValue(long concRetVal, IntegerValue symbRetVal) {
        this.concRetVal = concRetVal;
        this.symbRetVal = symbRetVal;
    }

    void setReturnValue(float concRetVal, RealValue symbRetVal) {
        this.concRetVal = concRetVal;
        this.symbRetVal = symbRetVal;
    }

    void setReturnValue(double concRetVal, RealValue symbRetVal) {
        this.concRetVal = concRetVal;
        this.symbRetVal = symbRetVal;
    }

    void setReturnValue(Object concRetVal, ReferenceExpression symbRetVal) {
        this.concRetVal = concRetVal;
        this.symbRetVal = symbRetVal;
    }

    /**
     * For non-static method invocations (not constructors) returns the symbolic
     * receiver.
     *
     * @return a NonNullReference with the symbolic object receiver.
     */
    protected final ReferenceConstant getSymbReceiver() {
        return (ReferenceConstant) symbReceiver;
    }

    /**
     * For non-static method invocations (neither constructors) returns the
     * concrete method receiver.
     *
     * @return a Object reference (non-null) with the concrete method receiver.
     */
    protected final Object getConcReceiver() {
        return this.concReceiver;
    }

    /**
     * Returns the i-th concrete parameter. The parameter must be an instance of
     * Object.
     *
     * @param i the parameter index.
     * @return a concrete Object reference to the concrete parameter.
     */
    protected final int getConcIntArgument(int i) {
        Integer int0 = (Integer) this.concArgs[i];
        return int0;
    }

    /**
     * Returns the i-th concrete parameter. The parameter must be a short value.
     *
     * @param i the parameter index.
     * @return a concrete short value.
     */
    protected final short getConcShortArgument(int i) {
        Short short0 = (Short) this.concArgs[i];
        return short0;
    }

    /**
     * Returns the i-th concrete parameter. The parameter must be a char value.
     *
     * @param i the parameter index.
     * @return a concrete char value.
     */
    protected final char getConcCharArgument(int i) {
        Character char0 = (Character) this.concArgs[i];
        return char0;
    }

    /**
     * Returns the i-th concrete parameter. The parameter must be a double
     * value.
     *
     * @param i the parameter index.
     * @return a concrete double value.
     */
    protected final double getConcDoubleArgument(int i) {
        Double double0 = (Double) this.concArgs[i];
        return double0;
    }

    /**
     * Returns the i-th concrete parameter. The parameter must be a float value.
     *
     * @param i the parameter index.
     * @return a concrete float value.
     */
    protected final float getConcFloatArgument(int i) {
        Float float0 = (Float) this.concArgs[i];
        return float0;
    }

    /**
     * Returns the i-th concrete parameter. The parameter must be a boolean
     * value.
     *
     * @param i the parameter index.
     * @return a concrete boolean value.
     */
    protected final boolean getConcBooleanArgument(int i) {
        Boolean boolean0 = (Boolean) this.concArgs[i];
        return boolean0;
    }

    /**
     * Returns the i-th concrete parameter. The parameter must be a byte value.
     *
     * @param i the parameter index.
     * @return a concrete byte value.
     */
    protected final byte getConcByteArgument(int i) {
        Byte byte0 = (Byte) this.concArgs[i];
        return byte0;
    }

    /**
     * Returns the i-th concrete parameter. The parameter must be a long value.
     *
     * @param i the parameter index.
     * @return a concrete long value.
     */
    protected final long getConcLongArgument(int i) {
        Long long0 = (Long) this.concArgs[i];
        return long0;
    }

    /**
     * Returns the i-th concrete parameter. The parameter must be an Object
     * reference.
     *
     * @param i the parameter index.
     * @return a concrete object reference.
     */
    protected final Object getConcArgument(int i) {
        Object arg = this.concArgs[i];
        return arg;
    }

    /**
     * Returns the i-th symbolic parameter. The parameter must be a symbolic
     * integer value.
     *
     * @param i the parameter index.
     * @return a symbolic integer value.
     */
    protected final IntegerValue getSymbIntegerArgument(int i) {
        IntegerValue intExpr = (IntegerValue) this.symbArgs[i];
        return intExpr;
    }

    /**
     * Returns the i-th symbolic parameter. The parameter must be a symbolic
     * real value.
     *
     * @param i the parameter index.
     * @return a symbolic real value.
     */
    protected final RealValue getSymbRealArgument(int i) {
        RealValue realExpr = (RealValue) this.symbArgs[i];
        return realExpr;
    }

    /**
     * Returns the i-th symbolic parameter. The parameter must be a symbolic
     * reference.
     *
     * @param i the parameter index.
     * @return a symbolic reference.
     */
    protected final ReferenceExpression getSymbArgument(int i) {
        ReferenceExpression ref = (ReferenceExpression) this.symbArgs[i];
        return ref;
    }

    /**
     * Returns the symbolic return value. The return value must be a symbolic
     * reference.
     *
     * @return a symbolic reference of the return value.
     */
    protected final ReferenceExpression getSymbRetVal() {
        return (ReferenceExpression) this.symbRetVal;
    }

    /**
     * Returns the symbolic return value. The return value must be a symbolic
     * integer value.
     *
     * @return a symbolic integer value of the return value.
     */
    protected final IntegerValue getSymbIntegerRetVal() {
        IntegerValue intExpr = (IntegerValue) this.symbRetVal;
        return intExpr;
    }

    /**
     * Returns the symbolic return value. The return value must be a symbolic
     * real value.
     *
     * @return a symbolic real return value.
     */
    protected final RealValue getSymbRealRetVal() {
        RealValue realExpr = (RealValue) this.symbRetVal;
        return realExpr;
    }

    /**
     * Returns new symbolic return value. All symbolic method invocations with
     * return values (except constructor calls or void calls) should return a
     * symbolic value. The old symbolic value can be obtained by using the
     * <code>getSymbRetVal</code>, <code>getSymbRealRetVal</code> and
     * <code>getSymbIntegerRetVal</code> methods.
     *
     * @return object!=null &amp;&amp; object instanceof Reference or object instanceof
     *         IntegerExpression or object instanceof RealExpression
     */
    public abstract Object executeFunction();

    /**
     * Returns the concrete return value of the concrete method execution. The
     * concrete return value should be an integer value.
     *
     * @return an integer value with the concrete method execution.
     */
    protected final int getConcIntRetVal() {
        Integer int0 = (Integer) this.concRetVal;
        return int0;
    }

    /**
     * Returns the concrete return value of the concrete method execution. The
     * concrete return value should be a short value.
     *
     * @return a short value with the concrete method execution.
     */
    protected final short getConcShortRetVal() {
        Integer integer0 = (Integer) this.concRetVal;
        return integer0.shortValue();
    }

    /**
     * Returns the concrete return value of the concrete method execution. The
     * concrete return value should be a char value.
     *
     * @return a char value with the concrete method execution.
     */
    protected final char getConcCharRetVal() {
        Integer char0 = (Integer) this.concRetVal;
        return (char) char0.intValue();
    }

    /**
     * Returns the concrete return value of the concrete method execution. The
     * concrete return value should be a double value.
     *
     * @return a double value with the concrete method execution.
     */
    protected final double getConcDoubleRetVal() {
        Double double0 = (Double) this.concRetVal;
        return double0;
    }

    /**
     * Returns the concrete return value of the concrete method execution. The
     * concrete return value should be a float value.
     *
     * @return a float value with the concrete method execution.
     */
    protected final float getConcFloatRetVal() {
        Float float0 = (Float) this.concRetVal;
        return float0;
    }

    /**
     * Returns the concrete return value of the concrete method execution. The
     * concrete return value should be a boolean value.
     *
     * @return a boolean value with the concrete method execution.
     */
    protected final boolean getConcBooleanRetVal() {
        Boolean boolean0 = (Boolean) this.concRetVal;
        return boolean0;
    }

    /**
     * Returns the concrete return value of the concrete method execution. The
     * concrete return value should be a byte value.
     *
     * @return a byte value with the concrete method execution.
     */
    protected final byte getConcByteRetVal() {
        Integer integer0 = (Integer) this.concRetVal;
        return integer0.byteValue();
    }

    /**
     * Returns the concrete return value of the concrete method execution. The
     * concrete return value should be a long value.
     *
     * @return a long value with the concrete method execution.
     */
    protected final long getConcLongRetVal() {
        Long long0 = (Long) this.concRetVal;
        return long0;
    }

    /**
     * Returns the concrete return value of the concrete method execution. The
     * concrete return value should be an Object reference.
     *
     * @return an Object reference with the concrete method execution.
     */
    protected final Object getConcRetVal() {
        Object arg = this.concRetVal;
        return arg;
    }

    public final String getDesc() {
        return desc;
    }

    /**
     * This callback-method is invoked by the VM before the actual execution of
     * the method.
     *
     * <p>This is the very last chance of saving concrete values before the
     * execution of the concrete method.</p>
     *
     * <p>This could return an IntegerConstraint (such as String.isInteger)</p>
     *
     * @return an IntegerConstraint if applicable, null otherwise
     */
    public IntegerConstraint beforeExecuteFunction() {
        return null;
    }
}
