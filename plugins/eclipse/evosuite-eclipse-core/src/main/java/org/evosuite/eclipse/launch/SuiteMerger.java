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
package org.evosuite.eclipse.launch;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextUtilities;
import org.evosuite.eclipse.Activator;

/**
 * Adds the tests of a newly generated suite to an existing suite of the same class.
 *
 * <p>Only suites that EvoSuite generated in the same way can be merged: both carry
 * {@code @EvoRunnerParameters}, use the same version of JUnit, and have the same runner
 * annotations and superclass, so that the new tests run under the settings they were generated
 * for. Within those, the merge
 * <ul>
 * <li>keeps the existing file as it is and appends the new tests at the end of the class;</li>
 * <li>skips new tests whose body equals that of an existing test, and the empty
 * {@code notGeneratedAnyTest} that EvoSuite writes when it found no tests;</li>
 * <li>continues the numbering when numbered tests ({@code test0}, ...) clash with existing ones,
 * and appends {@code _1}, {@code _2}, ... to other names that clash;</li>
 * <li>adds the imports the new tests need, and other members of the new suite, such as fields,
 * that the existing one has no member of that name for;</li>
 * <li>adds the classes the new suite initialises and resets between tests, in a JUnit 4 suite's
 * {@code initializeClasses()} and {@code resetClasses()}, to those of the existing one.</li>
 * </ul>
 */
public final class SuiteMerger {

    private static final Pattern NUMBERED = Pattern.compile("test(\\d+)");
    private static final Set<String> CLASS_LISTS = Set.of("initializeClasses", "resetClasses");
    /** The empty test EvoSuite writes into a suite for which it found no tests. */
    static final String PLACEHOLDER_TEST = "notGeneratedAnyTest";
    private static final String BYTE_ORDER_MARK = "\uFEFF";

    private SuiteMerger() {
    }

    /** The outcome of a merge. */
    public static final class Result {
        /** Why the suites could not be merged, or {@code null} if they were. */
        public final String incompatibility;
        /** The merged source, or {@code null} if the suites could not be merged. */
        public final String source;
        /** The names of the tests added, as they appear in the merged suite. */
        public final List<String> added;
        /** How many new tests were left out because an existing test has the same body. */
        public final int duplicates;

        private Result(String incompatibility, String source, List<String> added, int duplicates) {
            this.incompatibility = incompatibility;
            this.source = source;
            this.added = List.copyOf(added);
            this.duplicates = duplicates;
        }

        static Result incompatible(String reason) {
            return new Result(reason, null, List.of(), 0);
        }

        public boolean isMerged() {
            return incompatibility == null;
        }
    }

    /**
     * Merges the generated suite into the workspace file {@code existing}, which keeps its
     * previous contents in the local history.
     *
     * @return the outcome; the file is unchanged unless {@link Result#isMerged()} and tests
     *         were added
     */
    public static Result mergeInto(IFile existing, String generatedSource, String suiteName,
                                   IProgressMonitor monitor) throws CoreException {
        existing.refreshLocal(IResource.DEPTH_ZERO, monitor);
        ITextFileBuffer buffer = FileBuffers.getTextFileBufferManager()
                .getTextFileBuffer(existing.getFullPath(), LocationKind.IFILE);
        if (buffer != null && buffer.isDirty()) {
            return Result.incompatible(existing.getName() + " has unsaved changes in an editor");
        }
        String charset = existing.getCharset();
        String existingSource;
        try (InputStream in = existing.getContents()) {
            existingSource = new String(in.readAllBytes(), charset);
        } catch (IOException e) {
            throw new CoreException(Activator.error("Cannot read " + existing.getFullPath(), e));
        }
        Result result = merge(existingSource, generatedSource, suiteName);
        if (result.isMerged() && !result.added.isEmpty()) {
            existing.setContents(new ByteArrayInputStream(result.source.getBytes(Charset.forName(charset))),
                    IResource.KEEP_HISTORY, monitor);
        }
        return result;
    }

    /**
     * Merges {@code generatedSource} into {@code existingSource}, both suites of the class
     * {@code suiteName} (a simple name).
     */
    public static Result merge(String existingSource, String generatedSource, String suiteName) {
        // The parser takes a byte order mark for a stray character; the file keeps its own.
        boolean bom = existingSource.startsWith(BYTE_ORDER_MARK);
        Result result = mergeSources(withoutByteOrderMark(existingSource), withoutByteOrderMark(generatedSource),
                suiteName);
        if (!bom || result.source == null) {
            return result;
        }
        return new Result(null, BYTE_ORDER_MARK + result.source, result.added, result.duplicates);
    }

    private static String withoutByteOrderMark(String source) {
        return source.startsWith(BYTE_ORDER_MARK) ? source.substring(BYTE_ORDER_MARK.length()) : source;
    }

    private static Result mergeSources(String existingSource, String generatedSource, String suiteName) {
        CompilationUnit existingUnit = parse(existingSource);
        CompilationUnit generatedUnit = parse(generatedSource);
        if (hasErrors(existingUnit)) {
            return Result.incompatible("the existing suite does not parse");
        }
        if (hasErrors(generatedUnit)) {
            return Result.incompatible("the new suite does not parse");
        }
        TypeDeclaration existingType = findType(existingUnit, suiteName);
        TypeDeclaration generatedType = findType(generatedUnit, suiteName);
        if (existingType == null) {
            return Result.incompatible("the existing file declares no class " + suiteName);
        }
        if (generatedType == null) {
            return Result.incompatible("the new suite declares no class " + suiteName);
        }
        String incompatibility = incompatibility(existingUnit, existingType, existingSource,
                generatedUnit, generatedType, generatedSource);
        if (incompatibility != null) {
            return Result.incompatible(incompatibility);
        }

        // Which tests are new, and under what names.
        Set<String> existingBodies = new HashSet<>();
        Set<String> taken = new HashSet<>();
        int highestNumber = -1;
        for (MethodDeclaration method : existingType.getMethods()) {
            String name = method.getName().getIdentifier();
            taken.add(name);
            Matcher numbered = NUMBERED.matcher(name);
            if (numbered.matches() && numbered.group(1).length() < 9) {
                highestNumber = Math.max(highestNumber, Integer.parseInt(numbered.group(1)));
            }
            if (isTest(method) && method.getBody() != null) {
                existingBodies.add(normalise(source(existingSource, method.getBody())));
            }
        }
        List<MethodDeclaration> fresh = new ArrayList<>();
        int duplicates = 0;
        for (MethodDeclaration method : generatedType.getMethods()) {
            if (!isTest(method) || isPlaceholder(method)) {
                continue;
            }
            if (method.getBody() != null && existingBodies.contains(normalise(source(generatedSource, method.getBody())))) {
                duplicates++;
            } else {
                fresh.add(method);
            }
        }
        Map<MethodDeclaration, String> names = rename(fresh, taken, highestNumber);

        // Members other than tests that the existing suite lacks, such as fields.
        Set<String> existingMembers = memberNames(existingType);
        List<BodyDeclaration> otherMembers = new ArrayList<>();
        for (Object declaration : generatedType.bodyDeclarations()) {
            BodyDeclaration member = (BodyDeclaration) declaration;
            if (member instanceof MethodDeclaration method && (isTest(method) || isPlaceholder(method))) {
                continue;
            }
            Set<String> memberNames = memberNames(member);
            if (!memberNames.isEmpty() && memberNames.stream().noneMatch(existingMembers::contains)) {
                otherMembers.add(member);
            }
        }
        if (fresh.isEmpty()) {
            return new Result(null, existingSource, List.of(), duplicates);
        }

        IDocument document = new Document(existingSource);
        String delimiter = TextUtilities.getDefaultLineDelimiter(document);
        try {
            String importClash = addImportsAndClasses(existingUnit, existingType, generatedUnit, generatedType,
                    document);
            if (importClash != null) {
                return Result.incompatible(importClash);
            }
            // The imports moved the class; find its end again.
            CompilationUnit updated = parse(document.get());
            TypeDeclaration type = findType(updated, suiteName);
            StringBuilder members = new StringBuilder();
            for (BodyDeclaration member : otherMembers) {
                members.append(delimiter).append(memberSource(generatedUnit, generatedSource, member, null, delimiter));
            }
            for (MethodDeclaration method : fresh) {
                members.append(delimiter)
                        .append(memberSource(generatedUnit, generatedSource, method, names.get(method), delimiter));
            }
            insertBeforeClosingBrace(document, type, members.toString(), delimiter);
        } catch (BadLocationException e) {
            return Result.incompatible("the merge failed: " + e.getMessage());
        }
        String merged = document.get();
        if (hasErrors(parse(merged))) {
            return Result.incompatible("the merged suite would not parse");
        }
        return new Result(null, merged, fresh.stream().map(names::get).collect(Collectors.toList()), duplicates);
    }

    /** Why the two suites cannot be merged, or {@code null}. */
    private static String incompatibility(CompilationUnit existingUnit, TypeDeclaration existingType,
                                          String existingSource, CompilationUnit generatedUnit,
                                          TypeDeclaration generatedType, String generatedSource) {
        if (!hasAnnotation(existingType, "EvoRunnerParameters")) {
            return "it is not a suite generated by EvoSuite (it has no @EvoRunnerParameters)";
        }
        String existingFormat = testFormat(existingUnit);
        String generatedFormat = testFormat(generatedUnit);
        if (!existingFormat.equals(generatedFormat)) {
            return "it uses " + existingFormat + ", the new tests " + generatedFormat;
        }
        String existingAnnotations = annotations(existingType, existingSource);
        String generatedAnnotations = annotations(generatedType, generatedSource);
        if (!existingAnnotations.equals(generatedAnnotations)) {
            return "it runs with other settings: " + existingAnnotations + " instead of " + generatedAnnotations;
        }
        String existingSuper = existingType.getSuperclassType() == null ? ""
                : source(existingSource, existingType.getSuperclassType());
        String generatedSuper = generatedType.getSuperclassType() == null ? ""
                : source(generatedSource, generatedType.getSuperclassType());
        if (!existingSuper.equals(generatedSuper)) {
            return "it extends " + (existingSuper.isEmpty() ? "no class" : existingSuper) + ", the new suite "
                    + (generatedSuper.isEmpty() ? "no class" : generatedSuper);
        }
        return null;
    }

    /** JUnit 5 or JUnit 4, judged by what the suite imports. */
    static String testFormat(CompilationUnit unit) {
        boolean junit4 = false;
        for (Object declaration : unit.imports()) {
            String name = ((ImportDeclaration) declaration).getName().getFullyQualifiedName();
            if (name.startsWith("org.junit.jupiter.")) {
                return "JUnit 5";
            }
            junit4 |= name.startsWith("org.junit.");
        }
        return junit4 ? "JUnit 4" : "no known version of JUnit";
    }

    /**
     * Gives the new tests names that are free in the existing suite. Numbered tests, as EvoSuite
     * names them by default, continue the existing numbering as soon as one of them clashes, so
     * that they stay in order; other names get a suffix.
     */
    private static Map<MethodDeclaration, String> rename(List<MethodDeclaration> fresh, Set<String> taken,
                                                         int highestNumber) {
        Map<MethodDeclaration, String> names = new LinkedHashMap<>();
        boolean renumber = fresh.stream().map(m -> m.getName().getIdentifier())
                .anyMatch(name -> NUMBERED.matcher(name).matches() && taken.contains(name));
        Set<String> used = new HashSet<>(taken);
        Set<String> pending = fresh.stream().map(m -> m.getName().getIdentifier()).collect(Collectors.toSet());
        int next = highestNumber + 1;
        for (MethodDeclaration method : fresh) {
            String name = method.getName().getIdentifier();
            pending.remove(name);
            String chosen = name;
            if (renumber && NUMBERED.matcher(name).matches()) {
                while (used.contains("test" + next)) {
                    next++;
                }
                chosen = "test" + next++;
            } else if (used.contains(name)) {
                int suffix = 1;
                while (used.contains(name + "_" + suffix) || pending.contains(name + "_" + suffix)) {
                    suffix++;
                }
                chosen = name + "_" + suffix;
            }
            used.add(chosen);
            names.put(method, chosen);
        }
        return names;
    }

    /**
     * Adds the new suite's imports that the existing one lacks, and the classes its
     * {@code initializeClasses()} and {@code resetClasses()} name, to {@code document}.
     *
     * @return why the imports cannot be combined, or {@code null}
     */
    private static String addImportsAndClasses(CompilationUnit existingUnit, TypeDeclaration existingType,
                                               CompilationUnit generatedUnit, TypeDeclaration generatedType,
                                               IDocument document) throws BadLocationException {
        AST ast = existingUnit.getAST();
        ASTRewrite rewrite = ASTRewrite.create(ast);
        Map<String, String> singleImports = new LinkedHashMap<>();
        Set<String> imports = new HashSet<>();
        for (Object declaration : existingUnit.imports()) {
            ImportDeclaration existing = (ImportDeclaration) declaration;
            imports.add(importKey(existing));
            if (!existing.isOnDemand() && !existing.isStatic()) {
                singleImports.put(simpleName(existing), existing.getName().getFullyQualifiedName());
            }
        }
        ListRewrite importList = rewrite.getListRewrite(existingUnit, CompilationUnit.IMPORTS_PROPERTY);
        for (Object declaration : generatedUnit.imports()) {
            ImportDeclaration generated = (ImportDeclaration) declaration;
            if (imports.contains(importKey(generated))) {
                continue;
            }
            if (!generated.isOnDemand() && !generated.isStatic()) {
                String clash = singleImports.get(simpleName(generated));
                if (clash != null) {
                    return "its import of " + clash + " clashes with the new suite's import of "
                            + generated.getName().getFullyQualifiedName();
                }
            }
            importList.insertLast(ASTNode.copySubtree(ast, generated), null);
        }

        Map<String, MethodInvocation> existingLists = classLists(existingType);
        for (Map.Entry<String, MethodInvocation> entry : classLists(generatedType).entrySet()) {
            MethodInvocation existing = existingLists.get(entry.getKey());
            if (existing == null) {
                continue;
            }
            Set<String> listed = new HashSet<>();
            for (Object argument : existing.arguments()) {
                if (argument instanceof StringLiteral literal) {
                    listed.add(literal.getLiteralValue());
                }
            }
            ListRewrite arguments = rewrite.getListRewrite(existing, MethodInvocation.ARGUMENTS_PROPERTY);
            for (Object argument : entry.getValue().arguments()) {
                if (argument instanceof StringLiteral literal && listed.add(literal.getLiteralValue())) {
                    StringLiteral copy = ast.newStringLiteral();
                    copy.setLiteralValue(literal.getLiteralValue());
                    arguments.insertLast(copy, null);
                }
            }
        }
        rewrite.rewriteAST(document, JavaCore.getOptions()).apply(document);
        return null;
    }

    private static String importKey(ImportDeclaration declaration) {
        return (declaration.isStatic() ? "static " : "") + declaration.getName().getFullyQualifiedName()
                + (declaration.isOnDemand() ? ".*" : "");
    }

    private static String simpleName(ImportDeclaration declaration) {
        String name = declaration.getName().getFullyQualifiedName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** The calls that list the classes to initialise and reset, by the name of the call. */
    private static Map<String, MethodInvocation> classLists(TypeDeclaration type) {
        Map<String, MethodInvocation> lists = new LinkedHashMap<>();
        type.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation invocation) {
                String name = invocation.getName().getIdentifier();
                if (CLASS_LISTS.contains(name) && invocation.getExpression() != null
                        && invocation.getExpression().toString().endsWith("ClassStateSupport")) {
                    lists.putIfAbsent(name, invocation);
                }
                return true;
            }
        });
        return lists;
    }

    /**
     * The source of {@code member} with its leading comments, indented as in the generated
     * suite, under {@code newName} if given.
     */
    private static String memberSource(CompilationUnit unit, String source, BodyDeclaration member, String newName,
                                       String delimiter) {
        int start = unit.getExtendedStartPosition(member);
        int end = start + unit.getExtendedLength(member);
        String text;
        if (newName != null && member instanceof MethodDeclaration method) {
            int nameStart = method.getName().getStartPosition();
            text = source.substring(start, nameStart) + newName
                    + source.substring(nameStart + method.getName().getLength(), end);
        } else {
            text = source.substring(start, end);
        }
        int lineStart = start;
        while (lineStart > 0 && (source.charAt(lineStart - 1) == ' ' || source.charAt(lineStart - 1) == '\t')) {
            lineStart--;
        }
        String indentation = source.substring(lineStart, start);
        return indentation + text.replaceAll("\\r\\n|\\r|\\n", Matcher.quoteReplacement(delimiter))
                + delimiter;
    }

    /** Inserts {@code text} in front of the closing brace of {@code type}, on a line of its own. */
    private static void insertBeforeClosingBrace(IDocument document, TypeDeclaration type, String text,
                                                 String delimiter) throws BadLocationException {
        int brace = type.getStartPosition() + type.getLength() - 1;
        int line = document.getLineOfOffset(brace);
        int lineStart = document.getLineOffset(line);
        if (document.get(lineStart, brace - lineStart).isBlank()) {
            document.replace(lineStart, 0, text);
        } else {
            document.replace(brace, 0, delimiter + text);
        }
    }

    private static Set<String> memberNames(TypeDeclaration type) {
        Set<String> names = new HashSet<>();
        for (Object declaration : type.bodyDeclarations()) {
            names.addAll(memberNames((BodyDeclaration) declaration));
        }
        return names;
    }

    /** Field, method and type names a member declares; fields and methods do not clash. */
    private static Set<String> memberNames(BodyDeclaration member) {
        Set<String> names = new HashSet<>();
        if (member instanceof MethodDeclaration method) {
            names.add("method " + method.getName().getIdentifier());
        } else if (member instanceof FieldDeclaration field) {
            for (Object fragment : field.fragments()) {
                names.add("field " + ((VariableDeclarationFragment) fragment).getName().getIdentifier());
            }
        } else if (member instanceof AbstractTypeDeclaration type) {
            names.add("type " + type.getName().getIdentifier());
        }
        return names;
    }

    private static boolean isTest(MethodDeclaration method) {
        return hasAnnotation(method, "Test");
    }

    private static boolean isPlaceholder(MethodDeclaration method) {
        return PLACEHOLDER_TEST.equals(method.getName().getIdentifier());
    }

    /**
     * How many tests the class {@code suiteName} in {@code source} has, not counting the
     * placeholder EvoSuite writes when it found none; -1 if the source does not parse or lacks
     * the class.
     */
    public static int testCount(String source, String suiteName) {
        CompilationUnit unit = parse(withoutByteOrderMark(source));
        TypeDeclaration type = hasErrors(unit) ? null : findType(unit, suiteName);
        if (type == null) {
            return -1;
        }
        return (int) Arrays.stream(type.getMethods()).filter(m -> isTest(m) && !isPlaceholder(m)).count();
    }

    private static boolean hasAnnotation(BodyDeclaration declaration, String simpleName) {
        for (Object modifier : declaration.modifiers()) {
            if (modifier instanceof Annotation annotation) {
                String name = annotation.getTypeName().getFullyQualifiedName();
                if (name.equals(simpleName) || name.endsWith("." + simpleName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The class's annotations, with whitespace normalised. */
    private static String annotations(TypeDeclaration type, String source) {
        List<String> annotations = new ArrayList<>();
        for (Object modifier : type.modifiers()) {
            if (modifier instanceof Annotation annotation) {
                annotations.add(normalise(source(source, annotation)));
            }
        }
        return String.join(" ", annotations);
    }

    private static TypeDeclaration findType(CompilationUnit unit, String name) {
        for (Object type : unit.types()) {
            if (type instanceof TypeDeclaration declaration && declaration.getName().getIdentifier().equals(name)) {
                return declaration;
            }
        }
        return null;
    }

    private static String source(String source, ASTNode node) {
        return source.substring(node.getStartPosition(), node.getStartPosition() + node.getLength());
    }

    /** Collapses whitespace, so that formatting alone does not make two tests differ. */
    private static String normalise(String code) {
        return code.trim().replaceAll("\\s+", " ");
    }

    static CompilationUnit parse(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        parser.setCompilerOptions(options);
        parser.setSource(source.toCharArray());
        return (CompilationUnit) parser.createAST(null);
    }

    private static boolean hasErrors(CompilationUnit unit) {
        return Arrays.stream(unit.getProblems()).anyMatch(IProblem::isError);
    }
}
