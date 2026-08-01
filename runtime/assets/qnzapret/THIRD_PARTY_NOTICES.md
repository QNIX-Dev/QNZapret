# Third-party notices

## zapret2

QNZapret packages the official Linux x86_64 `nfqws2` binary and the
`zapret-lib.lua` / `zapret-antidpi.lua` libraries from `bol-van/zapret2`
`v0.9.5.2`, commit `7a69d56a4b35f814fb2d42e7bddb2f21c2314ff9`.
The component is distributed under the MIT license. The complete license text
is stored in `third_party/zapret2-LICENSE.txt`.

## tg-ws-proxy

QNZapret packages only the headless `proxy` Python package from
`Flowseal/tg-ws-proxy` `v1.7.0`, commit
`0eebdff69e7cdc2a2babe2de86e1f89a8cf35374`. Tray and GUI modules are not
packaged. The component is distributed under the MIT license. The complete
license text is stored in `third_party/tg-ws-proxy-LICENSE.txt`.

QNZapret carries a narrow hardening patch: the secret is read from a mode-0600
file, startup output does not print the secret or setup URI, and readiness is
written to a mode-0600 health marker.

## Existing QNZapret strategy data

The hostlists and binary payloads were introduced into the QNZapret repository
by commit `de2051edc395086a0ef1515f28b8a6eec566a014`. The large general list was
last updated by commit `4e9f15d15175aeee430de426a5e2dfca5ceba6f9`.
Earlier Git history does not record an external URL or a separate license for
these files. This limitation is stated explicitly instead of attributing them
to an unverified source.
