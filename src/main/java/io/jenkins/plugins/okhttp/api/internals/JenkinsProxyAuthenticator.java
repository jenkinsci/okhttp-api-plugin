package io.jenkins.plugins.okhttp.api.internals;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.ProxyConfiguration;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import okhttp3.Authenticator;
import okhttp3.Credentials;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import java.io.IOException;
import java.util.Locale;
import java.util.logging.Logger;

@Restricted(NoExternalUse.class)
public class JenkinsProxyAuthenticator implements Authenticator {
    private static final Logger LOGGER = Logger.getLogger(JenkinsProxyAuthenticator.class.getName());

    @Nullable
    @Override
    @SuppressFBWarnings(value = "NP_METHOD_PARAMETER_TIGHTENS_ANNOTATION", justification = "Prefer SpotBugs @Nullable")
    public Request authenticate(@Nullable Route route, Response response) throws IOException {

        ProxyConfiguration proxy = Jenkins.get().getProxy();
        if (proxy == null || proxy.getUserName() == null) {
            return null;
        }

        if (response.request().header("Proxy-Authorization") != null) {
            // If the header is not null, it means an authentication attempt failed. So giving up
            return null;
        }

        final String proxyAuthenticateHeader = response.header("Proxy-Authenticate");
        if (proxyAuthenticateHeader != null) {
            if (isAuthenticationSchemeSupported(proxyAuthenticateHeader)) {

                final String credential = Credentials.basic(proxy.getUserName(), Secret.toString(proxy.getSecretPassword()));
                return response.request().newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build();
            } else {
                LOGGER.warning("The proxy authentication scheme is not supported: " + proxyAuthenticateHeader);
            }
        }

        return null;
    }

    private boolean isAuthenticationSchemeSupported(@NonNull final String proxyAuthenticateHeader) {
        return proxyAuthenticateHeader.toLowerCase(Locale.ROOT).startsWith("basic");
    }
}
