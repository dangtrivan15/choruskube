package com.choruskube.core.architecture.fixtures;

import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * One nested class per call-site shape {@code ControlPlaneGitHubCallGuardTest} exercises — direct
 * call, lambda, method reference, subtype, interface, a pure method, and a mixed guarded
 * service's storage-only vs. network method. Never executed, only imported as bytecode.
 */
public final class Callers {

    private Callers() {}

    public static class Direct {
        public String use(FakeGitHubClient client) {
            return client.fetch(1);
        }
    }

    public static class Lambda {
        public void viaLambda(FakeGitHubClient client) {
            IntStream.range(0, 1).forEach(i -> client.fetch(i));
        }
    }

    public static class Reference {
        public IntFunction<String> viaReference(FakeGitHubClient client) {
            return client::fetch;
        }
    }

    public static class Subtype {
        static class SubClient extends FakeGitHubClient {}

        public String viaSubtype() {
            return new SubClient().fetch(1);
        }
    }

    public static class ViaInterface {
        public String viaInterface(ReadsPullRequests client) {
            return client.fetch(1);
        }
    }

    public static class Pure {
        public String parseOnly(FakeGitHubClient client) {
            return client.parse("raw");
        }
    }

    public static class Storage {
        public String storageOnly(FakeCredentialService service) {
            return service.storageOnly();
        }
    }

    public static class Mixed {
        public String useService(FakeCredentialService service) {
            return service.check();
        }
    }
}
