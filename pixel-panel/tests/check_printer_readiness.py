"""Verify live telemetry and the actual phone gateway without physical outputs.

Uses GET requests, plus an optional comment-only upload/read/delete round trip.
Never sends G-code, starts a print, changes heater targets or restarts a service.
Private gateway credentials stay in memory and are never included in the report.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--printer', default='http://10.1.39.216:7125')
    parser.add_argument('--upload-check', action='store_true')
    parser.add_argument('--apk', type=Path, default=Path('pixel-panel/.build/Voron.apk'))
    parser.add_argument('--output', type=Path, default=Path('pixel-panel/.build/printer-readiness.json'))
    args = parser.parse_args()
    adb = [str(Path(os.environ['LOCALAPPDATA'])/'Android/Sdk/platform-tools/adb.exe'), '-s', args.serial]
    def shell(*words):
        return subprocess.check_output([*adb, 'shell', *words], encoding='utf-8')
    installed = shell('pm','path','dev.leo.voronpanel').strip().removeprefix('package:')
    installed_hash = shell('sha256sum', installed).split()[0]
    expected_hash = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    assert installed_hash == expected_hash, 'Installed APK does not match the intended build'
    prefs = ET.fromstring(shell('run-as', 'dev.leo.voronpanel', 'cat', 'shared_prefs/panel.xml'))
    key = next(n.text for n in prefs if n.get('name') == 'camera_token')
    ip = re.search(r'inet (\d+\.\d+\.\d+\.\d+)', shell('ip', '-o', '-4', 'addr', 'show', 'wlan0')).group(1)
    phone = 'http://'+ip+':8765'
    def get(base, path, auth=False):
        with urlopen(Request(base+path, headers={'X-Api-Key':key} if auth else {}), timeout=15) as r:
            return r.read()
    def result(base, path, auth=False):
        return json.loads(get(base,path,auth))['result']
    server = result(args.printer, '/server/info')
    objects = result(args.printer, '/printer/objects/list')['objects']
    help_commands = result(args.printer, '/printer/gcode/help')
    required_objects = ['webhooks','print_stats','gcode_move','virtual_sdcard','toolhead','extruder',
                        'heater_bed','fan','manual_probe','y_axis_z_offset','bed_mesh']
    required_macros = ['LOAD_FILAMENT','UNLOAD_FILAMENT','CALIBRATE_COMPENSATED_BED_MESH',
                       'CALIBRATE_BED_TILT','PAUSE','RESUME','CANCEL_PRINT']
    assert not set(required_objects)-set(objects), 'Required printer objects missing'
    assert not set(required_macros)-set(help_commands), 'Required custom commands missing'
    # HELP omits ordinary undescribed G-codes and inactive manual-probe commands.
    status = result(phone, '/printer/objects/query?webhooks&print_stats&extruder&heater_bed', True)['status']
    assert status['webhooks']['state']=='ready'
    config = result(args.printer, '/printer/objects/query?configfile=settings')['status']['configfile']['settings']
    assert config['extruder']['max_temp'] > 240 and config['heater_bed']['max_temp'] > 100
    files = result(phone, '/server/files/list?root=gcodes', True)
    try:
        get(phone, '/printer/objects/query?webhooks')
        raise AssertionError('Unauthenticated gateway access accepted')
    except HTTPError as error:
        assert error.code==401
    report = {'checked_utc':datetime.now(timezone.utc).isoformat(), 'printer':args.printer,
              'phone_gateway':phone, 'phone_version':re.search(r'versionName=(\S+)',shell('dumpsys','package','dev.leo.voronpanel')).group(1),
              'klipper_state':server['klippy_state'], 'moonraker_warnings':server['warnings'],
              'required_objects_present':True, 'required_macros_present':True,
              'safe_preset_limits_verified':True, 'authenticated_lan_proxy_verified':True,
              'unauthenticated_rejected':True, 'stored_file_count':len(files),
              'job_state':status['print_stats']['state'],
              'heater_targets':{k:status[k]['target'] for k in ['extruder','heater_bed']},
              'physical_output_commands_sent':0, 'upload_round_trip':False}
    if args.upload_check:
        name='voron-readiness-'+uuid.uuid4().hex+'.gcode'
        boundary='voron'+uuid.uuid4().hex
        contents=b'; Voron connectivity check. Comment only. Never print.\n'
        body=(f'--{boundary}\r\nContent-Disposition: form-data; name="root"\r\n\r\ngcodes\r\n'
              f'--{boundary}\r\nContent-Disposition: form-data; name="print"\r\n\r\nfalse\r\n'
              f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
              'Content-Type: application/octet-stream\r\n\r\n').encode()+contents+f'\r\n--{boundary}--\r\n'.encode()
        created=False
        try:
            req=Request(phone+'/server/files/upload', data=body,
                        headers={'X-Api-Key':key,'Content-Type':'multipart/form-data; boundary='+boundary})
            with urlopen(req,timeout=20) as r:
                created=True
                payload=json.load(r)
                upload=payload.get('result',payload)
            assert upload['item']['path']==name and not upload.get('print_started',False)
            assert get(phone,'/server/files/gcodes/'+name,True)==contents
            report['upload_round_trip']=True
        finally:
            if created:
                with urlopen(Request(phone+'/server/files/gcodes/'+name, method='DELETE',
                                     headers={'X-Api-Key':key}),timeout=15) as r:
                    assert r.status==200
        after=result(phone,'/printer/objects/query?print_stats&extruder&heater_bed',True)['status']
        assert after['print_stats']['state']==status['print_stats']['state']
        assert all(after[k]['target']==status[k]['target'] for k in ['extruder','heater_bed'])
        assert not any(f['path']==name for f in result(phone,'/server/files/list?root=gcodes',True))
        report['temporary_file_removed']=True
    report['apk_sha256']=expected_hash
    report['installed_apk_verified']=True
    report['physical_tests']='Not performed; this checker never activates printer outputs'
    report['portable_usb_hotspot_power_recovery']='Not yet validated on dedicated hardware'
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,indent=2))


if __name__=='__main__':
    main()
