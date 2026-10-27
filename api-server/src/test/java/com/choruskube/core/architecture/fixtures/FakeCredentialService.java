package com.choruskube.core.architecture.fixtures;

/**
 * A synthetic mixed guarded service: {@link #storageOnly()} has no network call and must stay
 * free of the guarded set, while {@link #check()} reaches {@link FakeGitHubClient#fetch} and so
 * is itself guarded — the fixture for "a mixed class's method that reaches another guarded class
 * is itself guarded."
 */
public class FakeCredentialService {

    private final FakeGitHubClient client = new FakeGitHubClient();

    public String storageOnly() {
        return "no network here";
    }

    public String check() {
        return client.fetch(1);
    }
}
