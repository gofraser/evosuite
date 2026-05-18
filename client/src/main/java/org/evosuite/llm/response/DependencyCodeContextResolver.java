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

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.VoidType;
import org.evosuite.llm.prompt.BytecodeContextProvider;
import org.evosuite.llm.prompt.DecompiledContextProvider;
import org.evosuite.llm.prompt.SutContextProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fetches a focused excerpt of a non-CUT class's code so the LLM can repair tests
 * whose CUT-side code path triggered a failure inside that dependency. Only
 * applicable to {@link DependencyFailureAnalysis.Kind#DEP_MEMBER_FAILURE}: for
 * {@code <clinit>} failures the dependency's source is largely useless to the
 * LLM (the JVM has poisoned the class for the rest of the run), and the caller
 * should emit avoidance instructions instead.
 *
 * <p>Resolution chain: CFR decompiled source → ASM-disassembled bytecode. The
 * decompiled source is preferred because JavaParser can scope the excerpt to the
 * single failing method. Output is cached per {@code (className, methodName)}
 * because the same dependency tends to throw across many repair turns.
 */
public class DependencyCodeContextResolver {

    private static final Logger logger = LoggerFactory.getLogger(DependencyCodeContextResolver.class);
    private static final int MAX_CACHE_ENTRIES = 64;
    private static final int MIN_USEFUL_BUDGET = 256;

    private final SutContextProvider decompiledProvider;
    private final SutContextProvider bytecodeProvider;
    private final Map<String, String> cache;

    public DependencyCodeContextResolver() {
        this(new DecompiledContextProvider(), new BytecodeContextProvider());
    }

    DependencyCodeContextResolver(SutContextProvider decompiledProvider,
                                  SutContextProvider bytecodeProvider) {
        this.decompiledProvider = decompiledProvider;
        this.bytecodeProvider = bytecodeProvider;
        // Bounded LRU map.
        this.cache = Collections.synchronizedMap(new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > MAX_CACHE_ENTRIES;
            }
        });
    }

    /**
     * Returns a labeled, budget-bounded code excerpt focused on {@code frame},
     * or {@link Optional#empty()} when no usable code can be obtained.
     *
     * <p>The excerpt is plain text, ready to drop into a repair message; it always
     * starts with a header line that names the source mode and warns the LLM that
     * line numbers may not match the original source.
     */
    public Optional<String> resolveExcerpt(DependencyFailureAnalysis.Frame frame, int charBudget) {
        if (frame == null || frame.getClassName() == null || frame.getClassName().isEmpty()) {
            return Optional.empty();
        }
        if (charBudget < MIN_USEFUL_BUDGET) {
            return Optional.empty();
        }

        String cacheKey = frame.getClassName() + "#" + frame.getMethodName() + "@" + charBudget;
        String cached = cache.get(cacheKey);
        if (cached != null) {
            return cached.isEmpty() ? Optional.empty() : Optional.of(cached);
        }

        Optional<String> result = resolveFresh(frame, charBudget);
        // Negative cache miss (empty string) so we don't redecompile every turn.
        cache.put(cacheKey, result.orElse(""));
        return result;
    }

    private Optional<String> resolveFresh(DependencyFailureAnalysis.Frame frame, int charBudget) {
        String topClass = topLevelClassName(frame.getClassName());

        Optional<String> decompiled = safeFetch(decompiledProvider, topClass);
        if (decompiled.isPresent()) {
            String focused = focusOnMember(decompiled.get(), frame, charBudget);
            if (focused != null && !focused.trim().isEmpty()) {
                return Optional.of(formatExcerpt("decompiled source",
                        focused, frame, /*lineNumbersAccurate=*/false));
            }
        }

        Optional<String> bytecode = safeFetch(bytecodeProvider, topClass);
        if (bytecode.isPresent()) {
            String focused = focusOnBytecodeMember(bytecode.get(), frame, charBudget);
            if (focused != null && !focused.trim().isEmpty()) {
                return Optional.of(formatExcerpt("disassembled bytecode",
                        focused, frame, /*lineNumbersAccurate=*/false));
            }
        }

        return Optional.empty();
    }

    private Optional<String> safeFetch(SutContextProvider provider, String className) {
        try {
            return provider.getContext(className, null);
        } catch (Throwable t) {
            logger.debug("Provider {} failed for {}: {}",
                    provider.getClass().getSimpleName(), className, t.toString());
            return Optional.empty();
        }
    }

    private String formatExcerpt(String modeLabel,
                                 String body,
                                 DependencyFailureAnalysis.Frame frame,
                                 boolean lineNumbersAccurate) {
        StringBuilder sb = new StringBuilder();
        sb.append("// from ").append(frame.getClassName());
        if (frame.getMethodName() != null && !frame.getMethodName().isEmpty()) {
            sb.append(", member: ").append(frame.getMethodName());
        }
        if (frame.getLineNumber() > 0) {
            sb.append(" (around line ").append(frame.getLineNumber()).append(")");
        }
        sb.append("\n// source: ").append(modeLabel);
        if (!lineNumbersAccurate) {
            sb.append(" (line numbers may not match the original code)");
        }
        sb.append("\n").append(body);
        return sb.toString();
    }

    /**
     * Strips inner-class suffixes from a stack-frame class name so we fetch the
     * top-level class file. ASM/CFR resolve nested classes via the same .class
     * resource lookup, but we operate on the enclosing compilation unit.
     */
    static String topLevelClassName(String className) {
        if (className == null) {
            return null;
        }
        int dollar = className.indexOf('$');
        return dollar < 0 ? className : className.substring(0, dollar);
    }

    /**
     * JavaParser-based extraction: keep the targeted member in full, stub all
     * other members. Falls back to a hard truncation if parsing fails.
     */
    String focusOnMember(String source, DependencyFailureAnalysis.Frame frame, int charBudget) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(source);
        } catch (Throwable t) {
            logger.debug("JavaParser could not parse decompiled source for {}: {}",
                    frame.getClassName(), t.toString());
            return hardTruncate(source, charBudget);
        }

        TypeDeclaration<?> primary = findPrimaryType(cu);
        if (primary == null) {
            return hardTruncate(source, charBudget);
        }

        boolean keptAny = pruneMembers(primary, frame);

        if (!keptAny) {
            // Couldn't find the named member; fall back to full source and let
            // the budget-based truncator do its best.
            return hardTruncate(source, charBudget);
        }

        String result = cu.toString();
        if (result.length() <= charBudget) {
            return result;
        }
        return hardTruncate(result, charBudget);
    }

    /** Stubs members that don't match {@code frame}. Returns true if we kept anything. */
    private boolean pruneMembers(TypeDeclaration<?> primary,
                                 DependencyFailureAnalysis.Frame frame) {
        boolean keptAny = false;

        for (BodyDeclaration<?> member : primary.getMembers()) {
            if (member instanceof MethodDeclaration) {
                MethodDeclaration md = (MethodDeclaration) member;
                if (matchesMethod(md, frame)) {
                    keptAny = true;
                } else {
                    stubMethod(md);
                }
            } else if (member instanceof ConstructorDeclaration) {
                ConstructorDeclaration cd = (ConstructorDeclaration) member;
                if (frame.isConstructor() && (matchesByLine(cd, frame) || onlyConstructor(primary))) {
                    keptAny = true;
                } else if (frame.isConstructor()) {
                    // Multiple constructors and no line match — keep all (small budget cost).
                    keptAny = true;
                } else {
                    stubConstructor(cd);
                }
            } else if (member instanceof InitializerDeclaration) {
                InitializerDeclaration id = (InitializerDeclaration) member;
                if (frame.isStaticInitializer() && id.isStatic()) {
                    keptAny = true;
                } else {
                    id.setBody(new BlockStmt());
                    id.setLineComment(" body stubbed for brevity");
                }
            } else if (member instanceof TypeDeclaration) {
                ((TypeDeclaration<?>) member).getMembers().clear();
                ((TypeDeclaration<?>) member).setLineComment(" members stubbed for brevity");
            }
        }
        return keptAny;
    }

    private boolean matchesMethod(MethodDeclaration md, DependencyFailureAnalysis.Frame frame) {
        if (frame.getMethodName() == null || frame.getMethodName().isEmpty()) {
            return false;
        }
        if (frame.isStaticInitializer() || frame.isConstructor()) {
            return false;
        }
        return md.getNameAsString().equals(frame.getMethodName());
    }

    private boolean matchesByLine(Node node, DependencyFailureAnalysis.Frame frame) {
        if (frame.getLineNumber() <= 0) {
            return false;
        }
        return node.getRange()
                .map(r -> r.begin.line <= frame.getLineNumber() && frame.getLineNumber() <= r.end.line)
                .orElse(false);
    }

    private boolean onlyConstructor(TypeDeclaration<?> primary) {
        int count = 0;
        for (BodyDeclaration<?> member : primary.getMembers()) {
            if (member instanceof ConstructorDeclaration) {
                count++;
                if (count > 1) {
                    return false;
                }
            }
        }
        return count == 1;
    }

    private void stubMethod(MethodDeclaration md) {
        BlockStmt stub = new BlockStmt();
        Type returnType = md.getType();
        if (!(returnType instanceof VoidType)) {
            stub.addStatement(new ReturnStmt("null"));
        }
        md.setBody(stub);
        md.setLineComment(" body stubbed for brevity");
    }

    private void stubConstructor(ConstructorDeclaration cd) {
        cd.setBody(new BlockStmt());
        cd.setLineComment(" body stubbed for brevity");
    }

    private TypeDeclaration<?> findPrimaryType(CompilationUnit cu) {
        List<TypeDeclaration<?>> types = cu.getTypes();
        if (types.isEmpty()) {
            return null;
        }
        for (TypeDeclaration<?> type : types) {
            if (type.hasModifier(Modifier.Keyword.PUBLIC)) {
                return type;
            }
        }
        return types.get(0);
    }

    /**
     * Bytecode fallback: scan disassembled output for a method block whose
     * signature line names {@code frame.getMethodName()} and emit just that
     * block plus the class header. This is lossier than the source path but
     * remains compact enough to be useful.
     */
    String focusOnBytecodeMember(String disassembly,
                                  DependencyFailureAnalysis.Frame frame,
                                  int charBudget) {
        if (disassembly == null || disassembly.isEmpty()) {
            return null;
        }
        String[] lines = disassembly.split("\\R", -1);
        StringBuilder header = new StringBuilder();
        StringBuilder block = new StringBuilder();
        boolean inTargetBlock = false;
        boolean foundTarget = false;
        boolean pastHeader = false;

        String target = frame.getMethodName() == null ? "" : frame.getMethodName();
        for (String line : lines) {
            String trimmed = line.trim();
            boolean isAccessFlagsBoundary = trimmed.startsWith("// access flags");
            if (isAccessFlagsBoundary) {
                pastHeader = true;
                inTargetBlock = false;
            }
            if (!pastHeader) {
                header.append(line).append('\n');
                continue;
            }
            if (isAccessFlagsBoundary) {
                // Decide based on the next line's signature whether this block matches.
                inTargetBlock = false;
                continue;
            }
            // First non-access-flag line after a boundary is the signature line.
            if (!inTargetBlock && block.length() == 0 && containsTargetSignature(trimmed, target)) {
                inTargetBlock = true;
                foundTarget = true;
                block.append("// access flags ...\n");
            }
            if (inTargetBlock) {
                block.append(line).append('\n');
            }
        }

        if (!foundTarget) {
            return null;
        }
        StringBuilder out = new StringBuilder(header).append(block);
        if (out.length() > charBudget) {
            return hardTruncate(out.toString(), charBudget);
        }
        return out.toString();
    }

    private boolean containsTargetSignature(String trimmedLine, String target) {
        if (target == null || target.isEmpty()) {
            return false;
        }
        // Bytecode renderings: "  someMethod(...)" or "  <init>(...)" or "  <clinit>()V"
        return trimmedLine.contains(target + "(");
    }

    private String hardTruncate(String text, int charBudget) {
        if (text.length() <= charBudget) {
            return text;
        }
        int safe = Math.max(0, charBudget - 64);
        return text.substring(0, safe)
                + "\n// ... truncated (" + (text.length() - safe) + " chars omitted)";
    }
}
