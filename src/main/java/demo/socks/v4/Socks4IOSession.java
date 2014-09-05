package demo.socks.v4;

import com.google.common.base.Charsets;
import com.google.common.net.HostAndPort;
import org.apache.http.HttpHost;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.SessionBufferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;

import static com.google.common.base.Verify.verify;

public class Socks4IOSession implements IOSession {
    private static final Logger log = LoggerFactory.getLogger(Socks4IOSession.class);
    public final static String SESSION_KEY = "http.session.demo.socks";

    private final IOSession delegate;
    private final HttpHost targetHost;
    private final String userName;
    private boolean sent;
    private boolean initialized;

    public Socks4IOSession(IOSession delegate, String userName) {
        HttpRoute route = (HttpRoute) delegate.getAttribute(IOSession.ATTACHMENT_KEY);

        this.delegate = delegate;
        this.targetHost = route.getTargetHost();
        this.userName = userName;

        delegate.setAttribute(SESSION_KEY, this);
    }

    public void sendSocksConnect() throws IOException {
        if (sent) return;

        byte[] user = userName.getBytes(Charsets.ISO_8859_1);

        HostAndPort hostAndPort = HostAndPort.fromString(targetHost.toHostString());
        InetAddress host = InetAddress.getByName(hostAndPort.getHostText());
        int port = hostAndPort.getPort();

        if (log.isTraceEnabled())
            log.trace("demo.socks connect to {}:{}", host, port);

        ByteBuffer socksConnect = ByteBuffer.allocate(9 + user.length);
        socksConnect.put((byte) 0x4);
        socksConnect.put((byte) 0x1);
        socksConnect.put((byte) ((port >> 8) & 0xff));
        socksConnect.put((byte) ((port >> 0) & 0xff));
        socksConnect.put(host.getAddress());
        socksConnect.put(user);
        socksConnect.put((byte) 0x0);
        socksConnect.position(0);

        int written = delegate.channel().write(socksConnect);
        verify(written == 9 + user.length);

        sent = true;
    }

    public void receiveSocksConnect() throws IOException {
        if (initialized) return;

        ByteBuffer socksReply = ByteBuffer.allocate(8);
        int read = delegate.channel().read(socksReply);
        verify(read == 8, "expected demo.socks response of 8 bytes but got %s", read);
        socksReply.position(0);

        byte vn = socksReply.get();
        verify(vn == 0, "invalid demo.socks version %s received", vn);
        byte cd = socksReply.get();
        switch (cd) {
            case 90:
                if (log.isTraceEnabled())
                    log.trace("demo.socks connected OK");
                break;
            case 91:
                throw new IOException("demo.socks request rejected or failed");
            case 92:
                throw new IOException("demo.socks rejected: server cannot connect to specified address");
            case 93:
                throw new IOException("demo.socks rejected: authentication failed");
            default:
                throw new IOException("bad demo.socks status");
        }

        // server can reply with port/host (but can also be all zeros)
        short dstPort = socksReply.getShort();
        InetAddress dstAddr = InetAddress.getByAddress(new byte[]{socksReply.get(), socksReply.get(), socksReply.get(), socksReply.get()});

        initialized = true;

        if (log.isTraceEnabled())
            log.trace("demo.socks dst: {}:{}", dstAddr, dstPort);
    }

	public boolean isInitialized() {
		return initialized;
	}

	@Override
    public ByteChannel channel() {
        return delegate.channel();
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return delegate.getRemoteAddress();
    }

    @Override
    public SocketAddress getLocalAddress() {
        return delegate.getLocalAddress();
    }

    @Override
    public int getEventMask() {
        return delegate.getEventMask();
    }

    @Override
    public void setEventMask(int ops) {
        delegate.setEventMask(ops);
    }

    @Override
    public void setEvent(int op) {
        delegate.setEvent(op);
    }

    @Override
    public void clearEvent(int op) {
        delegate.clearEvent(op);
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public int getStatus() {
        return delegate.getStatus();
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public int getSocketTimeout() {
        return delegate.getSocketTimeout();
    }

    @Override
    public void setSocketTimeout(int timeout) {
        delegate.setSocketTimeout(timeout);
    }

    @Override
    public void setBufferStatus(SessionBufferStatus status) {
        delegate.setBufferStatus(status);
    }

    @Override
    public boolean hasBufferedInput() {
        return delegate.hasBufferedInput();
    }

    @Override
    public boolean hasBufferedOutput() {
        return delegate.hasBufferedOutput();
    }

    @Override
    public void setAttribute(String name, Object obj) {
        delegate.setAttribute(name, obj);
    }

    @Override
    public Object getAttribute(String name) {
        return delegate.getAttribute(name);
    }

    @Override
    public Object removeAttribute(String name) {
        return delegate.removeAttribute(name);
    }
}
