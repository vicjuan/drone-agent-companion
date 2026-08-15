#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFile, stat } from "node:fs/promises";
import { pathToFileURL } from "node:url";

const MAX_INPUT_BYTES = 64 * 1024;
const MAX_ISSUES = 64;
const MAX_OBSERVED_COUNT = 4096;
const MAX_CAPTURE_AGE_MILLIS = 5 * 60 * 1000;
const MAX_CAPTURE_FUTURE_SKEW_MILLIS = 30 * 1000;
const CHECK_NAMES = Object.freeze([
  "profile",
  "collection",
  "adapter",
  "staticIpv4",
  "dhcp",
  "gateway",
  "dns",
  "defaultRoute",
  "routeToG520",
  "ics",
  "bridge",
  "ipForwarding",
]);
const QUERY_NAMES = Object.freeze([
  "profileHash",
  "hostIdentity",
  "adapter",
  "ipv4",
  "dhcp",
  "gateway",
  "dns",
  "defaultRoute",
  "routeToG520",
  "ics",
  "bridge",
  "forwarding",
]);
const QUERY_CHECK = Object.freeze({
  profileHash: "collection",
  hostIdentity: "collection",
  adapter: "adapter",
  ipv4: "staticIpv4",
  dhcp: "dhcp",
  gateway: "gateway",
  dns: "dns",
  defaultRoute: "defaultRoute",
  routeToG520: "routeToG520",
  ics: "ics",
  bridge: "bridge",
  forwarding: "ipForwarding",
});
const PROFILE_POLICY_KEYS = Object.freeze([
  "requireUniqueAdapter",
  "requireAdapterUp",
  "requireStaticIpv4",
  "requireDhcpDisabled",
  "requireNoGateway",
  "requireNoDns",
  "requireNoDefaultRoute",
  "requireIcsDisabled",
  "requireBridgeDisabled",
  "requireIpForwardingDisabled",
]);

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function makeIssueCollector() {
  const issues = [];
  const seen = new Set();
  return {
    add(check, code, path) {
      const key = `${check}\u0000${code}\u0000${path}`;
      if (seen.has(key) || issues.length >= MAX_ISSUES) return;
      seen.add(key);
      issues.push({ check, code, path });
    },
    finish() {
      return issues.sort((left, right) =>
        left.check.localeCompare(right.check) ||
        left.code.localeCompare(right.code) ||
        left.path.localeCompare(right.path),
      );
    },
  };
}

function requireExactObject(value, keys, path, check, issueCollector) {
  if (!isPlainObject(value)) {
    issueCollector.add(check, "INVALID_TYPE", path);
    return false;
  }
  const expected = new Set(keys);
  for (const key of Object.keys(value).sort()) {
    if (!expected.has(key)) issueCollector.add(check, "UNKNOWN_FIELD", `${path}.${key}`);
  }
  for (const key of keys) {
    if (!Object.hasOwn(value, key)) issueCollector.add(check, "MISSING_FIELD", `${path}.${key}`);
  }
  return true;
}

function requireString(value, path, check, issueCollector, { nullable = false } = {}) {
  if (nullable && value === null) return null;
  if (typeof value !== "string") {
    issueCollector.add(check, "INVALID_TYPE", path);
    return null;
  }
  return value;
}

function requireBoolean(value, path, check, issueCollector, { nullable = false } = {}) {
  if (nullable && value === null) return null;
  if (typeof value !== "boolean") {
    issueCollector.add(check, "INVALID_TYPE", path);
    return null;
  }
  return value;
}

function requireInteger(value, path, check, issueCollector, { min, max, nullable = false }) {
  if (nullable && value === null) return null;
  if (!Number.isSafeInteger(value) || value < min || value > max) {
    issueCollector.add(check, "INVALID_INTEGER", path);
    return null;
  }
  return value;
}

function normalizeGuid(value) {
  if (typeof value !== "string") return null;
  const match = /^\{?([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\}?$/iu.exec(value);
  const normalized = match?.[1].toLowerCase() ?? null;
  return normalized === "00000000-0000-0000-0000-000000000000" ? null : normalized;
}

function normalizeSha256(value) {
  if (typeof value !== "string" || !/^[0-9a-f]{64}$/iu.test(value)) return null;
  const normalized = value.toLowerCase();
  return /^0{64}$/u.test(normalized) ? null : normalized;
}

function parseIpv4(value) {
  if (typeof value !== "string" || !/^(?:0|[1-9][0-9]{0,2})(?:\.(?:0|[1-9][0-9]{0,2})){3}$/u.test(value)) {
    return null;
  }
  const octets = value.split(".").map(Number);
  if (octets.some((octet) => octet > 255)) return null;
  return octets;
}

function ipv4ToUint32(octets) {
  return ((((octets[0] * 256) + octets[1]) * 256 + octets[2]) * 256 + octets[3]) >>> 0;
}

function sameSubnet(left, right, prefixLength) {
  const mask = (0xffffffff << (32 - prefixLength)) >>> 0;
  return (ipv4ToUint32(left) & mask) === (ipv4ToUint32(right) & mask);
}

function isRfc1918(octets) {
  return (
    octets[0] === 10 ||
    (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) ||
    (octets[0] === 192 && octets[1] === 168)
  );
}

function isUsableSlash30Host(octets) {
  const hostPart = ipv4ToUint32(octets) & 0x3;
  return hostPart === 1 || hostPart === 2;
}

function validateProfile(profile, issueCollector) {
  const result = {
    profileId: null,
    adapterGuid: null,
    pnpDeviceIdSha256: null,
    ifIndex: null,
    operatorIpv4: null,
    operatorIpv4Octets: null,
    prefixLength: null,
    g520Ipv4: null,
    g520Ipv4Octets: null,
    consolePort: null,
  };
  if (!requireExactObject(
    profile,
    ["schemaVersion", "profileId", "status", "operator", "g520", "console", "policy"],
    "profile",
    "profile",
    issueCollector,
  )) return result;

  if (profile.schemaVersion !== 1) issueCollector.add("profile", "UNSUPPORTED_SCHEMA_VERSION", "profile.schemaVersion");

  const profileId = requireString(profile.profileId, "profile.profileId", "profile", issueCollector);
  if (profileId !== null) {
    if (!/^[a-z0-9][a-z0-9._-]{0,63}$/u.test(profileId)) {
      issueCollector.add("profile", "INVALID_PROFILE_ID", "profile.profileId");
    } else {
      result.profileId = profileId;
    }
  }

  const status = requireString(profile.status, "profile.status", "profile", issueCollector);
  if (status !== null) {
    if (!["COMMISSIONING_CANDIDATE", "UNVERIFIED_TEMPLATE", "RETIRED"].includes(status)) {
      issueCollector.add("profile", "UNKNOWN_STATUS", "profile.status");
    } else if (status !== "COMMISSIONING_CANDIDATE") {
      issueCollector.add("profile", "PROFILE_NOT_COMMISSIONING_CANDIDATE", "profile.status");
    }
  }

  if (requireExactObject(
    profile.operator,
    ["adapterGuid", "pnpDeviceIdSha256", "ifIndex", "ipv4", "prefixLength"],
    "profile.operator",
    "profile",
    issueCollector,
  )) {
    const adapterGuidText = requireString(profile.operator.adapterGuid, "profile.operator.adapterGuid", "profile", issueCollector);
    if (adapterGuidText !== null) {
      result.adapterGuid = normalizeGuid(adapterGuidText);
      if (result.adapterGuid === null) issueCollector.add("profile", "INVALID_ADAPTER_GUID", "profile.operator.adapterGuid");
    }
    const pnpHashText = requireString(
      profile.operator.pnpDeviceIdSha256,
      "profile.operator.pnpDeviceIdSha256",
      "profile",
      issueCollector,
    );
    result.pnpDeviceIdSha256 = normalizeSha256(pnpHashText);
    if (pnpHashText !== null && result.pnpDeviceIdSha256 === null) {
      issueCollector.add("profile", "INVALID_PNP_IDENTITY_SHA256", "profile.operator.pnpDeviceIdSha256");
    }
    if (profile.operator.ifIndex !== null) {
      requireInteger(profile.operator.ifIndex, "profile.operator.ifIndex", "profile", issueCollector, {
        min: 1,
        max: 0x7fffffff,
        nullable: false,
      });
      issueCollector.add("profile", "PROFILE_IFINDEX_MUST_BE_NULL", "profile.operator.ifIndex");
    }
    result.operatorIpv4 = requireString(profile.operator.ipv4, "profile.operator.ipv4", "profile", issueCollector);
    result.operatorIpv4Octets = parseIpv4(result.operatorIpv4);
    if (result.operatorIpv4 !== null && result.operatorIpv4Octets === null) {
      issueCollector.add("profile", "INVALID_IPV4", "profile.operator.ipv4");
    }
    result.prefixLength = requireInteger(
      profile.operator.prefixLength,
      "profile.operator.prefixLength",
      "profile",
      issueCollector,
      { min: 1, max: 30, nullable: false },
    );
    if (result.prefixLength !== null && result.prefixLength !== 30) {
      issueCollector.add("profile", "PREFIX_MUST_BE_SLASH_30", "profile.operator.prefixLength");
    }
  }

  if (requireExactObject(profile.g520, ["ipv4"], "profile.g520", "profile", issueCollector)) {
    result.g520Ipv4 = requireString(profile.g520.ipv4, "profile.g520.ipv4", "profile", issueCollector);
    result.g520Ipv4Octets = parseIpv4(result.g520Ipv4);
    if (result.g520Ipv4 !== null && result.g520Ipv4Octets === null) {
      issueCollector.add("profile", "INVALID_IPV4", "profile.g520.ipv4");
    }
  }

  if (requireExactObject(profile.console, ["port", "origin"], "profile.console", "profile", issueCollector)) {
    result.consolePort = requireInteger(profile.console.port, "profile.console.port", "profile", issueCollector, {
      min: 1,
      max: 65535,
      nullable: false,
    });
    if (result.consolePort !== null && result.consolePort !== 8080) {
      issueCollector.add("profile", "CONSOLE_PORT_MUST_BE_8080", "profile.console.port");
    }
    const origin = requireString(profile.console.origin, "profile.console.origin", "profile", issueCollector);
    if (origin !== null && result.consolePort !== null && result.g520Ipv4 !== null) {
      const expectedOrigin = `http://${result.g520Ipv4}:${result.consolePort}`;
      if (origin !== expectedOrigin) {
        issueCollector.add("profile", "INVALID_CONSOLE_ORIGIN", "profile.console.origin");
      }
    }
  }

  if (requireExactObject(profile.policy, PROFILE_POLICY_KEYS, "profile.policy", "profile", issueCollector)) {
    for (const key of PROFILE_POLICY_KEYS) {
      const value = requireBoolean(profile.policy[key], `profile.policy.${key}`, "profile", issueCollector);
      if (value === false) issueCollector.add("profile", "UNSAFE_POLICY", `profile.policy.${key}`);
    }
  }

  if (
    result.operatorIpv4Octets !== null &&
    result.g520Ipv4Octets !== null &&
    result.prefixLength !== null
  ) {
    if (!isRfc1918(result.operatorIpv4Octets)) {
      issueCollector.add("profile", "OPERATOR_IPV4_NOT_RFC1918", "profile.operator.ipv4");
    }
    if (!isRfc1918(result.g520Ipv4Octets)) {
      issueCollector.add("profile", "G520_IPV4_NOT_RFC1918", "profile.g520.ipv4");
    }
    if (result.prefixLength === 30 && !isUsableSlash30Host(result.operatorIpv4Octets)) {
      issueCollector.add("profile", "OPERATOR_IPV4_NOT_USABLE_HOST", "profile.operator.ipv4");
    }
    if (result.prefixLength === 30 && !isUsableSlash30Host(result.g520Ipv4Octets)) {
      issueCollector.add("profile", "G520_IPV4_NOT_USABLE_HOST", "profile.g520.ipv4");
    }
    if (result.operatorIpv4 === result.g520Ipv4) {
      issueCollector.add("profile", "DUPLICATE_ENDPOINT_IPV4", "profile.g520.ipv4");
    } else if (!sameSubnet(result.operatorIpv4Octets, result.g520Ipv4Octets, result.prefixLength)) {
      issueCollector.add("profile", "ENDPOINTS_NOT_IN_SAME_SUBNET", "profile.g520.ipv4");
    }
  }

  return result;
}

function validateQueryStatuses(queries, issueCollector) {
  if (!requireExactObject(queries, QUERY_NAMES, "snapshot.queries", "collection", issueCollector)) return false;
  let allOk = true;
  for (const queryName of QUERY_NAMES) {
    const path = `snapshot.queries.${queryName}`;
    if (!requireExactObject(queries[queryName], ["complete", "status"], path, "collection", issueCollector)) {
      allOk = false;
      continue;
    }
    const complete = requireBoolean(queries[queryName].complete, `${path}.complete`, "collection", issueCollector);
    const status = requireString(queries[queryName].status, `${path}.status`, "collection", issueCollector);
    if (status === null) {
      allOk = false;
    } else if (!["OK", "ERROR", "NOT_RUN"].includes(status)) {
      issueCollector.add(QUERY_CHECK[queryName], "UNKNOWN_STATUS", `${path}.status`);
      allOk = false;
    } else if (status !== "OK") {
      issueCollector.add(QUERY_CHECK[queryName], "QUERY_FAILED", path);
      allOk = false;
    }
    if (complete !== null && complete !== (status === "OK")) {
      issueCollector.add("collection", "INCONSISTENT_QUERY_COMPLETENESS", path);
      allOk = false;
    }
  }
  return allOk;
}

function observedCount(value, path, check, issueCollector) {
  return requireInteger(value, path, check, issueCollector, {
    min: 0,
    max: MAX_OBSERVED_COUNT,
    nullable: false,
  });
}

function validateSnapshot(snapshot, expected, expectedProfileSha256, nowEpochMillis, issueCollector) {
  if (!requireExactObject(
    snapshot,
    [
      "schemaVersion",
      "captureStatus",
      "collector",
      "selector",
      "queries",
      "adapter",
      "ipv4",
      "dhcp",
      "gateway",
      "dns",
      "defaultRoute",
      "routeToG520",
      "ics",
      "bridge",
      "forwarding",
    ],
    "snapshot",
    "collection",
    issueCollector,
  )) return { captureStatus: "INVALID" };

  if (snapshot.schemaVersion !== 1) issueCollector.add("collection", "UNSUPPORTED_SCHEMA_VERSION", "snapshot.schemaVersion");
  const captureStatus = requireString(snapshot.captureStatus, "snapshot.captureStatus", "collection", issueCollector);
  if (captureStatus !== null) {
    if (!["COMPLETE", "PARTIAL"].includes(captureStatus)) {
      issueCollector.add("collection", "UNKNOWN_STATUS", "snapshot.captureStatus");
    } else if (captureStatus !== "COMPLETE") {
      issueCollector.add("collection", "SNAPSHOT_INCOMPLETE", "snapshot.captureStatus");
    }
  }

  if (requireExactObject(
    snapshot.collector,
    ["name", "version", "capturedAtUtc", "hostIdentitySha256", "profileSha256"],
    "snapshot.collector",
    "collection",
    issueCollector,
  )) {
    const name = requireString(snapshot.collector.name, "snapshot.collector.name", "collection", issueCollector);
    const version = requireString(snapshot.collector.version, "snapshot.collector.version", "collection", issueCollector);
    const capturedAtUtc = requireString(
      snapshot.collector.capturedAtUtc,
      "snapshot.collector.capturedAtUtc",
      "collection",
      issueCollector,
    );
    const hostIdentityText = requireString(
      snapshot.collector.hostIdentitySha256,
      "snapshot.collector.hostIdentitySha256",
      "collection",
      issueCollector,
    );
    const profileHashText = requireString(
      snapshot.collector.profileSha256,
      "snapshot.collector.profileSha256",
      "collection",
      issueCollector,
    );
    if (name !== null && name !== "windows-point-to-point-readonly") {
      issueCollector.add("collection", "UNKNOWN_COLLECTOR", "snapshot.collector.name");
    }
    if (version !== null && version !== "1.0.0") {
      issueCollector.add("collection", "UNSUPPORTED_COLLECTOR_VERSION", "snapshot.collector.version");
    }
    if (capturedAtUtc !== null) {
      const capturedAtMillis = Date.parse(capturedAtUtc);
      if (
        !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/u.test(capturedAtUtc) ||
        !Number.isFinite(capturedAtMillis) ||
        new Date(capturedAtMillis).toISOString() !== capturedAtUtc
      ) {
        issueCollector.add("collection", "INVALID_CAPTURE_TIMESTAMP", "snapshot.collector.capturedAtUtc");
      } else if (capturedAtMillis > nowEpochMillis + MAX_CAPTURE_FUTURE_SKEW_MILLIS) {
        issueCollector.add("collection", "CAPTURE_FROM_FUTURE", "snapshot.collector.capturedAtUtc");
      } else if (nowEpochMillis - capturedAtMillis > MAX_CAPTURE_AGE_MILLIS) {
        issueCollector.add("collection", "CAPTURE_STALE", "snapshot.collector.capturedAtUtc");
      }
    }
    const hostIdentitySha256 = normalizeSha256(hostIdentityText);
    if (hostIdentityText !== null && hostIdentitySha256 === null) {
      issueCollector.add("collection", "INVALID_HOST_IDENTITY_SHA256", "snapshot.collector.hostIdentitySha256");
    }
    const profileSha256 = normalizeSha256(profileHashText);
    if (profileHashText !== null && profileSha256 === null) {
      issueCollector.add("collection", "INVALID_PROFILE_SHA256", "snapshot.collector.profileSha256");
    } else if (
      profileSha256 !== null &&
      expectedProfileSha256 !== null &&
      profileSha256 !== expectedProfileSha256
    ) {
      issueCollector.add("collection", "PROFILE_SHA256_MISMATCH", "snapshot.collector.profileSha256");
    }
  }

  let selectorGuid = null;
  let selectorIfIndex = null;
  if (requireExactObject(snapshot.selector, ["adapterGuid", "ifIndex"], "snapshot.selector", "adapter", issueCollector)) {
    const selectorGuidText = requireString(snapshot.selector.adapterGuid, "snapshot.selector.adapterGuid", "adapter", issueCollector);
    selectorGuid = normalizeGuid(selectorGuidText);
    if (selectorGuidText !== null && selectorGuid === null) {
      issueCollector.add("adapter", "INVALID_ADAPTER_GUID", "snapshot.selector.adapterGuid");
    }
    selectorIfIndex = requireInteger(snapshot.selector.ifIndex, "snapshot.selector.ifIndex", "adapter", issueCollector, {
      min: 1,
      max: 0x7fffffff,
      nullable: false,
    });
    if (
      selectorGuid !== null &&
      selectorIfIndex !== null &&
      expected.adapterGuid !== null &&
      selectorGuid !== expected.adapterGuid
    ) {
      issueCollector.add("adapter", "SNAPSHOT_SELECTOR_MISMATCH", "snapshot.selector");
    }
  }

  const allQueriesOk = validateQueryStatuses(snapshot.queries, issueCollector);
  if (captureStatus === "COMPLETE" && !allQueriesOk) {
    issueCollector.add("collection", "INCONSISTENT_CAPTURE_STATUS", "snapshot.captureStatus");
  }

  if (requireExactObject(
    snapshot.adapter,
    ["exactMatchCount", "partialMatchCount", "adapterGuid", "pnpDeviceIdSha256", "ifIndex", "operationalStatus"],
    "snapshot.adapter",
    "adapter",
    issueCollector,
  )) {
    const exactMatchCount = observedCount(snapshot.adapter.exactMatchCount, "snapshot.adapter.exactMatchCount", "adapter", issueCollector);
    const partialMatchCount = observedCount(snapshot.adapter.partialMatchCount, "snapshot.adapter.partialMatchCount", "adapter", issueCollector);
    const observedGuidText = requireString(snapshot.adapter.adapterGuid, "snapshot.adapter.adapterGuid", "adapter", issueCollector, { nullable: true });
    const observedGuid = observedGuidText === null ? null : normalizeGuid(observedGuidText);
    if (observedGuidText !== null && observedGuid === null) {
      issueCollector.add("adapter", "INVALID_ADAPTER_GUID", "snapshot.adapter.adapterGuid");
    }
    const observedPnpHashText = requireString(
      snapshot.adapter.pnpDeviceIdSha256,
      "snapshot.adapter.pnpDeviceIdSha256",
      "adapter",
      issueCollector,
      { nullable: true },
    );
    const observedPnpHash = observedPnpHashText === null ? null : normalizeSha256(observedPnpHashText);
    if (observedPnpHashText !== null && observedPnpHash === null) {
      issueCollector.add("adapter", "INVALID_PNP_IDENTITY_SHA256", "snapshot.adapter.pnpDeviceIdSha256");
    }
    const observedIfIndex = requireInteger(snapshot.adapter.ifIndex, "snapshot.adapter.ifIndex", "adapter", issueCollector, {
      min: 1,
      max: 0x7fffffff,
      nullable: true,
    });
    const operationalStatus = requireString(
      snapshot.adapter.operationalStatus,
      "snapshot.adapter.operationalStatus",
      "adapter",
      issueCollector,
      { nullable: true },
    );

    if (exactMatchCount === 0) {
      issueCollector.add(
        "adapter",
        partialMatchCount !== null && partialMatchCount > 0 ? "ADAPTER_SELECTOR_MISMATCH" : "ADAPTER_MISSING",
        "snapshot.adapter.exactMatchCount",
      );
    } else if (exactMatchCount !== null && exactMatchCount > 1) {
      issueCollector.add("adapter", "ADAPTER_MULTIPLE", "snapshot.adapter.exactMatchCount");
    } else if (exactMatchCount === 1) {
      if (partialMatchCount !== null && partialMatchCount > 0) {
        issueCollector.add("adapter", "ADAPTER_PARTIAL_MATCHES_PRESENT", "snapshot.adapter.partialMatchCount");
      }
      if (
        observedGuid === null ||
        observedIfIndex === null ||
        selectorGuid === null ||
        selectorIfIndex === null ||
        observedGuid !== selectorGuid ||
        observedIfIndex !== selectorIfIndex ||
        (expected.adapterGuid !== null && observedGuid !== expected.adapterGuid)
      ) {
        issueCollector.add("adapter", "ADAPTER_SELECTOR_MISMATCH", "snapshot.adapter");
      }
      if (expected.pnpDeviceIdSha256 !== null && observedPnpHash !== expected.pnpDeviceIdSha256) {
        issueCollector.add("adapter", "PNP_IDENTITY_MISMATCH", "snapshot.adapter.pnpDeviceIdSha256");
      }
      if (operationalStatus === null) {
        issueCollector.add("adapter", "ADAPTER_STATUS_MISSING", "snapshot.adapter.operationalStatus");
      } else {
        const knownStatuses = ["Up", "Down", "Disconnected", "Disabled", "Not Present", "Lower Layer Down", "Testing"];
        if (!knownStatuses.includes(operationalStatus)) {
          issueCollector.add("adapter", "UNKNOWN_STATUS", "snapshot.adapter.operationalStatus");
        } else if (operationalStatus !== "Up") {
          issueCollector.add("adapter", "ADAPTER_NOT_UP", "snapshot.adapter.operationalStatus");
        }
      }
    }
  }

  if (requireExactObject(
    snapshot.ipv4,
    ["addressCount", "address", "prefixLength", "prefixOrigin", "suffixOrigin", "addressState"],
    "snapshot.ipv4",
    "staticIpv4",
    issueCollector,
  )) {
    const addressCount = observedCount(snapshot.ipv4.addressCount, "snapshot.ipv4.addressCount", "staticIpv4", issueCollector);
    const address = requireString(snapshot.ipv4.address, "snapshot.ipv4.address", "staticIpv4", issueCollector, { nullable: true });
    const prefixLength = requireInteger(snapshot.ipv4.prefixLength, "snapshot.ipv4.prefixLength", "staticIpv4", issueCollector, {
      min: 0,
      max: 32,
      nullable: true,
    });
    const prefixOrigin = requireString(snapshot.ipv4.prefixOrigin, "snapshot.ipv4.prefixOrigin", "staticIpv4", issueCollector, { nullable: true });
    const suffixOrigin = requireString(snapshot.ipv4.suffixOrigin, "snapshot.ipv4.suffixOrigin", "staticIpv4", issueCollector, { nullable: true });
    const addressState = requireString(snapshot.ipv4.addressState, "snapshot.ipv4.addressState", "staticIpv4", issueCollector, { nullable: true });

    if (addressCount !== null && addressCount !== 1) issueCollector.add("staticIpv4", "IPV4_ADDRESS_COUNT", "snapshot.ipv4.addressCount");
    if (
      addressCount === 1 &&
      [address, prefixLength, prefixOrigin, suffixOrigin, addressState].some((value) => value === null)
    ) {
      issueCollector.add("staticIpv4", "IPV4_OBSERVATION_INCOMPLETE", "snapshot.ipv4");
    }
    if (address !== null) {
      if (parseIpv4(address) === null) issueCollector.add("staticIpv4", "INVALID_IPV4", "snapshot.ipv4.address");
      if (expected.operatorIpv4 !== null && address !== expected.operatorIpv4) {
        issueCollector.add("staticIpv4", "STATIC_IPV4_MISMATCH", "snapshot.ipv4.address");
      }
    }
    if (prefixLength !== null && expected.prefixLength !== null && prefixLength !== expected.prefixLength) {
      issueCollector.add("staticIpv4", "STATIC_PREFIX_MISMATCH", "snapshot.ipv4.prefixLength");
    }
    if (prefixOrigin !== null) {
      if (!["Manual", "Dhcp", "RouterAdvertisement", "WellKnown", "Other", "Random"].includes(prefixOrigin)) {
        issueCollector.add("staticIpv4", "UNKNOWN_STATUS", "snapshot.ipv4.prefixOrigin");
      } else if (prefixOrigin !== "Manual") {
        issueCollector.add("staticIpv4", "IPV4_NOT_STATIC", "snapshot.ipv4.prefixOrigin");
      }
    }
    if (suffixOrigin !== null) {
      if (!["Manual", "Dhcp", "Link", "Random", "WellKnown", "Other"].includes(suffixOrigin)) {
        issueCollector.add("staticIpv4", "UNKNOWN_STATUS", "snapshot.ipv4.suffixOrigin");
      } else if (suffixOrigin !== "Manual") {
        issueCollector.add("staticIpv4", "IPV4_NOT_STATIC", "snapshot.ipv4.suffixOrigin");
      }
    }
    if (addressState !== null) {
      if (!["Invalid", "Tentative", "Duplicate", "Deprecated", "Preferred"].includes(addressState)) {
        issueCollector.add("staticIpv4", "UNKNOWN_STATUS", "snapshot.ipv4.addressState");
      } else if (addressState !== "Preferred") {
        issueCollector.add("staticIpv4", "IPV4_NOT_PREFERRED", "snapshot.ipv4.addressState");
      }
    }
  }

  if (requireExactObject(snapshot.dhcp, ["state"], "snapshot.dhcp", "dhcp", issueCollector)) {
    const state = requireString(snapshot.dhcp.state, "snapshot.dhcp.state", "dhcp", issueCollector, { nullable: true });
    if (state === null) {
      issueCollector.add("dhcp", "DHCP_STATE_MISSING", "snapshot.dhcp.state");
    } else {
      if (!["Enabled", "Disabled"].includes(state)) issueCollector.add("dhcp", "UNKNOWN_STATUS", "snapshot.dhcp.state");
      else if (state !== "Disabled") issueCollector.add("dhcp", "DHCP_ENABLED", "snapshot.dhcp.state");
    }
  }

  const countOnlyChecks = [
    [snapshot.gateway, "gateway", "count", "GATEWAY_PRESENT"],
    [snapshot.dns, "dns", "serverCount", "DNS_CONFIGURED"],
  ];
  for (const [object, check, key, issueCode] of countOnlyChecks) {
    const path = `snapshot.${check}`;
    if (requireExactObject(object, [key], path, check, issueCollector)) {
      const count = observedCount(object[key], `${path}.${key}`, check, issueCollector);
      if (count !== null && count > 0) issueCollector.add(check, issueCode, `${path}.${key}`);
    }
  }

  if (requireExactObject(
    snapshot.defaultRoute,
    ["count", "expectedConnectedRouteCount", "unexpectedRouteCount"],
    "snapshot.defaultRoute",
    "defaultRoute",
    issueCollector,
  )) {
    const defaultRouteCount = observedCount(
      snapshot.defaultRoute.count,
      "snapshot.defaultRoute.count",
      "defaultRoute",
      issueCollector,
    );
    if (defaultRouteCount !== null && defaultRouteCount > 0) {
      issueCollector.add("defaultRoute", "DEFAULT_ROUTE_PRESENT", "snapshot.defaultRoute.count");
    }
    const expectedConnectedRouteCount = observedCount(
      snapshot.defaultRoute.expectedConnectedRouteCount,
      "snapshot.defaultRoute.expectedConnectedRouteCount",
      "defaultRoute",
      issueCollector,
    );
    if (expectedConnectedRouteCount === 0) {
      issueCollector.add(
        "defaultRoute",
        "MISSING_EXPECTED_CONNECTED_ROUTE",
        "snapshot.defaultRoute.expectedConnectedRouteCount",
      );
    } else if (expectedConnectedRouteCount !== null && expectedConnectedRouteCount > 1) {
      issueCollector.add(
        "defaultRoute",
        "MULTIPLE_EXPECTED_CONNECTED_ROUTE",
        "snapshot.defaultRoute.expectedConnectedRouteCount",
      );
    }
    const unexpectedRouteCount = observedCount(
      snapshot.defaultRoute.unexpectedRouteCount,
      "snapshot.defaultRoute.unexpectedRouteCount",
      "defaultRoute",
      issueCollector,
    );
    if (unexpectedRouteCount !== null && unexpectedRouteCount > 0) {
      issueCollector.add(
        "defaultRoute",
        "UNEXPECTED_ROUTE_PRESENT",
        "snapshot.defaultRoute.unexpectedRouteCount",
      );
    }
  }

  if (requireExactObject(
    snapshot.routeToG520,
    [
      "sourceAddressResultCount",
      "routeResultCount",
      "targetAddress",
      "sourceInterfaceIndex",
      "routeInterfaceIndex",
      "sourceAddress",
    ],
    "snapshot.routeToG520",
    "routeToG520",
    issueCollector,
  )) {
    const sourceAddressResultCount = observedCount(
      snapshot.routeToG520.sourceAddressResultCount,
      "snapshot.routeToG520.sourceAddressResultCount",
      "routeToG520",
      issueCollector,
    );
    const routeResultCount = observedCount(
      snapshot.routeToG520.routeResultCount,
      "snapshot.routeToG520.routeResultCount",
      "routeToG520",
      issueCollector,
    );
    const targetAddress = requireString(
      snapshot.routeToG520.targetAddress,
      "snapshot.routeToG520.targetAddress",
      "routeToG520",
      issueCollector,
    );
    const sourceInterfaceIndex = requireInteger(
      snapshot.routeToG520.sourceInterfaceIndex,
      "snapshot.routeToG520.sourceInterfaceIndex",
      "routeToG520",
      issueCollector,
      { min: 1, max: 0x7fffffff, nullable: true },
    );
    const routeInterfaceIndex = requireInteger(
      snapshot.routeToG520.routeInterfaceIndex,
      "snapshot.routeToG520.routeInterfaceIndex",
      "routeToG520",
      issueCollector,
      { min: 1, max: 0x7fffffff, nullable: true },
    );
    const sourceAddress = requireString(
      snapshot.routeToG520.sourceAddress,
      "snapshot.routeToG520.sourceAddress",
      "routeToG520",
      issueCollector,
      { nullable: true },
    );
    if (sourceAddressResultCount !== null && sourceAddressResultCount !== 1) {
      issueCollector.add(
        "routeToG520",
        "ROUTE_SOURCE_SELECTION_COUNT",
        "snapshot.routeToG520.sourceAddressResultCount",
      );
    }
    if (routeResultCount !== null && routeResultCount !== 1) {
      issueCollector.add("routeToG520", "ROUTE_SELECTION_COUNT", "snapshot.routeToG520.routeResultCount");
    }
    if (
      sourceAddressResultCount === 1 &&
      routeResultCount === 1 &&
      (sourceInterfaceIndex === null || routeInterfaceIndex === null || sourceAddress === null)
    ) {
      issueCollector.add("routeToG520", "ROUTE_OBSERVATION_INCOMPLETE", "snapshot.routeToG520");
    }
    if (targetAddress !== null) {
      if (parseIpv4(targetAddress) === null) {
        issueCollector.add("routeToG520", "INVALID_IPV4", "snapshot.routeToG520.targetAddress");
      } else if (expected.g520Ipv4 !== null && targetAddress !== expected.g520Ipv4) {
        issueCollector.add("routeToG520", "ROUTE_TARGET_MISMATCH", "snapshot.routeToG520.targetAddress");
      }
    }
    if (
      routeResultCount === 1 &&
      routeInterfaceIndex !== null &&
      selectorIfIndex !== null &&
      routeInterfaceIndex !== selectorIfIndex
    ) {
      issueCollector.add(
        "routeToG520",
        "ROUTE_INTERFACE_MISMATCH",
        "snapshot.routeToG520.routeInterfaceIndex",
      );
    }
    if (
      sourceAddressResultCount === 1 &&
      sourceInterfaceIndex !== null &&
      selectorIfIndex !== null &&
      sourceInterfaceIndex !== selectorIfIndex
    ) {
      issueCollector.add(
        "routeToG520",
        "ROUTE_SOURCE_INTERFACE_MISMATCH",
        "snapshot.routeToG520.sourceInterfaceIndex",
      );
    }
    if (sourceAddressResultCount === 1 && sourceAddress !== null) {
      if (parseIpv4(sourceAddress) === null) {
        issueCollector.add("routeToG520", "INVALID_IPV4", "snapshot.routeToG520.sourceAddress");
      } else if (expected.operatorIpv4 !== null && sourceAddress !== expected.operatorIpv4) {
        issueCollector.add("routeToG520", "ROUTE_SOURCE_MISMATCH", "snapshot.routeToG520.sourceAddress");
      }
    }
  }

  if (requireExactObject(
    snapshot.ics,
    ["enabledConnectionCount", "selectedAdapterEnabled"],
    "snapshot.ics",
    "ics",
    issueCollector,
  )) {
    const enabledConnectionCount = observedCount(
      snapshot.ics.enabledConnectionCount,
      "snapshot.ics.enabledConnectionCount",
      "ics",
      issueCollector,
    );
    const selectedAdapterEnabled = requireBoolean(
      snapshot.ics.selectedAdapterEnabled,
      "snapshot.ics.selectedAdapterEnabled",
      "ics",
      issueCollector,
      { nullable: true },
    );
    if (enabledConnectionCount !== null && enabledConnectionCount > 0) {
      issueCollector.add("ics", "ICS_ENABLED", "snapshot.ics.enabledConnectionCount");
    }
    if (selectedAdapterEnabled === true) {
      issueCollector.add("ics", "ICS_SELECTED_ADAPTER_ENABLED", "snapshot.ics.selectedAdapterEnabled");
    } else if (selectedAdapterEnabled === null) {
      issueCollector.add("ics", "ICS_STATUS_MISSING", "snapshot.ics.selectedAdapterEnabled");
    }
  }

  if (requireExactObject(
    snapshot.bridge,
    ["adapterCount", "enabledMemberCount", "selectedAdapterMember"],
    "snapshot.bridge",
    "bridge",
    issueCollector,
  )) {
    const adapterCount = observedCount(snapshot.bridge.adapterCount, "snapshot.bridge.adapterCount", "bridge", issueCollector);
    const enabledMemberCount = observedCount(
      snapshot.bridge.enabledMemberCount,
      "snapshot.bridge.enabledMemberCount",
      "bridge",
      issueCollector,
    );
    const selectedAdapterMember = requireBoolean(
      snapshot.bridge.selectedAdapterMember,
      "snapshot.bridge.selectedAdapterMember",
      "bridge",
      issueCollector,
      { nullable: true },
    );
    if (adapterCount !== null && adapterCount > 0) issueCollector.add("bridge", "BRIDGE_PRESENT", "snapshot.bridge.adapterCount");
    if (enabledMemberCount !== null && enabledMemberCount > 0) {
      issueCollector.add("bridge", "BRIDGE_MEMBER_ENABLED", "snapshot.bridge.enabledMemberCount");
    }
    if (selectedAdapterMember === true) {
      issueCollector.add("bridge", "SELECTED_ADAPTER_IS_BRIDGE_MEMBER", "snapshot.bridge.selectedAdapterMember");
    } else if (selectedAdapterMember === null) {
      issueCollector.add("bridge", "BRIDGE_STATUS_MISSING", "snapshot.bridge.selectedAdapterMember");
    }
  }

  if (requireExactObject(
    snapshot.forwarding,
    ["enabledInterfaceCount", "unknownStateCount", "selectedAdapterEnabled"],
    "snapshot.forwarding",
    "ipForwarding",
    issueCollector,
  )) {
    const enabledInterfaceCount = observedCount(
      snapshot.forwarding.enabledInterfaceCount,
      "snapshot.forwarding.enabledInterfaceCount",
      "ipForwarding",
      issueCollector,
    );
    const unknownStateCount = observedCount(
      snapshot.forwarding.unknownStateCount,
      "snapshot.forwarding.unknownStateCount",
      "ipForwarding",
      issueCollector,
    );
    const selectedAdapterEnabled = requireBoolean(
      snapshot.forwarding.selectedAdapterEnabled,
      "snapshot.forwarding.selectedAdapterEnabled",
      "ipForwarding",
      issueCollector,
      { nullable: true },
    );
    if (enabledInterfaceCount !== null && enabledInterfaceCount > 0) {
      issueCollector.add("ipForwarding", "IP_FORWARDING_ENABLED", "snapshot.forwarding.enabledInterfaceCount");
    }
    if (unknownStateCount !== null && unknownStateCount > 0) {
      issueCollector.add("ipForwarding", "UNKNOWN_STATUS", "snapshot.forwarding.unknownStateCount");
    }
    if (selectedAdapterEnabled === true) {
      issueCollector.add(
        "ipForwarding",
        "SELECTED_ADAPTER_FORWARDING_ENABLED",
        "snapshot.forwarding.selectedAdapterEnabled",
      );
    } else if (selectedAdapterEnabled === null) {
      issueCollector.add("ipForwarding", "FORWARDING_STATUS_MISSING", "snapshot.forwarding.selectedAdapterEnabled");
    }
  }

  return { captureStatus: ["COMPLETE", "PARTIAL"].includes(captureStatus) ? captureStatus : "INVALID" };
}

export function validateWindowsPointToPointPreflight(
  profile,
  snapshot,
  { profileSha256 = null, nowEpochMillis = Date.now() } = {},
) {
  const issueCollector = makeIssueCollector();
  if (!Number.isSafeInteger(nowEpochMillis) || nowEpochMillis < 0) {
    return fatalResult("collection", "INVALID_VALIDATION_CLOCK", "options.nowEpochMillis");
  }
  const normalizedProfileSha256 = normalizeSha256(profileSha256);
  if (normalizedProfileSha256 === null) {
    issueCollector.add("collection", "PROFILE_SHA256_UNAVAILABLE", "profile");
  }
  const expected = validateProfile(profile, issueCollector);
  const observed = validateSnapshot(snapshot, expected, normalizedProfileSha256, nowEpochMillis, issueCollector);
  const issues = issueCollector.finish();
  const failedChecks = new Set(issues.map((issue) => issue.check));
  const checks = Object.fromEntries(CHECK_NAMES.map((name) => [name, failedChecks.has(name) ? "FAIL" : "PASS"]));
  return {
    schemaVersion: 1,
    valid: issues.length === 0,
    profileId: expected.profileId,
    snapshotStatus: observed.captureStatus,
    checks,
    issues,
  };
}

function fatalResult(check, code, path) {
  return {
    schemaVersion: 1,
    valid: false,
    profileId: null,
    snapshotStatus: "INVALID",
    checks: Object.fromEntries(CHECK_NAMES.map((name) => [name, name === check ? "FAIL" : "NOT_RUN"])),
    issues: [{ check, code, path }],
  };
}

async function readBoundedJson(filePath) {
  let fileStat;
  let bytes;
  try {
    fileStat = await stat(filePath);
    if (!fileStat.isFile() || fileStat.size > MAX_INPUT_BYTES) return { error: "INPUT_SIZE_OR_TYPE", value: null };
    bytes = await readFile(filePath);
  } catch {
    return { error: "INPUT_READ_FAILED", value: null };
  }
  if (bytes.length > MAX_INPUT_BYTES) return { error: "INPUT_SIZE_OR_TYPE", value: null };
  try {
    return {
      error: null,
      value: JSON.parse(bytes.toString("utf8")),
      sha256: createHash("sha256").update(bytes).digest("hex"),
    };
  } catch {
    return { error: "JSON_INVALID", value: null };
  }
}

async function runCli() {
  const args = process.argv.slice(2);
  if (args.length !== 2) {
    process.stdout.write(`${JSON.stringify(fatalResult("collection", "USAGE", "arguments"), null, 2)}\n`);
    process.exitCode = 2;
    return;
  }
  const profile = await readBoundedJson(args[0]);
  if (profile.error !== null) {
    process.stdout.write(`${JSON.stringify(fatalResult("profile", profile.error, "profile"), null, 2)}\n`);
    process.exitCode = 1;
    return;
  }
  const snapshot = await readBoundedJson(args[1]);
  if (snapshot.error !== null) {
    process.stdout.write(`${JSON.stringify(fatalResult("collection", snapshot.error, "snapshot"), null, 2)}\n`);
    process.exitCode = 1;
    return;
  }
  const result = validateWindowsPointToPointPreflight(profile.value, snapshot.value, { profileSha256: profile.sha256 });
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  process.exitCode = result.valid ? 0 : 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await runCli();
}
