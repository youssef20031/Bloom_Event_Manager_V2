/**
 * Format XML Tags Utility
 * Bloom Event Management Platform
 *
 * Pre-processes AI responses that contain XML-like tags and converts
 * them into clean Markdown that ReactMarkdown can render natively.
 *
 * Handles edge cases like truncated/missing closing tags.
 */

/**
 * Converts XML-like event proposal tags into Markdown.
 * Non-XML content passes through unchanged.
 */
export function formatXmlTags(content: string): string {
  if (!content) return content;

  let result = content;

  // ── Event Title ──────────────────────────────────────
  result = result.replace(
    /<event_title>([\s\S]*?)<\/event_title>/gi,
    (_match, title: string) => `# ${title.trim()}\n\n`
  );

  // ── Timeline with Slots ──────────────────────────────
  // Match <timeline>...</timeline>, <timeline>...</  (truncated), or <timeline>...(no close)
  const timelinePatterns = [
    /<timeline>([\s\S]*?)<\/timeline>/gi,
    /<timeline>([\s\S]*?)<\/\s*[\n\r]/gi,
    /<timeline>([\s\S]*?)<\/\s*$/gi,
  ];

  for (const pattern of timelinePatterns) {
    const beforeReplace = result;
    result = result.replace(pattern, (_match, timelineContent: string) => {
      return buildTimelineMarkdown(timelineContent);
    });
    // If we matched and replaced, stop trying other patterns
    if (result !== beforeReplace) break;
  }

  // Fallback: <timeline> with no closing tag at all but has slots inside
  if (/<timeline>/i.test(result)) {
    result = result.replace(
      /<timeline>([\s\S]*)$/gi,
      (_match, timelineContent: string) => {
        if (/<slot>/i.test(timelineContent)) {
          return buildTimelineMarkdown(timelineContent);
        }
        return _match;
      }
    );
  }

  // ── Plan Section ─────────────────────────────────────
  result = result.replace(
    /<plan>([\s\S]*?)(?:<\/plan>|$)/gi,
    (_match, planContent: string) => {
      // Clean inner XML tags within plan content
      let cleaned = cleanInnerXmlTags(planContent);
      return `\n\n---\n\n${cleaned.trim()}\n\n`;
    }
  );

  // ── Generic XML tag cleanup ──────────────────────────
  // Remove any remaining event-specific XML tags the AI might use
  result = cleanInnerXmlTags(result);

  // Clean up stray truncated closing tags like "</" on their own line
  result = result.replace(/^\s*<\/\s*$/gm, "");

  // Clean up excessive blank lines (3+ in a row → 2)
  result = result.replace(/\n{4,}/g, "\n\n\n");

  return result;
}

/**
 * Builds a Markdown table from timeline slot content.
 */
function buildTimelineMarkdown(timelineContent: string): string {
  const slots = parseSlots(timelineContent);
  if (slots.length === 0) return "";

  let table = "\n\n| Time | Activity | Details |\n";
  table += "| :--- | :--- | :--- |\n";

  for (const slot of slots) {
    table += `| ${slot.time} | ${slot.activity} | ${slot.details} |\n`;
  }

  return table + "\n\n";
}

/**
 * Cleans known inner XML tags that the AI may produce,
 * converting them to markdown equivalents or removing them.
 */
function cleanInnerXmlTags(content: string): string {
  let result = content;

  // Convert known heading-like tags to markdown headings
  const headingTags = ["title", "theme", "section_title", "heading"];
  for (const tag of headingTags) {
    result = result.replace(
      new RegExp(`<${tag}>([\\s\\S]*?)<\\/${tag}>`, "gi"),
      (_m, text: string) => `## ${text.trim()}\n\n`
    );
  }

  // Convert known block-like tags to bold labels
  const labelTags = [
    "target_audience", "core_problem", "objective", "goal",
    "venue", "budget", "duration", "location", "reason",
    "description", "summary", "notes", "requirements"
  ];
  for (const tag of labelTags) {
    const label = tag.replace(/_/g, " ").replace(/\b\w/g, c => c.toUpperCase());
    result = result.replace(
      new RegExp(`<${tag}>([\\s\\S]*?)<\\/${tag}>`, "gi"),
      (_m, text: string) => `**${label}:** ${text.trim()}\n\n`
    );
  }

  // Remove any remaining unknown XML-like tags (but keep their content)
  // This catches tags like <slot>, <time>, <activity>, <details>, etc.
  result = result.replace(/<\/?[a-z_][a-z0-9_]*\s*>/gi, "");

  return result;
}

interface SlotData {
  time: string;
  activity: string;
  details: string;
}

/**
 * Parses <slot> elements from the timeline body.
 */
function parseSlots(timelineContent: string): SlotData[] {
  const slots: SlotData[] = [];
  const slotRegex = /<slot>([\s\S]*?)<\/slot>/gi;
  let slotMatch;

  while ((slotMatch = slotRegex.exec(timelineContent)) !== null) {
    const slotBody = slotMatch[1];

    const time = extractTag(slotBody, "time");
    const activity = extractTag(slotBody, "activity");
    const details = extractTag(slotBody, "details");

    slots.push({
      time: time || "—",
      activity: activity || "—",
      details: details || "—",
    });
  }

  return slots;
}

/**
 * Extracts inner text of a simple XML tag.
 */
function extractTag(body: string, tagName: string): string {
  const regex = new RegExp(`<${tagName}>([\\s\\S]*?)<\\/${tagName}>`, "i");
  const match = body.match(regex);
  return match ? match[1].trim() : "";
}
