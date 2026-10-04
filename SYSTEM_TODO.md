# System screen — tracking

Context: the menu drawer (`AppDrawer.kt`) has a "System" entry (`DrawerDestination.SYSTEM`)
opening `ui/dashboard/SystemScreen.kt` (`SystemRoute`/`SystemScreen`) — three read-only sections,
each in its own `Card`: General settings, Network, Boot. Loaded together when the screen opens and
on manual refresh (button in the `TopAppBar`), no polling (same mechanics as Alerts/Apps) — see
`DashboardViewModel.loadSystemSettings`.

## Verified live (2026-08-29)

Same conditions as `REPORTING_TODO.md`: throwaway API key provided by the user for their real
TrueNAS (LAN), read-only calls, key and raw responses not kept after use (scratch files deleted).

- **`GET /api/v2.0/system/general`** (`getSystemGeneral`): confirmed live. The real response is
  much bigger than what's actually read — it embeds the web GUI's TLS certificate **and private
  key** in the clear (`ui_certificate.privatekey`). `SystemGeneralDto` only declares the fields
  actually shown (`timezone`, `kbdmap`, `ui_port`, `ui_httpsport`, `ui_httpsredirect`,
  `ui_httpsprotocols`) — Gson silently ignores the rest, so nothing sensitive is ever deserialized
  in the app. **Careful if this file is ever extended**: never add a generic field like
  `Map<String, Any>` or declare `ui_certificate` on this DTO.
  - Values seen live: `kbdmap: "fr"`, `timezone: "Europe/Paris"`, `ui_port: 80`,
    `ui_httpsport: 443`, `ui_httpsredirect: false`, `ui_httpsprotocols: ["TLSv1.2", "TLSv1.3"]`.
- **`GET /api/v2.0/network/configuration`** (`getNetworkInfo`, 1st call): confirmed live.
  `hostname`, `domain`, `ipv4gateway`/`ipv6gateway`, `nameserver1`/`2`/`3` exactly as coded.
  `ipv6gateway` was an empty string (not `null`) on the tested server — `?.ifBlank { null }` handles
  that case on the app side.
- **`GET /api/v2.0/interface`** (`getNetworkInfo`, 2nd call): confirmed live on 2 physical
  interfaces. Important point: configured IP addresses live under **`state.aliases`** (the live
  kernel view: `[{"type": "INET", "address": "<server's local ip>", "netmask": 24, ...}]`),
  **not** the top-level `aliases` field (empty on both tested interfaces, since the IP was
  obtained via DHCP — the top-level `aliases` would only have entries for a static config).
  `state.link_state` seen as `"LINK_STATE_UP"`/`"LINK_STATE_DOWN"` — `linkUp` checks equality with
  `"LINK_STATE_UP"`.
- **`GET /api/v2.0/boot/get_state`** (`getBootInfo`, 1st call): confirmed live — same shape as
  a `pool.query` entry (the boot pool isn't a regular pool, but the middleware serializes it the
  same way): `name` ("boot-pool"), `status`, `healthy`, `size`, `allocated`, `scan` (same fields
  as `ScanDto`, including `start_time`/`end_time` as `{"$date": epochMillis}`). `warning`/
  `status_code`/`status_detail` are also present but not read (not shown on this screen).
- **`GET /api/v2.0/boot/environment/query`** (`getBootInfo`, 2nd call): **pitfall found and
  fixed** — the middleware method `boot.environment` has no `/boot/environment` REST route
  (404 confirmed); it's a CRUD-style method whose "list" variant is exposed under
  `/boot/environment/query` (confirmed via `GET /api/v2.0/openapi.json`, which also lists
  `/boot/environment/activate`, `/clone`, `/destroy`, `/keep` for the other actions, not
  implemented here). Fields confirmed: `id` (version, e.g. `"24.10.2.2"`), `active`, `activated`,
  `created` (`{"$date": ...}`), `used_bytes`. Field `used` (human string, e.g. `"2.66 GiB"`) seen
  but not read — `formatBytes(usedBytes)` is already used everywhere else in the app.

## Deliberate choices / known limits

- **Read-only**: nothing on this screen changes the config (no editing timezone, network, no
  activating a boot environment) — not requested, not done. If that changes, plan for a real
  confirmation flow (especially for activating a boot environment, which requires a reboot on the
  TrueNAS side).
- No polling (unlike CPU/Memory/Pools): this information rarely changes, like Alerts/Apps.
- The three sections are loaded in parallel (`async`) but each keeps its own loading/error state,
  so one section failing (e.g. the server responding badly to one of the three calls) doesn't
  prevent the other two from showing.

## Tests to do

- [ ] Open the System screen on device/emulator: verify the three sections show consistent,
  readable data.
- [ ] Verify the manual refresh button.
- [ ] Verify the partial-failure case (e.g. cutting the network while loading): the sections that
  succeeded should stay displayed, only the failed one should show the error.
- [ ] Verify on a server with more than 2 network interfaces and/or a static IP (to confirm that
  the top-level `aliases` is indeed empty by default and that `state.aliases` remains the right
  source even outside DHCP).
- [ ] Verify back navigation (top-left arrow) from the System screen to the dashboard.
