# Linux runtime handoff

## Scope

Production scope: Linux x86_64, systemd-based Fedora 44, Debian 12 and
Ubuntu 24.04+. GUI executable is lowercase `qnzapret`; application ID remains
`dev.qnzapret`.

Linux uses NFQUEUE, not TUN:

```text
Flutter GUI (regular user)
  -> MethodChannel/EventChannel
  -> Linux runner
  -> system D-Bus dev.qnzapret.Runtime1
  -> Polkit dev.qnzapret.runtime.manage
  -> qnzapret-runtime (root/systemd)
  -> bundled nfqws2 + inet qnzapret

Flutter Start/Stop
  -> session D-Bus dev.qnzapret.Telegram1
  -> qnzapret-telegram-sidecar (regular user/systemd --user)
  -> pinned Flowseal headless core on 127.0.0.1:1443
```

GUI and privileged runtime are never one process. GUI does not execute
`nft`, `iptables`, `sysctl`, `systemctl`, `pkexec` or a shell.

## System D-Bus and Polkit

- bus: `dev.qnzapret.Runtime1`
- object: `/dev/qnzapret/Runtime1`
- interface: `dev.qnzapret.Runtime1`
- API version: `1.0.0`

Read-only `GetVersion`, `Prepare` and `GetSnapshot` are available without
root. `Start` and `Stop` call PolicyKit `CheckAuthorization` with a
`system-bus-name` subject built from the real unique D-Bus sender.

Default policy:

- active local session: `auth_admin_keep`;
- inactive session: `auth_admin`;
- other contexts: denied.

The runtime stores the owner UID in its snapshot. A second user cannot replace
an active transaction. Runtime state is root-owned in `/run/qnzapret`; no
world-writable socket/config/state is created.

## Strategy compiler

`StrategyProfile` is serialized by the runner, then parsed and validated again
inside the daemon. The compiler accepts only:

- `UnmatchedTrafficPolicy.direct`;
- TCP ports 80/443 and UDP port 443;
- protocols `http`, `tls`, `quic`;
- portable actions `fake`, `udpFake`, `split(position=1)` with input repeats
  equal to 1;
- canonical `qnzapret/lists/*` and `qnzapret/payloads/*` paths;
- central queue `200`.

No profile value becomes shell text. `nfqws2` and `nft` are invoked through
subprocess argument arrays; nftables input is passed through stdin.

Linux compiles that portable profile to a Fedora-smoke-verified strategy for
the pinned zapret2 `v0.9.5.2` binary rather than reusing Android socket
semantics. Every protocol uses `out-range=-d5`:

- HTTP: `fake_default_http`, `tcp_ts=-100`, two fake repeats and
  `multisplit(seqovl=2, pos=midsld, tcp_ts_up)`;
- TLS: canonical `tls_google` blob, `tcp_ts=-100`, two fake repeats and
  `multisplit(seqovl=2, pos=midsld, tcp_ts_up)` with that blob as the overlap
  pattern;
- QUIC: canonical `quic_google` blob, IPv4/IPv6 autottl `-1,3-10`, two fake
  repeats, then disordered UDP fragmentation at position 8.

`nfqws2` receives `--fwmark=0x40000000`, `--bind-fix4/6` and canonical blob
paths, then drops from root to the locked `qnzapret-runtime` system account.
The Android strategy is deliberately unchanged: its no-root VpnService/socket
transport requires a separate platform implementation.

## Start transaction

The platform default enables Telegram compatibility on Linux, while the
Android default remains disabled and keeps its independent no-root strategy.
Therefore the main Linux Start button is a transaction across both sidecar and
system runtime.

1. Reject concurrent owner/table/queue conflict and acquire
   `/run/qnzapret/runtime.lock`.
2. Verify every file listed by `/usr/lib/qnzapret/runtime/manifest.sha256`.
3. Compile and validate the strategy.
4. Execute pinned `nfqws2 --dry-run`.
5. Start `nfqws2`, dropping its process identity to system user
   `qnzapret-runtime`.
6. Asynchronously wait up to four seconds until queue 200 appears in
   `/proc/net/netfilter/nfnetlink_queue`.
7. Apply one nft transaction through `nft -f -`.
8. Publish `running`, `strategyEngineReady`,
   `trafficForwarderReady` and `trafficInterceptionActive`.

The only owned firewall object is `table inet qnzapret`. Its deterministic,
atomic topology follows the pinned upstream post-NAT model for IPv4 and IPv6:

| Chain | Hook / priority | Match window |
| --- | --- | --- |
| `predefrag` | output `-401` | marked injected packets and `notrack` handling |
| `postrouting` | postrouting `101` | TCP dport 80/443 original 1-20; UDP dport 443 original 1-5 |
| `prerouting` | prerouting `-101` | TCP sport 80/443 reply 1-10; UDP sport 443 reply 1-3 |

Outgoing rules reject packets carrying `0x40000000`, set post-NAT bit
`0x20000000`, expose counters and use `queue num 200 bypass`. There is no
`ct state new`, `flush ruleset`, global sysctl mutation or modification of
firewalld/ufw objects.

## Stop and crash recovery

Stop order:

1. Delete only `table inet qnzapret`.
2. Send SIGTERM to bundled `nfqws2`; use a bounded force-exit fallback.
3. Remove `/run/qnzapret/active-profile` and release the lock.
4. Stop the per-user Telegram child.
5. Publish `idle`.

`ExecStartPre` and `ExecStopPost` call
`/usr/libexec/qnzapret-runtime --cleanup`. The cleanup path removes only the
QNZapret table and volatile QNZapret files. It never flushes a ruleset or edits
firewalld/ufw tables.

An async child watch handles unexpected `nfqws2` exit: it deletes
`inet qnzapret`, clears queue/rules/interception readiness, removes the active
profile, releases the lock and emits `linux_nfqws_unexpected_exit`. Stop uses
a three-second bounded force-exit callback and never queries subprocess exit
state before a completed GLib wait.

Stable failures distinguish parse rejection, dry-run nonzero/signal/spawn,
worker spawn, queue timeout, nft transaction and unexpected worker exit:
`linux_profile_parse_rejected`, `linux_profile_dry_run_nonzero`,
`linux_profile_dry_run_signaled`, `linux_profile_dry_run_spawn_failed`,
`linux_nfqws_spawn_failed`, `linux_queue_registration_timeout`,
`linux_nft_transaction_failed`, `linux_nfqws_unexpected_exit`.

## Telegram sidecar

The user unit owns `dev.qnzapret.Telegram1` and supervises only the pinned MIT
headless `Flowseal/tg-ws-proxy` core. There is no upstream tray/UI.

- endpoint: `127.0.0.1:1443`;
- state: systemd `StateDirectory=qnzapret`, mode 0700; manual launches fall
  back to `XDG_STATE_HOME/qnzapret`;
- secret: `telegram.secret`, mode 0600;
- logs: bounded rotating file in the same state directory;
- health: mode-0600 marker distinguishing listener readiness from a live
  MTProxy handshake plus upstream WS bridge;
- setup URI: доступен только через session D-Bus `GetSetupUri`; обычный Start
  не запускает и не поднимает окно Telegram, а лишь держит локальный sidecar в
  фоне. Уже сохранённый proxy-профиль подключается, когда Telegram запущен;
- close GUI: system and user runtime continue;
- Stop: both runtime layers stop.

Sidecar Start returns only after the loopback listener is reachable. Flutter
Start is transactional: Telegram listener failure prevents system runtime
start, while later system runtime failure asynchronously rolls the sidecar
back. Snapshot fields `telegramSidecarState`, `degraded` and
`partialFailure*` prevent a partial failure from appearing fully healthy.

The system unit uses `MemoryDenyWriteExecute=no`, because bundled LuaJIT needs
executable mappings. Bounding and ambient capabilities are exactly
`CAP_NET_ADMIN`, `CAP_NET_RAW`, `CAP_SETUID`, `CAP_SETGID` and `CAP_KILL`.
The last capability lets the daemon stop a worker after it changes UID; GUI
and sidecar stay unprivileged.

The secret is not placed in child command-line arguments and the patched
headless core does not print it or the full setup URI.

Telegram itself requires the user to approve the proxy profile once. On the
verified Fedora path this confirmation produced a local MTProto connection,
changed the sidecar snapshot to `ready=true` and established the upstream WS
bridge. Listener probes use a direct loopback socket, so the hardened user unit
does not consult dconf or desktop proxy settings once per second.

## Bundled upstream

| Component | Version | Commit | License |
| --- | --- | --- | --- |
| `bol-van/zapret2` | `v0.9.5.2` | `7a69d56a4b35f814fb2d42e7bddb2f21c2314ff9` | MIT |
| `Flowseal/tg-ws-proxy` | `v1.7.0` | `0eebdff69e7cdc2a2babe2de86e1f89a8cf35374` | MIT |

Exact artifact SHA256 values and local tg-ws-proxy patches are recorded in
`runtime/assets/qnzapret/provenance.json`. Runtime never downloads code or
binaries.

## Diagnostics

```bash
systemctl status qnzapret-runtime.service
journalctl -u qnzapret-runtime.service
systemctl --user status qnzapret-telegram.service
journalctl --user -u qnzapret-telegram.service
gdbus call --system --dest dev.qnzapret.Runtime1 \
  --object-path /dev/qnzapret/Runtime1 \
  --method dev.qnzapret.Runtime1.GetSnapshot
nft list table inet qnzapret
cat /proc/net/netfilter/nfnetlink_queue
```

Recovery:

```bash
sudo systemctl stop qnzapret-runtime.service
sudo /usr/libexec/qnzapret-runtime --cleanup
systemctl --user stop qnzapret-telegram.service
```

## Known limitations

- Production packaging is x86_64 only.
- AppImage/Flatpak/Snap are not production installation paths because they
  cannot install the privileged system service by themselves.
- The inherited large `list-general.txt` and payload files have precise
  QNZapret commit provenance, but their earlier external URL/license was not
  recorded in Git history. This is explicit in `provenance.json`; legal
  redistribution review remains required before public distribution.
- Fedora/Debian clean-VM manual smoke is mandatory for release sign-off.
- `test/integration/linux_netns_runtime_test.sh` exits 77 without root. As
  root it creates isolated client/server network namespaces, sends real
  HTTP/TLS payloads, verifies nft counters and nfqws L7 diagnostics, kills the
  worker and checks daemon cleanup while preserving a foreign table.
- Windows release verification runs in Windows CI; it cannot be completed on a
  Linux host.
