package io.jenkins.plugins.okhttp.api.internals;

import hudson.ProxyConfiguration;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@Restricted(NoExternalUse.class)
public class JenkinsProxySelector extends ProxySelector {
    private static final Logger LOGGER = Logger.getLogger(JenkinsProxySelector.class.getName());

    @Override
    public List<Proxy> select(URI uri) {

        ProxyConfiguration configuration = Jenkins.get().getProxy();
        if (configuration == null) {
            return Collections.singletonList(Proxy.NO_PROXY);
        }
        return List.of(configuration.createProxy(uri.getHost()));
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        LOGGER.log(Level.WARNING, "Proxy connection failed", ioe);
    }
}
