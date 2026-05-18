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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses javac compile diagnostics to extract structured information about
 * access-violation errors (calls to private/protected members that are not
 * accessible from test code).
 *
 * <p>This is a parse-only utility — it does not modify source code. The
 * extracted {@link AccessViolation} records are used by the repair loop to
 * track recurrent violations across retries and to produce member-specific
 * repair guidance for the LLM.</p>
 */
public final class AccessViolationDiagnosticParser {

    private AccessViolationDiagnosticParser() {
        // utility class
    }

    /**
     * A single access-violation diagnostic: a member (method or field) that
     * the test tried to access but is not visible from the test's scope.
     */
    public static final class AccessViolation {
        private final String memberName;
        private final String accessLevel;
        private final String declaringClass;

        public AccessViolation(String memberName, String accessLevel, String declaringClass) {
            this.memberName = memberName;
            this.accessLevel = accessLevel;
            this.declaringClass = declaringClass;
        }

        public String getMemberName() {
            return memberName;
        }

        public String getAccessLevel() {
            return accessLevel;
        }

        public String getDeclaringClass() {
            return declaringClass;
        }

        /**
         * Returns a stable key for tracking recurrence across retries.
         * Format: {@code "memberName:declaringClass"}.
         */
        public String toTrackingKey() {
            return memberName + ":" + declaringClass;
        }

        @Override
        public String toString() {
            return memberName + " has " + accessLevel + " access in " + declaringClass;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AccessViolation that = (AccessViolation) o;
            return memberName.equals(that.memberName)
                    && accessLevel.equals(that.accessLevel)
                    && declaringClass.equals(that.declaringClass);
        }

        @Override
        public int hashCode() {
            int result = memberName.hashCode();
            result = 31 * result + accessLevel.hashCode();
            result = 31 * result + declaringClass.hashCode();
            return result;
        }
    }

    // javac diagnostic: "methodName(ArgTypes) has private access in DeclaringClass"
    // or: "fieldName has protected access in DeclaringClass"
    // The member name may include parameter types in parentheses for methods.
    private static final Pattern ACCESS_IN_PATTERN = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_$]*(?:\\([^)]*\\))?)\\s+has\\s+(private|protected)\\s+access\\s+in\\s+([A-Za-z_][A-Za-z0-9_$.]*)",
            Pattern.CASE_INSENSITIVE);

    // javac diagnostic: "SomeType is not public in package.name; cannot be accessed from outside package"
    private static final Pattern NOT_PUBLIC_IN_PACKAGE_PATTERN = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_$.]*)\\s+is\\s+not\\s+public\\s+in\\s+([A-Za-z_][A-Za-z0-9_$.]*);\\s*cannot\\s+be\\s+accessed\\s+from\\s+outside\\s+package",
            Pattern.CASE_INSENSITIVE);

    /**
     * Parses the given diagnostic text and returns all access violations found.
     *
     * @param diagnosticText the raw javac diagnostic output (may contain
     *                       multiple lines and interleaved non-access errors)
     * @return list of access violations, or empty list if none found
     */
    public static List<AccessViolation> parse(String diagnosticText) {
        if (diagnosticText == null || diagnosticText.isEmpty()) {
            return Collections.emptyList();
        }

        // Use a set to deduplicate identical violations from multi-line diagnostics
        Set<AccessViolation> violations = new LinkedHashSet<>();

        Matcher accessMatcher = ACCESS_IN_PATTERN.matcher(diagnosticText);
        while (accessMatcher.find()) {
            String memberRaw = accessMatcher.group(1).trim();
            String accessLevel = accessMatcher.group(2).trim().toLowerCase();
            String declaringClass = accessMatcher.group(3).trim();

            // Strip parameter list for the member name used in tracking,
            // but keep the full form for display.
            String memberName = stripParameterList(memberRaw);
            violations.add(new AccessViolation(memberName, accessLevel, simpleName(declaringClass)));
        }

        Matcher packageMatcher = NOT_PUBLIC_IN_PACKAGE_PATTERN.matcher(diagnosticText);
        while (packageMatcher.find()) {
            String typeName = packageMatcher.group(1).trim();
            String packageName = packageMatcher.group(2).trim();
            violations.add(new AccessViolation(simpleName(typeName), "package-private", packageName));
        }

        return new ArrayList<>(violations);
    }

    /**
     * Returns true if the diagnostic text contains any access-violation errors.
     */
    public static boolean containsAccessViolation(String diagnosticText) {
        if (diagnosticText == null || diagnosticText.isEmpty()) {
            return false;
        }
        String lower = diagnosticText.toLowerCase();
        return lower.contains("has private access")
                || lower.contains("has protected access")
                || lower.contains("cannot be accessed from outside package");
    }

    /**
     * Extracts tracking keys from the diagnostic text for recurrence detection.
     *
     * @return set of tracking keys in "memberName:declaringClass" format
     */
    public static Set<String> extractTrackingKeys(String diagnosticText) {
        List<AccessViolation> violations = parse(diagnosticText);
        Set<String> keys = new LinkedHashSet<>();
        for (AccessViolation v : violations) {
            keys.add(v.toTrackingKey());
        }
        return keys;
    }

    private static String stripParameterList(String memberRaw) {
        if (memberRaw == null) {
            return "";
        }
        int paren = memberRaw.indexOf('(');
        return paren >= 0 ? memberRaw.substring(0, paren).trim() : memberRaw.trim();
    }

    private static String simpleName(String fqcn) {
        if (fqcn == null || fqcn.isEmpty()) {
            return fqcn;
        }
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }
}
