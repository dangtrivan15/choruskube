package com.choruskube.core.architecture.fixtures;

/** The interface {@link FakeGitHubClient} implements, for the guard self-test's interface case. */
public interface ReadsPullRequests extends ReadsByNumber {
    @Override
    String fetch(int number);
}
