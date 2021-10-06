package io.jenkins.jenkins.plugins.okhttp.api;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides an asynchronous-like mechanism to OkHttp to get a response in an asynchronous way.
 *
 * @param <T> Type returned when a {@link Response} is get.
 */
public class OkHttpFuture<T> extends CompletableFuture<T> {
    private static final Logger LOGGER = Logger.getLogger(OkHttpFuture.class.getName());
    private final Call call;

    public OkHttpFuture(final Call call, final ResponseParser<T> parser) {
        this.call = call;

        this.call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                try {
                    parser.onFailure(call, e);
                } catch (Exception ex) {
                    // catch all to unblock the future
                    LOGGER.log(Level.WARNING, "Error processing ResponseParser#onFailure", e);
                }
                OkHttpFuture.this.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    T parsed = parser.onResponse(call, response);
                    OkHttpFuture.this.complete(parsed);
                } catch (Exception e) {
                    // catch all to unblock the future
                    OkHttpFuture.this.completeExceptionally(e);
                }
            }
        });
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        this.call.cancel();
        return super.cancel(mayInterruptIfRunning);
    }
}
