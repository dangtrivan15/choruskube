package com.choruskube.core.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shared DFS cycle check over candidate-local {@code key} edges, used by both {@link
 * RoadmapCandidatesArtifactResolver} (drop a cyclic edge at gate-display time) and {@link
 * RoadmapProposalValidator} (reject one at agent-validate time) so the two can never disagree on
 * what counts as a cycle.
 */
final class CandidateDependencyCycles {

    private CandidateDependencyCycles() {}

    /**
     * {@code true} iff adding the edge {@code blocking -> blocked} to the already-accepted {@code
     * declaredEdges} graph would close a cycle — i.e. {@code blocked} can already reach {@code
     * blocking} by following accepted edges forward.
     */
    static boolean wouldCreateCycle(Map<String, Set<String>> declaredEdges, String blocking, String blocked) {
        Deque<String> stack = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        stack.push(blocked);
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (current.equals(blocking)) {
                return true;
            }
            if (!visited.add(current)) {
                continue;
            }
            Set<String> next = declaredEdges.get(current);
            if (next != null) {
                stack.addAll(next);
            }
        }
        return false;
    }
}
