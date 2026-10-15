#!/usr/bin/env python3
"""Account-free release VPN datapath test; isolated emulator only."""
import argparse
import json
from pathlib import Path
import subprocess
import socket
import threading
import time
import android_qs_vpn_regression as fixture

ROOT = Path(__file__).resolve().parents[2]


def udp_association_probe(adb):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as echo:
        echo.bind(('0.0.0.0', 0))
        echo.settimeout(12)
        errors = []
        def serve():
            try:
                packets = [echo.recvfrom(2048), echo.recvfrom(2048)]
                # Reply in reverse order after both requests; the old shared relay
                # delivered both replies to the most recently registered client.
                for data, address in reversed(packets):
                    echo.sendto(data, address)
            except OSError as error:
                errors.append(type(error).__name__)
        worker = threading.Thread(target=serve, daemon=True)
        worker.start()
        adb('shell', 'run-as', fixture.PACKAGE, 'rm', '-f', 'files/udp-results.json')
        adb('shell', 'am', 'start', '-n', fixture.PACKAGE + '/.UdpAssociationProbeActivity',
            '--ei', 'echoPort', str(echo.getsockname()[1]))
        for _ in range(40):
            try:
                result = json.loads(adb('shell', 'run-as', fixture.PACKAGE, 'cat', 'files/udp-results.json'))
                worker.join(timeout=1)
                if errors:
                    result['echoError'] = errors[0]
                return result
            except (subprocess.CalledProcessError, json.JSONDecodeError):
                time.sleep(.3)
        raise RuntimeError('UDP association fixture timed out')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, default=Path.home() / 'Android/Sdk')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        raise SystemExit('Refusing physical device')
    def adb(*command):
        return fixture.run('adb', '-s', args.serial, *command)
    if adb('shell', 'getprop', 'ro.boot.qemu') != '1':
        raise SystemExit('Not an emulator')
    output = ROOT / 'build/verification'
    output.mkdir(parents=True, exist_ok=True)
    results = {'serial': args.serial, 'physicalDevice': False}
    def save():
        (output / 'android-runtime-smoke.json').write_text(json.dumps(results, indent=2) + '\n')
    save()
    fixture_path = output / 'vpn-fixture.apk'
    fixture.build_fixture(args.sdk, fixture_path)
    adb('install', '-r', str(args.apk.resolve()))
    adb('install', '-r', str(fixture_path))
    adb('shell', 'pm', 'grant', 'dev.qnzapret', 'android.permission.POST_NOTIFICATIONS')
    adb('shell', 'appops', 'set', 'dev.qnzapret', 'ACTIVATE_VPN', 'allow')
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard')
    x, y = fixture.show_tile(adb)
    started = time.monotonic()
    adb('shell', 'input', 'tap', str(x), str(y))
    pid = ''
    logs = ''
    for _ in range(35):
        try:
            pid = adb('shell', 'pidof', 'dev.qnzapret')
        except subprocess.CalledProcessError:
            pid = ''
        if pid:
            logs = adb('logcat', '-d', '--pid=' + pid, '-s', 'QNZapretTun', 'QNZapretService', 'QNZapretTile', 'AndroidRuntime')
            if 'tun2socks started proxy=' in logs:
                break
            if 'FATAL EXCEPTION' in logs or 'runtime start failed' in logs:
                raise SystemExit(logs[-1500:])
        time.sleep(.5)
    else:
        (output / 'android-runtime-start.log').write_text(logs)
        raise SystemExit('TUN/JNI start not confirmed')
    results['startSeconds'] = round(time.monotonic() - started, 2)
    results['tun2socksStarted'] = True
    save()
    time.sleep(2.5)
    vpn = adb('shell', 'dumpsys', 'vpn_management')
    if 'Active vpn type: 1' not in vpn or 'dev.qnzapret' not in vpn:
        raise SystemExit('VPN not active after JNI health interval')
    results['udpAssociationIsolation'] = udp_association_probe(adb)
    save()
    if not all(results['udpAssociationIsolation'].get(key) is True for key in
               ['distinctRelayPorts', 'firstReplyCorrect', 'secondReplyCorrect']):
        raise SystemExit('UDP association isolation failed: ' + json.dumps(results['udpAssociationIsolation']))
    adb('shell', 'run-as', fixture.PACKAGE, 'rm', '-f', 'files/network-results.json')
    adb('shell', 'cmd', 'statusbar', 'collapse')
    adb('shell', 'am', 'start', '-n', fixture.PACKAGE + '/.NetworkProbeActivity')
    for _ in range(180):
        try:
            data = adb('shell', 'run-as', fixture.PACKAGE, 'cat', 'files/network-results.json')
            probes = json.loads(data)
            break
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            time.sleep(.5)
    else:
        (output / 'android-network-smoke.log').write_text(adb('logcat', '-d', '--pid=' + pid,
            '-s', 'QNZapretProxy', 'QNZapretTun', 'QNZapretService', 'QNZapretNetTest'))
        raise SystemExit('HTTPS-over-TUN probe timeout')
    results['https'] = probes
    save()
    network_logs = adb('logcat', '-d', '--pid=' + pid, '-s', 'QNZapretProxy', 'QNZapretTun', 'QNZapretService', 'QNZapretNetTest')
    (output / 'android-network-smoke.log').write_text(network_logs)
    results['httpsPassed'] = all(item.get('status') == 200 and item.get('bytes', 0) > 0 for item in probes)
    x, y = fixture.show_tile(adb)
    adb('shell', 'input', 'tap', str(x), str(y))
    for _ in range(25):
        vpn = adb('shell', 'dumpsys', 'vpn_management')
        if 'Active vpn type: -1' in vpn:
            break
        time.sleep(.2)
    else:
        raise SystemExit('VPN failed to stop')
    time.sleep(2)
    results['stoppedWithoutResurrection'] = 'Active vpn type: -1' in adb('shell', 'dumpsys', 'vpn_management')
    save()
    adb('shell', 'cmd', 'statusbar', 'collapse')
    print(json.dumps(results, indent=2))
    if not results['httpsPassed']:
        raise SystemExit('HTTPS-over-TUN failed: ' + json.dumps(probes))


if __name__ == '__main__':
    main()
