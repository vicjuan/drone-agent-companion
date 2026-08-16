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
  assert.match(main, /<dialog id="command-confirmation"/);
  assert.match(main, /batteryProgress\.value\s*=/);
  assert.match(main, /surface\[action\]/);
  assert.match(main, /surface\.virtual_stick/);
  assert.match(main, /getIntentReadiness\(discreteActionIntent\(action\)\)/);
  assert.match(main, /getControlSurfaceReadiness\(\)/);
  assert.match(main, /runtimeTruth\(state,\s*nowMonotonicMs\)/);
  assert.match(main, /runtime lock.*temporary commissioning grant/is);
  assert.doesNotMatch(main, /window\.confirm/);
  assert.doesNotMatch(main, /\.style\s*\./);
  assert.doesNotMatch(main, /setAttribute\([^)]*["']style["']/);
  assert.doesNotMatch(main, /\sstyle\s*=/);
  assert.doesNotMatch(index, /\sstyle\s*=/);
});

test("built video UI uses a validated external frame with minimum permissions", async () => {
  const [main, playback] = await Promise.all([
    readFile(new URL("../dist/assets/main.js", import.meta.url), "utf8"),
    readFile(
      new URL("../dist/assets/video-playback.js", import.meta.url),
      "utf8",
    ),
  ]);

  assert.match(main, /id="video-playback-heading"/);
  assert.match(main, /CONFIGURED \/ SYNTHETIC/);
  assert.match(main, /document\.createElement\("iframe"\)/);
  assert.doesNotMatch(main, /<iframe[^>]+src=/);
  assert.match(
    main,
    /frame\.sandbox\.add\("allow-scripts", "allow-same-origin"\)/,
  );
  assert.match(main, /frame\.allow = "autoplay"/);
  assert.match(main, /frame\.referrerPolicy = "no-referrer"/);
  assert.match(main, /frame\.setAttribute\("aria-label", frame\.title\)/);
  assert.match(
    main,
    /frame\.setAttribute\("aria-describedby", "video-truth"\)/,
  );
  assert.doesNotMatch(
    main,
    /allow-(?:downloads|forms|modals|orientation-lock|pointer-lock|popups|presentation|top-navigation)/,
  );
  assert.doesNotMatch(
    main,
    /frame\.allow\s*=\s*"[^"]*(?:camera|display-capture|geolocation|microphone)/,
  );
  assert.match(playback, /\/api\/console\/v1\/media/);
  assert.match(playback, /127\.0\.0\.1/);
  assert.doesNotMatch(playback, /window\.location/);
});

test("built video UI leaves iframe 404 and load failures as in-frame playback evidence", async () => {
  const [main, playback] = await Promise.all([
    readFile(new URL("../dist/assets/main.js", import.meta.url), "utf8"),
    readFile(
      new URL("../dist/assets/video-playback.js", import.meta.url),
      "utf8",
    ),
  ]);

  assert.match(main, /CONFIGURED \/ SYNTHETIC/);
  assert.match(main, /不代表 MediaMTX 在線或影像已播放/);
  assert.match(main, /播放真值請以 iframe 內實際畫面為準/);
  assert.doesNotMatch(main, /<h2 id="video-playback-heading">Live video<\/h2>/);
  assert.doesNotMatch(
    main,
    /frame\.addEventListener\(\s*["'](?:load|error)["']/,
  );
  assert.match(playback, /phase: "configured"/);
  assert.doesNotMatch(playback, /phase: "ready"/);
});
