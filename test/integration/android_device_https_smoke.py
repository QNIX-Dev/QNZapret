#!/usr/bin/env python3
"""HTTPS отдельного UID на телефоне: только после явного согласия и user Start VPN."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import android_qs_vpn_regression as fixture

PACKAGE = 'dev.qnzapret.networkprobe'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--allow-physical-vpn', action='store_true')
    parser.add_argument('--baseline', action='store_true', help='Compare with all VPNs stopped; does not stop them itself')
    args = parser.parse_args()
    if not args.allow_physical_vpn or args.serial.startswith('emulator-'):
        raise SystemExit('Requires explicit consent for a physical-device test')
    def adb(*command):
        return fixture.run('adb', '-s', args.serial, *command)
    vpn = adb('shell', 'dumpsys', 'vpn_management')
    if args.baseline and 'Active vpn type: 1' in vpn:
        raise SystemExit('Stop VPN explicitly before baseline')
    if not args.baseline and ('Active package name: dev.qnzapret.preview' not in vpn or 'Active vpn type: 1' not in vpn):
        raise SystemExit('Start Preview VPN explicitly before running this test')
    installed = adb('shell', 'pm', 'list', 'packages', PACKAGE)
    if installed:
        raise SystemExit('Probe package already exists; refusing to overwrite or delete existing data')
    output = fixture.ROOT / 'build/verification'
    output.mkdir(parents=True, exist_ok=True)
    apk = output / 'network-only-fixture.apk'
    fixture.build_fixture(Path.home() / 'Android/Sdk', apk, network_only=True)
    results = {'physicalDevice': True, 'vpnPackage': None if args.baseline else 'dev.qnzapret.preview', 'baseline': args.baseline}
    try:
        adb('install', str(apk))
        adb('shell', 'am', 'start', '-n', PACKAGE + '/dev.qnzapret.vpnfixture.NetworkProbeActivity', '--ez', 'quick', 'true')
        for _ in range(80):
            try:
                probes = json.loads(adb('shell', 'run-as', PACKAGE, 'cat', 'files/network-results.json'))
                results['https'] = probes
                results['passed'] = all(p.get('status') == 200 and p.get('bytes', 0) > 0 for p in probes)
                break
            except (subprocess.CalledProcessError, json.JSONDecodeError):
                time.sleep(.5)
        else:
            results['passed'] = False
            results['error'] = 'HTTPS probe timeout'
    finally:
        # Только этот новый ephemeral package, без VPN service и Telegram handler.
        adb('uninstall', PACKAGE)
        name = 'android-physical-baseline.json' if args.baseline else 'android-physical-https.json'
        (output / name).write_text(json.dumps(results, indent=2) + '\n')
    print(json.dumps(results, indent=2))
    raise SystemExit(0 if results.get('passed') else 1)


if __name__ == '__main__':
    main()
