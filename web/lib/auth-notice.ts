/**
 * One-shot message handed to the login page after an account-security action
 * redirects the user out (password change / log out everywhere).
 *
 * sessionStorage survives the client-side route change without putting text in
 * the URL, and the value is consumed exactly once.
 */
const KEY = "agentcore.authNotice";

export function setAuthNotice(message: string): void {
  try {
    window.sessionStorage.setItem(KEY, message);
  } catch {
    // Storage can be unavailable (private mode); the redirect still works.
  }
}

export function takeAuthNotice(): string | null {
  try {
    const message = window.sessionStorage.getItem(KEY);
    if (message !== null) window.sessionStorage.removeItem(KEY);
    return message;
  } catch {
    return null;
  }
}
