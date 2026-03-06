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

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.comments.Comment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Strips comments from Java source code using JavaParser.
 * Used to reclaim prompt budget when SUT context exceeds the char limit.
 */
public final class JavaCommentStripper {

    private static final Logger logger = LoggerFactory.getLogger(JavaCommentStripper.class);

    private JavaCommentStripper() {
        // utility class
    }

    /**
     * Strips all comments (line, block, and Javadoc) from the given Java source.
     * If parsing fails (e.g. malformed decompiled output), returns the original text unchanged.
     */
    public static String stripComments(String javaSource) {
        if (javaSource == null || javaSource.isEmpty()) {
            return javaSource;
        }
        try {
            CompilationUnit cu = StaticJavaParser.parse(javaSource);
            List<Comment> comments = cu.getAllComments();
            for (Comment comment : comments) {
                comment.remove();
            }
            return cu.toString();
        } catch (Exception e) {
            logger.debug("JavaParser failed to parse source for comment stripping: {}", e.getMessage());
            return javaSource;
        }
    }
}
