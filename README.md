# drone-agent-companion

Headless DJI Mini 4 Pro agent for the G520 (MediaTek Genio 520) companion
computer running Android. Reuses the vendor-neutral modules of
[`drone-agent-android`](https://github.com/vicjuan/drone-agent-android); the
touch UI is replaced by an on-device web server operated from a browser.

> **Evidence boundary:** capability evidence recorded against the
> Pixel 8 Pro stack does **not** transfer to this stack. Every hardware row for
> **Mini 4 Pro + RC-N3 + G520 (Android)** starts at `UNKNOWN` until first-hand
> evidence exists on this stack. See [`docs/architecture.md`](docs/architecture.md).

## What changes vs. drone-agent-android

| Concern | drone-agent-android | drone-agent-companion |
| --- | --- | --- |
| Host device | Pixel 8 Pro (touch UI) | G520, headless |
| Operator UI | Compose (`app-debug-ui`) | Browser SPA served from the device |
| Operator protocol | Compose UI reads the in-process state model | Own console protocol, served over WebSocket |
| `gateway` role | Client of the drone-platform backend | Unchanged. The frozen agent protocol is not bent to serve the browser; the console protocol is separate and shares only the `core` state model and the `gateway.admission` policy objects |
| Reused modules | — | `core`, `drone-actuation`, `gateway`, `vision`, `drone-observation`, `adapter-dji`, `adapter-mock` |

## Documents

- [`AGENTS.md`](AGENTS.md): engineering discipline and hard boundaries,
  inherited from `drone-agent-android`. Agents start here.
- [`docs/architecture.md`](docs/architecture.md): system architecture, module
  reuse strategy, safety boundaries, and open decisions.
- [`docs/implementation-order.md`](docs/implementation-order.md): Weekend Web
  Control MVP-first coding order, with per-step completion criteria.
