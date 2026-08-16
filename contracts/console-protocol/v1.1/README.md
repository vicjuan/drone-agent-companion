# Companion Console Protocol v1.1 delta

This directory is a delta over the frozen v1.0 contract. `manifest.json` records
the SHA-256 of `../v1/manifest.json`; the v1.0 manifest continues to lock every
inherited envelope, message, fixture, error code and limit.

Negotiation always bootstraps with a v1.0 `client_hello`. A client advertises
`["1.1", "1.0"]`, the server selects the best common version, and a selected
v1.1 session then carries v1.1 envelopes. A `client_hello` inside a v1.1 envelope
is invalid.

v1.1 adds only the server-to-client `commissioning_authority_state` observation.
It is recipient-relative, read-only and server-owned. The browser cannot start,
revoke, renew or widen commissioning authority, and the client-to-server message
inventory is unchanged. Public `runtime_state.actuationLock` remains the
observer truth and stays `locked` during hardware commissioning; this authority
view does not change or contradict it.

The server sends `server_hello` before the session's initial authority snapshot.
When a terminal transition is observable on a writable transport, its terminal
authority snapshot precedes transport close. `stateRevision` is capture order,
so a fresh observer's `no_active_session` snapshot may have a nonzero revision;
that shape still has a null commissioning ID and generation `"0"`.
