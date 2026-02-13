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
package org.evosuite.testcase.statements.environment;

import org.evosuite.runtime.util.Inputs;
import org.evosuite.runtime.vnet.EndPointInfo;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Class used to keep track of what environment components (local files, remote URLs, etc)
 * a test case has accessed to.
 *
 * @author arcuri
 */
public class AccessedEnvironment implements Serializable {

    private static final long serialVersionUID = 2653568611955383431L;

    /**
     * Paths of accessed local files.
     */
    private final Set<String> localFiles;


    /**
     * URL of remote resources.
     */
    private final Set<String> remoteURLs;

    /**
     * TCP/UDP sockets opened by the SUT.
     */
    private final Set<EndPointInfo> localListeningPorts;


    /**
     * Remote addr/ports the SUT has contacted (ie initialized communication)
     * via TCP/UDP.
     */
    private final Set<EndPointInfo> remoteContactedPorts;


    /**
     * Constructor.
     */
    public AccessedEnvironment() {
        localFiles = new LinkedHashSet<>();
        remoteURLs = new LinkedHashSet<>();
        localListeningPorts = new LinkedHashSet<>();
        remoteContactedPorts = new LinkedHashSet<>();
    }

    /**
     * Copies the content of another AccessedEnvironment into this one.
     *
     * @param other the AccessedEnvironment to copy from.
     */
    public void copyFrom(AccessedEnvironment other) {
        clear();
        this.localFiles.addAll(other.localFiles);
        this.remoteURLs.addAll(other.remoteURLs);
        this.localListeningPorts.addAll(other.localListeningPorts);
        this.remoteContactedPorts.addAll(other.remoteContactedPorts);
    }

    /**
     * Clears all accessed environment data.
     */
    public void clear() {
        localFiles.clear();
        remoteURLs.clear();
        localListeningPorts.clear();
        remoteContactedPorts.clear();
    }

    /**
     * Checks if a specific property is present.
     *
     * @param property the property to check.
     * @return true if the property is present, false otherwise.
     * @throws IllegalArgumentException if the property is null.
     */
    public boolean hasProperty(String property) throws IllegalArgumentException {
        Inputs.checkNull(property);

        return false; //TODO
    }

    /**
     * Adds a collection of remote contacted ports.
     *
     * @param ports the ports to add.
     */
    public void addRemoteContactedPorts(Collection<EndPointInfo> ports) {
        remoteContactedPorts.addAll(ports);
    }

    /**
     * Returns an unmodifiable view of the remote contacted ports.
     *
     * @return a set of remote contacted ports.
     */
    public Set<EndPointInfo> getViewOfRemoteContactedPorts() {
        return Collections.unmodifiableSet(remoteContactedPorts);
    }

    /**
     * Adds a collection of local listening ports.
     *
     * @param ports the ports to add.
     */
    public void addLocalListeningPorts(Collection<EndPointInfo> ports) {
        localListeningPorts.addAll(ports);
    }

    /**
     * Returns an unmodifiable view of the local listening ports.
     *
     * @return a set of local listening ports.
     */
    public Set<EndPointInfo> getViewOfLocalListeningPorts() {
        return Collections.unmodifiableSet(localListeningPorts);
    }

    /**
     * Adds a collection of accessed local files.
     *
     * @param files the files to add.
     */
    public void addLocalFiles(Collection<String> files) {
        localFiles.addAll(files);
    }

    /**
     * Returns an unmodifiable view of the accessed local files.
     *
     * @return a set of accessed local files.
     */
    public Set<String> getViewOfAccessedFiles() {
        return Collections.unmodifiableSet(localFiles);
    }

    /**
     * Adds a collection of accessed remote URLs.
     *
     * @param urls the URLs to add.
     */
    public void addRemoteURLs(Collection<String> urls) {
        remoteURLs.addAll(urls);
    }

    /**
     * Returns an unmodifiable view of the accessed remote URLs.
     *
     * @return a set of accessed remote URLs.
     */
    public Set<String> getViewOfRemoteURLs() {
        return Collections.unmodifiableSet(remoteURLs);
    }

    /**
     * Checks if any network resource has been accessed.
     *
     * @return true if network has been accessed, false otherwise.
     */
    public boolean isNetworkAccessed() {
        return !remoteURLs.isEmpty() || !localListeningPorts.isEmpty() || !remoteContactedPorts.isEmpty();
    }

    /**
     * Checks if the file system has been accessed.
     *
     * @return true if file system has been accessed, false otherwise.
     */
    public boolean isFileSystemAccessed() {
        return !localFiles.isEmpty();
    }
}
