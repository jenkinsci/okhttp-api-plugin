package jenkins.plugins.okhttp.api;

import hudson.ProxyConfiguration;
import io.jenkins.plugins.okhttp.api.JenkinsOkHttpClient;
import io.jenkins.plugins.okhttp.api.OkHttpFuture;
import io.jenkins.plugins.okhttp.api.internals.JenkinsProxyAuthenticator;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RealJenkinsRule;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.Assume.assumeTrue;

/**
 * Real end to end tests with a real Proxy (because wiremocks proxy is not quite real enough)
 */
public class ProxyITest {

    @Rule 
    public RealJenkinsRule rjr = new RealJenkinsRule().javaOptions("-Xmx256M").withLogger(JenkinsProxyAuthenticator.class, Level.FINEST);

    @Before
    public void checkDocker() {
        // run as a Before so that individual tests are marked as skipped
        DockerClientFactory dcf = DockerClientFactory.instance();
        assumeTrue("Tests require Linux container support", dcf.isDockerAvailable());
        // ci.jenkins.io windows agents have Docker installed but use windows containers not Linux containers.
        String osType = dcf.getInfo().getOsType();
        assumeTrue("Tests require a Linux container support but found " + osType, "linux".equalsIgnoreCase(osType));
    }

    @Test
    public void testPreEmptiveProxyAuth() throws Throwable {
        rjr.javaOptions("-Dplugins.okhttp-api.proxy.preemptive-auth-enabled=true");
        // we should not see any proxy authentication request (407)
        Matcher<String> squidLogMatcher = allOf(
                not(containsString("TCP_DENIED/407 CONNECT dns.google:443 - HIER_NONE/-")),
                containsString("TCP_TUNNEL/200 CONNECT dns.google:443 proxy-user HIER_DIRECT/dns.google"));
        makeHttpCallAndCheckLogs(squidLogMatcher);
    }

    @Test
    public void testChallengedProxyAuth() throws Throwable {
        // we should see a denied 407 followed by a connect
        Matcher<String> squidLogMatcher = stringContainsInOrder(
                "TCP_DENIED/407 CONNECT dns.google:443 - HIER_NONE/-",
                "TCP_TUNNEL/200 CONNECT dns.google:443 proxy-user HIER_DIRECT/dns.google");
        makeHttpCallAndCheckLogs(squidLogMatcher);
    }

    public void makeHttpCallAndCheckLogs(Matcher<String> squidLogMatcher) throws Throwable {
        try (GenericContainer<?> squid = createSquidContainer()) {
            squid.start();

            // uncomment the following to route squid container output for debugging
            // squid.followOutput(f -> System.err.print("squid " + f.getUtf8String()));

            // use google.dns as a test URL so we do not have to mess with docker networking 
            // to enable squid to access things on potentially different (local) networks.
            // google dns is highly available and is does not have internationization
            final String testURL = "https://dns.google/";

            // start Jenkins, make a single call via the proxy.
            rjr.startJenkins();
            rjr.runRemotely(ProxyITest::configureProxy, "localhost", squid.getMappedPort(3128), "proxy-user", "proxy-pass");
            String content = rjr.runRemotely(ProxyITest::sendWebRequest, testURL);
            // squid will only log when any persistent connections are closed
            rjr.stopJenkins();

            // Sanity check the request worked
            assertThat(content, containsString("Google Public DNS"));

            // now check the request was via squid with any expected auth challenge(s)
            String accessLog = squid.copyFileFromContainer("/var/log/squid/access.log", is -> new String(is.readAllBytes(), StandardCharsets.UTF_8));
            assertThat(accessLog, squidLogMatcher);
        } finally {
            rjr.stopJenkins();
        }
    }

    /** A Squid proxy wrapped in a GenericContainer */
    @SuppressWarnings({ "resource"})
    private static GenericContainer<?> createSquidContainer() {
        // Minimal Squid config: allow CONNECT to any port (WireMock uses dynamic ports).
        final String squidConf = """
                # custom log format to make checking access more stable (no times, bytes transferred or IP addresses)
                logformat custom %Ss/%03>Hs %rm %ru %[un %Sh/%<A
                # use stdio logging so that entries are not buffered but written immediately
                access_log stdio:/var/log/squid/access.log custom

                # setup authentication
                auth_param basic program /usr/lib/squid/basic_fake_auth
                auth_param basic realm MySuperSecureRealm
                acl authenticated proxy_auth REQUIRED

                # allow access from non local networks as the container may be on a non NIC (e.g. windows)
                acl localnet src all

                # allow authenicates users from anywhere and deny anything else
                http_access allow authenticated localhost
                http_access allow authenticated localnet
                http_access deny all

                http_port 3128
                coredump_dir /var/spool/squid
                """;

        return new GenericContainer<>("ubuntu/squid:latest")
                .withExposedPorts(3128)
                .withCopyToContainer(Transferable.of(squidConf), "/etc/squid/squid.conf")
                .waitingFor(Wait.forListeningPort());
    }


    public static void configureProxy(JenkinsRule r, String host, Integer port, String user, String password) throws Throwable {
        r.jenkins.setProxy(new ProxyConfiguration(host,port, user, password));
    }

    public static String sendWebRequest(JenkinsRule r, String testUrl) throws Throwable {
        final OkHttpClient client = JenkinsOkHttpClient.newClientBuilder(new OkHttpClient()).build();

        final Request request = new Request.Builder()
                .get()
                .url(testUrl)
                .build();

        try (Response response = new OkHttpFuture<>(client.newCall(request), OkHttpFuture.GET_RESPONSE).get();
                ResponseBody body = response.body();
                BufferedSource source = body.source()) {
            return source.readString(StandardCharsets.UTF_8);
        }
    }
}
