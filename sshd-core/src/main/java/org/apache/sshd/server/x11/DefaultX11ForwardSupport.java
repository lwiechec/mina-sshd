/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sshd.server.x11;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Objects;

import org.apache.sshd.common.Closeable;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.io.IoAcceptor;
import org.apache.sshd.common.io.IoServiceFactory;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.session.ConnectionService;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.OsUtils;
import org.apache.sshd.common.util.Readable;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.closeable.AbstractInnerCloseable;

/**
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class DefaultX11ForwardSupport extends AbstractInnerCloseable implements X11ForwardSupport {

    private final ConnectionService service;
    private IoAcceptor acceptor;

    public DefaultX11ForwardSupport(ConnectionService service) {
        this.service = Objects.requireNonNull(service, "No connection service");
    }

    @Override
    public void close() throws IOException {
        close(true);
    }

    @Override
    protected Closeable getInnerCloseable() {
        return builder().close(acceptor).build();
    }

    // TODO consider reducing the 'synchronized' section to specific code locations rather than entire method
    @Override
    public synchronized String createDisplay(
            boolean singleConnection, String authenticationProtocol, String authenticationCookie, int screen)
                    throws IOException {
        if (isClosed()) {
            throw new IllegalStateException("X11ForwardSupport is closed");
        }
        if (isClosing()) {
            throw new IllegalStateException("X11ForwardSupport is closing");
        }

        // only support non windows systems
        if (OsUtils.isWin32()) {
            if (log.isDebugEnabled()) {
                log.debug("createDisplay(auth={}, cookie={}, screen={}) Windows O/S N/A",
                          authenticationProtocol, authenticationCookie, screen);
            }
            return null;
        }

        Session session = Objects.requireNonNull(service.getSession(), "No session");
        if (acceptor == null) {
            FactoryManager manager = Objects.requireNonNull(session.getFactoryManager(), "No factory manager");
            IoServiceFactory factory = Objects.requireNonNull(manager.getIoServiceFactory(), "No I/O service factory");
            acceptor = factory.createAcceptor(this);
        }

        int minDisplayNumber = session.getIntProperty(X11_DISPLAY_OFFSET, DEFAULT_X11_DISPLAY_OFFSET);
        int maxDisplayNumber = session.getIntProperty(X11_MAX_DISPLAYS, DEFAULT_X11_MAX_DISPLAYS);
        int basePort = session.getIntProperty(X11_BASE_PORT, DEFAULT_X11_BASE_PORT);
        String bindHost = session.getStringProperty(X11_BIND_HOST, DEFAULT_X11_BIND_HOST);
        InetSocketAddress addr = null;

        // try until bind successful or max is reached
        for (int displayNumber = minDisplayNumber; displayNumber < maxDisplayNumber; displayNumber++) {
            int port = basePort + displayNumber;
            addr = new InetSocketAddress(bindHost, port);
            try {
                acceptor.bind(addr);
                break;
            } catch (BindException bindErr) {
                if (log.isDebugEnabled()) {
                    log.debug("createDisplay(auth={}, cookie={}, screen={}) failed ({}) to bind to address={}: {}",
                              authenticationProtocol, authenticationCookie, screen,
                              bindErr.getClass().getSimpleName(), addr, bindErr.getMessage());
                }

                addr = null;
            }
        }

        if (addr == null) {
            log.warn("createDisplay(auth={}, cookie={}, screen={})"
                   + " failed to allocate internet-domain X11 display socket in range {}-{}",
                     authenticationProtocol, authenticationCookie, screen,
                     minDisplayNumber, maxDisplayNumber);
            Collection<SocketAddress> boundAddresses = acceptor.getBoundAddresses();
            if (GenericUtils.isEmpty(boundAddresses)) {
                if (log.isDebugEnabled()) {
                    log.debug("createDisplay(auth={}, cookie={}, screen={}) closing - no more bound addresses",
                              authenticationProtocol, authenticationCookie, screen);
                }
                close();
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("createDisplay(auth={}, cookie={}, screen={}) closing - remaining bound addresses: {}",
                              authenticationProtocol, authenticationCookie, screen, boundAddresses);
                }
            }

            return null;
        }

        int port = addr.getPort();
        int displayNumber = port - basePort;
        String authDisplay = "unix:" + displayNumber + "." + screen;
        try {
            Process p = new ProcessBuilder(XAUTH_COMMAND, "remove", authDisplay).start();
            int result = p.waitFor();
            if (log.isDebugEnabled()) {
                log.debug("createDisplay({}) {} remove result={}", authDisplay, XAUTH_COMMAND, result);
            }

            if (result == 0) {
                p = new ProcessBuilder(XAUTH_COMMAND, "add", authDisplay, authenticationProtocol, authenticationCookie).start();
                result = p.waitFor();

                if (log.isDebugEnabled()) {
                    log.debug("createDisplay({}) {} add result={}", authDisplay, XAUTH_COMMAND, result);
                }
            }

            if (result != 0) {
                throw new IllegalStateException("Bad " + XAUTH_COMMAND + " invocation result: " + result);
            }

            return bindHost + ":" + displayNumber + "." + screen;
        } catch (Throwable e) {
            log.warn("createDisplay({}) failed ({}) run xauth: {}",
                     authDisplay, e.getClass().getSimpleName(), e.getMessage());
            if (log.isDebugEnabled()) {
                log.debug("createDisplay(" + authDisplay + ") xauth failure details", e);
            }
            return null;
        }
    }

    @Override
    public void sessionCreated(IoSession session) throws Exception {
        ChannelForwardedX11 channel = new ChannelForwardedX11(session);
        session.setAttribute(ChannelForwardedX11.class, channel);
        if (log.isDebugEnabled()) {
            log.debug("sessionCreated({}) channel{}", session, channel);
        }
        this.service.registerChannel(channel);
        channel.open().verify(channel.getLongProperty(CHANNEL_OPEN_TIMEOUT_PROP, DEFAULT_CHANNEL_OPEN_TIMEOUT));
    }

    @Override
    public void sessionClosed(IoSession session) throws Exception {
        ChannelForwardedX11 channel = (ChannelForwardedX11) session.getAttribute(ChannelForwardedX11.class);
        if (channel != null) {
            if (log.isDebugEnabled()) {
                log.debug("sessionClosed({}) close channel={}", session, channel);
            }
            channel.close(false);
        }
    }

    @Override
    public void messageReceived(IoSession session, Readable message) throws Exception {
        ChannelForwardedX11 channel = (ChannelForwardedX11) session.getAttribute(ChannelForwardedX11.class);
        Buffer buffer = new ByteArrayBuffer(message.available() + Long.SIZE, false);
        buffer.putBuffer(message);

        if (log.isTraceEnabled()) {
            log.trace("messageReceived({}) channel={}, len={}", session, channel, buffer.available());
        }
        OutputStream outputStream = channel.getInvertedIn();
        outputStream.write(buffer.array(), buffer.rpos(), buffer.available());
        outputStream.flush();
    }

    @Override
    public void exceptionCaught(IoSession session, Throwable cause) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("exceptionCaught({}) {}: {}", session, cause.getClass().getSimpleName(), cause.getMessage());
        }
        if (log.isTraceEnabled()) {
            log.trace("exceptionCaught(" + session + ") caught exception details", cause);
        }
        session.close(false);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + ": " + service.getClass();
    }
}
