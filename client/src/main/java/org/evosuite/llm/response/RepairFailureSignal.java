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
package org.evosuite.llm.response;

/**
 * Structured signal extracted from repair failures. Signals are SUT-agnostic
 * and drive generic hint selection.
 */
public final class RepairFailureSignal {

    public enum Type {
        EXCEPTION_TYPE,
        NULL_RECEIVER_NPE,
        CLASS_CAST,
        STATIC_INIT_FAILURE,
        STATIC_INIT_AFTERSHOCK
    }

    private final Type type;
    private final String key;
    private final String summary;
    private final String exceptionType;
    private final String message;
    private final String detailA;
    private final String detailB;

    private RepairFailureSignal(Type type,
                                String key,
                                String summary,
                                String exceptionType,
                                String message,
                                String detailA,
                                String detailB) {
        this.type = type;
        this.key = key;
        this.summary = summary;
        this.exceptionType = exceptionType;
        this.message = message;
        this.detailA = detailA;
        this.detailB = detailB;
    }

    public static RepairFailureSignal exceptionType(String exceptionType, String message) {
        String safeType = safe(exceptionType);
        String safeMessage = safe(message);
        String key = "exception:" + safeType;
        String summary = safeMessage.isEmpty() ? safeType : safeType + ": " + safeMessage;
        return new RepairFailureSignal(Type.EXCEPTION_TYPE, key, summary, safeType, safeMessage, null, null);
    }

    public static RepairFailureSignal nullReceiverNpe(String memberSignature, String nullVariable) {
        String member = safe(memberSignature);
        String receiver = safe(nullVariable);
        String key = "npe:" + member + ":" + receiver;
        String summary = "Null receiver '" + receiver + "' in call '" + member + "'";
        return new RepairFailureSignal(Type.NULL_RECEIVER_NPE, key, summary,
                "NullPointerException", null, member, receiver);
    }

    public static RepairFailureSignal classCast(String sourceType, String targetType) {
        String src = safe(sourceType);
        String dst = safe(targetType);
        String key = "classcast:" + src + "->" + dst;
        String summary = "Class cast mismatch: " + src + " -> " + dst;
        return new RepairFailureSignal(Type.CLASS_CAST, key, summary, "ClassCastException", null, src, dst);
    }

    public static RepairFailureSignal staticInitFailure(String className) {
        String poisoned = safe(className);
        String key = "clinit:" + poisoned;
        String summary = "Static initialization failed for " + poisoned;
        return new RepairFailureSignal(Type.STATIC_INIT_FAILURE, key, summary,
                "ExceptionInInitializerError", null, poisoned, null);
    }

    public static RepairFailureSignal staticInitAftershock(String className) {
        String poisoned = safe(className);
        String key = "clinit-aftershock:" + poisoned;
        String summary = "Class remains unusable after failed static init: " + poisoned;
        return new RepairFailureSignal(Type.STATIC_INIT_AFTERSHOCK, key, summary,
                "NoClassDefFoundError", null, poisoned, null);
    }

    public Type getType() {
        return type;
    }

    public String getKey() {
        return key;
    }

    public String getSummary() {
        return summary;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public String getMessage() {
        return message;
    }

    public String getDetailA() {
        return detailA;
    }

    public String getDetailB() {
        return detailB;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
