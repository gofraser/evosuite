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

package org.evosuite.instrumentation;

import org.evosuite.Properties;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;


/**
 * <p>TransformationStatistics class.</p>
 * <p>
 * This class uses ThreadLocal to ensure that statistics are tracked independently
 * for each thread (e.g., when running multiple instrumentations in parallel).
 * </p>
 *
 * @author Gordon Fraser
 */
public class TransformationStatistics {

    private static final Logger logger = LoggerFactory.getLogger(TransformationStatistics.class);

    private static final ThreadLocal<Stats> stats = ThreadLocal.withInitial(Stats::new);

    private static class Stats {
        int transformedBooleanComparison = 0;
        int insertedGet = 0;
        int insertedPushInt0 = 0;
        int insertedPushInt1 = 0;
        int insertedPushIntRef = 0;
        int insertedPushIntNull = 0;
        int transformedComparison = 0;
        int transformedImplicitElse = 0;
        int transformedInstanceOf = 0;
        int transformedBooleanReturn = 0;
        int transformedBooleanParameter = 0;
        int transformedBooleanField = 0;
        int transformedBackToBooleanParameter = 0;
        int transformedBackToBooleanField = 0;
        int untransformableMethod = 0;
        int transformedStringComparison = 0;
        int transformedContainerComparison = 0;
        int transformedBitwise = 0;
    }

    /**
     * <p>reset.</p>
     */
    public static void reset() {
        stats.remove();
    }

    /**
     * IFEQ -&gt; IFLE / IFNE -&gt; IFGT.
     */
    public static void transformedBooleanComparison() {
        stats.get().transformedBooleanComparison++;
    }

    /**
     * Insertion of getPredicate.
     */
    public static void insertedGet() {
        stats.get().insertedGet++;
    }

    /**
     * Insertion of pushDistance.
     *
     * @param opcode a int.
     */
    public static void insertPush(int opcode) {
        switch (opcode) {
            case Opcodes.IFEQ:
            case Opcodes.IFNE:
            case Opcodes.IFLT:
            case Opcodes.IFGE:
            case Opcodes.IFGT:
            case Opcodes.IFLE:
                stats.get().insertedPushInt0++;
                break;
            case Opcodes.IF_ICMPEQ:
            case Opcodes.IF_ICMPNE:
            case Opcodes.IF_ICMPLT:
            case Opcodes.IF_ICMPGE:
            case Opcodes.IF_ICMPGT:
            case Opcodes.IF_ICMPLE:
                stats.get().insertedPushInt1++;
                break;
            case Opcodes.IFNULL:
            case Opcodes.IFNONNULL:
                stats.get().insertedPushIntRef++;
                break;
            case Opcodes.IF_ACMPEQ:
            case Opcodes.IF_ACMPNE:
                stats.get().insertedPushIntNull++;
                break;
            default:
                // GOTO, JSR: Do nothing
        }
    }

    /**
     * LCMPL, DCMPL, FCMPL.
     */
    public static void transformedComparison() {
        stats.get().transformedComparison++;
    }

    /**
     * Added implicit else.
     */
    public static void transformedImplicitElse() {
        stats.get().transformedImplicitElse++;
    }

    /**
     * InstanceOf.
     */
    public static void transformInstanceOf() {
        stats.get().transformedInstanceOf++;
    }

    /**
     * Return value was boolean.
     */
    public static void transformBooleanReturnValue() {
        stats.get().transformedBooleanReturn++;
    }

    /**
     * Parameter value was boolean.
     */
    public static void transformBooleanParameter() {
        stats.get().transformedBooleanParameter++;
    }

    /**
     * Field was boolean.
     */
    public static void transformBooleanField() {
        stats.get().transformedBooleanField++;
    }

    /**
     * Parameter value was boolean.
     */
    public static void transformBackToBooleanParameter() {
        stats.get().transformedBackToBooleanParameter++;
    }

    /**
     * Field was boolean.
     */
    public static void transformBackToBooleanField() {
        stats.get().transformedBackToBooleanField++;
    }

    /**
     * Method contains boolean in signature, but can't be transformed.
     */
    public static void foundUntransformableMethod() {
        stats.get().untransformableMethod++;
    }

    /**
     * String.equals or similar.
     */
    public static void transformedStringComparison() {
        stats.get().transformedStringComparison++;
    }

    /**
     * Container.isEmpty or similar.
     */
    public static void transformedContainerComparison() {
        stats.get().transformedContainerComparison++;
    }

    /**
     * Bitwise AND, OR, XOR.
     */
    public static void transformedBitwise() {
        stats.get().transformedBitwise++;
    }

    /**
     * <p>writeStatistics.</p>
     *
     * @param className a {@link java.lang.String} object.
     */
    public static void writeStatistics(String className) {
        String filename = Properties.REPORT_DIR + "/transformation.csv";
        File logfile = new File(filename);
        boolean needHeader = !logfile.exists();

        try (BufferedWriter out = new BufferedWriter(new FileWriter(logfile, true))) {
            if (needHeader) {
                out.write("ClassName,BooleanComparison,Get,Push0,Push1,PushRef,PushNull,Comparison,ImplicitElse,"
                        + "InstanceOf,BooleanReturn,BooleanParameter,BooleanField,BackToBooleanParameter,"
                        + "BackToBooleanField,UntransformableMethod,StringComparison,ContainerComparison,Bitwise\n");
            }

            Stats s = stats.get();

            out.write(className);
            out.write(",");
            out.write(s.transformedBooleanComparison + ",");
            out.write(s.insertedGet + ",");
            out.write(s.insertedPushInt0 + ",");
            out.write(s.insertedPushInt1 + ",");
            out.write(s.insertedPushIntRef + ",");
            out.write(s.insertedPushIntNull + ",");
            out.write(s.transformedComparison + ",");
            out.write(s.transformedImplicitElse + ",");
            out.write(s.transformedInstanceOf + ",");
            out.write(s.transformedBooleanReturn + ",");
            out.write(s.transformedBooleanParameter + ",");
            out.write(s.transformedBooleanField + ",");
            out.write(s.transformedBackToBooleanParameter + ",");
            out.write(s.transformedBackToBooleanField + ",");
            out.write(s.untransformableMethod + ",");
            out.write(s.transformedStringComparison + ",");
            out.write(s.transformedContainerComparison + ",");
            out.write(s.transformedBitwise + "");
            out.write("\n");
        } catch (IOException e) {
            logger.info("Exception while writing CSV data: " + e);
        }
    }
}
