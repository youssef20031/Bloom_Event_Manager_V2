import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { TOKEN_KEY } from "../services/apiFetch";
import { DRIVE_REAUTH_PATH } from "../services/driveAuth";
import EventIntakeForm from "./EventIntakeForm";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const settle = () =>
  act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });

const json = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/problem+json" } });

const driveNotConnected = () =>
  json(401, {
    status: 401,
    errorCode: "DRIVE_NOT_AUTHENTICATED",
    title: "Google Drive Not Authenticated",
    detail: "Google Drive not authenticated.",
    error: "Google Drive not authenticated.",
    message: "Google Drive not authenticated.",
    authUrl: DRIVE_REAUTH_PATH,
  });

describe("EventIntakeForm submit errors", () => {
  const assign = vi.fn();
  const open = vi.fn();
  let container: HTMLDivElement;
  let root: Root;

  const renderAndSubmit = async () => {
    await act(async () => {
      root.render(<EventIntakeForm />);
    });
    const form = container.querySelector("form")!;
    await act(async () => {
      form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    });
    await settle();
  };

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem(TOKEN_KEY, "valid");
    localStorage.setItem("bloom-event-intake-draft", JSON.stringify({ companyName: "Acme" }));
    assign.mockReset();
    open.mockReset();
    Object.defineProperty(window, "location", {
      value: { pathname: "/intake", search: "", assign },
      writable: true,
    });
    window.open = open;
    globalThis.fetch = vi.fn();
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
  });

  it("offers a Connect Google Drive action instead of opening the Bearer-protected endpoint in a tab", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(driveNotConnected());

    await renderAndSubmit();

    expect(open).not.toHaveBeenCalled();
    expect(assign).not.toHaveBeenCalled();
    expect(localStorage.getItem(TOKEN_KEY)).toBe("valid");
    expect(container.textContent).toContain("Google Drive is not connected");

    const connect = Array.from(container.querySelectorAll("button")).find(
      (b) => b.textContent === "Connect Google Drive",
    );
    expect(connect).toBeDefined();
    expect(connect!.type).toBe("button");

    const consentUrl = "https://accounts.google.com/o/oauth2/auth?client_id=x";
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ status: "success", authUrl: consentUrl }), { status: 200 }),
    );
    await act(async () => {
      connect!.click();
    });
    await settle();

    expect((fetch as ReturnType<typeof vi.fn>).mock.calls[1][0]).toBe(DRIVE_REAUTH_PATH);
    expect(assign).toHaveBeenCalledWith(consentUrl);
    expect(open).not.toHaveBeenCalled();
  });

  it("shows the ProblemDetail detail of a 503", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      json(503, {
        status: 503,
        errorCode: "SERVICE_UNAVAILABLE",
        title: "Service Unavailable",
        detail: "Storage service is temporarily unavailable.",
      }),
    );

    await renderAndSubmit();

    expect(container.textContent).toContain("Storage service is temporarily unavailable.");
    const connect = Array.from(container.querySelectorAll("button")).find(
      (b) => b.textContent === "Connect Google Drive",
    );
    expect(connect).toBeUndefined();
  });
});
