package io.jenkins.plugins.okhttp.api.internals;

import hudson.ProxyConfiguration;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

@Restricted(NoExternalUse.class)
public class JenkinsProxySelector extends ProxySelector {
    private static final Logger LOGGER = Logger.getLogger(JenkinsProxySelector.class.getName());
    private final ProxyConfiguration configuration;
    private final Proxy proxy;

    public JenkinsProxySelector(final ProxyConfiguration configuration) {
        this.configuration = configuration;
        this.proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(configuration.name, configuration.port));
    }

    @Override
    public List<Proxy> select(URI uri) {
        final String host = uri.getHost();
        for (Pattern p : configuration.getNoProxyHostPatterns()) {
            if (p.matcher(host).matches()) {
                return Collections.singletonList(Proxy.NO_PROXY);
            }
        }
        return Collections.singletonList(proxy);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        LOGGER.log(Level.WARNING, "Proxy connection failed", ioe);
    }
}
