package io.jenkins.plugins.okhttp.api;

import okhttp3.Call;

public class OkHttpFutureException extends RuntimeException {
    private final Call call;

    public OkHttpFutureException(final Call call, final Throwable cause) {
        super(cause);
        this.call = call;
    }

    /**
     * The call responsible of this exception.
     *
     * @return The call.
     */
    public Call getCall() {
        return call;
    }
}
