import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("built UI uses a progress value and contains no inline style mutation", async () => {
  const main = await readFile(
    new URL("../dist/assets/main.js", import.meta.url),
    "utf8",
  );
  const index = await readFile(new URL("../dist/index.html", import.meta.url), "utf8");

  assert.match(main, /<progress id="battery-progress"/);
  assert.match(main, /batteryProgress\.value\s*=/);
  assert.doesNotMatch(main, /\.style\s*\./);
  assert.doesNotMatch(main, /setAttribute\([^)]*["']style["']/);
  assert.doesNotMatch(main, /\sstyle\s*=/);
  assert.doesNotMatch(index, /\sstyle\s*=/);
});
