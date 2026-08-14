<!-- GENERATED from config/capability-matrix/g520-stack.json; DO NOT EDIT BY HAND. -->
# G520 Stack Capability Matrix

這是 Mini 4 Pro + RC-N3 + G520（Android）stack 的唯一硬體證據矩陣。
Pixel 8 Pro 或其他 stack 的證據不得轉移；mock、emulator、編譯與單元測試也不得升級硬體狀態。

- Matrix ID：`mini4pro-rcn3-g520-android`
- Aircraft：DJI Mini 4 Pro
- Remote controller：DJI RC-N3
- Host：MediaTek Genio 520 (Android)
- Last updated：`2026-08-15`
- Machine source：[`config/capability-matrix/g520-stack.json`](../config/capability-matrix/g520-stack.json)

## Capability evidence

狀態語彙只有 `CONFIRMED`、`LIMITED`、`UNKNOWN`。失敗結果附在 `UNKNOWN` 列；不能用 mock／emulator 成功取代第一手 G520 證據。

| ID | Capability | Status | Current assessment | Verification method | Tracking | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| `battery` | Battery telemetry | `UNKNOWN` | No first-hand evidence from the target stack. | Capture charge, voltage and temperature from a connected aircraft on G520. | #9 | — |
| `gps_position` | GPS position telemetry | `UNKNOWN` | No first-hand evidence from the target stack. | Capture fix, coordinates, altitude and satellite count outdoors on the target stack. | #9 | — |
| `flight_state` | Flight-state telemetry | `UNKNOWN` | No first-hand evidence from the target stack. | Correlate armed, flying, mode, home point and velocity fields with target-stack observations. | #9 | — |
| `gimbal_camera_state` | Gimbal and camera telemetry | `UNKNOWN` | No first-hand evidence from the target stack. | Capture gimbal attitude and camera mode/recording state from the target stack. | #9 | — |
| `stream_capability` | Aircraft-camera RTMP stream | `UNKNOWN` | No first-hand evidence from the target stack. | Publish the aircraft camera to the selected RTMP ingest and recover the stream through WHEP. | #7 | — |
| `headless_boot_service` | Headless boot service | `UNKNOWN` | Emulator behavior is not G520 hardware evidence. | Cold-boot G520 and verify foreground service startup and recovery without an Activity. | #2 | — |
| `point_to_point_ethernet` | Point-to-point Ethernet console | `UNKNOWN` | The topology is selected but has not been verified on G520. | Verify fixed IP, interface-specific bind, SPA fetch and stable WebSocket over direct Ethernet. | #6 | — |
| `rcn3_usb_attach` | RC-N3 USB attach and permission | `UNKNOWN` | No first-hand G520 USB-host or permission evidence. | Attach RC-N3 to G520, record enumeration, permission and reconnect behavior. | #8 | — |
| `msdk_registration_activation` | MSDK registration and activation | `UNKNOWN` | No first-hand MSDK activation evidence from G520. | Register MSDK on G520 and capture activation plus product-connection state. | #9 | — |
| `aircraft_connection` | Mini 4 Pro aircraft connection | `UNKNOWN` | No first-hand aircraft connection evidence from G520. | Record the G520 to RC-N3 to Mini 4 Pro connection lifecycle and stable connected state. | #9 | — |
| `rcn3_four_axis_input` | RC-N3 four-axis input | `UNKNOWN` | No first-hand stick-axis evidence from the target stack. | Capture all four RC-N3 axes with neutral, sign and range checks on G520. | #8, #9 | — |
| `takeoff_actuation` | Takeoff actuation | `UNKNOWN` | Mock takeoff is simulation and does not confirm hardware actuation. | Run the approved commissioning takeoff case and retain admission, command and flight-state evidence. | #9 | — |
| `landing_actuation` | Landing actuation | `UNKNOWN` | Mock landing is simulation and does not confirm hardware actuation. | Run the approved commissioning landing case and retain command plus grounded-state evidence. | #9 | — |
| `rth_actuation` | Return-to-home actuation | `UNKNOWN` | No target-stack execution path has been verified. | Run the approved commissioning RTH case and capture admission, execution and terminal state. | #9 | — |
| `virtual_stick_actuation` | Virtual-stick actuation | `UNKNOWN` | Mock velocity commands do not confirm G520 or aircraft authority. | Verify climb, descend, forward, backward, yaw-left and yaw-right under commissioning controls. | #9 | — |
| `continuous_control_neutralization` | Continuous-control neutralization | `UNKNOWN` | Software tests cannot confirm the aircraft response on this stack. | Observe neutral behavior after release, focus loss, disconnect, lease expiry and dead-man timeout. | #9 | — |
| `opencv_on_device_recognition` | On-device OpenCV recognition | `UNKNOWN` | Desktop and emulator runs are not G520 runtime evidence. | Identify the loaded G520 native build and retain a decoded-frame to OpenCV result trace. | #14 | — |

## Evidence records

目前沒有可升級狀態的第一手 G520 evidence record。格式與三種環境的明確範例見 [`docs/evidence/README.md`](evidence/README.md)。

## Runtime state is separate

Web Console 的 adapter、aircraft connection、actuation lock 與 control lease 是即時 runtime state；它們不得覆寫或推導本矩陣的 evidence status。
