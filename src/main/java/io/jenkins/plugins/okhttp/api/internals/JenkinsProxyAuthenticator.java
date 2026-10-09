package io.jenkins.plugins.okhttp.api.internals;

import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.ProxyConfiguration;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import okhttp3.Authenticator;
import okhttp3.Challenge;
import okhttp3.Credentials;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Restricted(NoExternalUse.class)
public class JenkinsProxyAuthenticator implements Authenticator {

    private static final Logger LOGGER = Logger.getLogger(JenkinsProxyAuthenticator.class.getName());

    /** Flag to enable pre-emtpive authentication for a proxy when doing HTTPS tunnelling (CONNECT) */
    private static /* almost final */boolean usePreemptiveAuth = SystemProperties.getBoolean("plugins.okhttp-api.proxy.preemptive-auth-enabled", false);

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
        if (response.code() == HttpURLConnection.HTTP_PROXY_AUTH) {
            boolean wasPreemptiveAuth = false;
            for (Challenge challenge : response.challenges()) {
                if ("Basic".equalsIgnoreCase(challenge.scheme())) {
                    LOGGER.finest("Attempting authentication against proxy");
                    final String credential = Credentials.basic(proxy.getUserName(), Secret.toString(proxy.getSecretPassword()), challenge.charset());
                    return response.request().newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build();
                }
                // the OkHttp-Preemptive header is a pseudo header sent by OKHttp and is always in this case
                // so there is no need check case insensitively.
                if ("OkHttp-Preemptive".equals(challenge.scheme())) {
                    wasPreemptiveAuth = true;
                    if (usePreemptiveAuth) {
                        LOGGER.finest("Attempting preemptive authentication against proxy");
                        final String credential = Credentials.basic(proxy.getUserName(), Secret.toString(proxy.getSecretPassword()), challenge.charset());
                        return response.request().newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build();
                    }
                }
            }
            if (!wasPreemptiveAuth) {
                // we did not handle the authentication request and it was not for pre-emptive auth
                // so we are not supporting the scheme that the proxy needs
                String schemes = response.challenges().stream().map(Challenge::scheme).collect(Collectors.joining(", "));
                LOGGER.warning("The proxy authentication scheme(s) supported by the Proxy do not support Basic.  Offered by the proxy are: " + schemes);
            }
        }
        return null;
    }

}
