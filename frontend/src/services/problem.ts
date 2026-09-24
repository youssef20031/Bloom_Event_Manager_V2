/**
 * Helpers for the backend's error bodies: RFC 9457 ProblemDetails
 * (`application/problem+json`) carrying an `errorCode`, plus the V1-era
 * `error` / `message` members some endpoints still add.
 */

/** Storage says Google Drive has no OAuth token; unrelated to the user's JWT. */
export const DRIVE_NOT_AUTHENTICATED = "DRIVE_NOT_AUTHENTICATED";

export const DRIVE_NOT_CONNECTED_MESSAGE =
  "Google Drive is not connected. Connect it in Settings, then try again.";

function member(body: unknown, key: string): unknown {
  return typeof body === "object" && body !== null ? (body as Record<string, unknown>)[key] : undefined;
}

export function isDriveNotConnected(body: unknown): boolean {
  return member(body, "errorCode") === DRIVE_NOT_AUTHENTICATED;
}

/**
 * The human-readable text of an error body: `detail`, then `message`, `error`,
 * `title`, then `fallback`. A Drive-not-connected problem gets a message that
 * says how to fix it.
 */
export function errorMessage(body: unknown, fallback: string): string {
  if (isDriveNotConnected(body)) return DRIVE_NOT_CONNECTED_MESSAGE;
  for (const key of ["detail", "message", "error", "title"]) {
    const value = member(body, key);
    if (typeof value === "string" && value.trim()) return value;
  }
  return fallback;
}

/** The parsed JSON body, or null when it is empty or not JSON (e.g. an nginx 502 page). */
export async function readJsonBody(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    return null;
  }
}
