import { beforeEach, describe, expect, it, vi } from "vitest";
import { TOKEN_KEY } from "./apiFetch";
import { connectGoogleDrive, DRIVE_REAUTH_PATH } from "./driveAuth";

describe("connectGoogleDrive", () => {
  const assign = vi.fn();
  const consentUrl = "https://accounts.google.com/o/oauth2/auth?client_id=x";

  beforeEach(() => {
    localStorage.clear();
    assign.mockReset();
    Object.defineProperty(window, "location", {
      value: { pathname: "/intake", search: "", assign },
      writable: true,
    });
    globalThis.fetch = vi.fn();
  });

  it("asks the gateway for the consent URL with the JWT, then navigates this tab to Google", async () => {
    localStorage.setItem(TOKEN_KEY, "tok");
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      new Response(JSON.stringify({ status: "success", authUrl: consentUrl }), { status: 200 }),
    );

    await expect(connectGoogleDrive()).resolves.toBe(true);

    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(DRIVE_REAUTH_PATH);
    expect(new Headers((init as RequestInit).headers).get("Authorization")).toBe("Bearer tok");
    expect(assign).toHaveBeenCalledWith(consentUrl);
  });

  it("never navigates to the gateway's own Bearer-protected path", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      new Response(JSON.stringify({ status: "success", authUrl: consentUrl }), { status: 200 }),
    );

    await connectGoogleDrive();

    expect(assign).not.toHaveBeenCalledWith(DRIVE_REAUTH_PATH);
  });

  it("reports failure without navigating when no consent URL comes back", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(new Response("{}", { status: 200 }));
    await expect(connectGoogleDrive()).resolves.toBe(false);

    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(new Response("oops", { status: 503 }));
    await expect(connectGoogleDrive()).resolves.toBe(false);

    expect(assign).not.toHaveBeenCalled();
  });
});
