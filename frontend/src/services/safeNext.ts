/** Where we land after login when there is no usable `next`. */
export const DEFAULT_LANDING = "/intake";

/**
 * Resolves the `?next=` login parameter to a path we are willing to navigate to.
 *
 * `next` reaches us from the URL bar, so it is attacker-controlled: anyone can
 * send a victim a link to /login?next=<anything>. Checking only for a leading
 * "/" is not enough — "//evil.example" and "/\evil.example" both start with a
 * slash and both resolve to a different origin once the browser normalises
 * them. Parsing against our own origin and demanding the result still points at
 * that origin closes the whole class rather than the two spellings we happened
 * to think of.
 */
export function safeNextPath(next: string | null | undefined): string {
  if (!next) {
    return DEFAULT_LANDING;
  }

  // Every `next` we mint comes from location.pathname, so it starts with
  // exactly one slash. Anything else -- a relative segment, a scheme, a
  // protocol-relative "//" -- did not come from us, so refuse it before
  // resolution rather than reasoning about what it resolves to.
  if (!next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) {
    return DEFAULT_LANDING;
  }

  try {
    const resolved = new URL(next, window.location.origin);
    if (resolved.origin !== window.location.origin) {
      return DEFAULT_LANDING;
    }
    // Re-serialise from the parsed pieces so the caller never sees the raw
    // input, and drop any fragment.
    const path = `${resolved.pathname}${resolved.search}`;
    return path.startsWith("/") && !path.startsWith("//") ? path : DEFAULT_LANDING;
  } catch {
    // Not a parseable URL or reference — not something to navigate to.
    return DEFAULT_LANDING;
  }
}
