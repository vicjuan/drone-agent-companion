import assert from "node:assert/strict";
import test from "node:test";

import {
  INITIAL_VIDEO_PLAYBACK_STATE,
  MEDIA_CONFIG_ENDPOINT,
  decodeVideoPlaybackConfig,
  loadVideoPlayback,
} from "../dist/assets/video-playback.js";

const disabledConfig = () => ({
  sourceKind: null,
  streamId: null,
  pageUrl: null,
});

const enabledConfig = (overrides = {}) => ({
  sourceKind: "synthetic_mac",
  streamId: "mock-main",
  pageUrl: "http://127.0.0.1:8891/mock-main",
  ...overrides,
});

test("decoder accepts only the exact all-null disabled shape", () => {
  const decoded = decodeVideoPlaybackConfig(disabledConfig());

  assert.deepEqual(decoded, { enabled: false, ...disabledConfig() });
  assert.ok(Object.isFrozen(decoded));
  assert.deepEqual(INITIAL_VIDEO_PLAYBACK_STATE, { phase: "loading" });
  assert.ok(Object.isFrozen(INITIAL_VIDEO_PLAYBACK_STATE));

  for (const partial of [
    enabledConfig({ sourceKind: null }),
    enabledConfig({ streamId: null }),
    enabledConfig({ pageUrl: null }),
  ]) {
    assert.throws(
      () => decodeVideoPlaybackConfig(partial),
      /entirely enabled or entirely disabled/,
    );
  }
});

test("decoder accepts the canonical synthetic Mac MediaMTX page and freezes it", () => {
  const decoded = decodeVideoPlaybackConfig(enabledConfig());

  assert.deepEqual(decoded, {
    enabled: true,
    sourceKind: "synthetic_mac",
    streamId: "mock-main",
    pageUrl: "http://127.0.0.1:8891/mock-main",
  });
  assert.ok(Object.isFrozen(decoded));
});

test("decoder rejects missing and unknown response fields", () => {
  const missing = enabledConfig();
  delete missing.pageUrl;
  assert.throws(
    () => decodeVideoPlaybackConfig(missing),
    /missing or unknown fields/,
  );
  assert.throws(
    () => decodeVideoPlaybackConfig({ ...enabledConfig(), extra: true }),
    /missing or unknown fields/,
  );
  assert.throws(
    () => decodeVideoPlaybackConfig([enabledConfig()]),
    /must be an object/,
  );
});

test("decoder locks source kind while accepting the server canonical stream grammar", () => {
  assert.throws(
    () => decodeVideoPlaybackConfig(enabledConfig({ sourceKind: "dji" })),
    /sourceKind is not allowed/,
  );

  const alternate = decodeVideoPlaybackConfig(
    enabledConfig({
      streamId: "weekend-demo_2",
      pageUrl: "http://127.0.0.1:8891/weekend-demo_2",
    }),
  );
  assert.deepEqual(alternate, {
    enabled: true,
    sourceKind: "synthetic_mac",
    streamId: "weekend-demo_2",
    pageUrl: "http://127.0.0.1:8891/weekend-demo_2",
  });
});

test("decoder rejects stream IDs outside the server canonical grammar", () => {
  for (const streamId of [
    "",
    "-leading-dash",
    "_leading-underscore",
    "has.dot",
    "has/slash",
    "has space",
    "合成影像",
    "a".repeat(65),
    7,
  ]) {
    assert.throws(
      () => decodeVideoPlaybackConfig(enabledConfig({ streamId })),
      /streamId must be canonical/,
      String(streamId),
    );
  }
});

test("decoder rejects non-loopback and non-HTTP frame destinations", () => {
  for (const pageUrl of [
    "http://192.168.10.20:8891/mock-main",
    "http://127.0.0.2:8891/mock-main",
    "http://localhost:8891/mock-main",
    "http://[::1]:8891/mock-main",
    "https://127.0.0.1:8891/mock-main",
    "data:text/html,unsafe",
  ]) {
    assert.throws(
      () => decodeVideoPlaybackConfig(enabledConfig({ pageUrl })),
      /loopback|valid absolute URL/,
      pageUrl,
    );
  }
});

test("decoder rejects a non-listening zero media port", () => {
  assert.throws(
    () =>
      decodeVideoPlaybackConfig(
        enabledConfig({ pageUrl: "http://127.0.0.1:0/mock-main" }),
      ),
    /port must be valid/,
  );
});

test("decoder rejects URL and query credentials without reflecting them", () => {
  for (const pageUrl of [
    "http://operator:secret@127.0.0.1:8891/mock-main",
    "http://127.0.0.1:8891/mock-main?token=secret",
    "http://127.0.0.1:8891/mock-main?USER=operator",
    "http://127.0.0.1:8891/mock-main?password=secret",
  ]) {
    assert.throws(
      () => decodeVideoPlaybackConfig(enabledConfig({ pageUrl })),
      (error) => {
        assert.match(error.message, /must not contain credentials/);
        assert.doesNotMatch(error.message, /operator|secret/);
        return true;
      },
    );
  }
});

test("decoder requires the MediaMTX page path to equal streamId", () => {
  for (const pageUrl of [
    "http://127.0.0.1:8891/other",
    "http://127.0.0.1:8891/live/mock-main",
    "http://127.0.0.1:8891/mock-main/",
    "http://127.0.0.1:8891/mock-main/whep",
  ]) {
    assert.throws(
      () => decodeVideoPlaybackConfig(enabledConfig({ pageUrl })),
      /path must equal streamId/,
      pageUrl,
    );
  }
});

test("decoder rejects fragments queries and non-canonical URL syntax", () => {
  for (const pageUrl of [
    "http://127.0.0.1:8891/mock-main#other",
    "http://127.0.0.1:8891/mock-main#",
    "http://127.0.0.1:8891/mock-main?controls=false&controls=false",
    "http://127.0.0.1:8891/mock-main?controls=true",
    "http://127.0.0.1:8891/mock-main?unreviewed=true",
    "http://127.0.0.1:8891/other/../mock-main",
    "http://127.0.0.1:8891/mock-main?",
  ]) {
    assert.throws(
      () => decodeVideoPlaybackConfig(enabledConfig({ pageUrl })),
      /fragment|query parameters|canonical URL syntax/,
      pageUrl,
    );
  }
});

test("decoder rejects blank padded and oversized page URLs", () => {
  for (const pageUrl of [
    "",
    " http://127.0.0.1:8891/mock-main",
    `http://127.0.0.1:8891/mock-main?${"x".repeat(2_048)}`,
  ]) {
    assert.throws(
      () => decodeVideoPlaybackConfig(enabledConfig({ pageUrl })),
      /bounded URL string/,
      pageUrl,
    );
  }
});

test("loader uses the exact read-only endpoint and maps disabled to unavailable", async () => {
  let capturedInput;
  let capturedInit;
  const state = await loadVideoPlayback(async (input, init) => {
    capturedInput = input;
    capturedInit = init;
    return jsonResponse(disabledConfig());
  });

  assert.equal(MEDIA_CONFIG_ENDPOINT, "/api/console/v1/media");
  assert.equal(capturedInput, MEDIA_CONFIG_ENDPOINT);
  assert.deepEqual(capturedInit, {
    method: "GET",
    headers: { Accept: "application/json" },
    cache: "no-store",
    credentials: "same-origin",
    redirect: "error",
  });
  assert.deepEqual(state, { phase: "unavailable", reason: "media_disabled" });
  assert.ok(Object.isFrozen(state));
});

test("loader returns configured only after strict decoding", async () => {
  const state = await loadVideoPlayback(async () => jsonResponse(enabledConfig()));

  assert.equal(state.phase, "configured");
  assert.deepEqual(state.config, {
    enabled: true,
    sourceKind: "synthetic_mac",
    streamId: "mock-main",
    pageUrl: "http://127.0.0.1:8891/mock-main",
  });
  assert.ok(Object.isFrozen(state));
  assert.ok(Object.isFrozen(state.config));
});

test("loader converts transport HTTP MIME JSON and schema failures into closed error states", async () => {
  const transport = await loadVideoPlayback(async () => {
    throw new Error("network detail must not escape");
  });
  assert.deepEqual(transport, {
    phase: "error",
    reason: "media_endpoint_unavailable",
  });

  for (const fetchMedia of [
    async () => jsonResponse(disabledConfig(), { status: 503 }),
    async () => new Response(JSON.stringify(disabledConfig()), { status: 200 }),
    async () =>
      new Response("not json", {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    async () => jsonResponse({ ...enabledConfig(), unexpected: true }),
  ]) {
    assert.deepEqual(await loadVideoPlayback(fetchMedia), {
      phase: "error",
      reason: "media_response_rejected",
    });
  }
});

function jsonResponse(value, init = {}) {
  const headers = new Headers(init.headers);
  headers.set("content-type", "application/json; charset=UTF-8");
  return new Response(JSON.stringify(value), { ...init, headers });
}
