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
package org.evosuite.testparser;

enum DiagnosticKind {

    NO_METHOD_SCOPE(
            "Cannot resolve method scope",
            "declare the receiver variable before this call, or use an existing static call "
                    + "in ClassName.method(...) form."),
    NO_UNSCOPED_METHOD(
            "Cannot resolve unscoped method call",
            "avoid bare helper calls; either inline the helper logic or call through a declared "
                    + "instance/class method that exists in SUT/JDK."),
    UNKNOWN_ARRAY_VAR(
            "Unknown array variable",
            "declare the array variable before indexing it (including dimensions), e.g. "
                    + "double[][] data = new double[1][1];"),
    VARIABLE_NOT_ARRAY(
            "Variable is not an array",
            "remove [] indexing for this variable or change its declaration to an array type."),
    UNKNOWN_FIELD_SCOPE(
            "Cannot resolve field scope",
            "declare the instance variable before field access, or use ClassName.FIELD for static fields."),
    INACCESSIBLE_MEMBER(
            "Member has private access",
            "do not access private/protected members directly; use public/package-private API "
                    + "(constructors, setters, methods) or assertions on observable behavior."),
    UNRESOLVED_TYPE(
            "Cannot resolve type",
            "do not invent local/helper types (e.g., Target, Input, Helper) in test code; "
                    + "instantiate only real SUT/JDK/dependency types from context, or pass null/Object "
                    + "when the API accepts it."),
    UNRESOLVED_VARIABLE(
            "Unresolved variable",
            "declare the variable earlier in the test and ensure the name matches exactly."),
    UNRESOLVED_CLASS_LITERAL(
            "Cannot resolve class literal",
            "use ExistingType.class where ExistingType is a real SUT/JDK class and imported."),
    NO_MATCHING_METHOD(
            "No matching method",
            "call a real overload with the correct argument count and types; do not invent helper APIs "
                    + "or rely on implicit casts from Object placeholders."),
    NO_MATCHING_CONSTRUCTOR(
            "No matching constructor",
            "instantiate the class with a real constructor signature from the SUT/JDK and provide "
                    + "arguments whose types match that signature."),
    MOCK_PREFIX_TYPE_INFERRED(
            "Resolved inferred mock-prefixed type",
            "declare mocks with the real target type instead of inventing MockX helper names."),
    INCOMPATIBLE_ALIAS_DECLARATION(
            "Incompatible declaration alias",
            "make the declared type match the assigned value, or add an explicit compatible cast only "
                    + "when the runtime type is guaranteed."),
    UNRESOLVED_CAST_TYPE(
            "Cannot resolve cast type",
            "use a real cast type that exists in the SUT/JDK/dependencies, or remove the cast if it "
                    + "is unnecessary."),
    UNSUPPORTED_CONSTRUCT_PRESERVED(
            "Unsupported construct preserved as raw source",
            "rewrite the construct using simpler Java expressions/statements that EvoSuite can model "
                    + "directly."),
    LAMBDA_TARGET_TYPE_REQUIRED(
            "Lambda expression has no functional interface target",
            "assign the lambda to a real functional interface type or pass it directly to a compatible API."),
    EXPRESSION_DEPTH_EXCEEDED(
            "Expression nesting depth exceeded",
            "split the expression into smaller intermediate locals before using it."),
    LEGACY_HELPER_CALL(
            "Legacy helper wrapper used",
            "avoid invented helper wrappers like setField()/invokeX(...); use direct public API or "
                    + "supported reflection helpers instead."),
    PARSE_FAILURE(
            "Failed to parse construct",
            "rewrite the construct into simpler Java that uses resolvable types, members, and literals only."),
    ENUM_CONSTANT_UNRESOLVED(
            "Failed to resolve enum constant",
            "use an existing enum constant declared on the resolved enum type."),
    NON_LITERAL_ARRAY_DIMENSION(
            "Non-literal array dimension",
            "use a literal or parser-resolvable integer expression for array sizes."),
    NON_LITERAL_ARRAY_INDEX(
            "Non-literal array index",
            "use a literal or parser-resolvable integer expression for array indices."),
    INVALID_ASSIGNMENT(
            "Invalid assignment",
            "assign only to declared variables, array elements, or accessible fields with type-compatible values."),
    NO_SUCH_FIELD(
            "No such field",
            "use a real field name on the resolved receiver/class, or remove the field access."),
    NON_STATIC_FIELD_REQUIRES_INSTANCE(
            "Non-static field requires instance",
            "use an instance receiver for non-static fields instead of class-qualified access."),
    ASSERTION_CONDITION_UNRESOLVED(
            "Cannot resolve assertion condition",
            "rewrite the assertion to reference declared variables or resolvable method calls only."),
    ASSERTION_ARGUMENT_UNRESOLVED(
            "Unresolved variable in assertion argument",
            "declare the variable earlier in the test and ensure the name matches exactly."),
    OBJECT_TO_SUBTYPE_MISMATCH(
            "Argument is Object but parameter expects subtype",
            "replace the Object placeholder with a value of the required concrete/subtype, or add an "
                    + "explicit cast only when that runtime type is guaranteed."),
    GENERIC_TYPE_MISMATCH(
            "Generic type mismatch",
            "match the generic type arguments expected by the API; do not pass collections or other "
                    + "parameterized values whose element types are incompatible."),
    PRIMITIVE_INIT_WITH_NULL(
            "Primitive declaration cannot be initialized with null",
            "initialize primitive locals with a concrete literal (0, false, '\\0', etc.); only "
                    + "reference types can be null."),
    BARE_CLASS_NAME_AS_VALUE(
            "Bare class name used as value expression",
            "use ExistingType.class for a class literal, or instantiate/use a variable; a bare type "
                    + "name is not a runtime value."),
    STRANDED_DECLARATION(
            "Variable declared but never assigned",
            "remove the declaration or assign a value before use; LLM tests must not declare locals they "
                    + "never write to"),
    STRANDED_WHEN_ALIAS(
            "Mockito `when(...)` alias has no terminal",
            "complete the stubbing with `.thenReturn(...)` or `.thenThrow(...)` on the captured alias, "
                    + "or remove the call entirely");

    static final String ACTION_REQUIRED_PREFIX = "LLM_REPAIR_ACTION_REQUIRED:";

    private final String template;
    private final String actionText;

    DiagnosticKind(String template, String actionText) {
        this.template = template;
        this.actionText = actionText;
    }

    String template() {
        return template;
    }

    String actionText() {
        return actionText;
    }

    String format(String details) {
        if (details == null || details.isEmpty()) {
            return template;
        }
        return template + ": " + details;
    }

    String appendRepairAction(String message) {
        if (message == null || message.isEmpty() || message.contains(ACTION_REQUIRED_PREFIX)) {
            return message;
        }
        return message + " " + ACTION_REQUIRED_PREFIX + " " + actionText;
    }
}
