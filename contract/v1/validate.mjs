#!/usr/bin/env node
/**
 * Validates contract v1 fixtures without starting the gateway or any external
 * service. Run in CI before the module test suites.
 */

import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(fileURLToPath(new URL(".", import.meta.url)), "fixtures");
const allowedEvents = new Set([
  "run_started",
  "thinking_delta",
  "text_delta",
  "tool_call_delta",
  "tool_call",
  "tool_progress",
  "tool_result",
  "message_end",
  "compaction",
  "retry",
  "run_settled",
]);

let count = 0;
let failed = false;
const names = new Set();

function fail(path, message) {
  failed = true;
  console.error(`FAIL ${path}: ${message}`);
}

for (const kind of ["gateway", "consumer"]) {
  const directory = join(root, kind);
  for (const file of readdirSync(directory).sort()) {
    if (!file.endsWith(".json")) continue;
    const path = join(directory, file);
    let fixture;
    try {
      fixture = JSON.parse(readFileSync(path, "utf8"));
    } catch (error) {
      fail(path, `invalid JSON: ${error.message}`);
      continue;
    }

    count += 1;
    if (fixture.kind !== kind) fail(path, `kind mismatch: ${fixture.kind}`);
    if (fixture.contractVersion !== "1") fail(path, "contractVersion must be 1");
    if (typeof fixture.name !== "string" || fixture.name.length === 0) fail(path, "missing name");
    if (names.has(fixture.name)) fail(path, `duplicate name: ${fixture.name}`);
    names.add(fixture.name);

    if (kind === "gateway") {
      const run = fixture.input?.run;
      if (!run?.runId || !run?.turnId || !run?.sessionId) fail(path, "input.run missing");

      const frames = fixture.expected?.frames;
      if (!Array.isArray(frames) || frames.length === 0) {
        fail(path, "expected.frames missing");
        continue;
      }
      frames.forEach((frame, index) => {
        if (frame.seq !== index + 1) fail(path, `seq ${frame.seq} at index ${index}`);
        if (!allowedEvents.has(frame.event)) fail(path, `unknown event: ${frame.event}`);
        if (!frame.data || typeof frame.data !== "object") fail(path, `${frame.event} missing data`);
      });
      if (frames[0]?.event !== "run_started") fail(path, "first frame must be run_started");
      if (frames.at(-1)?.event !== "run_settled") fail(path, "last frame must be run_settled");
      if (frames.filter((frame) => frame.event === "run_settled").length !== 1) {
        fail(path, "run_settled must appear exactly once");
      }

      const started = frames[0]?.data;
      for (const key of ["runId", "turnId", "sessionId"]) {
        if (started?.[key] !== run?.[key]) fail(path, `run_started.${key} mismatch`);
      }
      if (started?.contractVersion !== fixture.contractVersion) {
        fail(path, "run_started.contractVersion mismatch");
      }
      const terminal = frames.at(-1)?.data;
      if (terminal?.turnId !== run?.turnId) fail(path, "run_settled.turnId mismatch");

      let expectedSeqInTurn = 1;
      for (const frame of frames.filter((item) => item.event === "message_end")) {
        if (frame.data.seqInTurn !== expectedSeqInTurn) {
          fail(path, `message_end.seqInTurn ${frame.data.seqInTurn}, expected ${expectedSeqInTurn}`);
        }
        expectedSeqInTurn += 1;
      }
      for (const frame of frames.filter((item) => item.event === "tool_result")) {
        if (typeof frame.data.truncated !== "boolean") fail(path, "tool_result.truncated missing");
        if (typeof frame.data.content !== "string") fail(path, "tool_result.content missing");
      }
    } else {
      const frames = fixture.input?.frames;
      if (!Array.isArray(frames) || frames.length === 0) fail(path, "input.frames missing");
      if (frames?.some((frame) => frame.event === "run_settled")) {
        fail(path, "consumer input must not contain run_settled");
      }
      const terminal = fixture.expected?.syntheticTerminal;
      if (terminal?.status !== "error") fail(path, "syntheticTerminal.status must be error");
      if (terminal?.error?.code !== "STREAM_INTERRUPTED") {
        fail(path, "syntheticTerminal.error.code must be STREAM_INTERRUPTED");
      }
      if (fixture.expected?.assistant?.status !== "partial") {
        fail(path, "expected.assistant.status must be partial");
      }
    }
  }
}

console.log(`validated ${count} contract fixtures`);
if (failed) process.exit(1);
