import { useMemo } from "react";
import { useAuth } from "@/components/AuthProvider";
import { useExtensions } from "@/ExtensionsContext";
import { staticCommands, type Command } from "@/lib/commands";

/**
 * Returns the visible commands for the current user: core's static commands plus any
 * injected by an extension entrypoint (AppExtensions.commands), filtered by each command's
 * optional `visibleWhen` predicate.
 *
 * The memo depends on the whole `auth` object on purpose: AuthProvider rebuilds
 * its `value` literal on every render, so this memo recomputes every render.
 * That is acceptable — the registry has ~10 entries and `.filter` is O(n).
 * The alternative (listing individual auth fields) is brittle: the moment a new
 * command's `visibleWhen` reads a different field, the memo would silently go
 * stale. Re-evaluate this trade-off if the registry grows past ~100 entries.
 */
export function useVisibleCommands(): Command[] {
  const auth = useAuth();
  const { commands: injected } = useExtensions();
  return useMemo(
    () =>
      [...staticCommands, ...(injected ?? [])].filter(
        (c) => !c.visibleWhen || c.visibleWhen(auth)
      ),
    [auth, injected]
  );
}
