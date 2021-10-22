package io.jenkins.jenkins.plugins.okhttp.api;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import okhttp3.Call;
import okhttp3.Response;

import java.io.IOException;

/**
 * <p>A response converter allows to convert a {@link Response} to another object.</p>
 * <p>To be passed to {@link OkHttpFuture} to handle the HTTP response.</p>
 *
 * @param <T> the result type of the conversion
 */
public interface ResponseConverter<T> {
    /**
     * Called when the HTTP request finishes successfully and there is a response to process.
     *
     * @param call     the {@link Call} object that originated the HTTP request.
     * @param response HTTP response
     * @return the parsed object (nullable)
     */
    @CheckForNull
    T onResponse(Call call, Response response) throws IOException;
}

