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

import org.objectweb.asm.Type;

import java.io.Reader;
import java.io.StringReader;
import java.util.StringTokenizer;

import static org.objectweb.asm.Type.*;

/**
 * Constants for Types related to java.lang.String.
 *
 * @author galeotti
 */
public interface Types {

    /** CharSequence type. */
    Type CHARSEQ_TYPE = Type.getType(CharSequence.class);

    /** Object type. */
    Type OBJECT_TYPE = Type.getType(Object.class);

    /** String type. */
    Type STRING_TYPE = Type.getType(String.class);

    /** StringBuilder type. */
    Type STRING_BUILDER_TYPE = Type
            .getType(StringBuilder.class);

    /** ()I descriptor. */
    String TO_INT_DESCRIPTOR = getMethodDescriptor(INT_TYPE);

    /** ()Ljava/lang/String; descriptor. */
    String TO_STR_DESCRIPTOR = getMethodDescriptor(STRING_TYPE);

    /** (Ljava/lang/String;)I descriptor. */
    String STR_TO_INT_DESCRIPTOR = getMethodDescriptor(
            INT_TYPE, STRING_TYPE);

    /** (I)I descriptor. */
    String INT_TO_INT_DESCRIPTOR = getMethodDescriptor(
            INT_TYPE, INT_TYPE);

    /** (I)C descriptor. */
    String INT_TO_CHAR_DESCRIPTOR = getMethodDescriptor(
            CHAR_TYPE, INT_TYPE);

    /** (Ljava/lang/String;)Ljava/lang/String; descriptor. */
    String STR_TO_STR_DESCRIPTOR = getMethodDescriptor(
            STRING_TYPE, STRING_TYPE);

    /** (CC)Ljava/lang/String; descriptor. */
    String CHAR_CHAR_TO_STR_DESCRIPTOR = getMethodDescriptor(
            STRING_TYPE, CHAR_TYPE, CHAR_TYPE);

    /** (II)Ljava/lang/String; descriptor. */
    String INT_INT_TO_STR_DESCRIPTOR = getMethodDescriptor(
            STRING_TYPE, INT_TYPE, INT_TYPE);

    /** (I)Ljava/lang/String; descriptor. */
    String INT_TO_STR_DESCRIPTOR = getMethodDescriptor(
            STRING_TYPE, INT_TYPE);

    /** (J)Ljava/lang/String; descriptor. */
    String LONG_TO_STR_DESCRIPTOR = getMethodDescriptor(
            STRING_TYPE, LONG_TYPE);

    /** (C)Ljava/lang/String; descriptor. */
    String CHAR_TO_STR_DESCRIPTOR = getMethodDescriptor(
            STRING_TYPE, CHAR_TYPE);

    /** (Z)Ljava/lang/String; descriptor. */
    String BOOLEAN_TO_STR_DESCRIPTOR = getMethodDescriptor(
            STRING_TYPE, BOOLEAN_TYPE);

    /** (II)I descriptor. */
    String INT_INT_TO_INT_DESCRIPTOR = getMethodDescriptor(
            INT_TYPE, INT_TYPE, INT_TYPE);

    /** (Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String; descriptor. */
    String STR_STR_TO_STR_DESCRIPTOR = getMethodDescriptor(
            STRING_TYPE, STRING_TYPE, STRING_TYPE);

    /** (Ljava/lang/String;I)I descriptor. */
    String STR_INT_TO_INT_DESCRIPTOR = getMethodDescriptor(
            INT_TYPE, STRING_TYPE, INT_TYPE);

    /** (Ljava/lang/Object;)Z descriptor. */
    String OBJECT_TO_BOOL_DESCRIPTOR = getMethodDescriptor(
            BOOLEAN_TYPE, OBJECT_TYPE);

    /** (Ljava/lang/Object;)Ljava/lang/String; descriptor. */
    String OBJECT_TO_STR_DESCRIPTOR = getMethodDescriptor(
            STRING_TYPE, OBJECT_TYPE);

    /** (Ljava/lang/String;)Z descriptor. */
    String STR_TO_BOOL_DESCRIPTOR = getMethodDescriptor(
            BOOLEAN_TYPE, STRING_TYPE);

    /** (Ljava/lang/String;I)Z descriptor. */
    String STR_INT_TO_BOOL_DESCRIPTOR = getMethodDescriptor(
            BOOLEAN_TYPE, STRING_TYPE, INT_TYPE);

    /** (ZILjava/lang/String;II)Z descriptor. */
    String BOOL_INT_STR_INT_INT_TO_BOOL_DESCRIPTOR = getMethodDescriptor(
            BOOLEAN_TYPE, Type.BOOLEAN_TYPE, INT_TYPE, STRING_TYPE, INT_TYPE,
            INT_TYPE);

    /** (Ljava/lang/CharSequence;)Z descriptor. */
    String CHARSEQ_TO_BOOL_DESCRIPTOR = getMethodDescriptor(
            BOOLEAN_TYPE, CHARSEQ_TYPE);

    /** (Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String; descriptor. */
    String CHARSEQ_CHARSEQ_TO_STR_DESCRIPTOR = getMethodDescriptor(
            STRING_TYPE, CHARSEQ_TYPE, CHARSEQ_TYPE);

    /** (Ljava/lang/String;)V descriptor. */
    String STR_TO_VOID_DESCRIPTOR = getMethodDescriptor(
            VOID_TYPE, STRING_TYPE);

    /** (Ljava/lang/String;)Ljava/lang/StringBuilder; descriptor. */
    String STR_TO_STRBUILDER_DESCRIPTOR = getMethodDescriptor(
            STRING_BUILDER_TYPE, STRING_TYPE);

    /** (C)Ljava/lang/StringBuilder; descriptor. */
    String CHAR_TO_STRBUILDER_DESCRIPTOR = getMethodDescriptor(
            STRING_BUILDER_TYPE, CHAR_TYPE);

    /** (Ljava/lang/CharSequence;)V descriptor. */
    String CHARSEQ_TO_VOID_DESCRIPTOR = getMethodDescriptor(
            VOID_TYPE, CHARSEQ_TYPE);

    /** (I)Ljava/lang/StringBuilder; descriptor. */
    String INT_TO_STRBUILDER_DESCRIPTOR = getMethodDescriptor(
            STRING_BUILDER_TYPE, INT_TYPE);

    /** (J)Ljava/lang/StringBuilder; descriptor. */
    String LONG_TO_STRBUILDER_DESCRIPTOR = getMethodDescriptor(
            STRING_BUILDER_TYPE, LONG_TYPE);

    /** (Z)Ljava/lang/StringBuilder; descriptor. */
    String BOOLEAN_TO_STRBUILDER_DESCRIPTOR = getMethodDescriptor(
            STRING_BUILDER_TYPE, BOOLEAN_TYPE);

    /** (F)Ljava/lang/StringBuilder; descriptor. */
    String FLOAT_TO_STRBUILDER_DESCRIPTOR = getMethodDescriptor(
            STRING_BUILDER_TYPE, FLOAT_TYPE);

    /** (D)Ljava/lang/StringBuilder; descriptor. */
    String DOUBLE_TO_STRBUILDER_DESCRIPTOR = getMethodDescriptor(
            STRING_BUILDER_TYPE, DOUBLE_TYPE);

    /** (Ljava/lang/Object;)Ljava/lang/StringBuilder; descriptor. */
    String OBJECT_TO_STRBUILDER_DESCRIPTOR = getMethodDescriptor(
            STRING_BUILDER_TYPE, OBJECT_TYPE);

    /** java/lang/String name. */
    String JAVA_LANG_STRING = String.class.getName().replace(".",
            "/");

    /** java/lang/StringBuilder name. */
    String JAVA_LANG_STRING_BUILDER = StringBuilder.class
            .getName().replace('.', '/');

    /** java/util/StringTokenizer name. */
    String JAVA_UTIL_STRING_TOKENIZER = StringTokenizer.class
            .getName().replace('.', '/');

    /** (Ljava/lang/String;Ljava/lang/String;)V descriptor. */
    String STR_STR_TO_VOID_DESCRIPTOR = getMethodDescriptor(
            VOID_TYPE, STRING_TYPE, STRING_TYPE);

    /** ()Z descriptor. */
    String TO_BOOLEAN_DESCRIPTOR = getMethodDescriptor(BOOLEAN_TYPE);

    /** (ILjava/lang/String;II)Z descriptor. */
    String INT_STR_INT_INT_TO_BOOL_DESCRIPTOR = getMethodDescriptor(
            BOOLEAN_TYPE, INT_TYPE, STRING_TYPE, INT_TYPE, INT_TYPE);

    /** java/io/StringReader name. */
    String JAVA_IO_STRING_READER = StringReader.class.getName()
            .replace('.', '/');

    /** java/io/Reader name. */
    String JAVA_IO_READER = Reader.class.getName().replace(
            ".", "/");

}
