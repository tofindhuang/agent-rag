export interface SseFrame {
  id?: string;
  event?: string;
  data?: string;
}

/**
 * Parses a text/event-stream body into frames. Heartbeat comments (": ping")
 * are ignored; multi-line data is joined with newlines per the SSE spec.
 */
export async function* parseSse(body: ReadableStream<Uint8Array>): AsyncGenerator<SseFrame> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");

      let boundary: number;
      while ((boundary = buffer.indexOf("\n\n")) >= 0) {
        const raw = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        const frame = parseFrame(raw);
        if (frame) yield frame;
      }
    }

    buffer += decoder.decode().replace(/\r\n/g, "\n");
    const frame = parseFrame(buffer);
    if (frame) yield frame;
  } finally {
    reader.releaseLock();
  }
}

function parseFrame(raw: string): SseFrame | null {
  const frame: SseFrame = {};
  const data: string[] = [];

  for (const line of raw.split("\n")) {
    if (line.startsWith(":")) continue;

    const separator = line.indexOf(":");
    const field = separator === -1 ? line : line.slice(0, separator);
    let value = separator === -1 ? "" : line.slice(separator + 1);
    if (value.startsWith(" ")) value = value.slice(1);

    if (field === "id") frame.id = value;
    else if (field === "event") frame.event = value;
    else if (field === "data") data.push(value);
  }

  if (data.length > 0) frame.data = data.join("\n");
  return frame.id || frame.event || frame.data ? frame : null;
}
