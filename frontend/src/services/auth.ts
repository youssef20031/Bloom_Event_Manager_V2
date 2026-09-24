import { TOKEN_KEY, getToken } from "./apiFetch";

export interface LoginResult {
  token: string;
  tokenType: string;
  expiresIn: number;
}

export async function login(username: string, password: string): Promise<void> {
  const response = await fetch("/api/v1/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  if (!response.ok) {
    throw new Error(response.status === 401 ? "Invalid username or password." : `Login failed (${response.status}).`);
  }
  const result = (await response.json()) as LoginResult;
  localStorage.setItem(TOKEN_KEY, result.token);
}

export function logout(): void {
  localStorage.removeItem(TOKEN_KEY);
}

export function isLoggedIn(): boolean {
  return getToken() !== null;
}
