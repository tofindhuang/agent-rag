import { lookup } from "node:dns/promises";
import { isIP } from "node:net";

/** Raised when a user-supplied endpoint violates the outbound policy. */
export class EndpointBlockedError extends Error {
  constructor(message = "endpoint is not allowed") {
    super(message);
    this.name = "EndpointBlockedError";
  }
}

/** Raised when a hostname cannot be resolved before the policy check. */
export class EndpointResolutionError extends Error {
  constructor(message = "cannot resolve provider host") {
    super(message);
    this.name = "EndpointResolutionError";
  }
}

export interface ResolvedAddress {
  address: string;
  family: number;
}

export type Resolver = (hostname: string) => Promise<ResolvedAddress[]>;

export interface EndpointPolicy {
  /** Allow private networks, loopback and plain HTTP (self-hosted deployments). */
  allowPrivate: boolean;
  /** Injectable for tests. */
  resolve?: Resolver;
}

const ALWAYS_BLOCKED_V4: ReadonlyArray<readonly [string, number]> = [
  ["0.0.0.0", 8],
  ["169.254.0.0", 16], // link-local, includes most cloud metadata services
  ["192.0.2.0", 24],
  ["198.51.100.0", 24],
  ["203.0.113.0", 24],
  ["224.0.0.0", 4],
  ["240.0.0.0", 4],
];

const PRIVATE_V4: ReadonlyArray<readonly [string, number]> = [
  ["10.0.0.0", 8],
  ["100.64.0.0", 10],
  ["127.0.0.0", 8],
  ["172.16.0.0", 12],
  ["192.168.0.0", 16],
  ["198.18.0.0", 15],
];

/** Metadata services that live outside link-local ranges (never allowed). */
const METADATA_V4 = new Set(["169.254.169.254", "100.100.100.200"]);

/**
 * Validates a user-supplied provider URL before any request is made:
 * scheme, credentials, DNS results and private/metadata ranges.
 */
export async function assertSafeEndpoint(
  rawUrl: string,
  policy: EndpointPolicy,
): Promise<URL> {
  let url: URL;
  try {
    url = new URL(rawUrl);
  } catch {
    throw new EndpointBlockedError("invalid provider URL");
  }

  if (url.username.length > 0 || url.password.length > 0) {
    throw new EndpointBlockedError("credentials in provider URL are not allowed");
  }
  if (url.protocol !== "https:" && !(policy.allowPrivate && url.protocol === "http:")) {
    throw new EndpointBlockedError("only public HTTPS provider URLs are allowed");
  }

  const hostname = url.hostname.replace(/^\[|\]$/g, "");
  const literalFamily = isIP(hostname);
  let addresses: ResolvedAddress[];
  if (literalFamily !== 0) {
    addresses = [{ address: hostname, family: literalFamily }];
  } else {
    try {
      addresses = await (policy.resolve ?? defaultResolve)(hostname);
    } catch {
      throw new EndpointResolutionError();
    }
  }
  if (addresses.length === 0) {
    throw new EndpointResolutionError();
  }
  for (const { address } of addresses) {
    assertAddressAllowed(address, policy.allowPrivate);
  }
  return url;
}

async function defaultResolve(hostname: string): Promise<ResolvedAddress[]> {
  return lookup(hostname, { all: true, verbatim: true });
}

function assertAddressAllowed(address: string, allowPrivate: boolean): void {
  const lower = address.toLowerCase().split("%")[0];

  if (lower.includes(":")) {
    const bytes = ipv6Bytes(lower);
    if (bytes === null) {
      throw new EndpointBlockedError("invalid provider address");
    }
    assertV6Allowed(bytes, allowPrivate);
    return;
  }
  assertV4Allowed(lower, allowPrivate);
}

function assertV4Allowed(address: string, allowPrivate: boolean): void {
  const value = ipv4Value(address);
  if (value === null) {
    throw new EndpointBlockedError("invalid provider address");
  }
  if (METADATA_V4.has(address)) {
    throw new EndpointBlockedError("provider address is not allowed");
  }
  if (ALWAYS_BLOCKED_V4.some(([network, prefix]) => inV4Range(value, network, prefix))) {
    throw new EndpointBlockedError("provider address is not allowed");
  }
  if (!allowPrivate && PRIVATE_V4.some(([network, prefix]) => inV4Range(value, network, prefix))) {
    throw new EndpointBlockedError("private provider address is not allowed");
  }
}

function assertV6Allowed(bytes: number[], allowPrivate: boolean): void {
  if (isZeroPrefix(bytes, 128)) {
    throw new EndpointBlockedError("provider address is not allowed");
  }
  if (matchesV6(bytes, "fe80", 10) || matchesV6(bytes, "ff00", 8) || matchesV6(bytes, "2001:db8", 32)) {
    throw new EndpointBlockedError("provider address is not allowed");
  }
  if (matchesV6(bytes, "fd00:ec2::254", 128)) {
    throw new EndpointBlockedError("provider address is not allowed");
  }

  // Addresses that embed an IPv4 destination must be judged by that address;
  // URL literals like [::ffff:a9fe:a9fe] arrive in hex, not dotted quad.
  const embedded = embeddedV4(bytes);
  if (embedded !== null) {
    assertV4Allowed(embedded, allowPrivate);
    return;
  }

  if (!allowPrivate) {
    if (matchesV6(bytes, "::1", 128) || matchesV6(bytes, "fc00", 7)) {
      throw new EndpointBlockedError("private provider address is not allowed");
    }
  }
}

/** Extracts the IPv4 address embedded in mapped/NAT64/6to4/compatible forms. */
function embeddedV4(bytes: number[]): string | null {
  // ::ffff:0:0/96 — IPv4-mapped
  if (isZeroPrefix(bytes.slice(0, 10), 80) && bytes[10] === 0xff && bytes[11] === 0xff) {
    return v4String(bytes.slice(12, 16));
  }
  // 64:ff9b::/96 — well-known NAT64 prefix
  if (
    bytes[0] === 0x00 &&
    bytes[1] === 0x64 &&
    bytes[2] === 0xff &&
    bytes[3] === 0x9b &&
    isZeroPrefix(bytes.slice(4, 12), 64)
  ) {
    return v4String(bytes.slice(12, 16));
  }
  // 2002::/16 — 6to4
  if (bytes[0] === 0x20 && bytes[1] === 0x02) {
    return v4String(bytes.slice(2, 6));
  }
  // ::/96 — deprecated IPv4-compatible (excluding :: and ::1, handled above)
  if (isZeroPrefix(bytes.slice(0, 12), 96)) {
    return v4String(bytes.slice(12, 16));
  }
  return null;
}

function matchesV6(bytes: number[], network: string, prefixBits: number): boolean {
  const networkBytes = ipv6Bytes(network);
  if (networkBytes === null) return false;
  return isZeroPrefix(xorBytes(bytes, networkBytes), prefixBits);
}

function isZeroPrefix(bytes: number[], prefixBits: number): boolean {
  const fullBytes = Math.floor(prefixBits / 8);
  const remainingBits = prefixBits % 8;
  for (let index = 0; index < fullBytes; index += 1) {
    if (bytes[index] !== 0) return false;
  }
  if (remainingBits === 0) return true;
  const mask = (0xff << (8 - remainingBits)) & 0xff;
  return (bytes[fullBytes] & mask) === 0;
}

function xorBytes(left: number[], right: number[]): number[] {
  const result: number[] = [];
  for (let index = 0; index < 16; index += 1) {
    result.push((left[index] ?? 0) ^ (right[index] ?? 0));
  }
  return result;
}

function v4String(bytes: number[]): string {
  return bytes.join(".");
}

function ipv4Value(address: string): number | null {
  const parts = address.split(".");
  if (parts.length !== 4) return null;
  const numbers = parts.map((part) => Number(part));
  if (!numbers.every((value) => Number.isInteger(value) && value >= 0 && value <= 255)) {
    return null;
  }
  return ((numbers[0] << 24) | (numbers[1] << 16) | (numbers[2] << 8) | numbers[3]) >>> 0;
}

function inV4Range(value: number, network: string, prefix: number): boolean {
  const base = ipv4Value(network);
  if (base === null) return false;
  const mask = prefix === 0 ? 0 : (0xffffffff << (32 - prefix)) >>> 0;
  return (value & mask) === (base & mask);
}

/** Parses dotted or hex IPv6 forms into 16 bytes; null when invalid. */
function ipv6Bytes(address: string): number[] | null {
  let value = address.toLowerCase();
  const zone = value.indexOf("%");
  if (zone >= 0) value = value.slice(0, zone);

  // Resolver results may still carry a dotted IPv4 suffix.
  const lastColon = value.lastIndexOf(":");
  if (lastColon >= 0) {
    const suffix = value.slice(lastColon + 1);
    const v4 = ipv4Value(suffix);
    if (v4 !== null) {
      value = `${value.slice(0, lastColon)}:${((v4 >>> 16) & 0xffff).toString(16)}:${(v4 & 0xffff).toString(16)}`;
    }
  }

  const halves = value.split("::");
  if (halves.length > 2) return null;
  const left = halves[0] ? halves[0].split(":") : [];
  const right = halves.length === 2 && halves[1] ? halves[1].split(":") : [];
  const missing = 8 - left.length - right.length;
  if (halves.length === 1 ? left.length !== 8 : missing < 0) return null;

  const groups = [...left, ...Array(halves.length === 2 ? missing : 0).fill("0"), ...right];
  if (groups.length !== 8) return null;

  const bytes: number[] = [];
  for (const group of groups) {
    if (!/^[0-9a-f]{1,4}$/.test(group)) return null;
    const number = Number.parseInt(group, 16);
    bytes.push((number >> 8) & 0xff, number & 0xff);
  }
  return bytes;
}
