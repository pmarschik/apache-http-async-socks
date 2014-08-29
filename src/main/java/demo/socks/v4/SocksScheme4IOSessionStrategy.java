package demo.socks.v4;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.http.HttpHost;
import org.apache.http.nio.conn.SchemeIOSessionStrategy;
import org.apache.http.nio.reactor.IOSession;

import java.io.IOException;

public class SocksScheme4IOSessionStrategy implements SchemeIOSessionStrategy {
    private static final Logger log = LoggerFactory.getLogger(SocksScheme4IOSessionStrategy.class);

    public boolean isLayeringRequired() {
        return true;
    }

    @Override
    public IOSession upgrade(HttpHost host, IOSession iosession) throws IOException {
        if (log.isTraceEnabled())
            log.trace("upgrading session to demo.socks");
        Socks4IOSession socksSession = new Socks4IOSession(iosession, "user");
        socksSession.sendSocksConnect();
        return socksSession;
    }
}
