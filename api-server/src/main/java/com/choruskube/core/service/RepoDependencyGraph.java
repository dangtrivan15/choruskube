package com.choruskube.core.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Cycle detection over a repo dependency map (dependent to its dependencies). */
final class RepoDependencyGraph {

    private enum Colour {
        GREY,
        BLACK
    }

    private RepoDependencyGraph() {}

    /**
     * Returns a cycle as a closed path (first id repeated last), or empty. Nodes and neighbours are
     * visited in sorted id order so the same graph always reports the same path.
     */
    static Optional<List<UUID>> findCycle(Map<UUID, Set<UUID>> dependsOn) {
        Map<UUID, Colour> colour = new HashMap<>();
        List<UUID> path = new ArrayList<>();
        for (UUID start : new TreeSet<>(dependsOn.keySet())) {
            Optional<List<UUID>> cycle = visit(start, dependsOn, colour, path);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        return Optional.empty();
    }

    private static Optional<List<UUID>> visit(
            UUID node, Map<UUID, Set<UUID>> dependsOn, Map<UUID, Colour> colour, List<UUID> path) {
        Colour seen = colour.get(node);
        if (seen == Colour.BLACK) {
            return Optional.empty();
        }
        if (seen == Colour.GREY) {
            List<UUID> cycle = new ArrayList<>(path.subList(path.indexOf(node), path.size()));
            cycle.add(node);
            return Optional.of(cycle);
        }
        colour.put(node, Colour.GREY);
        path.add(node);
        for (UUID next : new TreeSet<>(dependsOn.getOrDefault(node, Set.of()))) {
            Optional<List<UUID>> cycle = visit(next, dependsOn, colour, path);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        path.remove(path.size() - 1);
        colour.put(node, Colour.BLACK);
        return Optional.empty();
    }
}
