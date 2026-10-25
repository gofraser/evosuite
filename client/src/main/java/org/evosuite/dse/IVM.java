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
package org.evosuite.dse;

/**
 * VM ByteCode instruction listener. Every callback has an empty default implementation, so a
 * listener only overrides the instructions it observes.
 *
 * <p>This class is taken and adapted from the DSC tool developed by Christoph Csallner.
 *
 * <p>Link at : http://ranger.uta.edu/~csallner/dsc/index.html
 *
 * @author csallner@uta.edu (Christoph Csallner)
 */
@SuppressWarnings({"checkstyle:MethodName", "checkstyle:AbbreviationAsWordInName"})
public interface IVM {

    /**
     * Pass a caller's nr-th concrete method argument value (which is sitting on
     * the caller's operand stack). There will be no such call for a potentially
     * present receiver instance ("this" parameter).
     *
     * <p>There is no CALLER_STACK_RECEIVER, as the receiver is already passed by
     * the corresponding INVOKE.
     *
     * @param nr                index of the parameter, not counting the receiver and ignoring
     *                          the additional width of category-2 parameters. E.g., in
     *                          foo(double a, int b) parameter b would always have nr 1,
     *                          regardless of foo being an instance method or not.
     * @param calleeLocalsIndex is the index in which this value is stored in the receiver
     *                          frame. In contrast to nr, this accounts for the receiver and
     *                          the different widths of category-1 and category-2 parameter
     *                          types.
     * @param value             the parameter value.
     */
    default void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, int value) {
    }

    default void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, boolean value) {
    }

    default void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, short value) {
    }

    default void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, byte value) {
    }

    default void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, char value) {
    }

    default void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, long value) {
    }

    default void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, float value) {
    }

    default void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, double value) {
    }

    default void CALLER_STACK_PARAM(int nr, int calleeLocalsIndex, Object value) {
    }

    /**
     * Line number in the Java source code.
     *
     * @param lineNr the line number.
     */
    default void SRC_LINE_NUMBER(int lineNr) {
    }

    /**
     * Start of a new method.
     *
     * @param access    the access flags of the method.
     * @param className the name of the class.
     * @param methName  the name of the method.
     * @param methDesc  the descriptor of the method.
     */
    default void METHOD_BEGIN(int access, String className, String methName,
                      String methDesc) {
    }

    /**
     * Max values of a method.
     *
     * @param className the name of the class.
     * @param methName  the name of the method.
     * @param methDesc  the descriptor of the method.
     * @param maxStack  the maximum stack size.
     * @param maxLocals the maximum number of local variables.
     */
    default void METHOD_MAXS(String className, String methName, String methDesc,
                     int maxStack, int maxLocals) {
    }

    /**
     * Pass index-th concrete parameter of the just called method. There will be
     * no such call for a potentially present receiver instance ("this"
     * parameter).
     *
     * @param nr                index of the parameter, not counting the receiver and ignoring
     *                          the additional width of category-2 parameters. E.g., in
     *                          foo(double a, int b) parameter b would always have nr 1,
     *                          regardless of foo being an instance method or not.
     * @param calleeLocalsIndex is the index in which this value is stored in the receiver
     *                          frame. In contrast to nr, this accounts for the receiver and
     *                          the different widths of category-1 and category-2 parameter
     *                          types.
     * @param value             the parameter value.
     */
    default void METHOD_BEGIN_PARAM(int nr, int calleeLocalsIndex, int value) {
    }

    default void METHOD_BEGIN_PARAM(int nr, int calleeLocalsIndex, boolean value) {
    }

    default void METHOD_BEGIN_PARAM(int nr, int calleeLocalsIndex, short value) {
    }

    default void METHOD_BEGIN_PARAM(int nr, int calleeLocalsIndex, byte value) {
    }

    default void METHOD_BEGIN_PARAM(int nr, int calleeLocalsIndex, char value) {
    }

    default void METHOD_BEGIN_PARAM(int nr, int calleeLocalsIndex, long value) {
    }

    default void METHOD_BEGIN_PARAM(int nr, int calleeLocalsIndex, float value) {
    }

    default void METHOD_BEGIN_PARAM(int nr, int calleeLocalsIndex, double value) {
    }

    default void METHOD_BEGIN_PARAM(int nr, int calleeLocalsIndex, Object value) {
    }

    /**
     * METHOD_BEGIN_PARAM for the receiver instance ("this"), if this method is
     * a non-constructor instance method.
     *
     * @param value the receiver instance.
     */
    default void METHOD_BEGIN_RECEIVER(Object value) {
    }

    /**
     * Value returned by the just completed method call.
     *
     * @param owner the owner of the method.
     * @param name  the name of the method.
     * @param desc  the descriptor of the method.
     */
    default void CALL_RESULT(String owner, String name, String desc) {
    }

    default void CALL_RESULT(boolean res, String owner, String name, String desc) {
    }

    default void CALL_RESULT(int res, String owner, String name, String desc) {
    }

    default void CALL_RESULT(long res, String owner, String name, String desc) {
    }

    default void CALL_RESULT(double res, String owner, String name, String desc) {
    }

    default void CALL_RESULT(float res, String owner, String name, String desc) {
    }

    default void CALL_RESULT(Object res, String owner, String name, String desc) {
    }

    /**
     * Start of a new basic block.
     */
    default void BB_BEGIN() {
    }

    /**
     * Start of a handler.
     *
     * @param access    the access flags of the method.
     * @param className the name of the class.
     * @param methName  the name of the method.
     * @param methDesc  the descriptor of the method.
     */
    default void HANDLER_BEGIN(int access, String className, String methName,
                       String methDesc) {
    }

    /*
     * Some 200 JVM ByteCode instructions
     */

    default void NOP() {
    }

    default void ACONST_NULL() {
    }

    default void ICONST_M1() {
    }

    default void ICONST_0() {
    }

    default void ICONST_1() {
    }

    default void ICONST_2() {
    }

    default void ICONST_3() {
    }

    default void ICONST_4() {
    }

    default void ICONST_5() {
    }

    default void LCONST_0() {
    }

    default void LCONST_1() {
    }

    default void FCONST_0() {
    }

    default void FCONST_1() {
    }

    default void FCONST_2() {
    }

    default void DCONST_0() {
    }

    default void DCONST_1() {
    }

    default void BIPUSH(int value) {
    }

    default void SIPUSH(int value) {
    }

    /**
     * LDC instruction.
     *
     * <p>See http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.doc8.html#ldc
     *
     * @param x the constant value.
     */
    default void LDC(String x) {
    }

    default void LDC(Class<?> x) {
    }

    default void LDC(int x) {
    }

    default void LDC(float x) {
    }

    default void LDC2_W(long x) {
    }

    default void LDC2_W(double x) {
    }

    /**
     * ILOAD instruction.
     *
     * <p>See http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.doc6.html#iload
     *
     * @param i the local variable index.
     */
    default void ILOAD(int i) {
    }

    default void LLOAD(int i) {
    }

    default void FLOAD(int i) {
    }

    default void DLOAD(int i) {
    }

    default void ALOAD(int i) {
    }

    /**
     * IALOAD instruction.
     *
     * <p>See http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.doc6.html#iaload
     *
     * @param receiver   the array reference.
     * @param index      the array index.
     * @param className  the name of the class.
     * @param methodName the name of the method.
     */
    default void IALOAD(Object receiver, int index, String className, String methodName) {
    }

    default void LALOAD(Object receiver, int index, String className, String methodName) {
    }

    default void FALOAD(Object receiver, int index, String className, String methodName) {
    }

    default void DALOAD(Object receiver, int index, String className, String methodName) {
    }

    default void AALOAD(Object receiver, int index, String className, String methodName) {
    }

    default void BALOAD(Object receiver, int index, String className, String methodName) {
    }

    default void CALOAD(Object receiver, int index, String className, String methodName) {
    }

    default void SALOAD(Object receiver, int index, String className, String methodName) {
    }

    /**
     * ISTORE instruction.
     *
     * <p>See http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.doc6.html#istore
     *
     * @param i the local variable index.
     */
    default void ISTORE(int i) {
    }

    default void LSTORE(int i) {
    }

    default void FSTORE(int i) {
    }

    default void DSTORE(int i) {
    }

    default void ASTORE(int i) {
    }

    default void IASTORE(Object receiver, int index, String className, String methodName) {
    }

    default void LASTORE(Object receiver, int index, String className, String methodName) {
    }

    default void FASTORE(Object receiver, int index, String className, String methodName) {
    }

    default void DASTORE(Object receiver, int index, String className, String methodName) {
    }

    default void BASTORE(Object receiver, int index, String className, String methodName) {
    }

    default void CASTORE(Object receiver, int index, String className, String methodName) {
    }

    default void SASTORE(Object receiver, int index, String className, String methodName) {
    }

    default void AASTORE(Object receiver, int index, Object value, String className, String methodName) {
    }

    default void POP() {
    }

    default void POP2() {
    }

    default void DUP() {
    }

    default void DUP_X1() {
    }

    default void DUP_X2() {
    }

    default void DUP2() {
    }

    default void DUP2_X1() {
    }

    default void DUP2_X2() {
    }

    default void SWAP() {
    }

    /**
     * IADD instruction.
     *
     * <p>See http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.doc6.html#iadd
     */
    default void IADD() {
    }

    default void LADD() {
    }

    default void FADD() {
    }

    default void DADD() {
    }

    default void ISUB() {
    }

    default void LSUB() {
    }

    default void FSUB() {
    }

    default void DSUB() {
    }

    default void IMUL() {
    }

    default void LMUL() {
    }

    default void FMUL() {
    }

    default void DMUL() {
    }

    default void IDIV(int rhs) {
    }

    default void LDIV(long rhs) {
    }

    default void FDIV(float rhs) {
    }

    default void DDIV(double rhs) {
    }

    default void IREM(int rhs) {
    }

    default void LREM(long rhs) {
    }

    default void FREM(float rhs) {
    }

    default void DREM(double rhs) {
    }

    default void INEG() {
    }

    default void LNEG() {
    }

    default void FNEG() {
    }

    default void DNEG() {
    }

    default void ISHL() {
    }

    default void LSHL() {
    }

    default void ISHR() {
    }

    default void LSHR() {
    }

    default void IUSHR() {
    }

    default void LUSHR() {
    }

    default void IAND() {
    }

    default void LAND() {
    }

    default void IOR() {
    }

    default void LOR() {
    }

    default void IXOR() {
    }

    default void LXOR() {
    }

    default void IINC(int i, int value) {
    }

    default void I2L() {
    }

    default void I2F() {
    }

    default void I2D() {
    }

    default void L2I() {
    }

    default void L2F() {
    }

    default void L2D() {
    }

    default void F2I() {
    }

    default void F2L() {
    }

    default void F2D() {
    }

    default void D2I() {
    }

    default void D2L() {
    }

    default void D2F() {
    }

    default void I2B() {
    }

    default void I2C() {
    }

    default void I2S() {
    }

    default void LCMP() {
    }

    default void FCMPL() {
    }

    default void FCMPG() {
    }

    default void DCMPL() {
    }

    default void DCMPG() {
    }

    default void IFEQ(String className, String methNane, int branchIndex,
              int param) {
    }

    default void IFNE(String className, String methNane, int branchIndex,
              int param) {
    }

    default void IFLT(String className, String methNane, int branchIndex,
              int param) {
    }

    default void IFGE(String className, String methNane, int branchIndex,
              int param) {
    }

    default void IFGT(String className, String methNane, int branchIndex,
              int param) {
    }

    default void IFLE(String className, String methNane, int branchIndex,
              int param) {
    }

    default void IF_ICMPEQ(String className, String methNane, int branchIndex,
                   int left, int right) {
    }

    default void IF_ICMPNE(String className, String methNane, int branchIndex,
                   int left, int right) {
    }

    default void IF_ICMPLT(String className, String methNane, int branchIndex,
                   int left, int right) {
    }

    default void IF_ICMPGE(String className, String methNane, int branchIndex,
                   int left, int right) {
    }

    default void IF_ICMPGT(String className, String methNane, int branchIndex,
                   int left, int right) {
    }

    default void IF_ICMPLE(String className, String methNane, int branchIndex,
                   int left, int right) {
    }

    default void IF_ACMPEQ(String className, String methNane, int branchIndex,
                   Object left, Object right) {
    }

    default void IF_ACMPNE(String className, String methNane, int branchIndex,
                   Object left, Object right) {
    }

    default void GOTO() {
    }

    default void JSR() {
    }

    default void RET() {
    }

    default void TABLESWITCH(String className, String methName, int branchIndex,
                     int target, int min, int max) {
    }

    default void LOOKUPSWITCH(String className, String methName,
                      int branchIndex, int target, int[] goals) {
    }

    default void IRETURN() {
    }

    default void LRETURN() {
    }

    default void FRETURN() {
    }

    default void DRETURN() {
    }

    default void ARETURN() {
    }

    default void RETURN() {
    }

    default void GETSTATIC(String owner, String name, String desc) {
    }

    default void PUTSTATIC(String owner, String name, String desc) {
    }

    default void GETFIELD(Object receiver, String owner, String name, String desc) {
    }

    default void PUTFIELD(Object receiver, String owner, String name, String desc) {
    }

    default void INVOKESTATIC(String owner, String name, String desc) {
    }

    default void INVOKESPECIAL(String owner, String name, String desc) {
    }

    default void INVOKESPECIAL(Object receiver, String owner, String name,
                       String desc) {
    }

    default void INVOKEVIRTUAL(Object receiver, String owner, String name,
                       String desc) {
    }

    default void INVOKEINTERFACE(Object receiver, String owner, String name,
                         String desc) {
    }

    default void INVOKEDYNAMIC(Object clazz, String owner) {
    }

    default void INVOKEDYNAMIC(String concatenationResult, String stringOwnerClass, String stringRecipe) {
    }

    default void UNUSED() {
    }

    default void NEW(String typeName) {
    }

    default void NEWARRAY(int length, Class<?> componentType, String className, String methodName) {
    }

    default void ANEWARRAY(int length, String componentTypeName, String className, String typeName) {
    }

    default void ARRAYLENGTH(Object reference) {
    }

    default void ATHROW(Throwable object) {
    }

    default void CHECKCAST(Object reference, String typeName) {
    }

    default void INSTANCEOF(Object reference, String typeName) {
    }

    default void MONITORENTER() {
    }

    default void MONITOREXIT() {
    }

    default void MULTIANEWARRAY(String arrayTypeDesc, int nrDimensions, String className, String methodName) {
    }

    default void IFNULL(String className, String methNane, int branchIndex,
                Object param) {
    }

    default void IFNONNULL(String className, String methNane, int branchIndex,
                   Object param) {
    }

    default void cleanUp() {
    }
}
        
