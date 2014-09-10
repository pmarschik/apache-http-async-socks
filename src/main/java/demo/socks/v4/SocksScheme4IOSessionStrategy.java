package demo.socks.v4;

import org.apache.http.HttpHost;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.nio.conn.SchemeIOSessionStrategy;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.nio.reactor.IOSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

public class SocksScheme4IOSessionStrategy implements SchemeIOSessionStrategy {
    private static final Logger log = LoggerFactory.getLogger(SocksScheme4IOSessionStrategy.class);

	private final SSLIOSessionStrategy sslioSessionStrategy;

	public SocksScheme4IOSessionStrategy(SSLIOSessionStrategy sslioSessionStrategy) {
		this.sslioSessionStrategy = sslioSessionStrategy;
	}

	public boolean isLayeringRequired() {
        return true;
    }

    @Override
    public IOSession upgrade(HttpHost host, IOSession iosession) throws IOException {
        if (log.isTraceEnabled())
            log.trace("upgrading session to demo.socks");

		HttpRoute route =((HttpRoute)iosession.getAttribute(IOSession.ATTACHMENT_KEY));
		String targetScheme = route.getTargetHost().getSchemeName();

		Socks4IOSession socksSession = new Socks4IOSession(iosession, "user");
		socksSession.initialize();

		IOSession resultSession = socksSession;

		if (Objects.equals("https", targetScheme)) {
			resultSession = sslioSessionStrategy.upgrade(route.getTargetHost(), socksSession);
			resultSession.setAttribute(Socks4IOSession.SESSION_KEY, socksSession);
		}

        return resultSession;
    }
}
