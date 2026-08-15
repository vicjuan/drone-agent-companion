# Companion Console Protocol v1

This directory is the canonical cross-language contract for the browser-facing
console protocol. It is owned by this repository and does not modify or extend
`contracts/agent-protocol/`, which remains frozen in the vendor repository.

Every message uses exactly four envelope fields:

```json
{"protocolVersion":"1.0","messageId":"example-001","type":"health","payload":{}}
```

- `protocolVersion` is exactly `1.0`.
- `messageId` matches `^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$`.
- Unknown envelope or payload fields are rejected.
- JSON `null` fields shown in fixtures are explicit and must not be omitted.
- Wire messages are at most 65536 UTF-8 bytes and at most 16 JSON object/array
  levels deep. Both checks happen before recursive JSON parsing.
- Client messages never carry an authority assertion. The server derives
  authority only after session, lease, TTL and sequence checks.
- Localhost development may send `client_hello.authentication = null`; the
  nullable authentication object is the extension point for issue #5.
- Continuous axes are normalized finite values in `[-1, 1]`; conversion to
  vendor-specific units is outside this wire contract.

## Direction and payload inventory

Client to server:

| Type | Exact payload fields |
| --- | --- |
| `client_hello` | `clientName`, `clientVersion`, `supportedProtocolVersions`, nullable `authentication { scheme, credential }` |
| `lease_acquire` | `requestedTtlMs` |
| `lease_renew` | `leaseId`, `requestedTtlMs` |
| `lease_release` | `leaseId` |
| `command_request` | `commandId`, `leaseId`, `action`, `ttlMs` |
| `control_frame` | `leaseId`, `inputSequence`, `ttlMs`, `forward`, `right`, `up`, `yaw` |
| `control_neutral` | `leaseId`, `inputSequence`, `reason` |

Server to client:

| Type | Exact payload fields |
| --- | --- |
| `server_hello` | `sessionId`, `serverVersion`, `selectedProtocolVersion`, `authenticationRequired`, `acceptedAuthenticationSchemes` |
| `runtime_state` | `adapter`, `aircraftConnection`, `actuationLock`, `operatingProfile` |
| `telemetry` | `sequence`, nullable battery/location/altitude/gimbal/camera readings, `flightState` |
| `capability_snapshot` | `matrixId`, `schemaVersion`, `lastUpdated`, `sourceDigestSha256`, `rows` |
| `health` | `status`, `uptimeMs`, nullable `detail` |
| `lease_state` | nullable `requestMessageId`, `state`, `leaseId`, `holderSessionId`, `expiresInMs`, `reason` |
| `command_ack` | `commandId`, `decision`, nullable `reason`, `intentDigestSha256` |
| `command_result` | `commandId`, `status`, nullable `reason`, nullable `detail` |
| `control_ack` | `leaseId`, `inputSequence`, `status`, nullable `reason` |
| `safety_event` | `action`, `outcome`, `trigger`, nullable `leaseId`, nullable `lastInputSequence`, nullable `detail` |
| `protocol_error` | nullable `relatedMessageId`, stable `code`, nullable safe `detail` |

`command_request.action` is exactly `takeoff`, `landing`, or
`return_to_home`. Virtual-stick control is never encoded as a discrete command;
it uses `control_frame`.

## Fail-closed limits

- IDs: at most 64 characters and match the `messageId` pattern above.
- Names, versions, schemes, matrix IDs and capability row IDs: at most 128
  characters. Capability row IDs additionally match `^[a-z][a-z0-9_]*$`.
- Authentication credentials: at most 4096 characters. Human-readable
  assessment, reason and detail fields: at most 1024 characters.
- Negotiation arrays: at most 16 unique, nonblank values. A client hello must
  include `1.0`; an authentication-required server hello must offer a scheme.
- Capability rows: 1–256 unique IDs; `matrixId` is exactly
  `mini4pro-rcn3-g520-android`; `schemaVersion` is `1`; `lastUpdated` is a real
  `YYYY-MM-DD` date; the source digest is 64 lowercase hexadecimal characters.
- JavaScript-safe integer range is enforced. Sequences are
  `1..9007199254740991`; uptime and remaining lease time may also be zero.
- JSON numbers use the RFC 8259 grammar, including no leading zeroes. Integer
  fields accept any mathematically integral JSON number (`500`, `500.0`, and
  `5e2` are equivalent); fractional values remain invalid.
- Lease TTL: 500–30000 ms. Discrete-command TTL: 100–30000 ms. Control-frame
  TTL: 50–1000 ms. The server starts command/frame TTLs from receipt using a
  monotonic clock; browser wall-clock time is never trusted.
- Lease state nullability is exact: `available` has no lease fields; `held` has
  lease ID, holder and remaining time; `denied` has only a reason; `released`
  has only the ended lease ID; `expired` has the ended lease ID and a reason.
- Accepted/succeeded/applied responses have a null reason; rejected,
  failed/timed-out/cancelled, and rejected/stale responses require a nonblank
  reason.
- A safety event reports the attempted action `neutralize` separately from its
  `succeeded` or `failed` outcome. A failed neutralization requires a nonblank
  detail and must never be reported as if neutralization succeeded. The trigger
  vocabulary includes `actuation_readiness_lost` so a server-owned runtime
  gate transition cannot be confused with a browser request.

`command_ack.intentDigestSha256` is computed by the server over the complete
companion command intent (`commandId`, `leaseId`, `action`, `ttlMs`). It is not a
client authority assertion and must not use the vendor inbox's legacy
name/stream-only digest.

`manifest.json` records each fixture's direction, message type and SHA-256.
`CANONICAL.sha256` locks the manifest; the manifest in turn locks every fixture.
Both Kotlin and TypeScript tests read these same files.
