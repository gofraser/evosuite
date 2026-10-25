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
package org.evosuite.symbolic.vm;

import org.evosuite.dse.IVM;
import org.evosuite.symbolic.expr.bv.IntegerValue;
import org.evosuite.symbolic.expr.constraint.IntegerConstraint;
import org.evosuite.symbolic.expr.fp.RealValue;
import org.evosuite.symbolic.expr.ref.ReferenceExpression;
import org.evosuite.symbolic.vm.apache.regex.Perl5Matcher_Matches;
import org.evosuite.symbolic.vm.bigint.BigInteger_Ctor;
import org.evosuite.symbolic.vm.bigint.BigInteger_DivideAndRemainder;
import org.evosuite.symbolic.vm.bigint.BigInteger_IntValue;
import org.evosuite.symbolic.vm.math.*;
import org.evosuite.symbolic.vm.regex.Matcher_Matches;
import org.evosuite.symbolic.vm.regex.Pattern_Matcher;
import org.evosuite.symbolic.vm.regex.Pattern_Matches;
import org.evosuite.symbolic.vm.string.*;
import org.evosuite.symbolic.vm.string.buffer.StringBuffer_Init.StringBufferInit_S;
import org.evosuite.symbolic.vm.string.buffer.StringBuffer_SetLength;
import org.evosuite.symbolic.vm.string.buffer.StringBuffer_ToString;
import org.evosuite.symbolic.vm.string.builder.StringBuilder_Init;
import org.evosuite.symbolic.vm.string.builder.StringBuilder_ToString;
import org.evosuite.symbolic.vm.string.reader.Reader_Read;
import org.evosuite.symbolic.vm.string.reader.StringReader_Init;
import org.evosuite.symbolic.vm.string.reader.StringReader_Read;
import org.evosuite.symbolic.vm.string.tokenizer.HasMoreTokens;
import org.evosuite.symbolic.vm.string.tokenizer.NextToken;
import org.evosuite.symbolic.vm.string.tokenizer.StringTokenizer_Init;
import org.evosuite.symbolic.vm.wrappers.*;
import org.objectweb.asm.Type;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Symbolic virtual machine for functions.
 *
 * @author galeotti
 */
public final class SymbolicFunctionVM implements IVM {

    private static class FunctionKey {
        public FunctionKey(String owner, String name, String desc) {
            super();
            this.owner = owner;
            this.name = name;
            this.desc = desc;
        }

        public String owner;
        public String name;
        public String desc;

        @Override
        public int hashCode() {
            return owner.hashCode() + name.hashCode() + desc.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || !o.getClass().equals(FunctionKey.class)) {
                return false;
            } else {
                FunctionKey that = (FunctionKey) o;
                return this.owner.equals(that.owner)
                        && this.name.equals(that.name)
                        && this.desc.equals(that.desc);
            }
        }
    }

    private final SymbolicEnvironment env;
    private final PathConditionCollector pc;

    /**
     * Builds a new SymbolicFunctionVM.
     *
     * @param env the symbolic environment
     * @param pc the path condition collector
     */
    public SymbolicFunctionVM(SymbolicEnvironment env, PathConditionCollector pc) {
        this.env = env;
        this.pc = pc;
        fillFunctionsTable();
    }

    /**
     * Fills the symbolic functions table.
     */
    private void fillFunctionsTable() {
        fillMath();
        fillBoxingWrappers();
        fillString();
        fillReader();
        fillTextRegex();
        fillBigInteger();
        fillStringReader();
        fillRegexPattern();
        fillStringBuffer();
        fillRegexMatcher();
        fillStringBuilder();
        fillStringTokenizer();
    }

    private void addFunctionToTable(SymbolicFunction f) {
        FunctionKey k = new FunctionKey(f.getOwner(), f.getName(), f.getDesc());
        functionsTable.put(k, f);
    }

    @Override
    public void INVOKESTATIC(String owner, String name, String desc) {
        functionUnderExecution = getFunction(owner, name, desc);
        if (functionUnderExecution != null) {
            if (Type.getArgumentTypes(desc).length == 0) {
                callBeforeExecution(functionUnderExecution);
            }
        }
    }

    @Override
    public void INVOKEVIRTUAL(Object concReceiver, String owner, String name,
                              String desc) {
        functionUnderExecution = getFunction(owner, name, desc);
        if (functionUnderExecution != null) {
            ReferenceExpression symbReceiver = getReceiverFromStack();
            functionUnderExecution.setReceiver(concReceiver, symbReceiver);
            if (Type.getArgumentTypes(desc).length == 0) {
                callBeforeExecution(functionUnderExecution);
            }
        }
    }

    private ReferenceExpression getReceiverFromStack() {
        String desc = this.functionUnderExecution.getDesc();
        Type[] argTypes = Type.getArgumentTypes(desc);
        Iterator<Operand> it = this.env.topFrame().operandStack.iterator();
        for (int i = 0; i < argTypes.length; i++) {
            it.next(); // move cursor until reference
        }
        Operand op = it.next();
        return ((ReferenceOperand) op).getReference();
    }

    private Operand getOperandFromStack(int nr) {
        String desc = functionUnderExecution.getDesc();
        Type[] argTypes = Type.getArgumentTypes(desc);
        int moves = argTypes.length - 1 - nr;

        Iterator<Operand> it = this.env.topFrame().operandStack.iterator();
        for (int i = 0; i < moves; i++) {
            it.next(); // move cursor until reference
        }
        Operand op = it.next();
        return op;
    }

    private IntegerValue getIntegerExprFromStack(int nr) {
        IntegerOperand op = (IntegerOperand) getOperandFromStack(nr);
        return op.getIntegerExpression();
    }

    private RealValue getRealExprFromStack(int moves) {
        RealOperand op = (RealOperand) getOperandFromStack(moves);
        return op.getRealExpression();
    }

    private ReferenceExpression getReferenceFromStack(int moves) {
        ReferenceOperand op = (ReferenceOperand) getOperandFromStack(moves);
        return op.getReference();
    }

    private final Map<FunctionKey, SymbolicFunction> functionsTable = new HashMap<>();

    private SymbolicFunction getFunction(String owner, String name, String desc) {
        FunctionKey k = new FunctionKey(owner, name, desc);
        SymbolicFunction f = functionsTable.get(k);
        return f;
    }

    @Override
    public void CALL_RESULT(int concRetVal, String owner, String name,
                            String desc) {

        if (functionUnderExecution != null) {
            if (!functionUnderExecution.getOwner().equals(owner)
                    || !functionUnderExecution.getName().equals(name)
                    || !functionUnderExecution.getDesc().equals(desc)) {

                functionUnderExecution = null;
            }
        }

        if (functionUnderExecution != null) {
            IntegerValue symbRetVal = this.env.topFrame().operandStack
                    .peekBv32();
            functionUnderExecution.setReturnValue(concRetVal, symbRetVal);
            IntegerValue newSymbRetVal = (IntegerValue) functionUnderExecution
                    .executeFunction();
            this.replaceTopBv32(newSymbRetVal);
        }
        functionUnderExecution = null;
    }

    @Override
    public void CALL_RESULT(Object concRetVal, String owner, String name,
                            String desc) {

        if (functionUnderExecution != null) {
            if (!functionUnderExecution.getOwner().equals(owner)
                    || !functionUnderExecution.getName().equals(name)
                    || !functionUnderExecution.getDesc().equals(desc)) {

                functionUnderExecution = null;
            }
        }

        if (functionUnderExecution != null) {
            ReferenceExpression symbRetVal = this.env.topFrame().operandStack.peekRef();
            functionUnderExecution.setReturnValue(concRetVal, symbRetVal);
            ReferenceExpression newSymbRetVal = (ReferenceExpression) functionUnderExecution
                    .executeFunction();
            this.replaceTopRef(newSymbRetVal);
        }
        functionUnderExecution = null;
    }

    private void replaceTopRef(ReferenceExpression ref) {
        env.topFrame().operandStack.popRef();
        env.topFrame().operandStack.pushRef(ref);
    }

    @Override
    public void CALL_RESULT(String owner, String name, String desc) {

        if (functionUnderExecution != null) {
            if (!functionUnderExecution.getOwner().equals(owner)
                    || !functionUnderExecution.getName().equals(name)
                    || !functionUnderExecution.getDesc().equals(desc)) {
                functionUnderExecution = null;
            }
        }

        if (functionUnderExecution != null) {
            functionUnderExecution.executeFunction();
        }
        functionUnderExecution = null;
    }

    @Override
    public void CALL_RESULT(boolean concRetVal, String owner, String name,
                            String desc) {
        if (functionUnderExecution != null) {
            if (!functionUnderExecution.getOwner().equals(owner)
                    || !functionUnderExecution.getName().equals(name)
                    || !functionUnderExecution.getDesc().equals(desc)) {

                functionUnderExecution = null;
            }
        }

        if (functionUnderExecution != null) {

            IntegerValue symbRetVal = this.env.topFrame().operandStack
                    .peekBv32();
            functionUnderExecution.setReturnValue(concRetVal, symbRetVal);
            IntegerValue newSymbRetVal = (IntegerValue) functionUnderExecution
                    .executeFunction();
            this.replaceTopBv32(newSymbRetVal);
        }
        functionUnderExecution = null;
    }

    @Override
    public void CALL_RESULT(long concRetVal, String owner, String name,
                            String desc) {

        if (functionUnderExecution != null) {
            if (!functionUnderExecution.getOwner().equals(owner)
                    || !functionUnderExecution.getName().equals(name)
                    || !functionUnderExecution.getDesc().equals(desc)) {

                functionUnderExecution = null;
            }
        }

        if (functionUnderExecution != null) {
            IntegerValue symbRetVal = this.env.topFrame().operandStack
                    .peekBv64();
            functionUnderExecution.setReturnValue(concRetVal, symbRetVal);
            IntegerValue newSymbRetVal = (IntegerValue) functionUnderExecution
                    .executeFunction();
            this.replaceTopBv64(newSymbRetVal);
        }
        functionUnderExecution = null;
    }

    @Override
    public void CALL_RESULT(double concRetVal, String owner, String name,
                            String desc) {

        if (functionUnderExecution != null) {
            if (!functionUnderExecution.getOwner().equals(owner)
                    || !functionUnderExecution.getName().equals(name)
                    || !functionUnderExecution.getDesc().equals(desc)) {

                functionUnderExecution = null;
            }
        }

        if (functionUnderExecution != null) {
            RealValue symbRetVal = this.env.topFrame().operandStack
                    .peekFp64();
            functionUnderExecution.setReturnValue(concRetVal, symbRetVal);
            RealValue newSymbRetVal = (RealValue) functionUnderExecution
                    .executeFunction();
            this.replaceTopFp64(newSymbRetVal);
        }
        functionUnderExecution = null;
    }

    @Override
    public void CALL_RESULT(float concRetVal, String owner, String name,
                            String desc) {

        if (functionUnderExecution != null) {
            if (!functionUnderExecution.getOwner().equals(owner)
                    || !functionUnderExecution.getName().equals(name)
                    || !functionUnderExecution.getDesc().equals(desc)) {

                functionUnderExecution = null;
            }
        }

        if (functionUnderExecution != null) {
            RealValue symbRetVal = this.env.topFrame().operandStack
                    .peekFp32();
            functionUnderExecution.setReturnValue(concRetVal, symbRetVal);
            RealValue newSymbRetVal = (RealValue) functionUnderExecution
                    .executeFunction();
            this.replaceTopFp32(newSymbRetVal);
        }
        functionUnderExecution = null;
    }

    @Override
    public void INVOKESPECIAL(String owner, String name, String desc) {
        functionUnderExecution = getFunction(owner, name, desc);
        if (functionUnderExecution != null) {
            ReferenceExpression symbReceiver = getReceiverFromStack();
            functionUnderExecution.setReceiver(
                    null /* receiver not yet ready */, symbReceiver);
            if (Type.getArgumentTypes(desc).length == 0) {
                callBeforeExecution(functionUnderExecution);
            }
        }
    }

    /**
     * Helper for creating a new symbolic integer expression from an existing one.
     *
     * @param myFunctionUnderExecution the symbolic function under execution
     */
    private void callBeforeExecution(SymbolicFunction myFunctionUnderExecution) {
        IntegerConstraint constraint = myFunctionUnderExecution
                .beforeExecuteFunction();
        if (constraint != null) {
            pc.appendSupportingConstraint(constraint);
        }
    }

    @Override
    public void INVOKESPECIAL(Object concReceiver, String owner, String name,
                              String desc) {
        functionUnderExecution = getFunction(owner, name, desc);
        if (functionUnderExecution != null) {
            ReferenceExpression symbReceiver = getReceiverFromStack();
            functionUnderExecution.setReceiver(concReceiver, symbReceiver);
            if (Type.getArgumentTypes(desc).length == 0) {
                callBeforeExecution(functionUnderExecution);
            }
        }

    }

    private SymbolicFunction functionUnderExecution;

    @Override
    public void INVOKEINTERFACE(Object concReceiver, String owner,
                                String name, String desc) {
        functionUnderExecution = getFunction(owner, name, desc);
        if (functionUnderExecution != null) {
            ReferenceExpression symbReceiver = getReceiverFromStack();
            functionUnderExecution.setReceiver(concReceiver, symbReceiver);
            if (Type.getArgumentTypes(functionUnderExecution.getDesc()).length == 0) {
                callBeforeExecution(functionUnderExecution);
            }
        }

    }

    @Override
    public void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, int concArg) {
        if (functionUnderExecution != null) {
            IntegerValue symbArg = getIntegerExprFromStack(nr);
            functionUnderExecution.setParam(nr, concArg, symbArg);
            beforeExecuteFunction(nr);

        }
    }

    @Override
    public void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex,
                                   boolean concArg) {
        if (functionUnderExecution != null) {
            IntegerValue symbArg = getIntegerExprFromStack(nr);
            functionUnderExecution.setParam(nr, concArg, symbArg);
            beforeExecuteFunction(nr);
        }
    }

    private void beforeExecuteFunction(int nr) {
        // String desc = functionUnderExecution.getDesc();
        if (nr == 0) {
            callBeforeExecution(functionUnderExecution);
        }
    }

    @Override
    public void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, short concArg) {
        if (functionUnderExecution != null) {
            IntegerValue symbArg = getIntegerExprFromStack(nr);
            functionUnderExecution.setParam(nr, concArg, symbArg);
            beforeExecuteFunction(nr);

        }
    }

    @Override
    public void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, byte concArg) {
        if (functionUnderExecution != null) {
            IntegerValue symbArg = getIntegerExprFromStack(nr);
            functionUnderExecution.setParam(nr, concArg, symbArg);
            beforeExecuteFunction(nr);

        }
    }

    @Override
    public void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, char concArg) {
        if (functionUnderExecution != null) {
            IntegerValue symbArg = getIntegerExprFromStack(nr);
            functionUnderExecution.setParam(nr, concArg, symbArg);
            beforeExecuteFunction(nr);

        }
    }

    @Override
    public void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, long concArg) {
        if (functionUnderExecution != null) {
            IntegerValue symbArg = getIntegerExprFromStack(nr);
            functionUnderExecution.setParam(nr, concArg, symbArg);
            beforeExecuteFunction(nr);

        }
    }

    @Override
    public void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, float concArg) {
        if (functionUnderExecution != null) {
            RealValue symbArg = getRealExprFromStack(nr);
            functionUnderExecution.setParam(nr, concArg, symbArg);
            beforeExecuteFunction(nr);

        }
    }

    @Override
    public void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex,
                                   double concArg) {
        if (functionUnderExecution != null) {
            RealValue symbArg = getRealExprFromStack(nr);
            functionUnderExecution.setParam(nr, concArg, symbArg);
            beforeExecuteFunction(nr);

        }
    }

    @Override
    public void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex,
                                   Object concArg) {
        if (functionUnderExecution != null) {
            ReferenceExpression symbArg = getReferenceFromStack(nr);
            functionUnderExecution.setParam(nr, concArg, symbArg);
            beforeExecuteFunction(nr);

        }
    }

    private void replaceTopBv32(IntegerValue expr) {
        env.topFrame().operandStack.popBv32();
        env.topFrame().operandStack.pushBv32(expr);
    }

    private void replaceTopBv64(IntegerValue expr) {
        env.topFrame().operandStack.popBv64();
        env.topFrame().operandStack.pushBv64(expr);
    }

    private void replaceTopFp32(RealValue expr) {
        this.env.topFrame().operandStack.popFp32();
        this.env.topFrame().operandStack.pushFp32(expr);
    }

    private void replaceTopFp64(RealValue expr) {
        env.topFrame().operandStack.popFp64();
        env.topFrame().operandStack.pushFp64(expr);
    }

    /**
     * Creation section.
     */
    private void fillBigInteger() {
        // java.math.BigInteger
        addFunctionToTable(new BigInteger_Ctor(env));
        addFunctionToTable(new BigInteger_IntValue(env));
        addFunctionToTable(new BigInteger_DivideAndRemainder(env));
    }

    private void fillTextRegex() {
        // org.apache.oro.text.regex
        addFunctionToTable(new Perl5Matcher_Matches(env));
    }

    private void fillRegexMatcher() {
        // java.util.regex.Matcher
        addFunctionToTable(new Matcher_Matches(env));
    }

    private void fillRegexPattern() {
        // java.util.regex.Pattern
        addFunctionToTable(new Pattern_Matches(env));
        addFunctionToTable(new Pattern_Matcher(env));
    }

    private void fillReader() {
        // java.io.Reader
        addFunctionToTable(new Reader_Read(env));
    }

    private void fillStringReader() {
        // java.io.StringReader
        addFunctionToTable(new StringReader_Init(env));
        addFunctionToTable(new StringReader_Read(env));
    }

    private void fillStringTokenizer() {
        // java.util.StringTokenizer
        addFunctionToTable(new StringTokenizer_Init(env));
        addFunctionToTable(new HasMoreTokens(env));
        addFunctionToTable(new NextToken(env));
    }

    private void fillStringBuilder() {
        // java.lang.StringBuilder
        addFunctionToTable(new StringBuilder_Init(env));
        Append.stringBuilderFunctions(env).forEach(this::addFunctionToTable);
        addFunctionToTable(new StringBuilder_ToString(env));
    }

    private void fillString() {
        // java.lang.String
        addFunctionToTable(new CharAt(env));
        addFunctionToTable(new CompareTo(env));
        addFunctionToTable(new CompareToIgnoreCase(env));
        addFunctionToTable(new Concat(env));
        addFunctionToTable(new Contains(env));
        addFunctionToTable(new EndsWith(env));
        addFunctionToTable(new Equals(env));
        addFunctionToTable(new EqualsIgnoreCase(env));
        IndexOf.indexOfFunctions(env).forEach(this::addFunctionToTable);
        IndexOf.lastIndexOfFunctions(env).forEach(this::addFunctionToTable);
        addFunctionToTable(new Length(env));
        addFunctionToTable(new Matches(env));
        addFunctionToTable(new RegionMatches(env));
        addFunctionToTable(new RegionMatches5(env));
        addFunctionToTable(new Replace.Replace_C(env));
        addFunctionToTable(new Replace.Replace_CS(env));
        addFunctionToTable(new ReplaceAll(env));
        addFunctionToTable(new ReplaceFirst(env));
        addFunctionToTable(new StartsWith.StartsWith_S(env));
        addFunctionToTable(new StartsWith.StartsWith_SI(env));
        addFunctionToTable(new Substring.Substring_I(env));
        addFunctionToTable(new Substring.Substring_II(env));
        addFunctionToTable(new ToLowerCase(env));
        addFunctionToTable(new ToString(env));
        addFunctionToTable(new ToUpperCase(env));
        addFunctionToTable(new Trim(env));
        addFunctionToTable(new ValueOf.ValueOf_O(env));
        addFunctionToTable(new ValueOf.ValueOf_I(env));
        addFunctionToTable(new ValueOf.ValueOf_J(env));
        addFunctionToTable(new ValueOf.ValueOf_C(env));
        addFunctionToTable(new ValueOf.ValueOf_B(env));
    }

    private void fillMath() {
        // java.lang.Math
        MathFunctions.create(env).forEach(this::addFunctionToTable);
    }

    private void fillBoxingWrappers() {
        // java.lang.Integer, Long, Float, Double, Short, Byte, Character and Boolean
        BoxedType.models(env).forEach(this::addFunctionToTable);
        addFunctionToTable(new Character_getNumericValue(env));
        addFunctionToTable(new Character_isDigit(env));
        addFunctionToTable(new Character_isLetter(env));
        addFunctionToTable(new I_ParseInt(env));
    }

    private void fillStringBuffer() {
        // java.lang.StringBuffer
        addFunctionToTable(new StringBufferInit_S(env));
        addFunctionToTable(new StringBuffer_ToString(env));
        Append.stringBufferFunctions(env).forEach(this::addFunctionToTable);
        addFunctionToTable(new StringBuffer_SetLength(env));
    }
}
