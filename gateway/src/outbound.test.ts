import assert from "node:assert/strict";
import { test } from "node:test";
import { assertSafeEndpoint, EndpointBlockedError, EndpointResolutionError, type ResolvedAddress } from "./outbound.js";

const PUBLIC: ResolvedAddress[] = [{ address: "93.184.216.34", family: 4 }];

function resolver(addresses: ResolvedAddress[]) {
  return async () => addresses;
}

test("allows public HTTPS endpoints", async () => {
  const url = await assertSafeEndpoint("https://api.example.com/v1", {
    allowPrivate: false,
    resolve: resolver(PUBLIC),
  });
  assert.equal(url.hostname, "api.example.com");
});

test("rejects plain HTTP unless private endpoints are allowed", async () => {
  await assert.rejects(
    () => assertSafeEndpoint("http://api.example.com/v1", { allowPrivate: false, resolve: resolver(PUBLIC) }),
    EndpointBlockedError,
  );
  await assertSafeEndpoint("http://api.example.com/v1", {
    allowPrivate: true,
    resolve: resolver(PUBLIC),
  });
});

test("rejects private addresses by default and allows them with the flag", async () => {
  const privateAddress = resolver([{ address: "10.0.0.5", family: 4 }]);
  await assert.rejects(
    () => assertSafeEndpoint("https://internal.example.com", { allowPrivate: false, resolve: privateAddress }),
    EndpointBlockedError,
  );
  await assertSafeEndpoint("https://internal.example.com", {
    allowPrivate: true,
    resolve: privateAddress,
  });
});

test("rejects loopback by default and allows it with the flag", async () => {
  const loopback = resolver([{ address: "127.0.0.1", family: 4 }]);
  await assert.rejects(
    () => assertSafeEndpoint("https://local.example.com", { allowPrivate: false, resolve: loopback }),
    EndpointBlockedError,
  );
  await assertSafeEndpoint("https://local.example.com", {
    allowPrivate: true,
    resolve: loopback,
  });
});

test("always rejects the IPv4 metadata address, even with the flag", async () => {
  await assert.rejects(
    () =>
      assertSafeEndpoint("https://metadata.example.com", {
        allowPrivate: true,
        resolve: resolver([{ address: "169.254.169.254", family: 4 }]),
      }),
    EndpointBlockedError,
  );
});

test("always rejects the IPv6 metadata address, even with the flag", async () => {
  await assert.rejects(
    () =>
      assertSafeEndpoint("https://metadata.example.com", {
        allowPrivate: true,
        resolve: resolver([{ address: "fd00:ec2::254", family: 6 }]),
      }),
    EndpointBlockedError,
  );
});

test("rejects a host when any resolved address is private by default", async () => {
  await assert.rejects(
    () =>
      assertSafeEndpoint("https://mixed.example.com", {
        allowPrivate: false,
        resolve: resolver([
          { address: "93.184.216.34", family: 4 },
          { address: "10.0.0.5", family: 4 },
        ]),
      }),
    EndpointBlockedError,
  );
});

test("rejects IPv4-mapped private addresses", async () => {
  await assert.rejects(
    () =>
      assertSafeEndpoint("https://mapped.example.com", {
        allowPrivate: false,
        resolve: resolver([{ address: "::ffff:10.0.0.5", family: 6 }]),
      }),
    EndpointBlockedError,
  );
});

test("rejects credentials in the URL", async () => {
  await assert.rejects(
    () =>
      assertSafeEndpoint("https://user:secret@api.example.com/v1", {
        allowPrivate: false,
        resolve: resolver(PUBLIC),
      }),
    EndpointBlockedError,
  );
});

test("rejects non-HTTP schemes", async () => {
  await assert.rejects(
    () => assertSafeEndpoint("ftp://api.example.com/v1", { allowPrivate: true, resolve: resolver(PUBLIC) }),
    EndpointBlockedError,
  );
});

test("rejects hosts that cannot be resolved", async () => {
  await assert.rejects(
    () =>
      assertSafeEndpoint("https://unknown.example.com", {
        allowPrivate: true,
        resolve: async () => {
          throw new Error("NXDOMAIN");
        },
      }),
    EndpointResolutionError,
  );
});

test("rejects hex IPv4-mapped metadata literals even with the flag", async () => {
  await assert.rejects(
    () => assertSafeEndpoint("https://[::ffff:a9fe:a9fe]/", { allowPrivate: true }),
    EndpointBlockedError,
  );
});

test("rejects hex IPv4-mapped loopback by default and allows it with the flag", async () => {
  await assert.rejects(
    () => assertSafeEndpoint("https://[::ffff:7f00:1]/", { allowPrivate: false }),
    EndpointBlockedError,
  );
  await assertSafeEndpoint("https://[::ffff:7f00:1]/", { allowPrivate: true });
});

test("rejects NAT64-embedded metadata", async () => {
  await assert.rejects(
    () => assertSafeEndpoint("https://[64:ff9b::a9fe:a9fe]/", { allowPrivate: true }),
    EndpointBlockedError,
  );
});

test("rejects 6to4-embedded metadata", async () => {
  await assert.rejects(
    () => assertSafeEndpoint("https://[2002:a9fe:a9fe::]/", { allowPrivate: true }),
    EndpointBlockedError,
  );
});

test("always rejects the Alibaba metadata address", async () => {
  await assert.rejects(
    () => assertSafeEndpoint("http://100.100.100.200/", { allowPrivate: true }),
    EndpointBlockedError,
  );
});
