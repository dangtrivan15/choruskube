package com.choruskube.core.executor;

/**
 * The registry-mirror/build-cache/dependency-proxy endpoint set a DinD-enabled workload's init
 * container and agent process route image pulls, build-cache traffic, and package-manager
 * downloads through.
 *
 * @param mirror       host:port a container runtime pulls images through
 * @param buildCache   host:port the build-cache push/pull path uses
 * @param depProxyBase base URL package-manager proxies (Go/npm) are rooted at
 */
public record RegistryMirror(String mirror, String buildCache, String depProxyBase) {}
