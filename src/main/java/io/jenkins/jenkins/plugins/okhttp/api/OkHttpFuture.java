package io.jenkins.jenkins.plugins.okhttp.api;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Provides an asynchronous-like mechanism to OkHttp to get a response in an asynchronous way.
 *
 * @param <T> Type returned when a {@link Response} is get.
 */
public class OkHttpFuture<T> extends CompletableFuture<T> {

    /**
     * A converter returning the response itself.
     */
    public static final ResponseConverter<Response> GET_RESPONSE = (call, response) -> response;

    private final Call call;

    /**
     * Creates a future with no converter.
     *
     * @param call The call that must be executed. Can not be {@code null}
     */
    public OkHttpFuture(final Call call) {
        this(call, null);
    }

    public OkHttpFuture(final Call call, final ResponseConverter<T> converter) {
        if (call == null) {
            throw new IllegalArgumentException("Can not create an OkHttpFuture with a null call");
        }

        this.call = call;

        this.call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                OkHttpFuture.this.completeExceptionally(new OkHttpFutureException(call, e));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (converter != null) {
                    try {
                        T parsed = converter.onResponse(call, response);
                        OkHttpFuture.this.complete(parsed);
                    } catch (Exception e) {
                        // catch all to unblock the future
                        OkHttpFuture.this.completeExceptionally(new OkHttpFutureException(call, e));
                    }
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
