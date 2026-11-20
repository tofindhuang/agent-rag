/**
 * Theme preference (ADR-0012).
 *
 * The choice is a device preference stored in localStorage. `system` means
 * "do not set data-theme" so the CSS prefers-color-scheme fallback decides;
 * explicit choices pin `data-theme` on <html>.
 */
export type ThemePreference = "system" | "light" | "dark";

export const THEME_STORAGE_KEY = "agentcore.theme";

export function isThemePreference(value: unknown): value is ThemePreference {
  return value === "system" || value === "light" || value === "dark";
}

export function readThemePreference(): ThemePreference {
  try {
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY);
    return isThemePreference(stored) ? stored : "system";
  } catch {
    return "system";
  }
}

/** Applies the preference to <html>; called by the switch and the first frame. */
export function applyThemePreference(preference: ThemePreference): void {
  const root = document.documentElement;
  if (preference === "system") {
    delete root.dataset.theme;
  } else {
    root.dataset.theme = preference;
  }
}

export function saveThemePreference(preference: ThemePreference): void {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, preference);
  } catch {
    // Storage can be unavailable (private mode); the in-session choice still applies.
  }
}
