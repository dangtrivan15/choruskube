package com.choruskube.core.architecture.fixtures;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * A synthetic guarded GitHub client for {@code ControlPlaneGitHubCallGuardTest} — never executed,
 * only imported as bytecode. Its shape exercises every call-site case the guard must find or
 * ignore: a network method ({@link #fetch}) reached directly, through its own private helper,
 * through a subtype, and through the {@link ReadsPullRequests} interface it implements; a pure
 * method ({@link #parse}) with no network call; and an intra-class call ({@link #fetchAll}).
 */
public class FakeGitHubClient implements ReadsPullRequests {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public String fetch(int number) {
        return send(number);
    }

    private String send(int number) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/" + number))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String parse(String raw) {
        return raw.trim();
    }

    public String fetchAll() {
        return fetch(0);
    }
}
