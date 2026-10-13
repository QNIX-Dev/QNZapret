#!/usr/bin/env python3
"""Account-free Telegram setup/WSS handshake, isolated emulator only; never logs secret."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import struct
import sys
import time
import xml.etree.ElementTree as ET
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
import android_qs_vpn_regression as fixture
sys.path.insert(0, str(fixture.ROOT / "linux/components"))
from telegram_cdn import parse_public_keys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--dcs', default='2', help='Comma-separated signed logical DC IDs')
    parser.add_argument('--req-pq', action='store_true', help='Require an unauthenticated nonce-matching Telegram response')
    parser.add_argument('--reuse-setup', action='store_true', help='Use the profile saved by an earlier native setup action; do not retest UI')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        raise SystemExit('Refusing physical device')
    def adb(*command):
        return fixture.run('adb', '-s', args.serial, *command)
    if adb('shell', 'getprop', 'ro.boot.qemu') != '1':
        raise SystemExit('Not an emulator')
    if 'Active vpn type: 1' in adb('shell', 'dumpsys', 'vpn_management'):
        raise SystemExit('Stop existing QA VPN before this test')
    results = {'physicalDevice': False, 'accountUsed': False}
    output = fixture.ROOT / 'build/verification/android-telegram-smoke.json'
    forward = None
    try:
        x, y = fixture.show_tile(adb)
        adb('shell', 'input', 'tap', str(x), str(y))
        for _ in range(30):
            vpn = adb('shell', 'dumpsys', 'vpn_management')
            if 'Active package name: dev.qnzapret' in vpn and 'Active vpn type: 1' in vpn:
                break
            time.sleep(.3)
        results['setupUiRetested'] = not args.reuse_setup
        if not args.reuse_setup:
            time.sleep(3)
            adb('shell', 'cmd', 'statusbar', 'collapse')
            adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
            time.sleep(2)
            adb('shell', 'cmd', 'statusbar', 'expand-notifications')
            time.sleep(2)
            for attempt in range(3):
                adb('shell', 'uiautomator', 'dump', '/sdcard/qnz-qa-notification.xml')
                root = ET.fromstring(adb('shell', 'cat', '/sdcard/qnz-qa-notification.xml'))
                node = next((n for n in root.iter('node') if
                    n.get('text', '').lower() == 'подключить telegram'), None)
                if node is not None:
                    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                    break
                adb('shell', 'input', 'swipe', '360', '10', '360', '650', '500')
                time.sleep(2)
            else:
                raise RuntimeError('Native Telegram setup action not visible')
        for _ in range(20):
            try:
                setup = json.loads(adb('shell', 'run-as', fixture.PACKAGE, 'cat', 'files/telegram-setup-status.json'))
                break
            except Exception:
                time.sleep(.2)
        else:
            raise RuntimeError('Setup URI not received')
        results['setupUriValid'] = setup.get('valid') is True
        if not results['setupUriValid']:
            raise RuntimeError('Invalid setup URI')
        secret = bytes.fromhex(adb('shell', 'run-as', fixture.PACKAGE, 'cat', 'files/telegram-probe-secret'))
        pid = adb('shell', 'pidof', 'dev.qnzapret')
        def logs():
            return adb('logcat', '-d', '--pid=' + pid, '-s', 'QNZapretTgCompat')
        forward = adb('forward', 'tcp:0', 'tcp:' + setup['port'])
        sessions = []
        for dc in map(int, args.dcs.split(',')):
            case = {'dc': dc}
            started = time.monotonic()
            baseline = logs().count('telegram compatibility bridge started')
            init = bytearray(os.urandom(64))
            init[0] = 0x65
            init[56:60] = bytes.fromhex('dddddddd')
            init[60:62] = dc.to_bytes(2, 'little', signed=True)
            key = hashlib.sha256(bytes(init[8:40]) + secret).digest()
            up = Cipher(algorithms.AES(key), modes.CTR(bytes(init[40:56]))).encryptor()
            encrypted = up.update(bytes(init))
            wire = bytes(init[:56]) + encrypted[56:]
            with socket.create_connection(('127.0.0.1', int(forward)), timeout=5) as client:
                client.settimeout(10)
                client.sendall(wire)
                for _ in range(40):
                    text = logs()
                    if text.count('telegram compatibility bridge started') > baseline:
                        case['bridgeStarted'] = True
                        break
                    time.sleep(.5)
                else:
                    case['bridgeStarted'] = False
                    case['errorCodes'] = sorted(set(re.findall(r'errorCode=([a-z_]+)', text)))
                if args.req_pq and case['bridgeStarted']:
                    reverse = wire[::-1]
                    down = Cipher(algorithms.AES(hashlib.sha256(reverse[8:40] + secret).digest()),
                        modes.CTR(reverse[40:56])).decryptor()
                    request_nonce = os.urandom(16)
                    body = struct.pack('<I', 0xbe7e8ef1) + request_nonce
                    envelope = bytes(8) + struct.pack('<Q', int(time.time() * 2**32) & ~3) + struct.pack('<I', len(body)) + body
                    packet = envelope + os.urandom(8)
                    client.sendall(up.update(struct.pack('<I', len(packet)) + packet))
                    plain = b''
                    try:
                        while len(plain) < 128:
                            data = client.recv(4096)
                            if not data: break
                            plain += down.update(data)
                            if len(plain) >= 4 and len(plain) >= 4 + (int.from_bytes(plain[:4], 'little') & 0x7fffffff): break
                        case['resPqNonceMatches'] = len(plain) >= 44 and plain[24:28] == bytes.fromhex('63241605') and plain[28:44] == request_nonce
                        if case['resPqNonceMatches']:
                            case['publicKeyFingerprints'] = sorted(format(key, '016x') for key in parse_public_keys(plain, request_nonce))
                    except socket.timeout:
                        case['resPqNonceMatches'] = False
                case['seconds'] = round(time.monotonic() - started, 2)
            sessions.append(case)
        results['sessions'] = sessions
        results['bridgeStarted'] = all(c.get('bridgeStarted') for c in sessions)
        results['passed'] = results.get('setupUriValid') and results['bridgeStarted'] and (
            not args.req_pq or all(c.get('resPqNonceMatches') for c in sessions))
        by_dc = {c['dc']: c for c in sessions}
        if args.req_pq and 2 in by_dc and 203 in by_dc:
            main = set(by_dc[2].get('publicKeyFingerprints', []))
            cdn = set(by_dc[203].get('publicKeyFingerprints', []))
            results['cdnKeysDistinct'] = bool(main and cdn and main.isdisjoint(cdn))
            results['passed'] = results['passed'] and results['cdnKeysDistinct']
    except Exception as error:
        results['passed'] = False
        results['error'] = type(error).__name__ + ': ' + str(error)
    finally:
        if forward:
            adb('forward', '--remove', 'tcp:' + forward)
        # Stop only this QA runtime, not another user's/device's VPN.
        adb('shell', 'am', 'force-stop', 'dev.qnzapret')
        output.write_text(json.dumps(results, indent=2) + '\n')
    print(json.dumps(results, indent=2))
    raise SystemExit(0 if results.get('passed') else 1)


if __name__ == '__main__':
    main()
