package demo.socks;

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
		try {
			if (trySocksInitialize(session))
				delegate.inputReady(session);
		} catch (RuntimeException e) {
			session.shutdown();
			throw e;
		}
	}

	@Override
	public void outputReady(IOSession session) {
		try {
			if (trySocksInitialize(session))
				delegate.outputReady(session);
		} catch (RuntimeException e) {
			session.shutdown();
			throw e;
		}
	}

	@Override
	public void timeout(IOSession session) {
		try {
			delegate.timeout(session);

			final Socks4IOSession socks4IOSession = (Socks4IOSession) session.getAttribute(Socks4IOSession.SESSION_KEY);
			if (socks4IOSession != null)
				socks4IOSession.shutdown();
		} catch (RuntimeException e) {
			session.shutdown();
			throw e;
		}
	}

	@Override
	public void disconnected(IOSession session) {
		delegate.disconnected(session);
	}

	private boolean trySocksInitialize(IOSession session) {
		Socks4IOSession socks4IOSession = (Socks4IOSession) session.getAttribute(Socks4IOSession.SESSION_KEY);
		if (socks4IOSession != null) {
			try {
				if (!socks4IOSession.isInitialized())
					return socks4IOSession.initialize();
			} catch (IOException | VerifyException e) {
				if (log.isErrorEnabled())
					log.error("error receiving socks response", e);
				onException(socks4IOSession, e);
				socks4IOSession.shutdown();
			}
		}
		return true;
	}

	private void onException(IOSession session, Exception e) {
		NHttpClientEventHandler handler = unwrapClientEventHandler(session);

		if (handler != null) {
			DefaultNHttpClientConnection conn = unwrapClientConnection(session);
			handler.exception(conn, e);
			session.shutdown();
		} else {
			if (log.isWarnEnabled())
				log.warn("provoking session closed exception since NHttpClientEventHandler couldn't be unwrapped");
			session.close();
			delegate.inputReady(session);
		}
	}

	private DefaultNHttpClientConnection unwrapClientConnection(IOSession session) {
		return (DefaultNHttpClientConnection) session.getAttribute(IOEventDispatch.CONNECTION_KEY);
	}

	private NHttpClientEventHandler unwrapClientEventHandler(IOSession session) {
		try {
			Field handlerField = delegate.getClass().getDeclaredField("handler");
			handlerField.setAccessible(true);
			return (NHttpClientEventHandler) handlerField.get(delegate);
		} catch (NoSuchFieldException | IllegalAccessException e) {
			if (log.isErrorEnabled())
				log.error("failed to unwrap NHttpClientEventHandler from session {}", session, e);
			return null;
		}
	}
}
