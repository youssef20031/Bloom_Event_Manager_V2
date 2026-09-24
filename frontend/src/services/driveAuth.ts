import { apiFetch } from "./apiFetch";
import { readJsonBody } from "./problem";

/**
 * Gateway path that returns `{ status, authUrl }`, where `authUrl` is Google's
 * consent page. It needs the JWT, so it must be fetched, never opened in a tab.
 */
export const DRIVE_REAUTH_PATH = "/api/v1/rag/sync/auth?force=true";

/**
 * Start Google Drive's OAuth flow in this tab. Same-tab navigation is not
 * subject to popup blocking; the callback lands back on /settings.
 * Resolves false when no consent URL could be obtained.
 */
export async function connectGoogleDrive(): Promise<boolean> {
  const response = await apiFetch(DRIVE_REAUTH_PATH);
  if (!response.ok) return false;
  const body = await readJsonBody(response);
  const authUrl = (body as { authUrl?: unknown } | null)?.authUrl;
  if (typeof authUrl !== "string" || !authUrl) return false;
  window.location.assign(authUrl);
  return true;
}
