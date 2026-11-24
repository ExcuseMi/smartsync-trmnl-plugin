# Smart Sync TRMNL plugin

A plugin for the G-Shock Smart Sync app (ExcuseMi/CasioGShockSmartSync, plugin interface `plugin-api`). It pushes each
watch's values to a TRMNL private plugin with the webhook strategy, so a TRMNL shows the watch without Home Assistant.

## Setup

1. On TRMNL: create a private plugin, strategy "Webhook", copy its Webhook URL.
2. In the app: Settings, TRMNL: paste the URL (one for all watches, or one per watch), turn "Send to TRMNL" on.
3. Optional: the minimum minutes between sends (6 to 240, default 10).

The markup that shows these values lives in the G-Shock TRMNL plugin (its webhook variant).

## TRMNL's webhook limits and how the plugin stays under them

From TRMNL's docs (docs.trmnl.com, "Webhooks", read 2026-11-24):

| Limit | Standard | TRMNL+ | Over it |
|---|---|---|---|
| Requests per hour (per plugin) | 12 | 30 | HTTP 429 |
| Payload (stored JSON) | 5 KB | 10 KB | HTTP 422 with the size |

| The plugin | Value |
|---|---|
| Sends | only when a watch's values changed (a fingerprint per watch and URL; the send time does not count) |
| Coalesces | waits 60 s after the first change; one request per URL carries every changed watch |
| Minimum interval per URL | 10 min by default, never under 6 min (at most 10 requests per hour) |
| Request budget | 3000 bytes, 60 % of the standard limit; a shared URL splits it between its watches |
| Shrinking, in turn | heart rate curve per 15, 30, 60 min, then no curve, then no hourly steps, then no tide |
| 429 | waits 15 min, doubling to 2 h; a longer Retry-After wins |
| Other 4xx (403, 404, 422) | waits 1 h, doubling to 24 h |
| 5xx, no network | 2 min, doubling to 1 h; a request that reached TRMNL still keeps the interval |
| Log | one App log line per kind of failure and one when sending works again; never the URL |

Sending runs in the plugin's own coroutine scope: events only mark a watch as changed, nothing waits on the network
while the watch is connected.

## Payload

```
{"merge_variables": {"w": {"<id>": {...}, ...}}, "merge_strategy": "deep_merge"}
```

`<id>` is the last 6 letters or digits of the watch's key (from its Bluetooth address). The same shape for a per watch
URL, with one entry. `deep_merge` keeps the other watches and the last known values; arrays are replaced. A value the
watch did not give is left out, never sent as 0.

| Key | Value |
|---|---|
| `n` | watch name |
| `t` | built at, epoch seconds |
| `c` | connected now |
| `b`, `bl` | battery percent, battery level (watch steps) |
| `tc` | temperature, degrees C |
| `ls` | last sync, epoch seconds |
| `f` | watch face (name, else its index) |
| `st`, `sg` | steps today, step goal |
| `sh` | steps per hour of today, 24 entries, null = no data |
| `kc`, `am` | kcal today, active minutes |
| `wm`, `rm`, `sm` | walking, running, still minutes |
| `hn`, `ht` | newest heart rate and its time (HH:MM) |
| `hl`, `hx`, `hrr` | heart rate min, max, resting today |
| `hc`, `hm` | heart rate curve of today (mean per `hm` minutes from 00:00, null = no value), minutes per entry |
| `ra` | health log recorded at (watch local time) |
| `td` | tide values, when the app has them |

Sizes (CI, `PayloadTest`): a full day of one watch (24 hourly steps, a 2 minute heart rate log as a 96 entry curve,
every value) is 859 bytes as a request, 17 % of the standard limit. Four watches on one URL fit 3000 bytes with a 30 or
60 minute curve.

## Build

GitHub Actions only (`.github/workflows/ci.yml`): unit tests, lint, release AAR. The plugin compiles against
`plugin-api` (`compileOnly`: the app provides it). For now that is a vendored copy in `vendor/plugin-api/` (see its
README), replaced by `com.github.ExcuseMi.CasioGShockSmartSync:plugin-api:plugin-api-v0.1.0` once tagged.

JitPack: `com.github.ExcuseMi:smartsync-trmnl-plugin:v<version>` (tags `v<version>`, version in `plugin/build.gradle`).

## License

Apache-2.0.
