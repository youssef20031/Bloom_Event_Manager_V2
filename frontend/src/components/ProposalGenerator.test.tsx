import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { TOKEN_KEY } from "../services/apiFetch";
import ProposalGenerator from "./ProposalGenerator";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const problem = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify({ status, ...body }), {
    status,
    headers: { "Content-Type": "application/problem+json" },
  });

const driveNotConnected = () =>
  problem(401, {
    errorCode: "DRIVE_NOT_AUTHENTICATED",
    title: "Google Drive Not Authenticated",
    detail: "Google Drive not authenticated.",
    error: "Google Drive not authenticated.",
    message: "Google Drive not authenticated.",
    authUrl: "/api/v1/rag/sync/auth?force=true",
  });

describe("ProposalGenerator", () => {
  const assign = vi.fn();
  let container: HTMLDivElement;
  let root: Root;

  const renderPage = async () => {
    await act(async () => {
      root.render(
        <MemoryRouter>
          <ProposalGenerator />
        </MemoryRouter>,
      );
    });
    // let the mount fetch and its JSON parse settle
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
  };

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem(TOKEN_KEY, "valid");
    assign.mockReset();
    Object.defineProperty(window, "location", {
      value: { pathname: "/proposals", search: "", assign },
      writable: true,
    });
    globalThis.fetch = vi.fn();
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
  });

  it("explains that Drive is not connected, links to Settings, and keeps the user logged in", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(driveNotConnected());

    await renderPage();

    expect(container.textContent).toContain("Google Drive is not connected");
    const settingsLink = container.querySelector('a[href="/settings"]');
    expect(settingsLink).not.toBeNull();
    expect(localStorage.getItem(TOKEN_KEY)).toBe("valid");
    expect(assign).not.toHaveBeenCalled();
  });

  it("shows the ProblemDetail detail of a 503", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      problem(503, {
        errorCode: "SERVICE_UNAVAILABLE",
        title: "Service Unavailable",
        detail: "Storage service is temporarily unavailable.",
      }),
    );

    await renderPage();

    expect(container.textContent).toContain("Storage service is temporarily unavailable.");
    expect(container.querySelector('a[href="/settings"]')).toBeNull();
  });
});
