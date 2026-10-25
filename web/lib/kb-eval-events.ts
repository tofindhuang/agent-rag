/**
 * Cross-panel signal: labels can be created from the passage viewer as well
 * as the retrieval panel, so the panel must refetch when the viewer mutates
 * them. A plain window event keeps the two components decoupled.
 */
export const KB_EVAL_LABELS_CHANGED = "kb:eval-labels-changed";

export function notifyKbEvalLabelsChanged(): void {
  window.dispatchEvent(new Event(KB_EVAL_LABELS_CHANGED));
}
