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

  if (response.status === 401 && options.redirectOn401 !== false) {
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
