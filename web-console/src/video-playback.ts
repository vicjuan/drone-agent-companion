export const MEDIA_CONFIG_ENDPOINT = "/api/console/v1/media";

const MEDIA_CONFIG_KEYS = ["pageUrl", "sourceKind", "streamId"] as const;
const SYNTHETIC_SOURCE_KIND = "synthetic_mac" as const;
const SYNTHETIC_MEDIA_HOST = "127.0.0.1";
const MAX_PAGE_URL_LENGTH = 2_048;
const CANONICAL_STREAM_ID = /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/;
const CREDENTIAL_PARAMETER_NAMES = new Set([
  "authorization",
  "jwt",
  "pass",
  "password",
  "token",
  "user",
  "username",
]);

export type DisabledVideoPlaybackConfig = Readonly<{
  enabled: false;
  sourceKind: null;
  streamId: null;
  pageUrl: null;
}>;

export type SyntheticMacVideoPlaybackConfig = Readonly<{
  enabled: true;
  sourceKind: typeof SYNTHETIC_SOURCE_KIND;
  streamId: string;
  pageUrl: string;
}>;

export type VideoPlaybackConfig =
  | DisabledVideoPlaybackConfig
  | SyntheticMacVideoPlaybackConfig;

export type VideoPlaybackState =
  | Readonly<{ phase: "loading" }>
  | Readonly<{ phase: "unavailable"; reason: "media_disabled" }>
  | Readonly<{
      phase: "configured";
      config: SyntheticMacVideoPlaybackConfig;
    }>
  | Readonly<{
      phase: "error";
      reason:
        | "media_endpoint_unavailable"
        | "media_response_rejected";
    }>;

export type MediaConfigFetch = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

export const INITIAL_VIDEO_PLAYBACK_STATE: VideoPlaybackState = Object.freeze({
  phase: "loading",
});

/**
 * Decodes the server-owned browser media bootstrap without trusting it as an iframe URL.
 * Only the Weekend MVP's loopback MediaMTX synthetic source is accepted. Any future source
 * kind or network placement must add an explicit reviewed policy instead of widening this one.
 */
export function decodeVideoPlaybackConfig(value: unknown): VideoPlaybackConfig {
  const record = requirePlainRecord(value);
  requireExactKeys(record, MEDIA_CONFIG_KEYS);

  const { pageUrl, sourceKind, streamId } = record;
  const nullFields = [pageUrl, sourceKind, streamId].filter(
    (field) => field === null,
  ).length;
  if (nullFields === MEDIA_CONFIG_KEYS.length) {
    return Object.freeze({
      enabled: false,
      sourceKind: null,
      streamId: null,
      pageUrl: null,
    });
  }
  if (nullFields !== 0) {
    throw new Error("Media config must be entirely enabled or entirely disabled");
  }
  if (sourceKind !== SYNTHETIC_SOURCE_KIND) {
    throw new Error("Media config sourceKind is not allowed");
  }
  if (
    typeof streamId !== "string" ||
    !CANONICAL_STREAM_ID.test(streamId)
  ) {
    throw new Error("Media config streamId must be canonical");
  }
  if (
    typeof pageUrl !== "string" ||
    pageUrl.length === 0 ||
    pageUrl.length > MAX_PAGE_URL_LENGTH ||
    pageUrl !== pageUrl.trim()
  ) {
    throw new Error("Media config pageUrl must be a bounded URL string");
  }

  const parsed = parseAllowedPageUrl(pageUrl, streamId);
  return Object.freeze({
    enabled: true,
    sourceKind,
    streamId,
    pageUrl: parsed.toString(),
  });
}

/** Loads the read-only bootstrap. Errors become a closed UI state and never escape to controls. */
export async function loadVideoPlayback(
  fetchMedia: MediaConfigFetch = globalThis.fetch.bind(globalThis),
): Promise<VideoPlaybackState> {
  let response: Response;
  try {
    response = await fetchMedia(MEDIA_CONFIG_ENDPOINT, {
      method: "GET",
      headers: { Accept: "application/json" },
      cache: "no-store",
      credentials: "same-origin",
      redirect: "error",
    });
  } catch {
    return Object.freeze({
      phase: "error",
      reason: "media_endpoint_unavailable",
    });
  }

  if (!response.ok || !isJsonContentType(response.headers.get("content-type"))) {
    return Object.freeze({
      phase: "error",
      reason: "media_response_rejected",
    });
  }

  try {
    const config = decodeVideoPlaybackConfig(await response.json());
    if (!config.enabled) {
      return Object.freeze({ phase: "unavailable", reason: "media_disabled" });
    }
    return Object.freeze({ phase: "configured", config });
  } catch {
    return Object.freeze({
      phase: "error",
      reason: "media_response_rejected",
    });
  }
}

function parseAllowedPageUrl(
  pageUrl: string,
  streamId: string,
): URL {
  let parsed: URL;
  try {
    parsed = new URL(pageUrl);
  } catch {
    throw new Error("Media config pageUrl is not a valid absolute URL");
  }
  if (parsed.protocol !== "http:") {
    throw new Error("Media config pageUrl must use loopback HTTP");
  }
  if (parsed.hostname !== SYNTHETIC_MEDIA_HOST) {
    throw new Error("Media config pageUrl must use canonical IPv4 loopback");
  }
  if (parsed.port === "0") {
    throw new Error("Media config pageUrl port must be valid");
  }
  if (parsed.username !== "" || parsed.password !== "") {
    throw new Error("Media config pageUrl must not contain credentials");
  }
  if (parsed.pathname !== `/${streamId}`) {
    throw new Error("Media config pageUrl path must equal streamId");
  }
  if (parsed.hash !== "" || pageUrl.includes("#")) {
    throw new Error("Media config pageUrl must not contain a fragment");
  }

  for (const name of parsed.searchParams.keys()) {
    const normalizedName = name.toLowerCase();
    if (CREDENTIAL_PARAMETER_NAMES.has(normalizedName)) {
      throw new Error("Media config pageUrl must not contain credentials");
    }
  }
  if (parsed.search !== "" || pageUrl.includes("?")) {
    throw new Error("Media config pageUrl must not contain query parameters");
  }
  if (parsed.toString() !== pageUrl) {
    throw new Error("Media config pageUrl must use canonical URL syntax");
  }
  return parsed;
}

function requirePlainRecord(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error("Media config must be an object");
  }
  const prototype = Object.getPrototypeOf(value);
  if (prototype !== Object.prototype && prototype !== null) {
    throw new Error("Media config must be a plain object");
  }
  return value as Record<string, unknown>;
}

function requireExactKeys(
  record: Record<string, unknown>,
  expected: readonly string[],
): void {
  const keys = Reflect.ownKeys(record);
  if (
    keys.length !== expected.length ||
    keys.some((key) => typeof key !== "string" || !expected.includes(key))
  ) {
    throw new Error("Media config has missing or unknown fields");
  }
}

function isJsonContentType(value: string | null): boolean {
  return value !== null && /^application\/json(?:\s*;|$)/i.test(value);
}
