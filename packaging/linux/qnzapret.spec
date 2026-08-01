Name:           qnzapret
Version:        0.0.6
Release:        1%{?dist}
Summary:        QNZapret desktop DPI-bypass client
License:        LicenseRef-proprietary
URL:            https://github.com/QNIX-Dev/QNZapret
Source0:        qnzapret-stage.tar.gz
BuildArch:      x86_64

Requires:       nftables
Requires:       polkit
Requires:       python3
Requires:       python3-cryptography
Requires:       systemd
Requires(pre):  shadow-utils
Requires(post): systemd
Requires(preun): systemd

%description
QNZapret provides an unprivileged Flutter GUI, a Polkit-authorized system
D-Bus runtime for nftables/NFQUEUE and a per-user Telegram compatibility
sidecar.

%prep

%build

%install
mkdir -p %{buildroot}
tar -xzf %{SOURCE0} -C %{buildroot}

%post
systemd-sysusers /usr/lib/sysusers.d/qnzapret.conf >/dev/null 2>&1 || :
systemd-tmpfiles --create /usr/lib/tmpfiles.d/qnzapret.conf >/dev/null 2>&1 || :
%systemd_post qnzapret-runtime.service
%systemd_user_post qnzapret-telegram.service

%preun
%systemd_preun qnzapret-runtime.service
%systemd_user_preun qnzapret-telegram.service
if [ "$1" -eq 0 ]; then
  /usr/libexec/qnzapret-runtime --cleanup >/dev/null 2>&1 || :
fi

%postun
%systemd_postun_with_restart qnzapret-runtime.service
%systemd_user_postun_with_restart qnzapret-telegram.service

%files
%dir /opt/qnzapret
/opt/qnzapret/*
/usr/bin/qnzapret
/usr/libexec/qnzapret-runtime
/usr/libexec/qnzapret-telegram-sidecar
/usr/lib/qnzapret
/usr/lib/systemd/system/qnzapret-runtime.service
/usr/lib/systemd/user/qnzapret-telegram.service
/usr/lib/sysusers.d/qnzapret.conf
/usr/lib/tmpfiles.d/qnzapret.conf
/usr/share/applications/dev.qnzapret.desktop
/usr/share/metainfo/dev.qnzapret.metainfo.xml
/usr/share/icons/hicolor/256x256/apps/dev.qnzapret.png
/usr/share/dbus-1/system-services/dev.qnzapret.Runtime1.service
/usr/share/dbus-1/services/dev.qnzapret.Telegram1.service
/usr/share/dbus-1/system.d/dev.qnzapret.Runtime1.conf
/usr/share/polkit-1/actions/dev.qnzapret.runtime.policy
/usr/share/doc/qnzapret

%changelog
* Sat Aug 01 2026 QNZapret <dev@qnix.dev> - 0.0.6-1
- Keep Telegram sidecar startup in background and refine desktop scrolling.

* Sat Aug 01 2026 QNZapret <dev@qnix.dev> - 0.0.5-1
- Deliver Telegram proxy setup through the application D-Bus Open method.
- Probe the loopback listener without consulting desktop proxy settings.

* Sat Aug 01 2026 QNZapret <dev@qnix.dev> - 0.0.4-1
- Ship the Fedora-smoke-verified Linux HTTP/TLS/QUIC strategy.

* Sat Aug 01 2026 QNZapret <dev@qnix.dev> - 0.0.3-1
- Reload the Telegram user unit on package upgrades.

* Sat Aug 01 2026 QNZapret <dev@qnix.dev> - 0.0.2-1
- Fix systemd runtime capabilities, Telegram state and isolated bundle builds.

* Thu Jul 30 2026 QNZapret <dev@qnix.dev> - 0.0.1-1
- Add production Linux runtime packaging.
