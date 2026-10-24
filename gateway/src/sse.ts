/** Minimal text/event-stream parser for the run_code callback (contract v1 §8). */
export interface SseFrame {
  event: string;
  data: string;
}

/**
 * Incremental parser: chunks may split lines, frames or UTF-8 code points.
 * Comment/heartbeat lines are ignored; multi-line data is joined with "\n".
 */
export class SseFrameParser {
  private readonly decoder = new TextDecoder();
  private buffer = "";
  private event = "";
  private data: string[] = [];

  push(chunk: Uint8Array): SseFrame[] {
    this.buffer += this.decoder.decode(chunk, { stream: true });
    return this.drain();
  }

  end(): SseFrame[] {
    this.buffer += this.decoder.decode();
    const frames = this.drain();
    if (this.buffer.length > 0) {
      this.consumeLine(this.buffer);
      this.buffer = "";
    }
    if (this.data.length > 0) {
      frames.push(this.dispatch());
    }
    return frames;
  }

  private drain(): SseFrame[] {
    const frames: SseFrame[] = [];
    let newline = this.buffer.indexOf("\n");
    while (newline >= 0) {
      let line = this.buffer.slice(0, newline);
      this.buffer = this.buffer.slice(newline + 1);
      if (line.endsWith("\r")) line = line.slice(0, -1);
      const frame = this.consumeLine(line);
      if (frame) frames.push(frame);
      newline = this.buffer.indexOf("\n");
    }
    return frames;
  }

  private consumeLine(line: string): SseFrame | null {
    if (line === "") {
      return this.data.length > 0 ? this.dispatch() : null;
    }
    if (line.startsWith(":")) {
      return null;
    }
    const colon = line.indexOf(":");
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "event") {
      this.event = value;
    } else if (field === "data") {
      this.data.push(value);
    }
    return null;
  }

  private dispatch(): SseFrame {
    const frame: SseFrame = {
      event: this.event || "message",
      data: this.data.join("\n"),
    };
    this.event = "";
    this.data = [];
    return frame;
  }
}
