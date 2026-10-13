#!/usr/bin/env python3
"""Реальный competing-VPN regression; запускается ТОЛЬКО на эмуляторе."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import time
import zipfile
import re
import json
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'dev.qnzapret.vpnfixture'
TILE = 'dev.qnzapret/.QnzapretQuickSettingsTileService'


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, timeout=60, **kwargs).stdout.strip()


def build_fixture(sdk, output, network_only=False):
    tools = sdk / 'build-tools' / sorted((sdk / 'build-tools').iterdir(), key=lambda p: tuple(map(int, p.name.split('.'))))[-1].name
    platform = sdk / 'platforms/android-36/android.jar'
    with tempfile.TemporaryDirectory(prefix='qnz-vpn-fixture-') as temporary:
        work = Path(temporary)
        for name in ['gen', 'classes', 'dex']:
            (work / name).mkdir()
        source = ROOT / 'test/integration/android_vpn_fixture'
        manifest = source / 'AndroidManifest.xml'
        if network_only:
            manifest = work / 'AndroidManifest.xml'
            manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
 package="dev.qnzapret.networkprobe"><uses-permission android:name="android.permission.INTERNET"/>
 <application android:label="QNZapret HTTPS probe" android:debuggable="true"
 android:theme="@android:style/Theme.Material.Light.NoActionBar">
 <activity android:name="dev.qnzapret.vpnfixture.NetworkProbeActivity" android:exported="true"/>
 </application></manifest>''')
        run(str(tools / 'aapt2'), 'link', '--manifest', str(manifest),
            '--java', str(work / 'gen'), '-I', str(platform), '--min-sdk-version', '26',
            '--target-sdk-version', '36', '-o', str(work / 'resources.apk'))
        sources = [source / 'src/dev/qnzapret/vpnfixture/NetworkProbeActivity.java'] if network_only else source.rglob('*.java')
        java = [str(path) for path in sources] + [str(path) for path in (work / 'gen').rglob('*.java')]
        run('javac', '-source', '8', '-target', '8', '-classpath', str(platform), '-d', str(work / 'classes'), *java)
        run(str(tools / 'd8'), '--lib', str(platform), '--output', str(work / 'dex'),
            *[str(path) for path in (work / 'classes').rglob('*.class')])
        with zipfile.ZipFile(work / 'resources.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
            apk.write(work / 'dex/classes.dex', 'classes.dex')
        run(str(tools / 'zipalign'), '-f', '4', str(work / 'resources.apk'), str(output))
        run(str(tools / 'apksigner'), 'sign', '--ks', str(Path.home() / '.android/debug.keystore'),
            '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--ks-key-alias', 'androiddebugkey', str(output))


def show_tile(adb):
    """Observe the tile on a real QS page; set-tiles is ignored on some API 36 images."""
    adb('shell', 'cmd', 'statusbar', 'add-tile', TILE)
    dimensions = re.findall(r'(\d+)x(\d+)', adb('shell', 'wm', 'size'))[-1]
    width, height = map(int, dimensions)
    # Real gestures also work while SystemUI ignores asynchronous expand requests.
    for _ in range(2):
        adb('shell', 'input', 'swipe', str(width//2), '10', str(width//2), str(height*9//10), '500')
        time.sleep(.5)
    for _ in range(6):
        time.sleep(.4)
        adb('shell', 'uiautomator', 'dump', '/sdcard/qnz-qa-qs.xml')
        root = ET.fromstring(adb('shell', 'cat', '/sdcard/qnz-qa-qs.xml'))
        for node in root.iter('node'):
            if (node.get('content-desc', '').startswith('QNZapret') or node.get('text') == 'QNZapret'):
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds', '')))
                return (x1+x2)//2, (y1+y2)//2
        adb('shell', 'input', 'swipe', str(width*9//10), str(height*3//5),
            str(width//10), str(height*3//5), '300')
    raise RuntimeError('QNZapret tile was not visibly rendered')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--skip-gui', action='store_true', help='QS-only run when emulator graphics cannot render Flutter')
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk'))))
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        raise SystemExit('Refusing a physical device; test changes VPN consent/settings')
    def adb(*command):
        return run('adb', '-s', args.serial, *command)
    if adb('shell', 'getprop', 'ro.boot.qemu') != '1' and adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
        raise SystemExit('Device is not an emulator')
    directory = ROOT / 'build/verification'
    directory.mkdir(parents=True, exist_ok=True)
    fixture = directory / 'vpn-fixture.apk'
    build_fixture(args.sdk, fixture)
    adb('install', '-r', str(args.apk.resolve()))
    adb('install', '-r', str(fixture))
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard')
    # В изолированном эмуляторе разрешаем обоим VPN подготовку без личного consent UI.
    adb('shell', 'appops', 'set', 'dev.qnzapret', 'ACTIVATE_VPN', 'allow')
    adb('shell', 'appops', 'set', PACKAGE, 'ACTIVATE_VPN', 'allow')
    adb('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
    for _ in range(30):
        try:
            if adb('shell', 'run-as', PACKAGE, 'cat', 'files/state') == 'ACTIVE':
                break
        except subprocess.CalledProcessError:
            pass
        time.sleep(.2)
    else:
        raise SystemExit('Fixture VPN did not establish')
    show_tile(adb)
    time.sleep(3)
    state = adb('shell', 'run-as', PACKAGE, 'cat', 'files/state')
    results = {'tileVisiblyRendered': True, 'afterQs': state, 'guiSkipped': args.skip_gui}
    result_path = directory / 'android-qs-vpn-regression.json'
    result_path.write_text(json.dumps(results, indent=2) + '\n')
    adb('shell', 'cmd', 'statusbar', 'collapse')
    # Открытие GUI/чтение snapshot тоже не должно отбирать чужой VPN.
    after_gui = 'SKIPPED' if args.skip_gui else None
    if not args.skip_gui:
        adb('shell', 'am', 'start', '-n', 'dev.qnzapret/.MainActivity')
        time.sleep(3)
        after_gui = adb('shell', 'run-as', PACKAGE, 'cat', 'files/state')
    results['afterGui'] = after_gui
    result_path.write_text(json.dumps(results, indent=2) + '\n')
    (directory / 'android-qs-vpn-regression.log').write_text(
        'Tile visibly rendered: true\nAfter QS render: ' + state + '\nAfter GUI snapshot: ' + after_gui + '\n')
    adb('shell', 'am', 'force-stop', PACKAGE)
    if state != 'ACTIVE' or (not args.skip_gui and after_gui != 'ACTIVE'):
        raise SystemExit('FAIL: read-only QS/GUI revoked a competing VPN')
    print('PASS: competing VPN survived visible QS rendering' + ('' if args.skip_gui else ' and GUI snapshot'))


if __name__ == '__main__':
    main()
