export type CapabilityEvidenceStatus = "CONFIRMED" | "LIMITED" | "UNKNOWN";
export type EvidenceEnvironment =
  | "MOCK"
  | "ANDROID_EMULATOR"
  | "G520_HARDWARE";
export type EvidenceOutcome = "PASS" | "LIMITED" | "FAIL";

export interface CapabilityEvidenceRow {
  readonly id: string;
  readonly title: string;
  readonly status: CapabilityEvidenceStatus;
  readonly assessment: string;
  readonly verificationMethod: string;
  readonly trackingIssues: readonly number[];
  readonly evidenceRefs: readonly string[];
}

export interface CapabilityEvidenceRecord {
  readonly id: string;
  readonly recordKind: "EVIDENCE";
  readonly environment: EvidenceEnvironment;
  readonly capturedAt: string;
  readonly commit: string;
  readonly operator: string;
  readonly runtimeProfile: string;
  readonly deviceSetId: string | null;
  readonly rawLogLocation: string;
  readonly trackingIssue: number;
  readonly outcome: EvidenceOutcome;
  readonly summary: string;
}

export interface G520CapabilityMatrix {
  readonly schemaVersion: 1;
  readonly matrixId: "mini4pro-rcn3-g520-android";
  readonly targetStack: {
    readonly aircraft: "DJI Mini 4 Pro";
    readonly remoteController: "DJI RC-N3";
    readonly host: "MediaTek Genio 520 (Android)";
  };
  readonly lastUpdated: string;
  readonly rows: readonly CapabilityEvidenceRow[];
  readonly evidenceRecords: readonly CapabilityEvidenceRecord[];
}

const MATRIX_URL = "/capability-matrix/g520-stack.json";
const MATRIX_ID = "mini4pro-rcn3-g520-android";
const TARGET_STACK = {
  aircraft: "DJI Mini 4 Pro",
  remoteController: "DJI RC-N3",
  host: "MediaTek Genio 520 (Android)",
} as const;
const REQUIRED_ROW_IDS = new Set([
  "battery",
  "gps_position",
  "flight_state",
  "gimbal_camera_state",
  "stream_capability",
  "headless_boot_service",
  "point_to_point_ethernet",
  "rcn3_usb_attach",
  "msdk_registration_activation",
  "aircraft_connection",
  "rcn3_four_axis_input",
  "takeoff_actuation",
  "landing_actuation",
  "rth_actuation",
  "virtual_stick_actuation",
  "continuous_control_neutralization",
  "opencv_on_device_recognition",
]);
const STATUS_VALUES = new Set<CapabilityEvidenceStatus>([
  "CONFIRMED",
  "LIMITED",
  "UNKNOWN",
]);
const ENVIRONMENT_VALUES = new Set<EvidenceEnvironment>([
  "MOCK",
  "ANDROID_EMULATOR",
  "G520_HARDWARE",
]);
const OUTCOME_VALUES = new Set<EvidenceOutcome>(["PASS", "LIMITED", "FAIL"]);
const DOCUMENT_FIELDS = new Set([
  "schemaVersion",
  "matrixId",
  "targetStack",
  "lastUpdated",
  "rows",
  "evidenceRecords",
]);
const TARGET_FIELDS = new Set(["aircraft", "remoteController", "host"]);
const ROW_FIELDS = new Set([
  "id",
  "title",
  "status",
  "assessment",
  "verificationMethod",
  "trackingIssues",
  "evidenceRefs",
]);
const EVIDENCE_FIELDS = new Set([
  "id",
  "recordKind",
  "environment",
  "capturedAt",
  "commit",
  "operator",
  "runtimeProfile",
  "deviceSetId",
  "rawLogLocation",
  "trackingIssue",
  "outcome",
  "summary",
]);
const COMMIT_PATTERN = /^[0-9a-f]{40}$/;
const EVIDENCE_ID_PATTERN = /^[a-z0-9][a-z0-9._-]*$/;
const DEVICE_SET_PATTERN =
  /^device-set:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const TEMPLATE_DEVICE_SET_ID =
  "device-set:00000000-0000-4000-8000-000000000000";
const RAW_EVIDENCE_PATTERN =
  /^evidence:\/\/bundle\/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\/[A-Za-z0-9._-]+(?:\/[A-Za-z0-9._-]+)*$/;
const TEMPLATE_RAW_EVIDENCE_PREFIX =
  "evidence://bundle/00000000-0000-4000-8000-000000000000/";

export async function loadCapabilityEvidenceMatrix(
  fetcher: typeof fetch = fetch,
): Promise<G520CapabilityMatrix> {
  const response = await fetcher(MATRIX_URL, { cache: "no-store" });
  if (!response.ok) {
    throw new Error(`Capability matrix request failed: HTTP ${response.status}`);
  }
  const value: unknown = await response.json();
  assertCapabilityMatrix(value);
  return deepFreeze(value);
}

/** Mirrors the Kotlin promotion gate so the UI rejects unsupported evidence claims. */
export function assertCapabilityMatrix(
  value: unknown,
): asserts value is G520CapabilityMatrix {
  if (!isRecord(value) || value.schemaVersion !== 1) {
    throw new Error("Unsupported or malformed capability matrix");
  }
  requireExactKeys(value, DOCUMENT_FIELDS, "capability matrix");
  if (value.matrixId !== MATRIX_ID) {
    throw new Error("Capability matrix targets an unexpected stack");
  }
  const lastUpdated = requireDate(value, "lastUpdated");
  if (!isRecord(value.targetStack)) {
    throw new Error("Capability matrix targetStack is missing");
  }
  requireExactKeys(value.targetStack, TARGET_FIELDS, "targetStack");
  for (const field of ["aircraft", "remoteController", "host"] as const) {
    if (value.targetStack[field] !== TARGET_STACK[field]) {
      throw new Error(`Capability matrix targetStack.${field} is unexpected`);
    }
  }
  if (!Array.isArray(value.rows) || !Array.isArray(value.evidenceRecords)) {
    throw new Error("Capability matrix rows or evidenceRecords are missing");
  }

  const evidenceById = new Map<string, Record<string, unknown>>();
  for (const candidate of value.evidenceRecords) {
    if (!isRecord(candidate)) {
      throw new Error("Malformed capability evidence record");
    }
    requireExactKeys(candidate, EVIDENCE_FIELDS, "capability evidence");
    const id = requireString(candidate, "id");
    if (!EVIDENCE_ID_PATTERN.test(id)) {
      throw new Error(`Capability evidence ${id} has an invalid ID`);
    }
    if (candidate.recordKind !== "EVIDENCE") {
      throw new Error(`Capability evidence ${id} is not a production record`);
    }
    const environment = requireEnum(candidate, "environment", ENVIRONMENT_VALUES);
    requireEnum(candidate, "outcome", OUTCOME_VALUES);
    const capturedAt = requireInstant(candidate, "capturedAt");
    if (capturedAt.toISOString().slice(0, 10) > lastUpdated) {
      throw new Error(`Capability evidence ${id} is newer than matrix lastUpdated`);
    }
    const commit = requireString(candidate, "commit");
    if (!COMMIT_PATTERN.test(commit) || /^0+$/.test(commit)) {
      throw new Error(`Capability evidence ${id} has an invalid commit`);
    }
    const operator = requireString(candidate, "operator");
    requireString(candidate, "runtimeProfile");
    const rawLogLocation = requireString(candidate, "rawLogLocation");
    requireString(candidate, "summary");
    if (
      operator === "example-operator" ||
      !RAW_EVIDENCE_PATTERN.test(rawLogLocation) ||
      rawLogLocation
        .slice(rawLogLocation.indexOf("/", "evidence://bundle/".length) + 1)
        .split("/")
        .some((segment) => segment === "." || segment === "..") ||
      rawLogLocation.startsWith(TEMPLATE_RAW_EVIDENCE_PREFIX)
    ) {
      throw new Error(`Capability evidence ${id} has an invalid raw evidence reference`);
    }
    if (!Number.isInteger(candidate.trackingIssue) || Number(candidate.trackingIssue) <= 0) {
      throw new Error(`Capability evidence ${id} has invalid trackingIssue`);
    }
    if (environment === "G520_HARDWARE") {
      if (
        typeof candidate.deviceSetId !== "string" ||
        !DEVICE_SET_PATTERN.test(candidate.deviceSetId) ||
        candidate.deviceSetId === TEMPLATE_DEVICE_SET_ID
      ) {
        throw new Error(`Capability evidence ${id} has invalid deviceSetId`);
      }
    } else if (candidate.deviceSetId !== null) {
      throw new Error(`Capability evidence ${id} must not claim a hardware device set`);
    }
    if (evidenceById.has(id)) {
      throw new Error(`Duplicate capability evidence ${id}`);
    }
    evidenceById.set(id, candidate);
  }

  const rowIds = new Set<string>();
  const referencedEvidence = new Set<string>();
  for (const candidate of value.rows) {
    if (!isRecord(candidate)) {
      throw new Error("Malformed capability row");
    }
    requireExactKeys(candidate, ROW_FIELDS, "capability row");
    const id = requireString(candidate, "id");
    requireString(candidate, "title");
    requireString(candidate, "assessment");
    requireString(candidate, "verificationMethod");
    const status = requireEnum(candidate, "status", STATUS_VALUES);
    const trackingIssues = requirePositiveIntegerArray(candidate, "trackingIssues");
    const evidenceRefs = requireStringArray(candidate, "evidenceRefs");
    requireUnique(trackingIssues, `Capability ${id} has duplicate tracking issues`);
    requireUnique(evidenceRefs, `Capability ${id} has duplicate evidence references`);
    if (rowIds.has(id)) {
      throw new Error(`Duplicate capability row ${id}`);
    }
    rowIds.add(id);

    const evidence = evidenceRefs.map((reference) => {
      const record = evidenceById.get(reference);
      if (record === undefined) {
        throw new Error(`Capability row ${id} references missing evidence ${reference}`);
      }
      if (!trackingIssues.includes(Number(record.trackingIssue))) {
        throw new Error(`Capability ${id} cites evidence from an unrelated issue`);
      }
      referencedEvidence.add(reference);
      return record;
    });
    const requiredOutcome = status === "CONFIRMED" ? "PASS" : "LIMITED";
    if (
      status !== "UNKNOWN" &&
      !evidence.some(
        (record) =>
          record.environment === "G520_HARDWARE" &&
          record.recordKind === "EVIDENCE" &&
          record.outcome === requiredOutcome,
      )
    ) {
      throw new Error(`Capability ${id} lacks matching first-hand G520 evidence`);
    }
  }

  if (
    rowIds.size !== REQUIRED_ROW_IDS.size ||
    ![...REQUIRED_ROW_IDS].every((id) => rowIds.has(id))
  ) {
    throw new Error("Capability matrix inventory does not match the G520 schema");
  }
  for (const id of evidenceById.keys()) {
    if (!referencedEvidence.has(id)) {
      throw new Error(`Capability evidence ${id} is orphaned`);
    }
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function requireString(record: Record<string, unknown>, key: string): string {
  const value = record[key];
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new Error(`Capability matrix field ${key} must be a non-empty string`);
  }
  return value;
}

function requireDate(record: Record<string, unknown>, key: string): string {
  const value = requireString(record, key);
  const parsed = new Date(`${value}T00:00:00Z`);
  if (
    !/^\d{4}-\d{2}-\d{2}$/.test(value) ||
    Number.isNaN(parsed.valueOf()) ||
    parsed.toISOString().slice(0, 10) !== value
  ) {
    throw new Error(`Capability matrix field ${key} must be an ISO date`);
  }
  return value;
}

function requireInstant(record: Record<string, unknown>, key: string): Date {
  const value = requireString(record, key);
  const instant = new Date(value);
  if (
    Number.isNaN(instant.valueOf()) ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(
      value,
    )
  ) {
    throw new Error(`Capability matrix field ${key} must be an RFC3339 instant`);
  }
  return instant;
}

function requireEnum<T extends string>(
  record: Record<string, unknown>,
  key: string,
  values: ReadonlySet<T>,
): T {
  const value = record[key];
  if (typeof value !== "string" || !values.has(value as T)) {
    throw new Error(`Capability matrix field ${key} has an invalid value`);
  }
  return value as T;
}

function requirePositiveIntegerArray(
  record: Record<string, unknown>,
  key: string,
): number[] {
  const value = record[key];
  if (
    !Array.isArray(value) ||
    value.length === 0 ||
    !value.every((item) => Number.isInteger(item) && Number(item) > 0)
  ) {
    throw new Error(`Capability matrix field ${key} must contain positive integers`);
  }
  return value as number[];
}

function requireStringArray(
  record: Record<string, unknown>,
  key: string,
): string[] {
  const value = record[key];
  if (!Array.isArray(value) || !value.every((item) => typeof item === "string")) {
    throw new Error(`Capability matrix field ${key} must contain strings`);
  }
  return value as string[];
}

function requireUnique<T>(values: readonly T[], message: string): void {
  if (new Set(values).size !== values.length) {
    throw new Error(message);
  }
}

function requireExactKeys(
  record: Record<string, unknown>,
  expected: ReadonlySet<string>,
  context: string,
): void {
  const keys = Object.keys(record);
  if (keys.length !== expected.size || !keys.every((key) => expected.has(key))) {
    throw new Error(`${context} contains missing or unknown fields`);
  }
}

function deepFreeze<T>(value: T): T {
  if (typeof value !== "object" || value === null || Object.isFrozen(value)) {
    return value;
  }
  for (const child of Object.values(value)) {
    deepFreeze(child);
  }
  return Object.freeze(value);
}
