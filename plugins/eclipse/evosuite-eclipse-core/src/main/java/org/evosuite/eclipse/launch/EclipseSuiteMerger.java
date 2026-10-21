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
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.evosuite.eclipse.Activator;
import org.evosuite.ide.SuiteMerger;
import org.evosuite.ide.SuiteMerger.Result;

/** Eclipse file-buffer and local-history adapter for the shared merger. */
public final class EclipseSuiteMerger {
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
        Result result = SuiteMerger.merge(existingSource, generatedSource, suiteName);
        if (result.isMerged() && !result.added.isEmpty()) {
            existing.setContents(new ByteArrayInputStream(result.source.getBytes(Charset.forName(charset))),
                    IResource.KEEP_HISTORY, monitor);
        }
        return result;
    }

}
