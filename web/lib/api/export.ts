import { toApiError } from "./errors";
import { authFetch } from "./http";

/**
 * Downloads the personal data package (ADR-0034). The password is required
 * only for accounts that have a local one; the server enforces that rule.
 */
export async function downloadPersonalExport(password?: string): Promise<void> {
  const response = await authFetch("/api/export/me", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(password ? { password } : {}),
  });
  if (!response.ok) {
    throw await toApiError(response);
  }
  const blob = await response.blob();
  const disposition = response.headers.get("content-disposition") ?? "";
  const match = disposition.match(/filename="?([^";]+)"?/);
  const filename = match?.[1] ?? "agent-core-export.zip";
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
