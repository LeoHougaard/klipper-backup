"""Phone gateway checks. Never sends motion, heating, or print-start commands.

The key is read privately via Android's development run-as, never printed.
An ADB port forward tests the actual app's HTTP implementation, not a mock.
"""
from pathlib import Path
import argparse
import json
import os
import subprocess
import time
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET

ADB = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"


def adb(*args):
    return subprocess.check_output([str(ADB), *args], text=True, encoding="utf-8", stderr=subprocess.STDOUT)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--snapshot", type=Path)
    parser.add_argument("--lan", help="Test the phone's real LAN address instead of an ADB forward")
    parser.add_argument("--upload-test", action="store_true", help="Upload, read back and delete a unique comment-only G-code; never print")
    args = parser.parse_args()
    prefs = ET.fromstring(adb("shell", "run-as", "dev.leo.voronpanel", "cat", "shared_prefs/panel.xml"))
    key = next(node.text for node in prefs if node.get("name") == "camera_token")
    if not args.lan:
        adb("forward", "tcp:18765", "tcp:8765")
    base = f"http://{args.lan}:8765" if args.lan else "http://127.0.0.1:18765"

    def get(path, auth=True):
        request = Request(base + path, headers={"X-Api-Key": key} if auth else {})
        with urlopen(request, timeout=12) as response:
            return response.status, response.read()

    try:
        for path in ("/health", "/camera/snapshot", "/printer/objects/query?webhooks"):
            try:
                get(path, False)
                raise AssertionError("Unauthenticated access allowed")
            except HTTPError as error:
                assert error.code == 401
        print("PASS: camera, health and printer gateway require authentication")
        deadline = time.monotonic() + 12
        while True:
            code, body = get("/health")
            health = json.loads(body)
            if health["frame_age_ms"] >= 0 or time.monotonic() >= deadline:
                break
            time.sleep(.3)
        assert code == 200 and health["root_required"] is False
        assert 0 <= health["frame_age_ms"] < 5000, health
        code, frame = get("/camera/snapshot")
        assert code == 200 and frame.startswith(b"\xff\xd8") and frame.endswith(b"\xff\xd9")
        if args.snapshot:
            args.snapshot.write_bytes(frame)
        print(f"PASS: live JPEG snapshot ({len(frame)} bytes)")
        with urlopen(Request(base + "/camera/stream", headers={"X-Api-Key": key}), timeout=12) as stream:
            assert "multipart/x-mixed-replace" in stream.headers["Content-Type"]
            assert b"--voron\r\nContent-Type: image/jpeg" in stream.read(256)
        print("PASS: MJPEG streaming")
        result = json.loads(get("/printer/objects/query?webhooks&print_stats")[1])["result"]
        assert "status" in result
        print("PASS: authenticated Moonraker status passes through the phone")
        if args.upload_test:
            name = "voron-panel-check-" + uuid.uuid4().hex + ".gcode"
            boundary = "panel" + uuid.uuid4().hex
            gcode = b"; Voron panel connectivity check. No printer commands.\n"
            body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"root\"\r\n\r\ngcodes\r\n"
                    f"--{boundary}\r\nContent-Disposition: form-data; name=\"print\"\r\n\r\nfalse\r\n"
                    f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\n"
                    "Content-Type: application/octet-stream\r\n\r\n").encode() + gcode + f"\r\n--{boundary}--\r\n".encode()
            request = Request(base + "/server/files/upload", data=body,
                              headers={"X-Api-Key": key, "Content-Type": f"multipart/form-data; boundary={boundary}"})
            created = False
            try:
                with urlopen(request, timeout=15) as response:
                    created = True
                    payload = json.load(response)
                    upload = payload.get("result", payload)
                assert upload["item"]["path"] == name and not upload.get("print_started", False)
                assert get("/server/files/gcodes/" + name)[1] == gcode
                print("PASS: G-code upload and exact read-back through Pixel; printing not started")
            finally:
                if created:
                    with urlopen(Request(base + "/server/files/gcodes/" + name, method="DELETE",
                                         headers={"X-Api-Key": key}), timeout=12) as response:
                        assert response.status == 200
                    print("PASS: temporary connectivity-check file removed")
        code, html = get("/")
        assert code == 200 and b"/camera/stream" in html and b"<title>Voron</title>" in html
        time.sleep(1.5)
        assert json.loads(get("/health")[1])["frame_age_ms"] < 3000
        print("PASS: device dashboard and advancing camera frames")
    finally:
        if not args.lan:
            adb("forward", "--remove", "tcp:18765")


if __name__ == "__main__":
    main()
