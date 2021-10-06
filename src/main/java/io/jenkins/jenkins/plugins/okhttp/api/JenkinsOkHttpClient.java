package io.jenkins.jenkins.plugins.okhttp.api;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.ProxyConfiguration;
import jenkins.model.Jenkins;
import okhttp3.Authenticator;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * This class allows to take into consideration if Jenkins is running behind a proxy or not and return a proper {@link OkHttpClient}
 * client.
 */
public class JenkinsOkHttpClient {

    private static final Logger LOGGER = Logger.getLogger(JenkinsOkHttpClient.class.getName());

    /**
     * Override to enable insecure handling of TLS connections.
     */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Allow runtime modification")
    @Restricted(NoExternalUse.class) // no direct linking against this field please
    private static boolean acceptAnyCertificate = Boolean.getBoolean(JenkinsOkHttpClient.class.getName() + ".acceptAnyCertificate");

    private JenkinsOkHttpClient() {
    }

    /**
     * Generates a new builder for the given base client.
     * It applies the current {@link Jenkins#proxy} configuration.
     *
     * @return a builder to configure the client
     */
    public static OkHttpClient.Builder newClientBuilder(OkHttpClient httpClient) {
        OkHttpClient.Builder reBuild = httpClient.newBuilder();
        if (Jenkins.get().proxy != null) {
            final ProxyConfiguration proxy = Jenkins.get().proxy;
            Proxy proxyServer = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy.name, proxy.port));
            if (proxy.getUserName() != null) {
                Authenticator proxyAuthenticator = new Authenticator() {
                    @Override public Request authenticate(Route route, Response response) throws IOException {
                        String credential = Credentials.basic(proxy.getUserName(), proxy.getPassword());
                        return response.request().newBuilder()
                                       .header("Proxy-Authorization", credential)
                                       .build();
                    }
                };
                reBuild.proxyAuthenticator(proxyAuthenticator);
            }

            // TODO: https://github.com/square/okhttp/issues/3787
            // not sure if this is correct, needs manual testing
            if (proxy.name.startsWith("https")) {
                reBuild.socketFactory(SSLSocketFactory.getDefault());
            }
            ProxySelector proxySelector = new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    final List<Proxy> proxies = new ArrayList<>(1);
                    String host = uri.getHost();
                    for (Pattern p : proxy.getNoProxyHostPatterns()) {
                        if (p.matcher(host).matches()) {
                            proxies.add(Proxy.NO_PROXY);
                            return proxies;
                        }
                    }
                    proxies.add(proxyServer);
                    return proxies;
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                    LOGGER.log(Level.WARNING, "Proxy connection failed", ioe);
                }
            };
            reBuild.proxySelector(proxySelector);
        } else {
            reBuild.proxy(Proxy.NO_PROXY);
        }

        if (acceptAnyCertificate) {
            try {
                applyInsecureConfiguration(reBuild);
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                LOGGER.log(Level.WARNING, "Cannot apply insecure trust all certs configuration to okhttp", e);
            }
        }
        return reBuild;
    }

    /**
     * Updates non-proxy-hosts on the given client (returns a new builder object as a rebuild of the given one).
     * @param httpClient the base client
     * @param nonProxyHosts list of non proxy hosts patterns
     * @return the builder for the new client
     */
    public static OkHttpClient.Builder withNonProxyHosts(OkHttpClient httpClient, List<String> nonProxyHosts) {
        ProxySelector proxySelector = httpClient.proxySelector();
        List<Pattern> noProxyHostPatterns = ProxyConfiguration.getNoProxyHostPatterns(String.join(",", nonProxyHosts));
        OkHttpClient.Builder reBuild = httpClient.newBuilder();
        reBuild.proxySelector(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                final List<Proxy> proxies = new ArrayList<>(1);
                String host = uri.getHost();
                for (Pattern p : noProxyHostPatterns) {
                    if (p.matcher(host).matches()) {
                        proxies.add(Proxy.NO_PROXY);
                        return proxies;
                    }
                }
                return proxySelector.select(uri);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                LOGGER.log(Level.WARNING, "Proxy connection failed", ioe);
            }
        });
        return reBuild;
    }

    private static void applyInsecureConfiguration(OkHttpClient.Builder reBuild) throws NoSuchAlgorithmException, KeyManagementException {
        final TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                    @Override
                    public void checkServerTrusted(final X509Certificate[] chain, final String authType) {}
                    @Override
                    public void checkClientTrusted(final X509Certificate[] chain, final String authType) {}
                }
        };
        SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, trustAllCerts, new SecureRandom());
        reBuild.sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0]);

        HostnameVerifier hostnameVerifier = (hostname, session) -> {
            LOGGER.log(Level.FINE, "THIS IS INSECURE - Blindly trusting host for SSL session: " + hostname);
            return true;
        };
        reBuild.hostnameVerifier(hostnameVerifier);
    }
}