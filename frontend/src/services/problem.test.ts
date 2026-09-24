import { describe, expect, it } from "vitest";
import { DRIVE_NOT_CONNECTED_MESSAGE, errorMessage, isDriveNotConnected, readJsonBody } from "./problem";

describe("errorMessage", () => {
  it("prefers the ProblemDetail detail over every other member", () => {
    const body = {
      errorCode: "SERVICE_UNAVAILABLE",
      title: "Service Unavailable",
      detail: "The AI service is temporarily unavailable.",
      message: "legacy message",
      error: "legacy error",
      status: 503,
    };
    expect(errorMessage(body, "fallback")).toBe("The AI service is temporarily unavailable.");
  });

  it("falls back through message, then error, then title", () => {
    expect(errorMessage({ message: "m", error: "e", title: "t" }, "fb")).toBe("m");
    expect(errorMessage({ error: "e", title: "t" }, "fb")).toBe("e");
    expect(errorMessage({ title: "t" }, "fb")).toBe("t");
  });

  it("skips blank and non-string members", () => {
    expect(errorMessage({ detail: "  ", message: 42, error: "e" }, "fb")).toBe("e");
  });

  it("returns the fallback for an empty, null or non-object body", () => {
    expect(errorMessage({}, "fb")).toBe("fb");
    expect(errorMessage(null, "fb")).toBe("fb");
    expect(errorMessage(undefined, "fb")).toBe("fb");
    expect(errorMessage("<html>502</html>", "fb")).toBe("fb");
  });

  it("tells the user to connect Drive in Settings when Drive is not connected", () => {
    const body = {
      errorCode: "DRIVE_NOT_AUTHENTICATED",
      title: "Google Drive Not Authenticated",
      detail: "Google Drive not authenticated.",
      error: "Google Drive not authenticated.",
      authUrl: "/api/v1/rag/sync/auth?force=true",
      status: 401,
    };
    expect(errorMessage(body, "fb")).toBe(DRIVE_NOT_CONNECTED_MESSAGE);
    expect(DRIVE_NOT_CONNECTED_MESSAGE).toMatch(/Google Drive is not connected/);
    expect(DRIVE_NOT_CONNECTED_MESSAGE).toMatch(/Settings/);
  });
});

describe("isDriveNotConnected", () => {
  it("recognises the DRIVE_NOT_AUTHENTICATED error code", () => {
    expect(isDriveNotConnected({ errorCode: "DRIVE_NOT_AUTHENTICATED" })).toBe(true);
  });

  it("rejects every other body", () => {
    expect(isDriveNotConnected({ errorCode: "UNAUTHENTICATED" })).toBe(false);
    expect(isDriveNotConnected({})).toBe(false);
    expect(isDriveNotConnected(null)).toBe(false);
    expect(isDriveNotConnected("DRIVE_NOT_AUTHENTICATED")).toBe(false);
  });
});

describe("readJsonBody", () => {
  it("parses a JSON body", async () => {
    const body = await readJsonBody(new Response('{"errorCode":"X"}', { status: 400 }));
    expect(body).toEqual({ errorCode: "X" });
  });

  it("returns null for a non-JSON or empty body instead of throwing", async () => {
    expect(await readJsonBody(new Response("<html>Bad Gateway</html>", { status: 502 }))).toBeNull();
    expect(await readJsonBody(new Response(null, { status: 401 }))).toBeNull();
  });
});
