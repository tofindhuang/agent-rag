/**
 * Admission barrier for drain (ADR-0024).
 *
 * A request must enter the gate before it can start a session operation. Drain
 * flips `draining` and then waits for the in-flight count to reach zero, so a
 * request that passed the check cannot be cut off by process exit.
 */
export class DrainGate {
  private draining = false;
  private inflight = 0;
  private waiters: Array<() => void> = [];

  isDraining(): boolean {
    return this.draining;
  }

  inflightCount(): number {
    return this.inflight;
  }

  /** Atomically admit a request, or reject it once drain has started. */
  tryEnter(): boolean {
    if (this.draining) return false;
    this.inflight += 1;
    return true;
  }

  leave(): void {
    if (this.inflight === 0) return;
    this.inflight -= 1;
    if (this.inflight === 0) {
      const waiters = this.waiters;
      this.waiters = [];
      for (const resolve of waiters) resolve();
    }
  }

  beginDrain(): void {
    this.draining = true;
  }

  /** Resolves true when the gate is empty, false when the deadline expires. */
  waitForZero(timeoutMs: number): Promise<boolean> {
    if (this.inflight === 0) return Promise.resolve(true);
    return new Promise((resolve) => {
      let settled = false;
      const finish = (value: boolean): void => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve(value);
      };
      const timer = setTimeout(() => finish(false), Math.max(0, timeoutMs));
      this.waiters.push(() => finish(true));
    });
  }
}
