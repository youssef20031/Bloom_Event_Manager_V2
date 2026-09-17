import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiFetch, TOKEN_KEY } from "./apiFetch";

describe("apiFetch", () => {
  const assign = vi.fn();

  beforeEach(() => {
    localStorage.clear();
    assign.mockReset();
    Object.defineProperty(window, "location", {
      value: { pathname: "/advisor", search: "", assign },
      writable: true,
    });
    globalThis.fetch = vi.fn();
  });

  it("attaches the stored token as a Bearer header", async () => {
    localStorage.setItem(TOKEN_KEY, "tok");
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(new Response("{}", { status: 200 }));

    await apiFetch("/api/v1/rag/chat/sessions");

    const init = (fetch as ReturnType<typeof vi.fn>).mock.calls[0][1] as RequestInit;
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer tok");
  });

  it("sends no Authorization header when logged out", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(new Response("{}", { status: 200 }));

    await apiFetch("/api/v1/rag/sync/status");

    const init = (fetch as ReturnType<typeof vi.fn>).mock.calls[0][1] as RequestInit;
    expect(new Headers(init.headers).has("Authorization")).toBe(false);
  });

  it("clears the token and redirects to login on 401", async () => {
    localStorage.setItem(TOKEN_KEY, "expired");
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(new Response("{}", { status: 401 }));

    const response = await apiFetch("/api/v1/rag/chat");

    expect(response.status).toBe(401);
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(assign).toHaveBeenCalledWith("/login?next=%2Fadvisor");
  });

  it("does not redirect when opted out (used by status polling)", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(new Response("{}", { status: 401 }));

    await apiFetch("/api/v1/rag/sync/status", {}, { redirectOn401: false });

    expect(assign).not.toHaveBeenCalled();
  });
});
