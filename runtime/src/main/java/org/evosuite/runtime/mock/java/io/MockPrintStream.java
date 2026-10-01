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
package org.evosuite.runtime.mock.java.io;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.OverrideMock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Objects;

/**
 * Custom mock implementation of {@link java.io.PrintStream}.
 */
public class MockPrintStream extends PrintStream implements OverrideMock {

    /* -- constructors  from PrintStream ----------
     *
     * here, we just need to replace FileOutputStream with
     * MockFileOutputStream.
     * This is simple as PrintStream does have a constructor
     * that takes as input an OutputStream
     */

    /**
     * The charset given to the {@link #MockPrintStream(OutputStream, boolean, Charset)}
     * constructor, or {@code null} for every other constructor. Non-null only for
     * instances that must bypass {@link PrintStream}'s own character encoding (see that
     * constructor's javadoc for why); {@link #print(boolean)} and friends below fall
     * straight back to {@code super} when this is {@code null}, leaving every other
     * construction path's behaviour exactly as before.
     */
    private final Charset charset;

    /**
     * Scratch buffer that {@link #encoder} drains into on every write; see {@link #writeEncoded}.
     * {@code null} whenever {@link #charset} is, so the no-charset path allocates nothing extra.
     * A {@link BoundedByteArrayOutputStream} so one unusually large print does not retain its
     * whole backing array for the rest of the stream's lifetime.
     */
    private final ByteArrayOutputStream encoderSink;

    /**
     * Guards {@link #close()}'s custom encoder finalization so it runs exactly once. {@link
     * PrintStream}'s own (always-present, unused) {@code charOut} writer is built from {@code
     * this} as its destination {@link OutputStream}, so closing it in {@code super.close()}
     * re-enters this override before the real underlying stream is closed. Without this guard,
     * that reentrant call -- and any later explicit {@code close()} call -- would redo the custom
     * finalization and, since draining {@link #encoderSink} alone does not stop it being read
     * again, replay the same final bytes.
     */
    private boolean encoderClosed;

    /**
     * Bounds the scratch sink's retained backing array. {@link ByteArrayOutputStream#reset()}
     * clears the logical size but keeps the backing array at its largest-ever capacity; left
     * alone, a single large print would retain that whole array for the stream's lifetime. This
     * only trims the byte sink underneath {@link #encoder} and never touches the encoder itself,
     * so its state (pending surrogate, BOM-written flag) is unaffected.
     */
    private static final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {

        /**
         * Large enough that ordinary prints never trigger a reallocation on the next write, small
         * enough that one oversized print cannot pin multiple megabytes per stream.
         */
        private static final int MAX_RETAINED_CAPACITY = 8192;

        @Override
        public synchronized void reset() {
            super.reset();
            if (buf.length > MAX_RETAINED_CAPACITY) {
                buf = new byte[MAX_RETAINED_CAPACITY];
            }
        }

        synchronized int capacity() {
            return buf.length;
        }
    }

    /**
     * One {@link OutputStreamWriter} -- i.e. one {@link java.nio.charset.CharsetEncoder} -- for
     * the lifetime of this stream, exactly like real {@link PrintStream}'s private {@code charOut}
     * field. This is what keeps a byte-order mark from being re-emitted on every {@code print} call
     * and lets a surrogate pair split across two {@code print} calls encode correctly: both of
     * those depend on encoder state (whether a BOM was already written, a leftover high surrogate
     * waiting for its partner) that only survives if the same encoder instance is reused.
     * {@code null} whenever {@link #charset} is.
     */
    private final Writer encoder;

    /** The platform line separator, resolved once; see {@link #println()}. */
    private static final String LINE_SEPARATOR = System.lineSeparator();

    public MockPrintStream(OutputStream out) {
        super(out);
        this.charset = null;
        this.encoderSink = null;
        this.encoder = null;
    }

    public MockPrintStream(OutputStream out, boolean autoFlush) {
        super(out, autoFlush);
        this.charset = null;
        this.encoderSink = null;
        this.encoder = null;
    }

    public MockPrintStream(OutputStream out, boolean autoFlush, String encoding)
            throws UnsupportedEncodingException {
        super(out, autoFlush, encoding);
        this.charset = null;
        this.encoderSink = null;
        this.encoder = null;
    }

    /**
     * Mirrors {@link PrintStream#PrintStream(OutputStream, boolean, Charset)}, which is a Java 10
     * API and does not exist on a Java 8 JVM (confirmed via {@code javap} against Corretto 8: only
     * the {@code (OutputStream, boolean, String)} overload is present).
     *
     * <p>There is no way to fix this by choosing a different {@code super(...)} call: Java
     * requires the super-constructor call to be the unconditional first statement, so a single
     * constructor cannot pick between "call the Java 10+ {@code (OutputStream, boolean, Charset)}
     * constructor" and "fall back for Java 8" at runtime -- unlike a static method, which {@link
     * org.evosuite.runtime.mock.javax.swing.MockHeadlessSwing
     * #invokeComponentSetMixingCutoutShapeReflective} can resolve reflectively once and either
     * call or no-op. Reflection cannot help on the other side either: constructing the charset
     * directly into an already-{@code super()}-initialized {@code this} would require rewriting
     * {@link PrintStream}'s private {@code charOut}/{@code textOut} fields, and {@code
     * Field.setAccessible} on them is refused since Java 17 ({@code module java.base does not
     * "opens java.io"}), confirmed empirically against this module's own Corretto 17 runtime.
     *
     * <p>So instead this always calls the Java 8-safe {@code (OutputStream, boolean)}
     * constructor purely to satisfy that first-statement requirement, and overrides every
     * {@code print}/{@code println} overload -- including the no-argument {@link #println()} --
     * to route its text through {@link #encoder} instead: one {@link OutputStreamWriter} built
     * directly from the given {@link
     * Charset} -- a Java 8 API -- and kept for the stream's lifetime. Building the encoder this
     * way, instead of resolving the charset back to a name via {@link Charset#forName(String)},
     * also means an unregistered {@link Charset} (one that implements {@link
     * Charset#newEncoder()} without registering a {@code java.nio.charset.spi.CharsetProvider})
     * works exactly as well as a registered one -- {@code Charset.forName} cannot find such a
     * charset by name at all.
     *
     * <p>Keeping one encoder for the stream's lifetime (rather than re-encoding each fragment
     * with {@link String#getBytes(Charset)}, as an earlier version of this constructor did) is
     * required for correctness, not just efficiency: a stateful charset like UTF-16 only emits its
     * byte-order mark on the encoder's first write, and a code point outside the BMP can arrive as
     * a surrogate pair split across two {@code print} calls, which only encodes correctly if the
     * high surrogate's encoder remembers it is waiting for the low surrogate. Per-fragment
     * encoding starts a fresh encoder -- and so a fresh "have I written the BOM yet" / "am I
     * mid-surrogate-pair" state -- on every single call, which re-emits the BOM mid-stream and
     * turns a split surrogate pair into two replacement characters. See {@link #writeEncoded} for
     * how the encoder's output reaches the underlying stream.
     *
     * <p>{@link MockPrintWriter}'s equivalent constructor does not need any override machinery at
     * all for the same reason this one needs {@link #encoder}: {@link java.io.PrintWriter} keeps
     * its destination in a {@code protected Writer out} field, so handing it an {@link
     * OutputStreamWriter} built from the given {@link Charset} directly *is* giving it the
     * long-lived encoder. {@link PrintStream}'s character encoding is bolted on top of a byte
     * stream through private fields instead, which is why this class has to keep its own encoder
     * alongside {@code super}'s (unused) one and funnel every text-producing override through it.
     */
    public MockPrintStream(OutputStream out, boolean autoFlush, Charset charset) {
        super(out, autoFlush);
        this.charset = Objects.requireNonNull(charset, "charset");
        this.encoderSink = new BoundedByteArrayOutputStream();
        this.encoder = new OutputStreamWriter(encoderSink, charset);
    }

    @Override
    public void print(boolean b) {
        if (charset == null) {
            super.print(b);
            return;
        }
        writeEncoded(String.valueOf(b));
    }

    @Override
    public void print(char c) {
        if (charset == null) {
            super.print(c);
            return;
        }
        writeEncoded(String.valueOf(c));
    }

    @Override
    public void print(int i) {
        if (charset == null) {
            super.print(i);
            return;
        }
        writeEncoded(String.valueOf(i));
    }

    @Override
    public void print(long l) {
        if (charset == null) {
            super.print(l);
            return;
        }
        writeEncoded(String.valueOf(l));
    }

    @Override
    public void print(float f) {
        if (charset == null) {
            super.print(f);
            return;
        }
        writeEncoded(String.valueOf(f));
    }

    @Override
    public void print(double d) {
        if (charset == null) {
            super.print(d);
            return;
        }
        writeEncoded(String.valueOf(d));
    }

    @Override
    public void print(char[] s) {
        if (charset == null) {
            super.print(s);
            return;
        }
        writeEncoded(String.valueOf(s));
    }

    @Override
    public void print(String s) {
        if (charset == null) {
            super.print(s);
            return;
        }
        writeEncoded(s == null ? "null" : s);
    }

    @Override
    public void print(Object obj) {
        if (charset == null) {
            super.print(obj);
            return;
        }
        writeEncoded(String.valueOf(obj));
    }

    @Override
    public void println() {
        if (charset == null) {
            super.println();
            return;
        }
        writeEncoded(LINE_SEPARATOR);
    }

    @Override
    public void println(boolean x) {
        if (charset == null) {
            super.println(x);
            return;
        }
        synchronized (this) {
            print(x);
            println();
        }
    }

    @Override
    public void println(char x) {
        if (charset == null) {
            super.println(x);
            return;
        }
        synchronized (this) {
            print(x);
            println();
        }
    }

    @Override
    public void println(int x) {
        if (charset == null) {
            super.println(x);
            return;
        }
        synchronized (this) {
            print(x);
            println();
        }
    }

    @Override
    public void println(long x) {
        if (charset == null) {
            super.println(x);
            return;
        }
        synchronized (this) {
            print(x);
            println();
        }
    }

    @Override
    public void println(float x) {
        if (charset == null) {
            super.println(x);
            return;
        }
        synchronized (this) {
            print(x);
            println();
        }
    }

    @Override
    public void println(double x) {
        if (charset == null) {
            super.println(x);
            return;
        }
        synchronized (this) {
            print(x);
            println();
        }
    }

    @Override
    public void println(char[] x) {
        if (charset == null) {
            super.println(x);
            return;
        }
        synchronized (this) {
            print(x);
            println();
        }
    }

    @Override
    public void println(String x) {
        if (charset == null) {
            super.println(x);
            return;
        }
        synchronized (this) {
            print(x);
            println();
        }
    }

    @Override
    public void println(Object x) {
        if (charset == null) {
            super.println(x);
            return;
        }
        synchronized (this) {
            print(x);
            println();
        }
    }

    /**
     * Pushes {@code s} through the long-lived {@link #encoder} and writes whatever bytes that
     * produces via {@link PrintStream#write(byte[], int, int)}, which is untouched here and
     * already implements {@code ensureOpen}/error-tracking/{@code autoFlush} correctly for raw
     * bytes.
     *
     * <p>{@link #encoder}'s {@link Writer#write(String)} only appends {@code s} to the encoder's
     * internal buffers; it does not by itself push bytes out to {@link #encoderSink}. {@link
     * Writer#flush()} does that (via the encoder's package-private {@code flushBuffer}, which is
     * the only part of this reachable through the public {@link Writer} API) without resetting
     * the encoder itself, so a pending leftover high surrogate or an already-written byte-order
     * mark survives into the next call -- that persistence is the entire point of keeping
     * {@link #encoder} around. {@link #encoderSink} is a plain in-memory buffer ({@link
     * ByteArrayOutputStream#flush()} is a no-op), so this never touches the real underlying
     * stream or its {@code autoFlush} setting; only the final {@link #write(byte[], int, int)}
     * call below does, exactly as it did before this method kept any encoder state.
     *
     * <p>The whole encode-drain-write sequence is synchronized because {@link #encoder} and
     * {@link #encoderSink} are now mutable state shared across calls -- unlike the stateless
     * {@link String#getBytes(Charset)} this replaces, a second thread's write interleaved between
     * encoding and draining here could corrupt or reorder output. {@link Writer#write(String)}
     * and {@link Writer#flush()} are declared to throw {@link IOException}, but cannot really do
     * so here: {@link #encoderSink} never throws, and an unmappable or malformed character is
     * replaced by the encoder, not rejected. The {@code catch} only exists so that a theoretical
     * failure is translated into {@link #setError()} rather than escaping -- a real {@link
     * PrintStream} never throws from a print/println call.
     *
     * <p>One observable difference from {@link PrintStream}'s own text path: under
     * {@code autoFlush}, a tracking-output probe records one flush of the real underlying stream
     * for real {@code print("x")} but two for real {@code print("x\n")}, versus one from this
     * class's {@link #write(byte[], int, int)} call in both cases. That is a flush-count
     * difference only -- it cannot change which bytes are written -- and is an acceptable
     * trade-off for reusing the existing, correct, public byte-writing path instead of
     * re-deriving it.
     */
    private void writeEncoded(String s) {
        synchronized (this) {
            byte[] bytes;
            try {
                encoder.write(s);
                encoder.flush();
            } catch (IOException e) {
                setError();
                return;
            }
            bytes = encoderSink.toByteArray();
            encoderSink.reset();
            write(bytes, 0, bytes.length);
        }
    }

    /**
     * Finalizes {@link #encoder} before delegating to {@link PrintStream#close()}. A real
     * {@link PrintStream} does the equivalent for its own {@code charOut} as part of closing
     * {@code textOut}: closing the encoder flushes any still-pending state -- in particular, a
     * leftover high surrogate from a split pair that never got its partner is turned into a
     * replacement character instead of silently vanishing. Guarded by {@link #charset} so the
     * no-charset path is unaffected and keeps delegating straight to {@code super}.
     *
     * <p>{@link #encoderClosed} makes this idempotent and reentry-safe: {@code super.close()}
     * below closes {@link PrintStream}'s own {@code charOut}, an {@link OutputStreamWriter} built
     * directly from {@code this}, so closing it writes through to {@code this} and then closes
     * {@code this} again -- reentering this very method before the underlying stream is actually
     * closed. The guard, together with resetting {@link #encoderSink} immediately after reading
     * it, ensures the finalization bytes are written exactly once no matter how many times -- or
     * how deeply nested -- {@code close()} ends up being called.
     */
    @Override
    public void close() {
        if (charset != null) {
            synchronized (this) {
                if (!encoderClosed) {
                    encoderClosed = true;
                    try {
                        encoder.close();
                        byte[] bytes = encoderSink.toByteArray();
                        encoderSink.reset();
                        if (bytes.length > 0) {
                            write(bytes, 0, bytes.length);
                        }
                    } catch (IOException e) {
                        setError();
                    }
                }
            }
        }
        super.close();
    }

    public MockPrintStream(String fileName) throws FileNotFoundException {
        this(!MockFramework.isEnabled()
                ? new FileOutputStream(fileName) : new MockFileOutputStream(fileName));
    }

    /**
     * Creates a new MockPrintStream, without automatic line flushing, with the
     * specified file name and charset.
     *
     * @param fileName the name of the file to use as the destination of this print stream
     * @param csn      the name of a supported charset
     * @throws FileNotFoundException        if the given file object does not denote an existing,
     *                                      writable regular file and a new regular file of that name
     *                                      cannot be created, or if some other error occurs while
     *                                      opening or creating the file
     * @throws UnsupportedEncodingException if the named charset is not supported
     */
    public MockPrintStream(String fileName, String csn)
            throws FileNotFoundException, UnsupportedEncodingException {
        this((!MockFramework.isEnabled()
                ? new FileOutputStream(fileName) : new MockFileOutputStream(fileName)),
                false, csn);
    }

    /**
     * Creates a new MockPrintStream, without automatic line flushing, with the
     * specified file name and charset.
     *
     * @param fileName the name of the file to use as the destination of this print stream
     * @param charset  the charset
     * @throws IOException if the file cannot be opened
     */
    public MockPrintStream(String fileName, Charset charset) throws IOException {
        this((!MockFramework.isEnabled()
                ? new FileOutputStream(fileName)
                : new MockFileOutputStream(fileName)),
                false, charset);
    }

    public MockPrintStream(File file) throws FileNotFoundException {
        this(!MockFramework.isEnabled()
                ? new FileOutputStream(file) : new MockFileOutputStream(file));
    }

    /**
     * Creates a new MockPrintStream, without automatic line flushing, with the
     * specified file and charset.
     *
     * @param file the file to use as the destination of this print stream
     * @param csn  the name of a supported charset
     * @throws FileNotFoundException        if the given file object does not denote an existing,
     *                                      writable regular file and a new regular file of that name
     *                                      cannot be created, or if some other error occurs while
     *                                      opening or creating the file
     * @throws UnsupportedEncodingException if the named charset is not supported
     */
    public MockPrintStream(File file, String csn)
            throws FileNotFoundException, UnsupportedEncodingException {
        // ensure charset is checked before the file is opened
        this((!MockFramework.isEnabled()
                ? new FileOutputStream(file) : new MockFileOutputStream(file)),
                false, csn);
    }

    /**
     * Creates a new MockPrintStream, without automatic line flushing, with the
     * specified file and charset.
     *
     * @param file    the file to use as destination
     * @param charset the charset
     * @throws IOException if the file cannot be opened
     */
    public MockPrintStream(File file, Charset charset) throws IOException {
        this((!MockFramework.isEnabled()
                ? new FileOutputStream(file)
                : new MockFileOutputStream(file)),
                false, charset);
    }
}
