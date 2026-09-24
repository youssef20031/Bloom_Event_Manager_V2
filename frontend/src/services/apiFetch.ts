import { isDriveNotConnected, readJsonBody } from "./problem";

export const TOKEN_KEY = "bloom.token";

export interface ApiFetchOptions {
  /** Status polling on the landing page must not bounce anonymous visitors to /login. */
  redirectOn401?: boolean;
}

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

/**
 * fetch() with the gateway's JWT attached. A 401 means the token is missing,
 * expired, or the gateway's signing key changed: drop it and go log in.
 *
 * Exception: a 401 whose problem body says DRIVE_NOT_AUTHENTICATED is about
 * Google Drive's OAuth token, not the user's session. The caller shows it.
 */
export async function apiFetch(
  input: RequestInfo | URL,
  init: RequestInit = {},
  options: ApiFetchOptions = {},
): Promise<Response> {
  const headers = new Headers(init.headers);
  const token = getToken();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(input, { ...init, headers });

  if (
    response.status === 401 &&
    options.redirectOn401 !== false &&
    !isDriveNotConnected(await readJsonBody(response.clone()))
  ) {
    try {
      localStorage.removeItem(TOKEN_KEY);
    } catch {
      /* storage unavailable — nothing to clear */
    }
    if (window.location.pathname !== "/login") {
      const next = encodeURIComponent(window.location.pathname + window.location.search);
      window.location.assign(`/login?next=${next}`);
    }
  }

  return response;
}
