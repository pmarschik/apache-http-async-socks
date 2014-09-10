package demo.socks.v4;

import com.google.common.base.Charsets;
import com.google.common.net.HostAndPort;
import com.google.common.net.InetAddresses;
import org.apache.http.HttpHost;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.SessionBufferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;

public class Socks4IOSession implements IOSession {
	private static final Logger log = LoggerFactory.getLogger(Socks4IOSession.class);
	private static final InetAddress SOCKS_SERVER_NAME_LOOKUP_ADDRESS = InetAddresses.fromInteger(0x01);
	public final static String SESSION_KEY = "http.session.socks";

    private final IOSession delegate;
    private final HttpHost targetHost;
    private final String userName;

	private SocketAddress remoteAddress;

	private ByteBuffer in;

	private int status = IOSession.ACTIVE;
	private boolean endOfStream = false;

	private volatile boolean connectSent;
	private volatile boolean connectReceived;
	private volatile boolean initialized;

	public Socks4IOSession(IOSession delegate, String userName) {
		HttpRoute route = (HttpRoute) delegate.getAttribute(IOSession.ATTACHMENT_KEY);

		this.delegate = delegate;
		this.targetHost = route.getTargetHost();
		this.userName = userName;

		// we need to report the correct remote address in case we are wrapped in an ssl session
		this.remoteAddress = targetHost.getAddress() != null && !targetHost.getAddress().isAnyLocalAddress()
				? new InetSocketAddress(targetHost.getAddress(), targetHost.getPort())
				: new InetSocketAddress(targetHost.getHostName(), targetHost.getPort());

		in = ByteBuffer.allocate(8);

		delegate.setAttribute(SESSION_KEY, this);
	}

	public boolean initialize() throws IOException {
		if (initialized) return true;

		if (delegate.getStatus() >= IOSession.CLOSING)
			return false;

		beginConnect();
		return initialized = doConnect();
	}

	private void beginConnect() throws IOException {
		if (connectSent) return;
		sendSocksConnect();
	}

	private boolean doConnect() throws IOException {
		if (!connectReceived) {
			int read = delegate.channel().read(in);
			if (!endOfStream && read == -1) {
				endOfStream = true;
				status = IOSession.CLOSED;
				delegate.close();
				return false;
			}

			if (in.position() < 8)
				return false;
			in.flip();
			receiveSocksConnect();
		}

		return true;
	}

	private void sendSocksConnect() throws IOException {
		checkState(!connectSent, "connect already sent");

		/*
		CONNECT

		The client connects to the SOCKS server and sends a CONNECT request when
		it wants to establish a connection to an application server. The client
		includes in the request packet the IP address and the port number of the
		destination host, and userid, in the following format.

				+----+----+----+----+----+----+----+----+----+----+....+----+
				| VN | CD | DSTPORT |      DSTIP        | USERID       |NULL|
				+----+----+----+----+----+----+----+----+----+----+....+----+
				   1    1      2              4           variable       1

		VN is the SOCKS protocol version number and should be 4. CD is the
		SOCKS command code and should be 1 for CONNECT request. NULL is a byte
		of all zero bits.

		The SOCKS server checks to see whether such a request should be granted
		based on any combination of source IP address, destination IP address,
		destination port number, the userid, and information it may obtain by
		consulting IDENT, cf. RFC 1413.  If the request is granted, the SOCKS
		server makes a connection to the specified port of the destination host.
		A reply packet is sent to the client when this connection is established,
		or when the request is rejected or the operation fails.
		*/

		HostAndPort hostAndPort = HostAndPort.fromString(targetHost.toHostString());

		byte[] user = userName.getBytes(Charsets.ISO_8859_1);
		byte[] hostName = hostAndPort.getHostText().getBytes(Charsets.ISO_8859_1);

		// if we got the socks server as a domain name instead of ip address we will uses SOCKS4a to let the server handle the lookup
		InetAddress host = SOCKS_SERVER_NAME_LOOKUP_ADDRESS;
		boolean needsSocksServerHostnameLookup = false;
		if (InetAddresses.isInetAddress(hostAndPort.getHostText()))
			host = InetAddresses.forString(hostAndPort.getHostText());
		else
			needsSocksServerHostnameLookup = true;

		int port = hostAndPort.getPort();

        if (log.isTraceEnabled())
            log.trace("demo.socks connect to {}:{}", host, port);

		final int packetSize = 9 + user.length + (needsSocksServerHostnameLookup ? hostName.length + 1 : 0);
		ByteBuffer socksConnect = ByteBuffer.allocate(packetSize);
		socksConnect.put((byte) 0x4);
		socksConnect.put((byte) 0x1);
		socksConnect.put((byte) ((port >> 8) & 0xff));
		socksConnect.put((byte) ((port >> 0) & 0xff));
		socksConnect.put(host.getAddress());
		socksConnect.put(user);
		socksConnect.put((byte) 0x0);

		if (needsSocksServerHostnameLookup) {
			socksConnect.put(hostName);
			socksConnect.put((byte) 0x0);
		}

		socksConnect.flip();

		int written = delegate.channel().write(socksConnect);
		verify(written == packetSize);

		connectSent = true;
	}

	public void receiveSocksConnect() throws IOException {
		checkState(!connectReceived, "connect reply already received");

		/*
		+----+----+----+----+----+----+----+----+
        | VN | CD | DSTPORT |      DSTIP        |
        +----+----+----+----+----+----+----+----+
           1    1      2              4

		VN is the version of the reply code and should be 0. CD is the result
		code with one of the following values:

			90: request granted
			91: request rejected or failed
			92: request rejected becasue SOCKS server cannot connect to
				identd on the client
			93: request rejected because the client program and identd
				report different user-ids

		The remaining fields are ignored.

		The SOCKS server closes its connection immediately after notifying
		the client of a failed or rejected request. For a successful request,
		the SOCKS server gets ready to relay traffic on both directions. This
		enables the client to do I/O on its connection as if it were directly
		connected to the application server.
		 */

		ByteBuffer socksReply = in;
		verify(in.limit() == 8, "expected socks response of 8 bytes but got %s", in.limit());

		byte vn = socksReply.get();
		verify(vn == 0, "invalid socks version %s received", vn);
		byte cd = socksReply.get();
		switch (cd) {
			case 90:
				if (log.isTraceEnabled())
					log.trace("socks connected OK");
				break;
			case 91:
				close();
				throw new IOException("socks request rejected or failed");
			case 92:
				close();
				throw new IOException("socks rejected: server cannot connect to specified address");
			case 93:
				close();
				throw new IOException("socks rejected: authentication failed");
			default:
				throw new IOException("bad socks status");
		}

		// server can reply with port/host (but can also be all zeros)
		short dstPort = socksReply.getShort();
		byte[] dstAddrBytes = new byte[4];
		socksReply.get(dstAddrBytes);
		InetAddress dstAddr = InetAddress.getByAddress(dstAddrBytes);

		connectReceived = true;

		if (log.isTraceEnabled())
			log.trace("socks dst: {}:{}", dstAddr, dstPort);
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
		return remoteAddress;
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
		if (status == IOSession.CLOSED)
			return;
		delegate.close();
	}

    @Override
    public void shutdown() {
		if (status == IOSession.CLOSED)
			return;
		delegate.shutdown();
	}

    @Override
    public int getStatus() {
		return status;
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
