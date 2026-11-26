package com.choruskube.core.service;

import org.testcontainers.utility.DockerImageName;

/**
 * The SeaweedFS release every Testcontainers store test in this module pins, held in one place so
 * a bump touches one line here plus {@code docker-compose.yaml}, {@code docker-compose.e2e.yaml}
 * and {@code agent-images/choruskube-dind/preload-stack.txt}. Move it only once {@link
 * PresignedRequestStoreContractTest} passes at the new tag.
 */
final class PinnedObjectStore {

    static final DockerImageName IMAGE = DockerImageName.parse("chrislusf/seaweedfs:4.54");

    private PinnedObjectStore() {}
}
