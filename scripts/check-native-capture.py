#!/usr/bin/env python3
"""Synthetic OS capture acceptance. Requires a disposable AVD and isolated TLS18447 fixture."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--endpoint', required=True)
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--artifacts', default='output/native-capture')
parser.add_argument('--with-model', action='store_true', help='Use a separately started synthetic stub on loopback18753; leaves fixture model configured until worker verification/restoration.')
args = parser.parse_args()
if not re.fullmatch(r'https://[a-z0-9.-]+\.ts\.net:18447', args.endpoint):
    parser.error('Dedicated tailnet fixture origin on18447 required')
adb = Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'

def run(*command):
    result = subprocess.run([str(adb), '-s', args.serial, *command], capture_output=True, text=True, check=False)
    # AOSP34 netpolicy returns255 even after applying a valid saved-WiFi policy.
    policy_set = command[:4] == ('shell', 'cmd', 'netpolicy', 'set')
    if result.returncode and not (policy_set and result.returncode == 255):
        raise RuntimeError('Disposable emulator command failed: ' + ' '.join(command[:4]))
    return result.stdout.strip()

def shell(*command):
    return run('shell', *command)

if not args.serial.startswith('emulator-') or run('emu', 'avd', 'name').splitlines()[0] != 'pocketlog-ux-check':
    raise RuntimeError('Refusing any device other than the disposable pocketlog-ux-check AVD')
settings = {key: shell('settings', 'get', 'secure', key) for key in ('enabled_accessibility_services', 'accessibility_enabled')}
operation = shell('appops', 'get', 'tw.skyhong.pocketlog', 'GET_USAGE_STATS')
match = re.search(r'GET_USAGE_STATS: (\w+)', operation)
previous_mode = match.group(1) if match else 'default'
networks = [line.split(';')[-1] for line in shell('cmd', 'netpolicy', 'list', 'wifi-networks').splitlines() if line.startswith('AndroidWifi;')]
if not networks or len(set(networks)) != 1:
    raise RuntimeError('Expected one consistent synthetic AndroidWifi policy')
previous_policy = {'true': 'true', 'false': 'false', 'none': 'undefined'}[networks[0]]
process = None
try:
    shell('appops', 'set', 'tw.skyhong.pocketlog', 'GET_USAGE_STATS', 'allow')
    shell('cmd', 'netpolicy', 'set', 'metered-network', 'AndroidWifi', 'true')
    if any(not line.endswith(';true') for line in shell('cmd', 'netpolicy', 'list', 'wifi-networks').splitlines() if line.startswith('AndroidWifi;')):
        raise RuntimeError('Synthetic network policy did not apply')
    command = [str(adb), '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-e', 'synthetic', 'yes', '-e', 'capture', 'yes', '-e', 'endpoint', args.endpoint]
    if args.with_model:
        command += ['-e', 'model', 'yes']
    command += ['tw.skyhong.pocketlog.test/tw.skyhong.pocketlog.UiSnapshotInstrumentation']
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    # Starting instrumentation kills the previously bound service. Rebind afterwards.
    time.sleep(2)
    if process.poll() is None:
        component = 'tw.skyhong.pocketlog/tw.skyhong.pocketlog.ContextService'
        enabled = [] if settings['enabled_accessibility_services'] in ('null', '') else settings['enabled_accessibility_services'].split(':')
        services = ':'.join(dict.fromkeys([*enabled, component]))
        shell('settings', 'delete', 'secure', 'enabled_accessibility_services')
        shell('settings', 'put', 'secure', 'enabled_accessibility_services', services)
        shell('settings', 'put', 'secure', 'accessibility_enabled', '1')
        time.sleep(1)
        shell('am', 'start', '-n', 'org.example.pocketlogfixture/.FixtureActivity', '--es', 'variant', 'editable', '-f', '268468224')
    result = process.communicate(timeout=55)[0]
    if 'Synthetic real-window capture, input exclusion, encrypted queue and journal-device API upload passed.' not in result:
        raise RuntimeError('Capture acceptance failed: ' + result.strip())
    folder = Path(args.artifacts)
    folder.mkdir(parents=True, exist_ok=True)
    run('pull', '/sdcard/Android/data/tw.skyhong.pocketlog/files/ux-capture/.', str(folder))
    print('Synthetic OS capture, input exclusion, encrypted queue, journal-device upload/revocation passed.')
    print('Only synthetic artifacts:', folder)
finally:
    if process is not None and process.poll() is None:
        process.terminate()
        process.communicate(timeout=10)
    cleanup = [
        ('am', 'force-stop', 'org.example.pocketlogfixture'),
        ('cmd', 'netpolicy', 'set', 'metered-network', 'AndroidWifi', previous_policy),
        ('appops', 'set', 'tw.skyhong.pocketlog', 'GET_USAGE_STATS', previous_mode),
    ]
    for key, value in settings.items():
        cleanup.append(('settings', 'delete', 'secure', key) if value in ('null', '') else ('settings', 'put', 'secure', key, value))
    failures = []
    for command in cleanup:
        try:
            shell(*command)
        except RuntimeError:
            failures.append(command[:3])
    if failures:
        raise RuntimeError('Emulator cleanup needs attention: ' + str(failures))
