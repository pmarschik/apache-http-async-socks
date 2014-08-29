package demo.socks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOReactorException;

import java.io.InterruptedIOException;

public class SocksConnectingIOReactor extends DefaultConnectingIOReactor {
    private static final Logger log = LoggerFactory.getLogger(SocksConnectingIOReactor.class);

    public SocksConnectingIOReactor(IOReactorConfig config) throws IOReactorException {
        super(config);
    }

    @Override
    public void execute(IOEventDispatch eventDispatch) throws InterruptedIOException, IOReactorException {
        if (log.isTraceEnabled())
            log.trace("proxying IOEventDispatch");
        super.execute(new SocksIOEventDispatchProxy(eventDispatch));
    }
}
