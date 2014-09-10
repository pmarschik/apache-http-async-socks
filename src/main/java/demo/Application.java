package demo;

import com.google.common.net.UrlEscapers;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class Application {
	private static final Logger log = LoggerFactory.getLogger(Application.class);
	private static final int HTTP_REQUEST_COUNT = 20;

	public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
		HttpHost[] proxies = new HttpHost[]{
				new HttpHost("127.0.0.1", 8888, "socks"), // local socks4 proxy
				new HttpHost("127.0.0.1", 8889, "socks"), // local socks4 proxy
				new HttpHost("67.43.35.64", 8118, "http"), // free http proxy
				new HttpHost("127.0.0.1", 9999, "socks") // invalid local socks4 proxy
		};

        SocksConnectingIOReactor ioReactor = new SocksConnectingIOReactor(IOReactorConfig.custom().build());

		SSLIOSessionStrategy sslioSessionStrategy = SSLIOSessionStrategy.getDefaultStrategy();
        SchemeIOSessionStrategy socksSchemeIOSessionStrategy = new SocksScheme4IOSessionStrategy(sslioSessionStrategy);

        Registry<SchemeIOSessionStrategy> sessionStrategyRegistry = RegistryBuilder.<SchemeIOSessionStrategy>create()
                .register("socks", socksSchemeIOSessionStrategy)
                .register("http", NoopIOSessionStrategy.INSTANCE)
                .register("https", sslioSessionStrategy)
                .build();

        NHttpClientConnectionManager connectionManager = new PoolingNHttpClientConnectionManager(ioReactor, sessionStrategyRegistry);

        CloseableHttpAsyncClient client = HttpAsyncClientBuilder.create()
                .setConnectionManager(connectionManager)
                .build();
        client.start();

        URI requestUri = URI.create("http://httpbin.org/get");

        try {
			List<Future<String>> successFutures = new ArrayList<>();
			List<Future<String>> failFutures = new ArrayList<>();
			//Random random = new Random(System.currentTimeMillis());

			for (int i = 0; i < HTTP_REQUEST_COUNT; i++) {
				HttpHost proxy = proxies[i % proxies.length];

				HttpRequest request = new HttpGet(requestUri + "?proxy=" + UrlEscapers.urlFragmentEscaper().escape(proxy.toString()) + "&requestNo=" + (i + 1));

				HttpAsyncRequestProducer requestProducer = new BasicAsyncRequestProducer(
						new HttpHost(requestUri.getHost(), requestUri.getPort(), requestUri.getScheme()),
						request);

				HttpClientContext httpContext = new HttpClientContext();
				httpContext.setRequestConfig(RequestConfig.custom()
						.setProxy(proxy)
						.setSocketTimeout(10000)
						.setConnectTimeout(1000)
						.setConnectionRequestTimeout(1000)
						.build());

				FutureCallback<String> callback = new LoggingFutureCallback();
				HttpAsyncResponseConsumer<String> responseConsumer = new LoggingAsyncCharConsumer();

				Future<String> future = client.execute(requestProducer, responseConsumer, httpContext, callback);

				if (proxy.getPort() >= 9000)
					failFutures.add(future);
				else
					successFutures.add(future);
			}

			List<String> results = new ArrayList<>();

			for (Future<String> future : successFutures)
				try {
					String response = future.get();
					results.add("Successful response: " + response.replace('\n', ' '));
				} catch (Exception e) {
					log.error("http request failed", e);
					results.add("Failed response: " + e.getMessage());
				}

			for (Future<String> future : failFutures) {
				try {
					String response = future.get();
					log.error("shouldn't be able to get fail future");
					results.add("Failed error: " + response);
				} catch (Exception e) {
					results.add("Successful error: " + e.getMessage());
				}
			}

			for (String result : results) {
				if (result.startsWith("Failed"))
					System.err.println(result);
				else
					System.out.println(result);
			}
		} finally {
			client.close();
		}
	}

	private static class LoggingFutureCallback implements FutureCallback<String> {
		private static final Logger log = LoggerFactory.getLogger(LoggingFutureCallback.class);

		@Override
		public void completed(String result) {
			if (log.isInfoEnabled())
				log.info("future completed, result: {}...", result.replace('\n', ' ').substring(0, Math.min(8, result.length())));
		}

		@Override
		public void failed(Exception ex) {
			if (log.isErrorEnabled())
				log.error("future error", ex);
		}

		@Override
		public void cancelled() {
			if (log.isInfoEnabled())
				log.info("cancelled");
		}
	}

	private static class LoggingAsyncCharConsumer extends AsyncCharConsumer<String> {
		private static final Logger log = LoggerFactory.getLogger(LoggingAsyncCharConsumer.class);
		private StringBuilder builder = new StringBuilder(1024);

		@Override
		protected void onCharReceived(CharBuffer buf, IOControl ioctrl) throws IOException {
			if (log.isTraceEnabled())
				log.trace("char received");
			builder.append(buf);
		}

		@Override
		protected void onResponseReceived(HttpResponse response) throws HttpException, IOException {
			if (log.isInfoEnabled())
				log.info("response received: status {}", response.getStatusLine().getStatusCode());
			builder.delete(0, builder.length());
		}

		@Override
		protected String buildResult(HttpContext context) throws Exception {
			if (log.isInfoEnabled())
				log.info("build result");
			return builder.toString();
		}
	}
}
