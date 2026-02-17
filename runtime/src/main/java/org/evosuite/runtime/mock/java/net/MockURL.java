/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.runtime.mock.java.net;

import org.evosuite.runtime.mock.StaticReplacementMock;
import org.evosuite.runtime.mock.java.io.MockIOException;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MockURL implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return URL.class.getName();
    }

    private static URLStreamHandlerFactory factory;
    private static Map<String, URLStreamHandler> handlers = new ConcurrentHashMap<>();
    private static final Object streamHandlerLock = new Object();

    /**
     * Initializes the static state of MockURL.
     */
    public static void initStaticState() {
        factory = null;
        handlers.clear();
    }

    /**
     * Provide a valid URL example for the http protocol.
     */
    public static URL getHttpExample() {
        try {
            return URL("http://www.someFakeButWellFormedURL.org/fooExample");
        } catch (MalformedURLException e) {
            //should never happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Provide a valid URL example for the ftp protocol.
     *
     * @return a valid ftp URL
     */
    public static URL getFtpExample() {
        try {
            return URL("ftp://ftp.someFakeButWellFormedURL.org/fooExample");
        } catch (MalformedURLException e) {
            //should never happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Provide a valid URL example for the file protocol.
     *
     * @return a valid file URL
     */
    public static URL getFileExample() {
        try {
            return URL("file://some/fake/but/wellformed/url");
        } catch (MalformedURLException e) {
            //should never happen
            throw new RuntimeException(e);
        }
    }

    // -----  constructors ------------

    /**
     * Mocked constructor for {@link URL#URL(String)}.
     *
     * @param spec the URL specification
     * @return the new URL
     * @throws MalformedURLException if the URL is invalid
     */
    @SuppressWarnings("checkstyle:MethodName")
    public static URL URL(String spec) throws MalformedURLException {
        return URL(null, spec);
    }

    /**
     * Mocked constructor for {@link URL#URL(URL, String)}.
     *
     * @param context the context URL
     * @param spec    the URL specification
     * @return the new URL
     * @throws MalformedURLException if the URL is invalid
     */
    @SuppressWarnings("checkstyle:MethodName")
    public static URL URL(URL context, String spec) throws MalformedURLException {
        return URL(context, spec, null);
    }

    /**
     * Mocked constructor for {@link URL#URL(String, String, String)}.
     *
     * @param protocol the protocol
     * @param host     the host
     * @param file     the file
     * @return the new URL
     * @throws MalformedURLException if the URL is invalid
     */
    @SuppressWarnings("checkstyle:MethodName")
    public static URL URL(String protocol, String host, String file)
            throws MalformedURLException {
        return URL(protocol, host, -1, file);
    }

    /**
     * Mocked constructor for {@link URL#URL(String, String, int, String)}.
     *
     * @param protocol the protocol
     * @param host     the host
     * @param port     the port
     * @param file     the file
     * @return the new URL
     * @throws MalformedURLException if the URL is invalid
     */
    @SuppressWarnings("checkstyle:MethodName")
    public static URL URL(String protocol, String host, int port, String file)
            throws MalformedURLException {
        return URL(protocol, host, port, file, null);
    }

    /**
     * Mocked constructor for {@link URL#URL(String, String, int, String, URLStreamHandler)}.
     *
     * @param protocol the protocol
     * @param host     the host
     * @param port     the port
     * @param file     the file
     * @param handler  the stream handler
     * @return the new URL
     * @throws MalformedURLException if the URL is invalid
     */
    @SuppressWarnings("checkstyle:MethodName")
    public static URL URL(String protocol, String host, int port, String file,
                          URLStreamHandler handler) throws MalformedURLException {

        URL url = new URL(protocol, host, port, file, handler);

        // we just need to deal with "handler" if it wasn't specified
        if (handler == null) {
            /*
             * if no handler is specified, then parent would load one based on
             * protocol. As the function there is package level, we cannot modify/override it,
             * and we still have to call a constructor.
             * So, just replace the handler via reflection
             */
            handler = getMockedURLStreamHandler(protocol);
            URLUtil.setHandler(url, handler);
        }

        return url;
    }

    /**
     * Mocked constructor for {@link URL#URL(URL, String, URLStreamHandler)}.
     *
     * @param context the context URL
     * @param spec    the URL specification
     * @param handler the stream handler
     * @return the new URL
     * @throws MalformedURLException if the URL is invalid
     */
    @SuppressWarnings("checkstyle:MethodName")
    public static URL URL(URL context, String spec, URLStreamHandler handler)
            throws MalformedURLException {

        URL url = new URL(context, spec, handler);

        // we just need to deal with "handler" if it wasn't specified
        if (handler == null) {
            /*
             * if no handler is specified, then parent would load one based on
             * protocol. As the function there is package level, we cannot modify/override it,
             * and we still have to call a constructor.
             * So, just replace the handler via reflection
             */
            handler = getMockedURLStreamHandler(url.getProtocol());
            URLUtil.setHandler(url, handler);

            //this is needed, as called on the handler in the constructor we are mocking
            handleParseUrl(url, spec, handler);
        }

        return url;
    }

    private static void handleParseUrl(URL url, String spec, URLStreamHandler handler) throws MalformedURLException {

        //code here is based on URL constructor

        int i;
        int limit;
        int c;
        int start = 0;
        boolean hasRef = false;

        limit = spec.length();
        while ((limit > 0) && (spec.charAt(limit - 1) <= ' ')) {
            limit--;        //eliminate trailing whitespace
        }
        while ((start < limit) && (spec.charAt(start) <= ' ')) {
            start++;        // eliminate leading whitespace
        }

        if (spec.regionMatches(true, start, "url:", 0, 4)) {
            start += 4;
        }

        if (start < spec.length() && spec.charAt(start) == '#') {
            hasRef = true;
        }

        for (i = start; !hasRef && (i < limit)
                && ((c = spec.charAt(i)) != '/'); i++) {
            if (c == ':') {

                String s = spec.substring(start, i).toLowerCase();
                if (isValidProtocol(s)) {
                    start = i + 1;
                }
                break;
            }
        }

        i = spec.indexOf('#', start);
        if (i >= 0) {
            limit = i;
        }

        try {
            URLStreamHandlerUtil.parseURL(handler, url, spec, start, limit);
        } catch (InvocationTargetException e) {
            throw new MalformedURLException(e.getCause().toString());
        }
    }

    //From URL
    private static boolean isValidProtocol(String protocol) {
        int len = protocol.length();
        if (len < 1) {
            return false;
        }
        char c = protocol.charAt(0);
        if (!Character.isLetter(c)) {
            return false;
        }
        for (int i = 1; i < len; i++) {
            c = protocol.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '.' && c != '+'
                    && c != '-') {
                return false;
            }
        }
        return true;
    }

    // ---------------------

    /**
     * Replacement for {@link URL#getQuery()}.
     *
     * @param url the URL
     * @return the query part of the URL
     */
    public static String getQuery(URL url) {
        return url.getQuery();
    }

    /**
     * Replacement for {@link URL#getPath()}.
     *
     * @param url the URL
     * @return the path part of the URL
     */
    public static String getPath(URL url) {
        return url.getPath();
    }

    /**
     * Replacement for {@link URL#getUserInfo()}.
     *
     * @param url the URL
     * @return the user info part of the URL
     */
    public static String getUserInfo(URL url) {
        return url.getUserInfo();
    }

    /**
     * Replacement for {@link URL#getAuthority()}.
     *
     * @param url the URL
     * @return the authority part of the URL
     */
    public static String getAuthority(URL url) {
        return url.getAuthority();
    }

    /**
     * Returns the port number of the URL.
     *
     * @param url the URL
     * @return the port number, or -1 if the port is not set
     */
    public static int getPort(URL url) {
        return url.getPort();
    }

    public static int getDefaultPort(URL url) {
        return url.getDefaultPort();
    }

    /**
     * Returns the protocol name of the URL.
     *
     * @param url the URL
     * @return the protocol
     */
    public static String getProtocol(URL url) {
        return url.getProtocol();
    }

    public static String getHost(URL url) {
        return url.getHost();
    }

    public static String getFile(URL url) {
        return url.getFile();
    }

    public static String getRef(URL url) {
        return url.getRef();
    }

    /**
     * Compares this URL for equality with another object.
     *
     * @param url the URL
     * @param obj the object to compare against
     * @return true if the objects are the same; false otherwise.
     */
    public static boolean equals(URL url, Object obj) {
        // URL equals is blocking and broken:
        // https://stackoverflow.com/questions/3771081/proper-way-to-check-for-url-equality
        if (!(obj instanceof URL)) {
            return false;
        }
        URL u2 = (URL) obj;

        try {
            return url.toURI().equals(u2.toURI());
        } catch (URISyntaxException e) {
            return url.getPath().equals(u2.getPath());
        }
    }

    /**
     * Creates an integer suitable for hash table indexing.
     *
     * @param url the URL
     * @return a hash code for this URL.
     */
    public static synchronized int hashCode(URL url) {
        try {
            return url.toURI().hashCode();
        } catch (URISyntaxException e) {
            return url.getPath().hashCode();
        }
    }

    public static boolean sameFile(URL url, URL other) {
        return url.sameFile(other);
    }

    /**
     * Returns a string representation of the URL.
     *
     * @param url the URL
     * @return a string representation
     */
    public static String toString(URL url) {
        return url.toString();
    }

    public static String toExternalForm(URL url) {
        return url.toExternalForm();
    }

    public static URI toURI(URL url) throws URISyntaxException {
        return new URI(url.toString());
    }

    /**
     * Returns a {@link URLConnection} instance that represents a connection to the
     * remote object referred to by the {@code URL}.
     *
     * @param url the URL
     * @return a {@link URLConnection} to the URL
     * @throws java.io.IOException if an I/O error occurs
     */
    public static URLConnection openConnection(URL url) throws java.io.IOException {
        URLConnection connection = url.openConnection();
        if (connection == null) {
            throw new MockIOException("URL.openConnection() returned null for " + url);
        }
        return connection;
    }

    /**
     * Returns a {@link URLConnection} instance that represents a connection to the
     * remote object referred to by the {@code URL}.
     *
     * @param url   the URL
     * @param proxy the proxy through which the connection will be made
     * @return a {@link URLConnection} to the URL
     * @throws java.io.IOException if an I/O error occurs
     */
    public static URLConnection openConnection(URL url, Proxy proxy)
            throws java.io.IOException {
        if (proxy == null) {
            throw new IllegalArgumentException("proxy can not be null");
        }

        // Create a copy of Proxy as a security measure
        //Proxy p = proxy == Proxy.NO_PROXY ? Proxy.NO_PROXY : sun.net.ApplicationProxy.create(proxy);
        try {
            URLConnection connection = URLStreamHandlerUtil.openConnection(URLUtil.getHandler(url), url, proxy);
            if (connection == null) {
                throw new MockIOException("URL.openConnection(proxy) returned null for " + url);
            }
            return connection;
        } catch (InvocationTargetException e) {
            throw new MockIOException(e.getCause());
        }
    }

    /**
     * Opens a connection to this URL and returns an InputStream for reading from that connection.
     *
     * @param url the URL
     * @return an input stream for reading from the URL connection
     * @throws java.io.IOException if an I/O error occurs
     */
    public static InputStream openStream(URL url) throws java.io.IOException {
        return openConnection(url).getInputStream();
    }

    /**
     * Gets the contents of this URL.
     *
     * @param url the URL
     * @return the contents of this URL
     * @throws java.io.IOException if an I/O error occurs
     */
    public static Object getContent(URL url) throws java.io.IOException {
        return url.getContent();
    }

    /**
     * Gets the contents of this URL.
     *
     * @param url     the URL
     * @param classes an array of Java types
     * @return the content object that is the first match of the types specified in the classes array.
     *         null if no match is found.
     * @throws java.io.IOException if an I/O error occurs
     */
    public static Object getContent(URL url, Class[] classes)
            throws java.io.IOException {
        return url.getContent(classes);
    }

    /**
     * Sets an application's {@code URLStreamHandlerFactory}.
     *
     * @param fac the desired factory
     */
    public static void setURLStreamHandlerFactory(URLStreamHandlerFactory fac) {
        synchronized (streamHandlerLock) {
            if (factory != null) {
                throw new Error("factory already defined");
            }
            handlers.clear();
            factory = fac;
        }
    }

    protected static URLStreamHandler getMockedURLStreamHandler(String protocol) throws MalformedURLException {

        URLStreamHandler handler = handlers.get(protocol);
        if (handler == null) {

            // Use the factory (if any)
            if (factory != null) {
                handler = factory.createURLStreamHandler(protocol);
            }

            // create new instance
            if (handler == null) {
                if (EvoURLStreamHandler.isValidProtocol(protocol)) {
                    handler = new EvoURLStreamHandler(protocol);
                } else {
                    throw new MalformedURLException("unknown protocol: " + protocol);
                }
            }

            handlers.put(protocol, handler);
        }

        return handler;
    }
}
