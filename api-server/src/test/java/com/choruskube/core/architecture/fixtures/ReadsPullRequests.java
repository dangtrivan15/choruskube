package com.choruskube.core.architecture.fixtures;

/** The interface {@link FakeGitHubClient} implements, for the guard self-test's interface case. */
public interface ReadsPullRequests {
    String fetch(int number);
}
