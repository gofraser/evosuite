/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.llm.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Selective truncator for BYTECODE_DISASSEMBLED mode.
 * Splits disassembled bytecode into method blocks using regex and selectively
 * stubs method bodies to fit within the character budget.
 *
 * <p>Bytecode disassembly (from ASM's Textifier) follows a predictable structure:
 * each method starts with an access-flags comment line followed by a method signature.
 */
public class BytecodeSelectiveTruncator implements SelectiveMethodTruncator {

    private static final Logger logger = LoggerFactory.getLogger(BytecodeSelectiveTruncator.class);

    /** Pattern to split on method boundaries (access flags comment lines). */
    private static final Pattern METHOD_BOUNDARY =
            Pattern.compile("(?=^\\s*// access flags 0x)", Pattern.MULTILINE);

    /** Extracts the hex access flags value from the comment line. */
    private static final Pattern ACCESS_FLAGS =
            Pattern.compile("// access flags 0x([0-9A-Fa-f]+)");

    /** Matches the method/constructor signature line (e.g. "  public <init>()V"). */
    private static final Pattern SIGNATURE_LINE =
            Pattern.compile("^\\s*(?:public |private |protected )?(?:static |final |abstract |synchronized |native )*(?:<(?:cl)?init>|\\S+)\\s*\\(", Pattern.MULTILINE);

    private static final int ACC_PUBLIC = 0x0001;

    @Override
    public String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }

        String[] rawBlocks = METHOD_BOUNDARY.split(text);
        if (rawBlocks.length < 2) {
            // No method boundaries found — can't parse structure
            logger.debug("No method boundaries found in bytecode for selective truncation");
            return null;
        }

        // First block is the header (class declaration, fields, etc.)
        String header = rawBlocks[0];
        List<MethodBlock> methods = new ArrayList<>();

        for (int i = 1; i < rawBlocks.length; i++) {
            methods.add(new MethodBlock(rawBlocks[i]));
        }

        // Partition: constructors, public methods, non-public methods
        List<MethodBlock> constructors = new ArrayList<>();
        List<MethodBlock> publicMethods = new ArrayList<>();
        List<MethodBlock> nonPublicMethods = new ArrayList<>();

        for (MethodBlock mb : methods) {
            if (mb.isConstructor) {
                constructors.add(mb);
            } else if (mb.isPublic) {
                publicMethods.add(mb);
            } else {
                nonPublicMethods.add(mb);
            }
        }

        // Sort public methods smallest-first
        Collections.sort(publicMethods, new Comparator<MethodBlock>() {
            @Override
            public int compare(MethodBlock a, MethodBlock b) {
                return a.fullText.length() - b.fullText.length();
            }
        });

        // Step 1: Stub all non-public methods
        for (MethodBlock mb : nonPublicMethods) {
            mb.stubbed = true;
        }

        // Check budget
        String result = buildResult(header, methods);
        if (result.length() <= maxChars) {
            return result;
        }

        // Step 2: Stub public methods largest first
        for (int i = publicMethods.size() - 1; i >= 0; i--) {
            publicMethods.get(i).stubbed = true;
            result = buildResult(header, methods);
            if (result.length() <= maxChars) {
                return result;
            }
        }

        // Step 3: Stub constructors largest first
        Collections.sort(constructors, new Comparator<MethodBlock>() {
            @Override
            public int compare(MethodBlock a, MethodBlock b) {
                return a.fullText.length() - b.fullText.length();
            }
        });
        for (int i = constructors.size() - 1; i >= 0; i--) {
            constructors.get(i).stubbed = true;
            result = buildResult(header, methods);
            if (result.length() <= maxChars) {
                return result;
            }
        }

        // Still over budget
        logger.debug("Bytecode selective truncation could not fit within {} chars (result: {} chars)",
                maxChars, result.length());
        return null;
    }

    private String buildResult(String header, List<MethodBlock> methods) {
        StringBuilder sb = new StringBuilder(header);
        for (MethodBlock mb : methods) {
            if (mb.stubbed) {
                sb.append(mb.stubText);
            } else {
                sb.append(mb.fullText);
            }
        }
        return sb.toString();
    }

    private static class MethodBlock {
        final String fullText;
        final String stubText;
        final boolean isConstructor;
        final boolean isPublic;
        boolean stubbed;

        MethodBlock(String blockText) {
            // The block text does not include the leading "// access flags" since split removes delimiter,
            // but it starts at the access-flags line content
            this.fullText = blockText;

            // Parse access flags
            Matcher flagsMatcher = ACCESS_FLAGS.matcher(blockText);
            int flags = 0;
            if (flagsMatcher.find()) {
                try {
                    flags = Integer.parseInt(flagsMatcher.group(1), 16);
                } catch (NumberFormatException ignored) {
                    // leave as 0
                }
            }
            this.isPublic = (flags & ACC_PUBLIC) != 0;

            // Detect constructor
            this.isConstructor = blockText.contains("<init>") || blockText.contains("<clinit>");

            // Build stub: keep only the access-flags comment and signature line, add "// body omitted"
            this.stubText = buildStub(blockText);
        }

        private String buildStub(String blockText) {
            String[] lines = blockText.split("\n", -1);
            StringBuilder stub = new StringBuilder();
            boolean pastSignature = false;
            for (String line : lines) {
                if (!pastSignature) {
                    stub.append(line).append("\n");
                    // The signature line typically follows the access flags comment
                    // and contains the method descriptor (parenthesis)
                    if (SIGNATURE_LINE.matcher(line).find()) {
                        pastSignature = true;
                        stub.append("    // body omitted\n");
                    }
                }
                // Skip remaining lines (bytecode instructions)
            }
            if (!pastSignature) {
                // Could not find a signature line — keep first 2 lines + stub marker
                stub.setLength(0);
                for (int i = 0; i < Math.min(2, lines.length); i++) {
                    stub.append(lines[i]).append("\n");
                }
                stub.append("    // body omitted\n");
            }
            return stub.toString();
        }
    }
}
