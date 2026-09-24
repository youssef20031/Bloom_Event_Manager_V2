import { describe, expect, it } from "vitest";
import { safeNextPath } from "./safeNext";

describe("safeNextPath", () => {
  it("keeps a same-origin path with its query string", () => {
    expect(safeNextPath("/advisor")).toBe("/advisor");
    expect(safeNextPath("/proposals?company=acme")).toBe("/proposals?company=acme");
  });

  it("falls back when there is no next param", () => {
    expect(safeNextPath(null)).toBe("/intake");
    expect(safeNextPath("")).toBe("/intake");
  });

  it("rejects a protocol-relative URL, which the browser resolves off-origin", () => {
    expect(safeNextPath("//evil.example")).toBe("/intake");
    expect(safeNextPath("//evil.example/steal")).toBe("/intake");
  });

  it("rejects a backslash-prefixed path, which some browsers normalise to //", () => {
    expect(safeNextPath("/\\evil.example")).toBe("/intake");
    expect(safeNextPath("\\\\evil.example")).toBe("/intake");
  });

  it("rejects an absolute URL to another origin", () => {
    expect(safeNextPath("https://evil.example/steal")).toBe("/intake");
    expect(safeNextPath("javascript:alert(1)")).toBe("/intake");
  });

  it("rejects anything not starting with a single slash", () => {
    expect(safeNextPath("intake")).toBe("/intake");
    expect(safeNextPath("../etc")).toBe("/intake");
  });
});
