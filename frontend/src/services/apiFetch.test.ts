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

  describe("a 401 meaning Google Drive is not connected", () => {
    const driveProblem = {
      type: "about:blank",
      title: "Google Drive Not Authenticated",
      status: 401,
      detail: "Google Drive not authenticated.",
      errorCode: "DRIVE_NOT_AUTHENTICATED",
      error: "Google Drive not authenticated.",
      message: "Google Drive not authenticated.",
      authUrl: "/api/v1/rag/sync/auth?force=true",
    };
    const driveResponse = () =>
      new Response(JSON.stringify(driveProblem), {
        status: 401,
        headers: { "Content-Type": "application/problem+json" },
      });

    it("keeps the token and stays on the page", async () => {
      localStorage.setItem(TOKEN_KEY, "valid");
      (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(driveResponse());

      const response = await apiFetch("/api/v1/rag/proposals/companies");

      expect(response.status).toBe(401);
      expect(localStorage.getItem(TOKEN_KEY)).toBe("valid");
      expect(assign).not.toHaveBeenCalled();
    });

    it("leaves the body readable by the caller", async () => {
      (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(driveResponse());

      const response = await apiFetch("/api/v1/rag/event-intake", { method: "POST" });

      expect(await response.json()).toEqual(driveProblem);
    });
  });

  it("still logs out on the gateway's UNAUTHENTICATED problem", async () => {
    localStorage.setItem(TOKEN_KEY, "expired");
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      new Response(
        JSON.stringify({ errorCode: "UNAUTHENTICATED", title: "Authentication Required", status: 401 }),
        { status: 401, headers: { "Content-Type": "application/problem+json" } },
      ),
    );

    await apiFetch("/api/v1/rag/chat");

    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(assign).toHaveBeenCalledWith("/login?next=%2Fadvisor");
  });

  it("treats a 401 with an empty or non-JSON body as a dead token", async () => {
    localStorage.setItem(TOKEN_KEY, "expired");
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(new Response(null, { status: 401 }));

    await apiFetch("/api/v1/rag/chat");

    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(assign).toHaveBeenCalledTimes(1);

    localStorage.setItem(TOKEN_KEY, "expired");
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response("<html>Unauthorized</html>", { status: 401, headers: { "Content-Type": "text/html" } }),
    );

    await apiFetch("/api/v1/rag/chat");

    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(assign).toHaveBeenCalledTimes(2);
  });
});
