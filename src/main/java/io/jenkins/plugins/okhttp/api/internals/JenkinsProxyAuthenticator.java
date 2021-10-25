package io.jenkins.plugins.okhttp.api.internals;

import com.burgstaller.okhttp.digest.DigestAuthenticator;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.ProxyConfiguration;
import hudson.util.Secret;
import okhttp3.Authenticator;
import okhttp3.Credentials;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

import java.io.IOException;
import java.util.logging.Logger;

public class JenkinsProxyAuthenticator implements Authenticator {
    private static final Logger LOGGER = Logger.getLogger(JenkinsProxyAuthenticator.class.getName());
    private final ProxyConfiguration proxy;

    public JenkinsProxyAuthenticator(final ProxyConfiguration proxy) {
        this.proxy = proxy;
    }

    @Nullable
    @Override
    public Request authenticate(@Nullable Route route, Response response) throws IOException {
        if (response.request().header("Proxy-Authorization") != null) {
            // If the header is not null, it means an authentication attempt failed. So giving up
            return null;
        }

        final String proxyAuthenticateHeader = response.header("Proxy-Authenticate");
        if (proxyAuthenticateHeader != null) {
            if (proxyAuthenticateHeader.startsWith("Basic")) {
                final String credential = Credentials.basic(proxy.getUserName(), Secret.toString(proxy.getSecretPassword()));
                return response.request().newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build();
            } else if (proxyAuthenticateHeader.startsWith("Digest")) {
                final com.burgstaller.okhttp.digest.Credentials credentials = new com.burgstaller.okhttp.digest.Credentials(proxy.getUserName(), Secret.toString(proxy.getSecretPassword()));
                return new DigestAuthenticator(credentials).authenticate(route, response);
            } else {
                LOGGER.warning("The proxy authentication scheme is not supported: " + proxyAuthenticateHeader);
            }
        }

        return null;
    }
}