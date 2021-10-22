package io.jenkins.jenkins.plugins.okhttp.api;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import okhttp3.Call;
import okhttp3.Response;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Response parser.
 * To be passed to {@link OkHttpFuture} to handle the HTTP response.
 *
 * @param <T> the result type of the parsing
 */
public interface ResponseParser<T> {
    Logger LOGGER = Logger.getLogger(ResponseParser.class.getName());

    /**
     * Called when the HTTP request finishes successfully and there is a response to process.
     *
     * @param call     the {@link Call} object that originated the HTTP request.
     * @param response HTTP response
     * @return the parsed object (nullable)
     */
    @CheckForNull
    T onResponse(Call call, Response response) throws IOException;

    /**
     * Called when the HTTP request fails.
     *
     * @param call the {@link Call} object that originated the HTTP request
     * @param e    the exception causing the failure
     */
    default void onFailure(Call call, IOException e) {
        LOGGER.log(Level.WARNING, "A failure occurred", e);
    }
}

