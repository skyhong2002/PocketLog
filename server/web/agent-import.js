/* Local-only parsing of a user-chosen file. Never reads tools, environment, paths or attachments. */
function parseAgentJsonl(text, source, now = Date.now()) {
  if (
    !["codex", "claude"].includes(source) ||
    new TextEncoder().encode(text).length > 524288
  )
    throw Error("請選擇 512 KiB 內的 Codex／Claude JSONL");
  const lines = text.split("\n");
  if (lines.length > 2000)
    throw Error("檔案最多 2000 行；請先選取較小的工作紀錄");
  const turns = [];
  for (let index = 0; index < lines.length; index++) {
    if (!lines[index].trim()) continue;
    const record = JSON.parse(lines[index]);
    let message;
    if (
      source === "codex" &&
      record.type === "response_item" &&
      record.payload?.type === "message"
    )
      message = record.payload;
    if (source === "claude" && ["user", "assistant"].includes(record.type))
      message = record.message;
    if (!message || !["user", "assistant"].includes(message.role)) continue;
    const at = Date.parse(record.timestamp);
    if (!Number.isFinite(at) || at <= now - 30 * 86400000 || at > now + 60000)
      continue;
    const content =
      typeof message.content === "string"
        ? message.content
        : Array.isArray(message.content)
          ? message.content
              .filter(
                (b) =>
                  ["text", "input_text", "output_text"].includes(b.type) &&
                  typeof b.text === "string",
              )
              .map((b) => b.text)
              .join("\n")
          : "";
    const clean = content
      .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/g, "")
      .trim();
    if (clean)
      turns.push({
        id: `turn-${index}`,
        at_ms: at,
        role: message.role,
        text: Array.from(clean).slice(0, 1000).join(""),
      });
  }
  return turns.sort((a, b) => a.at_ms - b.at_ms).slice(-40);
}
if (typeof module !== "undefined") module.exports = { parseAgentJsonl };
