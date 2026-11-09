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
package org.evosuite.intellij.run;

import com.intellij.openapi.application.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.newvfs.ManagingFS;

import org.jetbrains.concurrency.Promise;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class IdeThread {
    private IdeThread() {}

    /** Index-dependent reads must also wait for indexing started after the action was invoked. */
    public static <T> T smartRead(Project project, Supplier<T> supplier) {
        if (ApplicationManager.getApplication().isReadAccessAllowed()) return supplier.get();
        return ReadAction.nonBlocking(supplier::get)
                .inSmartMode(project)
                .expireWith(project)
                .executeSynchronously();
    }

    /** Flush asynchronous VFS writes before starting a process that reads those files. */
    public static void flushPendingVfsWrites() throws IOException {
        try {
            // Builds before 262 write synchronously and lack this API.
            ManagingFS.class.getMethod("flushPendingUpdates").invoke(ManagingFS.getInstance());
        } catch (NoSuchMethodException ignored) {
        } catch (IllegalAccessException ex) {
            throw new IOException("Cannot flush saved sources to disk", ex);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IOException("Cannot flush saved sources to disk", ex.getCause());
        }
    }

    public static <T> T edt(Supplier<T> supplier) {
        if (ApplicationManager.getApplication().isDispatchThread()) return supplier.get();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ApplicationManager.getApplication()
                .invokeAndWait(
                        () -> {
                            try { result.set(supplier.get()); }
                            catch (Throwable ex) { failure.set(ex); }
                        }, ModalityState.defaultModalityState());
        // invokeAndWait can swallow cancellation from the callback.
        Throwable ex = failure.get();
        if (ex instanceof RuntimeException runtime) throw runtime;
        if (ex instanceof Error error) throw error;
        if (ex != null) throw new IllegalStateException(ex);
        return result.get();
    }

    public static void writeCommand(Project project, String name, Runnable edit) {
        edt(() -> {
            WriteCommandAction.runWriteCommandAction(project, name, null, edit);
            return null;
        });
    }

    public static <T> T await(Promise<T> promise, ProgressIndicator indicator) {
        CompletableFuture<T> future = new CompletableFuture<>();
        promise.onSuccess(future::complete).onError(future::completeExceptionally);
        return await(future, indicator);
    }

    public static <T> T await(CompletableFuture<T> future, ProgressIndicator indicator) {
        while (true) {
            indicator.checkCanceled();
            try {
                return future.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ProcessCanceledException(ex);
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof ProcessCanceledException cancelled) throw cancelled;
                throw new IllegalStateException(ex.getCause());
            }
        }
    }
}
