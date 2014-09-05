package demo.socks;

import com.google.common.base.Throwables;
import com.google.common.base.VerifyException;
import demo.socks.v4.Socks4IOSession;
import org.apache.http.impl.nio.DefaultNHttpClientConnection;
import org.apache.http.nio.NHttpClientEventHandler;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;

public class SocksIOEventDispatchProxy implements IOEventDispatch {
    private static final Logger log = LoggerFactory.getLogger(SocksIOEventDispatchProxy.class);

    private IOEventDispatch delegate;

    public SocksIOEventDispatchProxy(IOEventDispatch delegate) {
        this.delegate = delegate;
    }

    @Override
    public void connected(IOSession session) {
        delegate.connected(session);
    }

    @Override
    public void inputReady(IOSession session) {
        Socks4IOSession socks4IOSession = (Socks4IOSession) session.getAttribute(Socks4IOSession.SESSION_KEY);
		if (socks4IOSession != null && !socks4IOSession.isInitialized()) {
			if (log.isTraceEnabled())
				log.trace("receiving demo.socks response");

            try {
                socks4IOSession.receiveSocksConnect();
            } catch (IOException | VerifyException e) {
                if (log.isErrorEnabled())
                    log.error("error receiving demo.socks response", e);

                try {
                    DefaultNHttpClientConnection conn = (DefaultNHttpClientConnection) session.getAttribute(IOEventDispatch.CONNECTION_KEY);
                    Field handlerField = delegate.getClass().getDeclaredField("handler");
                    handlerField.setAccessible(true);
                    NHttpClientEventHandler handler = (NHttpClientEventHandler) handlerField.get(delegate);
                    handler.exception(conn, e);
                } catch (NoSuchFieldException | IllegalAccessException e1) {
                    if (log.isErrorEnabled())
                        log.error("failed to gracefully handle demo.socks response exception, provoking session closed exception.");

                    // provoke connection closed exception
                    session.close();
                    delegate.inputReady(session);
                }

                throw Throwables.propagate(e);
            }

			return;
		}

		delegate.inputReady(session);
    }

    @Override
    public void outputReady(IOSession session) {
		Socks4IOSession socks4IOSession = (Socks4IOSession) session.getAttribute(Socks4IOSession.SESSION_KEY);
		if (socks4IOSession != null && !socks4IOSession.isInitialized()) {
			if (log.isTraceEnabled())
				log.trace("outputReady but not yet initialized.");
			return;
		}

		delegate.outputReady(session);
	}

    @Override
    public void timeout(IOSession session) {
        delegate.timeout(session);
    }

    @Override
    public void disconnected(IOSession session) {
        delegate.disconnected(session);
    }
}
