package jenkins.plugins.okhttp.api;

import io.jenkins.jenkins.plugins.okhttp.api.JenkinsOkHttpClient;
import io.jenkins.jenkins.plugins.okhttp.api.OkHttpFuture;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

public class ClientTest {

    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    @Test
    public void testHappyPath() throws ExecutionException, InterruptedException {
        final OkHttpClient client = JenkinsOkHttpClient.newClientBuilder(new OkHttpClient())
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        final Request request = new Request.Builder().get().url("https://jenkins.io").build();

        final OkHttpFuture<Response> future = new OkHttpFuture<>(client.newCall(request), (call, response) -> response);
        final Response response = future.get();

        assertTrue("Request to " + request.url() + " isn't successful", response.isSuccessful());
    }

    @Test
    public void testInexistingUrl() throws ExecutionException, InterruptedException {
        final OkHttpClient client = JenkinsOkHttpClient.newClientBuilder(new OkHttpClient())
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        final Request request = new Request.Builder().get().url("https://jenkins.io-does-not-exist").build();

        Optional<Response> futureResponse = new OkHttpFuture<>(client.newCall(request), (call, response) -> Optional.of(response))
                .exceptionally(ex -> Optional.empty())
                .get();
        assertFalse("A response was sent while none was expected due to calling a non existing URL", futureResponse.isPresent());
    }

    @Test
    public void testSimulatingListeners() throws ExecutionException, InterruptedException {
        final OkHttpClient client = JenkinsOkHttpClient.newClientBuilder(new OkHttpClient())
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        final Request request = new Request.Builder().get().url("https://jenkins.io").build();

        CompletableFuture<Optional<Response>> futureResponse = new OkHttpFuture<>(client.newCall(request), (call, response) -> Optional.of(response))
                .exceptionally(ex -> Optional.empty());

        final CompletableFuture<Optional<String>> listener1 = futureResponse.thenApply(response -> Optional.of("Listener 1 OK"));
        final CompletableFuture<Optional<String>> listener2 = futureResponse.thenApply(response -> Optional.of("Listener 2 OK"));

        assertEquals("Listener 1 OK", listener1.get().get());
        assertEquals("Listener 2 OK", listener2.get().get());
    }
}
