package demo;

import demo.socks.SocksConnectingIOReactor;
import demo.socks.v4.SocksScheme4IOSessionStrategy;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.client.methods.AsyncCharConsumer;
import org.apache.http.nio.conn.NHttpClientConnectionManager;
import org.apache.http.nio.conn.NoopIOSessionStrategy;
import org.apache.http.nio.conn.SchemeIOSessionStrategy;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.nio.protocol.BasicAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.protocol.HttpContext;

import java.io.IOException;
import java.net.URI;
import java.nio.CharBuffer;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class Application {

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        HttpHost proxy = null;

        if (args.length >= 3) {
            String scheme = args[0];
            String host = args[1];
            int port = Integer.parseInt(args[2]);

            proxy = new HttpHost(host, port, scheme);
        }

//        proxy = new HttpHost(InetAddress.getLoopbackAddress(), 8888, "socks"); // valid local socks4 proxy
//        proxy = new HttpHost(InetAddress.getLoopbackAddress(), 9999, "socks"); // invalid local socks4 proxy
//        proxy = new HttpHost("93.82.197.107", 3129, "http"); // http proxy

        SocksConnectingIOReactor ioReactor = new SocksConnectingIOReactor(IOReactorConfig.custom().build());

        SchemeIOSessionStrategy socksSchemeIOSessionStrategy = new SocksScheme4IOSessionStrategy();

        Registry<SchemeIOSessionStrategy> sessionStrategyRegistry = RegistryBuilder.<SchemeIOSessionStrategy>create()
                .register("socks", socksSchemeIOSessionStrategy)
                .register("http", NoopIOSessionStrategy.INSTANCE)
                .register("https", SSLIOSessionStrategy.getDefaultStrategy())
                .build();

        NHttpClientConnectionManager connectionManager = new PoolingNHttpClientConnectionManager(ioReactor, sessionStrategyRegistry);

        CloseableHttpAsyncClient client = HttpAsyncClientBuilder.create()
                .setConnectionManager(connectionManager)
                .build();
        client.start();

        URI requestUri = URI.create("http://httpbin.org/get");
        HttpRequest request = new HttpGet(requestUri);

        HttpAsyncRequestProducer requestProducer = new BasicAsyncRequestProducer(
                new HttpHost(requestUri.getHost(), requestUri.getPort(), requestUri.getScheme()),
                request);

        HttpAsyncResponseConsumer<Void> responseConsumer = new AsyncCharConsumer<Void>() {
            @Override
            protected void onCharReceived(CharBuffer buf, IOControl ioctrl) throws IOException {
                System.out.print(buf.array());
            }

            @Override
            protected void onResponseReceived(HttpResponse response) throws HttpException, IOException {
                System.out.printf("response received: status %d\n", response.getStatusLine().getStatusCode());
            }

            @Override
            protected Void buildResult(HttpContext context) throws Exception {
                System.out.println("build result");
                return null;
            }
        };

        HttpClientContext httpContext = new HttpClientContext();
        httpContext.setRequestConfig(RequestConfig.custom()
                .setProxy(proxy)
                .setAuthenticationEnabled(true)
                .setProxyPreferredAuthSchemes(Collections.singleton("none"))
                .build());

        FutureCallback<Void> callback = new FutureCallback<Void>() {
            @Override
            public void completed(Void result) {
                System.out.println("completed");
            }

            @Override
            public void failed(Exception ex) {
                System.err.println("failed");
                ex.printStackTrace();
            }

            @Override
            public void cancelled() {
                System.out.println("cancelled");
            }
        };

        try {
            Future<Void> future = client.execute(requestProducer, responseConsumer, httpContext, callback);
            future.get();
        } finally {
            client.close();
        }
    }
}
