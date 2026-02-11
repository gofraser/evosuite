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

import org.evosuite.Properties;
import org.evosuite.dse.AbstractVM;
import org.evosuite.symbolic.instrument.ConcolicConfig;
import org.evosuite.symbolic.vm.instructionlogger.IInstructionLogger;
import org.evosuite.symbolic.vm.instructionlogger.InstructionLoggerFactory;

/**
 * Logs the name of a ByteCode instruction and any of its parameters.
 *
 * <p>This class is adapted from the DSC tool developed by Christoph Csallner.
 *
 * @see <a href="http://ranger.uta.edu/~csallner/dsc/index.html">DSC Tool</a>
 * @author csallner@uta.edu (Christoph Csallner)
 */
public class InstructionLoggerVM extends AbstractVM {

    private final IInstructionLogger instructionLogger;

    /**
     * Constucts a new {@code InstructionLoggerVM} with the logger mode defined in {@link Properties}.
     */
    public InstructionLoggerVM() {
        this.instructionLogger = InstructionLoggerFactory.getInstance()
                .getInstructionLogger(Properties.BYTECODE_LOGGING_MODE);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the source line number.
     */
    @Override
    public void SRC_LINE_NUMBER(int lineNr) {
        instructionLogger.log("\t\t\t\t\tsrc line: ");
        instructionLogger.logln(lineNr);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs a parameter on the caller's stack.
     */
    @Override
    public void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, boolean value) {
        CALLER_STACK_PARAM(nr, calleeLocalsIndex, (Object) value);
    }

    @Override
    public void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, byte value) {
        CALLER_STACK_PARAM(nr, calleeLocalsIndex, (Object) value);
    }

    @Override
    public void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, char value) {
        CALLER_STACK_PARAM(nr, calleeLocalsIndex, (Object) value);
    }

    @Override
    public void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, double value) {
        CALLER_STACK_PARAM(nr, calleeLocalsIndex, (Object) value);
    }

    @Override
    public void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, float value) {
        CALLER_STACK_PARAM(nr, calleeLocalsIndex, (Object) value);
    }

    @Override
    public void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, int value) {
        CALLER_STACK_PARAM(nr, calleeLocalsIndex, (Object) value);
    }

    @Override
    public void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, long value) {
        CALLER_STACK_PARAM(nr, calleeLocalsIndex, (Object) value);
    }

    @Override
    public void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, short value) {
        CALLER_STACK_PARAM(nr, calleeLocalsIndex, (Object) value);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs an object parameter on the caller's stack.
     */
    @Override
    public void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, Object value) {
        instructionLogger.log("callerStackParam ");
        instructionLogger.log(nr);
        // TODO: why theres a null value coming here? should that happend?
        if (value != null) {
            instructionLogger.logln(" ", value.toString());
        } else {
            instructionLogger.logln(" ", "null");
        }
    }


    /**
     * {@inheritDoc}
     *
     * <p>Logs the beginning of a method call.
     */
    @Override
    public void METHOD_BEGIN(int access, String className, String methName, String methDesc) {
        // FIXME: print modifiers (static, public, etc.)
        instructionLogger.log("-------------------", "enter method ");
        instructionLogger.log(className, " ");
        instructionLogger.log(methName, " ");
        instructionLogger.log(methDesc);
        instructionLogger.logln();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs a parameter at the beginning of a method.
     */
    @Override
    public void METHOD_BEGIN_PARAM(int nr, int calleeLocalsIndex, boolean value) {
        METHOD_BEGIN_PARAM(nr, calleeLocalsIndex, (Object) value);
    }

    @Override
    public void METHOD_BEGIN_PARAM(int nr, int calleeLocalsIndex, byte value) {
        METHOD_BEGIN_PARAM(nr, calleeLocalsIndex, (Object) value);
    }

    @Override
    public void METHOD_BEGIN_PARAM(int nr, int calleeLocalsIndex, char value) {
        METHOD_BEGIN_PARAM(nr, calleeLocalsIndex, (Object) value);
    }

    @Override
    public void METHOD_BEGIN_PARAM(int nr, int calleeLocalsIndex, double value) {
        METHOD_BEGIN_PARAM(nr, calleeLocalsIndex, (Object) value);
    }

    @Override
    public void METHOD_BEGIN_PARAM(int nr, int calleeLocalsIndex, float value) {
        METHOD_BEGIN_PARAM(nr, calleeLocalsIndex, (Object) value);
    }

    @Override
    public void METHOD_BEGIN_PARAM(int nr, int calleeLocalsIndex, int value) {
        METHOD_BEGIN_PARAM(nr, calleeLocalsIndex, (Object) value);
    }

    @Override
    public void METHOD_BEGIN_PARAM(int nr, int calleeLocalsIndex, long value) {
        METHOD_BEGIN_PARAM(nr, calleeLocalsIndex, (Object) value);
    }

    @Override
    public void METHOD_BEGIN_PARAM(int nr, int calleeLocalsIndex, short value) {
        METHOD_BEGIN_PARAM(nr, calleeLocalsIndex, (Object) value);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs an object parameter at the beginning of a method.
     */
    @Override
    public void METHOD_BEGIN_PARAM(int nr, int calleeLocalsIndex, Object value) {
        instructionLogger.log("methodBeginParam ");
        instructionLogger.log(nr);
        instructionLogger.log(" ");
        instructionLogger.log(calleeLocalsIndex);
        if (value != null) {
            instructionLogger.logln(" ", value.toString());
        } else {
            instructionLogger.logln(" ", "null");
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the receiver object at the beginning of a method.
     */
    @Override
    public void METHOD_BEGIN_RECEIVER(Object value) {
        instructionLogger.log("methodBeginReceiver ");
        if (value != null) {
            instructionLogger.logln(" ", value.toString());
        } else {
            instructionLogger.logln(" ", "null");
        }
    }


    /**
     * {@inheritDoc}
     *
     * <p>Logs the result of a method call.
     */
    @Override
    public void CALL_RESULT(String owner, String name, String desc) {
        callResult("void");
    }

    @Override
    public void CALL_RESULT(boolean res, String owner, String name, String desc) {
        callResult(Boolean.valueOf(res));
    }

    @Override
    public void CALL_RESULT(int res, String owner, String name, String desc) {
        callResult(Integer.valueOf(res));
    }

    @Override
    public void CALL_RESULT(long res, String owner, String name, String desc) {
        callResult(Long.valueOf(res));
    }

    @Override
    public void CALL_RESULT(double res, String owner, String name, String desc) {
        callResult(Double.valueOf(res));
    }

    @Override
    public void CALL_RESULT(float res, String owner, String name, String desc) {
        callResult(Float.valueOf(res));
    }

    @Override
    public void CALL_RESULT(Object res, String owner, String name, String desc) {
        callResult(res);
    }

    /**
     * Logs the result object.
     *
     * @param res the result object
     */
    protected void callResult(Object res) {
        instructionLogger.log("\t ==> ");
        if (res == null) {
            instructionLogger.logln("null");
        } else {
            instructionLogger.logln(res.toString());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the beginning of a basic block.
     */
    @Override
    public void BB_BEGIN() {
        instructionLogger.logln("---------- basic block");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the beginning of an exception handler block.
     */
    @Override
    public void HANDLER_BEGIN(int access, String className, String methName,
                              String methDesc) {
        instructionLogger.log("---------- handler block in ");
        instructionLogger.logln(className, ".", methName);
    }

    /*
     * Some 200 JVM ByteCode instructions
     */

    /**
     * {@inheritDoc}
     *
     * <p>Logs the NOP instruction.
     */
    @Override
    public void NOP() {
        logInsn(0);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the ACONST_NULL instruction.
     */
    @Override
    public void ACONST_NULL() {
        logInsn(1);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the ICONST_M1 instruction.
     */
    @Override
    public void ICONST_M1() {
        logInsn(2);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the ICONST_0 instruction.
     */
    @Override
    public void ICONST_0() {
        logInsn(3);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the ICONST_1 instruction.
     */
    @Override
    public void ICONST_1() {
        logInsn(4);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the ICONST_2 instruction.
     */
    @Override
    public void ICONST_2() {
        logInsn(5);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the ICONST_3 instruction.
     */
    @Override
    public void ICONST_3() {
        logInsn(6);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the ICONST_4 instruction.
     */
    @Override
    public void ICONST_4() {
        logInsn(7);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the ICONST_5 instruction.
     */
    @Override
    public void ICONST_5() {
        logInsn(8);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LCONST_0 instruction.
     */
    @Override
    public void LCONST_0() {
        logInsn(9);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LCONST_1 instruction.
     */
    @Override
    public void LCONST_1() {
        logInsn(10);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the FCONST_0 instruction.
     */
    @Override
    public void FCONST_0() {
        logInsn(11);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the FCONST_1 instruction.
     */
    @Override
    public void FCONST_1() {
        logInsn(12);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the FCONST_2 instruction.
     */
    @Override
    public void FCONST_2() {
        logInsn(13);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DCONST_0 instruction.
     */
    @Override
    public void DCONST_0() {
        logInsn(14);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DCONST_1 instruction.
     */
    @Override
    public void DCONST_1() {
        logInsn(15);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the BIPUSH instruction.
     */
    @Override
    public void BIPUSH(int value) {
        logInsn(16, value);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the SIPUSH instruction.
     */
    @Override
    public void SIPUSH(int value) {
        logInsn(17, value);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LDC instruction.
     */
    @Override
    public void LDC(Class<?> x) {
        logInsn(18, x);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LDC instruction with String.
     */
    @Override
    public void LDC(String x) {
        logInsn(18, x);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LDC instruction with int.
     */
    @Override
    public void LDC(int x) {
        logInsn(18, x);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LDC instruction with float.
     */
    @Override
    public void LDC(float x) {
        logInsn(18, Float.valueOf(x));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LDC2_W instruction.
     */
    @Override
    public void LDC2_W(long x) {
        logInsn(20, Long.valueOf(x));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LDC2_W instruction with double.
     */
    @Override
    public void LDC2_W(double x) {
        logInsn(20, Double.valueOf(x));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the ILOAD instruction.
     */
    @Override
    public void ILOAD(int i) {
        logInsn(21, i);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LLOAD instruction.
     */
    @Override
    public void LLOAD(int i) {
        logInsn(22, i);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the FLOAD instruction.
     */
    @Override
    public void FLOAD(int i) {
        logInsn(23, i);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DLOAD instruction.
     */
    @Override
    public void DLOAD(int i) {
        logInsn(24, i);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the ALOAD instruction.
     */
    @Override
    public void ALOAD(int i) {
        logInsn(25, i);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IALOAD instruction.
     */
    @Override
    public void IALOAD(Object receiver, int index, String className, String methodName) {
        logInsn(46, receiver);
    } // visitInsn

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LALOAD instruction.
     */
    @Override
    public void LALOAD(Object receiver, int index, String className, String methodName) {
        logInsn(47, receiver);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the FALOAD instruction.
     */
    @Override
    public void FALOAD(Object receiver, int index, String className, String methodName) {
        logInsn(48, receiver);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DALOAD instruction.
     */
    @Override
    public void DALOAD(Object receiver, int index, String className, String methodName) {
        logInsn(49, receiver);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the AALOAD instruction.
     */
    @Override
    public void AALOAD(Object receiver, int index, String className, String methodName) {
        logInsn(50, receiver);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the BALOAD instruction.
     */
    @Override
    public void BALOAD(Object receiver, int index, String className, String methodName) {
        logInsn(51, receiver);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the CALOAD instruction.
     */
    @Override
    public void CALOAD(Object receiver, int index, String className, String methodName) {
        logInsn(52, receiver);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the SALOAD instruction.
     */
    @Override
    public void SALOAD(Object receiver, int index, String className, String methodName) {
        logInsn(53, receiver);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the ISTORE instruction.
     */
    @Override
    public void ISTORE(int i) {
        logInsn(54, i);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LSTORE instruction.
     */
    @Override
    public void LSTORE(int i) {
        logInsn(55, i);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the FSTORE instruction.
     */
    @Override
    public void FSTORE(int i) {
        logInsn(56, i);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DSTORE instruction.
     */
    @Override
    public void DSTORE(int i) {
        logInsn(57, i);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the ASTORE instruction.
     */
    @Override
    public void ASTORE(int i) {
        logInsn(58, i);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IASTORE instruction.
     */
    @Override
    public void IASTORE(Object arr, int index, String className, String methodName) {
        logInsn(79);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LASTORE instruction.
     */
    @Override
    public void LASTORE(Object arr, int index, String className, String methodName) {
        logInsn(80);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the FASTORE instruction.
     */
    @Override
    public void FASTORE(Object arr, int index, String className, String methodName) {
        logInsn(81);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DASTORE instruction.
     */
    @Override
    public void DASTORE(Object arr, int index, String className, String methodName) {
        logInsn(82);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the AASTORE instruction.
     */
    @Override
    public void AASTORE(Object arr, int index, Object ref, String className, String methodName) {
        logInsn(83);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the BASTORE instruction.
     */
    @Override
    public void BASTORE(Object arr, int index, String className, String methodName) {
        logInsn(84);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the CASTORE instruction.
     */
    @Override
    public void CASTORE(Object arr, int index, String className, String methodName) {
        logInsn(85);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the SASTORE instruction.
     */
    @Override
    public void SASTORE(Object arr, int index, String className, String methodName) {
        logInsn(86);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the POP instruction.
     */
    @Override
    public void POP() {
        logInsn(87);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the POP2 instruction.
     */
    @Override
    public void POP2() {
        logInsn(88);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DUP instruction.
     */
    @Override
    public void DUP() {
        logInsn(89);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DUP_X1 instruction.
     */
    @Override
    public void DUP_X1() {
        logInsn(90);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DUP_X2 instruction.
     */
    @Override
    public void DUP_X2() {
        logInsn(91);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DUP2 instruction.
     */
    @Override
    public void DUP2() {
        logInsn(92);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DUP2_X1 instruction.
     */
    @Override
    public void DUP2_X1() {
        logInsn(93);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DUP2_X2 instruction.
     */
    @Override
    public void DUP2_X2() {
        logInsn(94);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the SWAP instruction.
     */
    @Override
    public void SWAP() {
        logInsn(95);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IADD instruction.
     */
    @Override
    public void IADD() {
        logInsn(96);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LADD instruction.
     */
    @Override
    public void LADD() {
        logInsn(97);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the FADD instruction.
     */
    @Override
    public void FADD() {
        logInsn(98);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DADD instruction.
     */
    @Override
    public void DADD() {
        logInsn(99);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the ISUB instruction.
     */
    @Override
    public void ISUB() {
        logInsn(100);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LSUB instruction.
     */
    @Override
    public void LSUB() {
        logInsn(101);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the FSUB instruction.
     */
    @Override
    public void FSUB() {
        logInsn(102);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DSUB instruction.
     */
    @Override
    public void DSUB() {
        logInsn(103);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IMUL instruction.
     */
    @Override
    public void IMUL() {
        logInsn(104);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LMUL instruction.
     */
    @Override
    public void LMUL() {
        logInsn(105);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the FMUL instruction.
     */
    @Override
    public void FMUL() {
        logInsn(106);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DMUL instruction.
     */
    @Override
    public void DMUL() {
        logInsn(107);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IDIV instruction.
     */
    @Override
    public void IDIV(int rhs) {
        logInsn(108, rhs);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LDIV instruction.
     */
    @Override
    public void LDIV(long rhs) {
        logInsn(109, Long.valueOf(rhs));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the FDIV instruction.
     */
    @Override
    public void FDIV(float rhs) {
        logInsn(110, Float.valueOf(rhs));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DDIV instruction.
     */
    @Override
    public void DDIV(double rhs) {
        logInsn(111, Double.valueOf(rhs));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IREM instruction.
     */
    @Override
    public void IREM(int rhs) {
        logInsn(112, rhs);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LREM instruction.
     */
    @Override
    public void LREM(long rhs) {
        logInsn(113, Long.valueOf(rhs));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the FREM instruction.
     */
    @Override
    public void FREM(float rhs) {
        logInsn(114, Float.valueOf(rhs));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DREM instruction.
     */
    @Override
    public void DREM(double rhs) {
        logInsn(115, Double.valueOf(rhs));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the INEG instruction.
     */
    @Override
    public void INEG() {
        logInsn(116);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LNEG instruction.
     */
    @Override
    public void LNEG() {
        logInsn(117);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the FNEG instruction.
     */
    @Override
    public void FNEG() {
        logInsn(118);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DNEG instruction.
     */
    @Override
    public void DNEG() {
        logInsn(119);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the ISHL instruction.
     */
    @Override
    public void ISHL() {
        logInsn(120);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LSHL instruction.
     */
    @Override
    public void LSHL() {
        logInsn(121);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the ISHR instruction.
     */
    @Override
    public void ISHR() {
        logInsn(122);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LSHR instruction.
     */
    @Override
    public void LSHR() {
        logInsn(123);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IUSHR instruction.
     */
    @Override
    public void IUSHR() {
        logInsn(124);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LUSHR instruction.
     */
    @Override
    public void LUSHR() {
        logInsn(125);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IAND instruction.
     */
    @Override
    public void IAND() {
        logInsn(126);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LAND instruction.
     */
    @Override
    public void LAND() {
        logInsn(127);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IOR instruction.
     */
    @Override
    public void IOR() {
        logInsn(128);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LOR instruction.
     */
    @Override
    public void LOR() {
        logInsn(129);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IXOR instruction.
     */
    @Override
    public void IXOR() {
        logInsn(130);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LXOR instruction.
     */
    @Override
    public void LXOR() {
        logInsn(131);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IINC instruction.
     */
    @Override
    public void IINC(int i, int value) {
        logInsn(132, i, value);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the I2L instruction.
     */
    @Override
    public void I2L() {
        logInsn(133);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the I2F instruction.
     */
    @Override
    public void I2F() {
        logInsn(134);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the I2D instruction.
     */
    @Override
    public void I2D() {
        logInsn(135);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the L2I instruction.
     */
    @Override
    public void L2I() {
        logInsn(136);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the L2F instruction.
     */
    @Override
    public void L2F() {
        logInsn(137);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the L2D instruction.
     */
    @Override
    public void L2D() {
        logInsn(138);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the F2I instruction.
     */
    @Override
    public void F2I() {
        logInsn(139);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the F2L instruction.
     */
    @Override
    public void F2L() {
        logInsn(140);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the F2D instruction.
     */
    @Override
    public void F2D() {
        logInsn(141);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the D2I instruction.
     */
    @Override
    public void D2I() {
        logInsn(142);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the D2L instruction.
     */
    @Override
    public void D2L() {
        logInsn(143);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the D2F instruction.
     */
    @Override
    public void D2F() {
        logInsn(144);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the I2B instruction.
     */
    @Override
    public void I2B() {
        logInsn(145);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the I2C instruction.
     */
    @Override
    public void I2C() {
        logInsn(146);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the I2S instruction.
     */
    @Override
    public void I2S() {
        logInsn(147);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LCMP instruction.
     */
    @Override
    public void LCMP() {
        logInsn(148);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the FCMPL instruction.
     */
    @Override
    public void FCMPL() {
        logInsn(149);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the FCMPG instruction.
     */
    @Override
    public void FCMPG() {
        logInsn(150);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DCMPL instruction.
     */
    @Override
    public void DCMPL() {
        logInsn(151);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DCMPG instruction.
     */
    @Override
    public void DCMPG() {
        logInsn(152);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IFEQ instruction.
     */
    @Override
    public void IFEQ(String className, String methNane, int branchIndex, int p) {
        logInsn(153, p);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IFNE instruction.
     */
    @Override
    public void IFNE(String className, String methNane, int branchIndex, int p) {
        logInsn(154, p);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IFLT instruction.
     */
    @Override
    public void IFLT(String className, String methNane, int branchIndex, int p) {
        logInsn(155, p);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IFGE instruction.
     */
    @Override
    public void IFGE(String className, String methNane, int branchIndex, int p) {
        logInsn(156, p);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IFGT instruction.
     */
    @Override
    public void IFGT(String className, String methNane, int branchIndex, int p) {
        logInsn(157, p);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IFLE instruction.
     */
    @Override
    public void IFLE(String className, String methNane, int branchIndex, int p) {
        logInsn(158, p);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IF_ICMPEQ instruction.
     */
    @Override
    public void IF_ICMPEQ(String className, String methNane, int branchIndex, int left, int right) {
        logInsn(159, left, right);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IF_ICMPNE instruction.
     */
    @Override
    public void IF_ICMPNE(String className, String methNane, int branchIndex, int left, int right) {
        logInsn(160, left, right);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IF_ICMPLT instruction.
     */
    @Override
    public void IF_ICMPLT(String className, String methNane, int branchIndex, int left, int right) {
        logInsn(161, left, right);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IF_ICMPGE instruction.
     */
    @Override
    public void IF_ICMPGE(String className, String methNane, int branchIndex, int left, int right) {
        logInsn(162, left, right);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IF_ICMPGT instruction.
     */
    @Override
    public void IF_ICMPGT(String className, String methNane, int branchIndex, int left, int right) {
        logInsn(163, left, right);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IF_ICMPLE instruction.
     */
    @Override
    public void IF_ICMPLE(String className, String methNane, int branchIndex, int left, int right) {
        logInsn(164, left, right);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IF_ACMPEQ instruction.
     */
    @Override
    public void IF_ACMPEQ(String className, String methNane, int branchIndex, Object left, Object right) {
        logInsn(165, left, right);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IF_ACMPNE instruction.
     */
    @Override
    public void IF_ACMPNE(String className, String methNane, int branchIndex, Object left, Object right) {
        logInsn(166, left, right);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the GOTO instruction.
     */
    @Override
    public void GOTO() {
        logInsn(167);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the JSR instruction.
     */
    @Override
    public void JSR() {
        logInsn(168);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the RET instruction.
     */
    @Override
    public void RET() {
        logInsn(169);
    } // visitVarInsn

    /**
     * {@inheritDoc}
     *
     * <p>Logs the TABLESWITCH instruction.
     */
    @Override
    public void TABLESWITCH(String className, String methName, int branchIndex, int target, int min, int max) {
        logInsn(170, target);
    } // visiTableSwitchInsn

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LOOKUPSWITCH instruction.
     */
    @Override
    public void LOOKUPSWITCH(String className, String methName,
                             int branchIndex, int target, int[] goals) {
        logInsn(171, target);
    } // visitLookupSwitch

    /**
     * Helper method to log method exit.
     */
    protected void exit() {
        instructionLogger.logln("-------------------- exit method");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IRETURN instruction.
     */
    @Override
    public void IRETURN() {
        logInsn(172);
        exit();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the LRETURN instruction.
     */
    @Override
    public void LRETURN() {
        logInsn(173);
        exit();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the FRETURN instruction.
     */
    @Override
    public void FRETURN() {
        logInsn(174);
        exit();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the DRETURN instruction.
     */
    @Override
    public void DRETURN() {
        logInsn(175);
        exit();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the ARETURN instruction.
     */
    @Override
    public void ARETURN() {
        logInsn(176);
        exit();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the RETURN instruction.
     */
    @Override
    public void RETURN() {
        logInsn(177);
        exit();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the GETSTATIC instruction.
     */
    @Override
    public void GETSTATIC(String owner, String name, String desc) {
        logInsn(178, owner, name, desc);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the PUTSTATIC instruction.
     */
    @Override
    public void PUTSTATIC(String owner, String name, String desc) {
        logInsn(179, owner, name, desc);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the GETFIELD instruction.
     */
    @Override
    public void GETFIELD(Object receiver, String owner, String name, String desc) {
        logInsn(180, receiver, owner, name, desc);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the PUTFIELD instruction.
     */
    @Override
    public void PUTFIELD(Object receiver, String owner, String name, String desc) {
        logInsn(181, receiver, owner, name, desc);
    }


    /**
     * {@inheritDoc}
     *
     * <p>Logs the INVOKESTATIC instruction.
     */
    @Override
    public void INVOKESTATIC(String owner, String name, String desc) {
        logInsn(184, owner, name, desc);
    }


    /**
     * {@inheritDoc}
     *
     * <p>Logs the INVOKEVIRTUAL instruction.
     */
    @Override
    public void INVOKEVIRTUAL(Object receiver, String owner, String name, String desc) {
        logInsn(182, owner, name, desc, receiver);
    }


    /**
     * {@inheritDoc}
     *
     * <p>Logs the INVOKESPECIAL instruction.
     */
    @Override
    public void INVOKESPECIAL(String owner, String name, String desc) {
        logInsn(183, owner, name, desc);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the INVOKESPECIAL instruction with receiver.
     */
    @Override
    public void INVOKESPECIAL(Object receiver, String owner, String name, String desc) {
        logInsn(183, owner, name, desc, receiver);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the INVOKEINTERFACE instruction.
     */
    @Override
    public void INVOKEINTERFACE(Object receiver, String owner, String name, String desc) {
        logInsn(185, owner, name, desc, receiver);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the INVOKEDYNAMIC instruction.
     */
    @Override
    public void INVOKEDYNAMIC(Object instance, String desc) {
        logInsn(186, desc, instance);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the NEW instruction.
     */
    @Override
    public void NEW(String typeName) {
        logInsn(187, typeName);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the NEWARRAY instruction.
     */
    @Override
    public void NEWARRAY(int length, Class<?> componentType, String className, String methodName) {
        logInsn(188, componentType, "[", length);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the ANEWARRAY instruction.
     */
    @Override
    public void ANEWARRAY(int length, String componentTypeName, String className, String typeName) {
        logInsn(189, typeName, "[", length);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the ARRAYLENGTH instruction.
     */
    @Override
    public void ARRAYLENGTH(Object reference) {
        logInsn(190);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the ATHROW instruction.
     */
    @Override
    public void ATHROW(Throwable throwable) {
        logInsn(191);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the CHECKCAST instruction.
     */
    @Override
    public void CHECKCAST(Object reference, String typeName) {
        logInsn(192, typeName);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the INSTANCEOF instruction.
     */
    @Override
    public void INSTANCEOF(Object reference, String typeName) {
        logInsn(193, typeName);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the MONITORENTER instruction.
     */
    @Override
    public void MONITORENTER() {
        logInsn(194);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the MONITOREXIT instruction.
     */
    @Override
    public void MONITOREXIT() {
        logInsn(195);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the WIDE instruction.
     */
    @Override
    public void WIDE() {
        logInsn(196);
    } // NOT VISITED

    /**
     * {@inheritDoc}
     *
     * <p>Logs the MULTIANEWARRAY instruction.
     */
    @Override
    public void MULTIANEWARRAY(String arrayTypeDesc, int nrDimensions, String className, String methodName) {
        logInsn(197, arrayTypeDesc);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IFNULL instruction.
     */
    @Override
    public void IFNULL(String className, String methNane, int branchIndex,
                       Object p) {
        logInsn(198, p);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the IFNONNULL instruction.
     */
    @Override
    public void IFNONNULL(String className, String methNane, int branchIndex,
                          Object p) {
        logInsn(199, p);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the GOTO_W instruction.
     */
    @Override
    public void GOTO_W() {
        logInsn(200);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs the JSR_W instruction.
     */
    @Override
    public void JSR_W() {
        logInsn(201);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Cleans up the instruction logger.
     */
    @Override
    public void cleanUp() {
        instructionLogger.cleanUp();
    }

    /**
     * Logs an instruction with no parameters.
     *
     * @param opcode the bytecode opcode
     */
    protected void logInsn(int opcode) {
        instructionLogger.logln(ConcolicConfig.BYTECODE_NAME[opcode]);
        // logLoopIterations();
    }

    /**
     * Logs an instruction with one integer parameter.
     *
     * @param opcode the bytecode opcode
     * @param p the integer parameter
     */
    protected void logInsn(int opcode, int p) {
        logInsn(opcode, Integer.valueOf(p));
    }

    /**
     * Logs an instruction with one object parameter.
     *
     * @param opcode the bytecode opcode
     * @param p the object parameter
     */
    protected void logInsn(int opcode, Object p) {
        instructionLogger.log(ConcolicConfig.BYTECODE_NAME[opcode], " ");
        if (p != null) {
            instructionLogger.logln(" ", p.toString());
        } else {
            instructionLogger.logln(" ", "null");
        }
        // instructionLogger.log(p.toString());
        instructionLogger.logln();
        // logLoopIterations();
    }

    /**
     * Logs an instruction with two object parameters.
     *
     * @param opcode the bytecode opcode
     * @param p1 the first object parameter
     * @param p2 the second object parameter
     */
    protected void logInsn(int opcode, Object p1, Object p2) {
        instructionLogger.log(ConcolicConfig.BYTECODE_NAME[opcode], " ");
        if (p1 != null) {
            instructionLogger.logln(p1.toString(), " ");
        } else {
            instructionLogger.logln("null", " ");
        }

        if (p2 != null) {
            instructionLogger.logln(p2.toString());
        } else {
            instructionLogger.logln("null");
        }

        // instructionLogger.log(p1.toString(), " ");
        // instructionLogger.log(p2.toString());
        instructionLogger.logln();
        // logLoopIterations();
    }

    /**
     * Logs an instruction with three object parameters.
     *
     * @param opcode the bytecode opcode
     * @param p1 the first object parameter
     * @param p2 the second object parameter
     * @param p3 the third object parameter
     */
    protected void logInsn(int opcode, Object p1, Object p2, Object p3) {
        instructionLogger.log(ConcolicConfig.BYTECODE_NAME[opcode], " ");
        if (p1 != null) {
            instructionLogger.logln(p1.toString(), " ");
        } else {
            instructionLogger.logln("null", " ");
        }
        if (p2 != null) {
            instructionLogger.logln(p2.toString(), " ");
        } else {
            instructionLogger.logln("null", " ");
        }
        if (p3 != null) {
            instructionLogger.logln(p3.toString());
        } else {
            instructionLogger.logln("null");
        }
        instructionLogger.logln();
        // logLoopIterations();
    }

    /**
     * Logs an instruction with four object parameters.
     *
     * @param opcode the bytecode opcode
     * @param p1 the first object parameter
     * @param p2 the second object parameter
     * @param p3 the third object parameter
     * @param p4 the fourth object parameter
     */
    protected void logInsn(int opcode, Object p1, Object p2, Object p3, Object p4) {
        instructionLogger.log(ConcolicConfig.BYTECODE_NAME[opcode], " ");
        if (p1 != null) {
            instructionLogger.logln(p1.toString(), " ");
        } else {
            instructionLogger.logln("null", " ");
        }
        if (p2 != null) {
            instructionLogger.logln(p2.toString(), " ");
        } else {
            instructionLogger.logln("null", " ");
        }
        if (p3 != null) {
            instructionLogger.logln(p3.toString(), " ");
        } else {
            instructionLogger.logln("null", " ");
        }
        if (p4 != null) {
            instructionLogger.logln(p4.toString());
        } else {
            instructionLogger.logln("null");
        }
        instructionLogger.logln();
        // logLoopIterations();
    }

    /**
     * Logs an instruction with two integer parameters.
     *
     * @param opcode the bytecode opcode
     * @param p1 the first integer parameter
     * @param p2 the second integer parameter
     */
    protected void logInsn(int opcode, int p1, int p2) {
        logInsn(opcode, Integer.valueOf(p1), Integer.valueOf(p2));
    }

    // TODO (ilebrero): we need to add loop counting first
    // protected void logLoopIterations() {
    //     if (!conf.ENABLE_STATIC_ANALYSIS || !conf.LOG_LOOP_ITERATION_COUNTS)
    //         return;
    //
    //     final TObjectIntHashMap<Loop> counts = topFrame().iterationCounts;
    //     for (Loop loop : counts.keys(new Loop[counts.size()])) {
    //         instructionLogger.logln("\t" + counts.get(loop) + " " + loop.getHead());
    //         for (Stmt stmt : loop.getLoopStatements())
    //             instructionLogger.logln("\t\t" + stmt);
    //     }
    // }
}
