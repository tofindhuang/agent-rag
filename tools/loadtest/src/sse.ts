/**
 * Minimal SSE parser for the gateway contract: frames are separated by a
 * blank line, `data:` lines are joined with `\n`, and `: ping` comments are
 * ignored. Frames are yielded as soon as they are complete so callers can
 * measure first-token latency while the stream is still open.
 */
export interface SseFrame {
  id?: string;
  event: string;
  data: string;
}

export async function* parseSse(
  stream: AsyncIterable<string | Uint8Array>,
): AsyncGenerator<SseFrame> {
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  let id: string | undefined;
  let event = "";
  const dataLines: string[] = [];

  const dispatch = (): SseFrame | null => {
    if (dataLines.length === 0) {
      id = undefined;
      event = "";
      return null;
    }
    const frame: SseFrame = {
      ...(id !== undefined ? { id } : {}),
      event: event || "message",
      data: dataLines.join("\n"),
    };
    id = undefined;
    event = "";
    dataLines.length = 0;
    return frame;
  };

  const consumeLine = (line: string): SseFrame | null => {
    if (line === "") return dispatch();
    if (line.startsWith(":")) return null;
    const colon = line.indexOf(":");
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "id") id = value;
    else if (field === "event") event = value;
    else if (field === "data") dataLines.push(value);
    return null;
  };

  for await (const chunk of stream) {
    buffer += typeof chunk === "string" ? chunk : decoder.decode(chunk, { stream: true });
    let newline = buffer.indexOf("\n");
    while (newline !== -1) {
      let line = buffer.slice(0, newline);
      buffer = buffer.slice(newline + 1);
      if (line.endsWith("\r")) line = line.slice(0, -1);
      const frame = consumeLine(line);
      if (frame) yield frame;
      newline = buffer.indexOf("\n");
    }
  }

  buffer += decoder.decode();
  if (buffer.length > 0) {
    const line = buffer.endsWith("\r") ? buffer.slice(0, -1) : buffer;
    const frame = consumeLine(line);
    if (frame) yield frame;
  }
  const trailing = dispatch();
  if (trailing) yield trailing;
}

export function parseSseData<T>(frame: SseFrame): T {
  return JSON.parse(frame.data) as T;
}
