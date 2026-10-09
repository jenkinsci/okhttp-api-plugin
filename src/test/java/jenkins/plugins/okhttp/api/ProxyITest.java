package jenkins.plugins.okhttp.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import hudson.ProxyConfiguration;
import hudson.logging.LogRecorder;
import hudson.logging.LogRecorder.Target;
import io.jenkins.plugins.okhttp.api.JenkinsOkHttpClient;
import io.jenkins.plugins.okhttp.api.OkHttpFuture;
import io.jenkins.plugins.okhttp.api.internals.JenkinsProxyAuthenticator;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RealJenkinsRule;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Real(ish) end to end tests with a real Proxy (because wiremocks proxy is not quite real enough)
 */
public class ProxyITest {

    @Rule 
    public RealJenkinsRule rjr = new RealJenkinsRule().javaOptions("-Xmx256M").withLogger(JenkinsProxyAuthenticator.class, Level.FINEST);

    @Test
    public void testPreEmptiveProxyAuth() throws Throwable {
        rjr.javaOptions("-Dplugins.okhttp-api.proxy.preemptive-auth-enabled=true");
        makeHttpCallAndCheckLogIs("Attempting preemptive authentication against proxy");
    }

    @Test
    public void testChallengedProxyAuth() throws Throwable {
        makeHttpCallAndCheckLogIs("Attempting authentication against proxy");
    }

    public void makeHttpCallAndCheckLogIs(String expectedMessage ) throws Throwable {

        // Bound to 0.0.0.0 so the Squid container can reach it via the Docker bridge gateway.
        final WireMockServer httpsServer = new WireMockServer(options()
                .bindAddress("0.0.0.0")
                .dynamicPort()
                .dynamicHttpsPort());
        httpsServer.stubFor(get("/hello").willReturn(ok("Hello World!")));
        httpsServer.start();

        // Expose the HTTPS port to containers: Testcontainers sets up host.testcontainers.internal
        // in each container's /etc/hosts pointing to the Docker bridge gateway so Squid can reach
        // the WireMock server bound to 0.0.0.0 on the host.
        Testcontainers.exposeHostPorts(httpsServer.httpsPort());


        try (GenericContainer<?> squid = createSquidContainer()) {
            squid.start();

            // host.testcontainers.internal resolves to the Docker bridge gateway inside the Squid
            // container, which is where WireMock (bound to 0.0.0.0) is reachable.
            final String helloWorldURL = "https://host.testcontainers.internal:" + httpsServer.httpsPort() + "/hello";

            // start Jenkins, make a single call via the proxy.
            rjr.startJenkins();
            rjr.runRemotely(new ConfigureProxyStep(), "127.0.0.1", squid.getMappedPort(3128), "proxy-user", "proxy-pass");
            rjr.runRemotely(new ConfigreLogRecorder(), "proxy-auth-log", JenkinsProxyAuthenticator.class, Level.FINEST);
            String content = rjr.runRemotely(new WebRequest(), helloWorldURL);

            // Sanity check
            assertThat(content, is("Hello World!"));
            // and there was only a single request
            httpsServer.verify(WireMock.exactly(1), WireMock.getRequestedFor(WireMock.anyUrl()));

            // Check if we sent credentials when challenged or pre-emptive credentials
            // and that there are not any other unexpected log messages.
            ArrayList<LogRecord> logs = rjr.runRemotely(new GetLogRecords(), "proxy-auth-log");
            assertThat(logs, Matchers.hasSize(1));
            assertThat(logs.get(0).getMessage(), is(expectedMessage));
        } finally {
            httpsServer.stop();
            rjr.stopJenkins();
        }
    }


    /** TLS Socket factory that trusts all TLS certs 
     * @throws KeyManagementException 
     * @throws NoSuchAlgorithmException */ 
    private static SSLSocketFactory socketFactory() throws KeyManagementException, NoSuchAlgorithmException {
        final SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null,  new TrustManager[]{blindTustManager()}, new java.security.SecureRandom());
        return sslContext.getSocketFactory();
    }

    /** TrustManager that trusts all certificates */
    private static X509TrustManager blindTustManager() {
        return new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
    }

    /** A Squid proxy wrapped in a GenericContainer */
    @SuppressWarnings({ "resource"})
    private static GenericContainer<?> createSquidContainer() {
        // Minimal Squid config: allow CONNECT to any port (WireMock uses dynamic ports).
        final String squidConf = """
                auth_param basic program /usr/lib/squid/basic_fake_auth
                auth_param basic realm MySuperSecureRealm
                acl localnet src 0.0.0.0/0
                acl SSL_ports port 1-65535
                acl Safe_ports port 1-65535
                acl CONNECT method CONNECT
                acl authenticated proxy_auth REQUIRED
                http_access deny !authenticated
                http_access allow CONNECT SSL_ports
                http_access allow localnet
                http_access deny all
                http_port 3128
                coredump_dir /var/spool/squid
                """;

        return new GenericContainer<>("ubuntu/squid:latest")
                .withExposedPorts(3128)
                .withCopyToContainer(Transferable.of(squidConf), "/etc/squid/squid.conf")
                .waitingFor(Wait.forListeningPort());
    }


    static class ConfigureProxyStep implements RealJenkinsRule.StepWithFourArgs<String, Integer, String, String> {

        private static final long serialVersionUID = 1L;

        @Override
        public void run(JenkinsRule r, String host, Integer port, String user, String password) throws Throwable {
            r.jenkins.setProxy(new ProxyConfiguration(host,port, user, password));
        }
    }

    static class ConfigreLogRecorder implements RealJenkinsRule.StepWithThreeArgs<String, Class<?>, Level> {

        private static final long serialVersionUID = 1L;

        @Override
        public void run(JenkinsRule r, String loggerName, Class<?> clazz, Level level) throws Throwable {
            List<LogRecorder> recorders = r.jenkins.getLog().getRecorders();
            LogRecorder lr = new LogRecorder(loggerName);
            List<Target> loggers = new ArrayList<>();
            loggers.add(new Target(clazz.getName(), level));
            lr.setLoggers(loggers);
            recorders.add(lr);
            lr.save();
            return;
        }
    }

    static class GetLogRecords implements RealJenkinsRule.StepWithReturnAndOneArg<ArrayList<LogRecord>, String> {

        private static final long serialVersionUID = 1L;

        @Override
        public ArrayList<LogRecord> run(JenkinsRule r, String loggerName) throws Throwable {
            return new ArrayList<>(r.jenkins.getLog().getLogRecorder(loggerName).getLogRecords());
        }
    }

    static class WebRequest implements RealJenkinsRule.StepWithReturnAndOneArg<String, String> {

        private static final long serialVersionUID = 1L;

        @Override
        public String run(JenkinsRule r, String testUrl) throws Throwable {
            final OkHttpClient client = JenkinsOkHttpClient.newClientBuilder(new OkHttpClient())
                    .sslSocketFactory(socketFactory(), blindTustManager())
                    .hostnameVerifier((hostname, session) -> true)
                    .build();

            final Request request = new Request.Builder()
                    .get()
                    .url(testUrl)
                    .build();

            try (Response response = new OkHttpFuture<>(client.newCall(request), OkHttpFuture.GET_RESPONSE)
                    .exceptionally(e -> null)
                    .get(); 
                    ResponseBody body = response.body(); 
                    BufferedSource source = body.source()) {
                return source.readString(StandardCharsets.UTF_8);
            }
        }
    }
}
