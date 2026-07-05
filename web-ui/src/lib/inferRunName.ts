import type { InputSchemaField } from "./types";

const URL_PATTERN = /^https?:\/\//i;
const MAX_NAME_LENGTH = 30;
const TRUNCATION_MARKER = "…";

/**
 * Infers a run name from the current input values.
 *
 * Heuristic:
 * 1. Pick the first non-URL text/textarea input value that is not empty.
 * 2. For textarea fields, use only the first line.
 * 3. Truncate to MAX_NAME_LENGTH characters; if truncated, the last char is
 *    replaced with "…" to signal the original was longer. Mirrors the
 *    backend's RunService.trimName behaviour.
 * 4. Return empty string if no suitable value is found.
 */
export function inferRunName(
  schema: InputSchemaField[],
  inputValues: Record<string, string>,
): string {
  for (const field of schema) {
    if (field.type !== "text" && field.type !== "textarea") continue;

    const raw = inputValues[field.name];
    if (!raw) continue;

    // For textarea fields, extract only the first line before trimming
    let value: string;
    if (field.type === "textarea") {
      value = raw.split("\n")[0].trim();
    } else {
      value = raw.trim();
    }

    if (!value) continue;
    if (URL_PATTERN.test(value)) continue;

    return value.length > MAX_NAME_LENGTH
      ? value.slice(0, MAX_NAME_LENGTH - TRUNCATION_MARKER.length) + TRUNCATION_MARKER
      : value;
  }

  return "";
}
