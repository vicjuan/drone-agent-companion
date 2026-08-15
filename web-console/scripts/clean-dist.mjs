import { rm } from "node:fs/promises";

// TypeScript writes into dist/assets, so stale-output cleanup must happen before tsc rather than
// inside copy-static.mjs. Otherwise copying the SPA shell would erase the freshly compiled modules.
await rm(new URL("../dist/", import.meta.url), { recursive: true, force: true });
