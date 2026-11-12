"""Dependency-free Android SDK build. Signing material stays outside the checkout."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
SDK = Path(os.environ.get("ANDROID_HOME", Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"))
JDK = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Android/Android Studio/jbr"))
TOOLS = SDK / "build-tools/36.0.0"
ANDROID = SDK / "platforms/android-37.0/android.jar"
BUILD = Path(os.environ["LOCALAPPDATA"]) / "VoronPanel/build"
OUTPUT = ROOT / ".build"

def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)

def main():
    source_hashes = {str(p.relative_to(ROOT)).replace('\\', '/'): hashlib.sha256(p.read_bytes()).hexdigest()
                     for p in sorted((ROOT/'app').rglob('*')) if p.is_file()}
    BUILD.mkdir(parents=True, exist_ok=True)
    OUTPUT.mkdir(exist_ok=True)
    for name in ("classes", "dex"):
        target = (BUILD / name).resolve()
        assert target.is_relative_to(BUILD.resolve())
        if target.exists():
            shutil.rmtree(target)
        target.mkdir()
    run(TOOLS / "aapt2.exe", "compile", "--dir", ROOT / "app/res", "-o", BUILD / "resources.zip")
    run(TOOLS / "aapt2.exe", "link", "-o", BUILD / "unsigned.apk", "-I", ANDROID,
        "--manifest", ROOT / "app/AndroidManifest.xml", "-A", ROOT / "app/assets", BUILD / "resources.zip")
    run(JDK / "bin/javac.exe", "--release", "8", "-encoding", "UTF-8", "-classpath", ANDROID,
        "-d", BUILD / "classes", *sorted((ROOT / "app/src").glob("*.java")))
    run(JDK / "bin/java.exe", "-cp", TOOLS / "lib/d8.jar", "com.android.tools.r8.D8", "--lib", ANDROID,
        "--min-api", "31", "--output", BUILD / "dex", *BUILD.glob("classes/**/*.class"))
    with zipfile.ZipFile(BUILD / "unsigned.apk", "a") as apk:
        apk.write(BUILD / "dex/classes.dex", "classes.dex")
    run(TOOLS / "zipalign.exe", "-f", "4", BUILD / "unsigned.apk", BUILD / "aligned.apk")
    key = Path(os.environ["LOCALAPPDATA"]) / "VoronPanel/signing/development.jks"
    key.parent.mkdir(parents=True, exist_ok=True)
    if not key.exists():
        run(JDK / "bin/keytool.exe", "-genkeypair", "-keystore", key, "-storepass", "android",
            "-keypass", "android", "-alias", "voron", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "10000", "-dname", "CN=Voron Panel Development")
    signer = (JDK / "bin/java.exe", "-jar", TOOLS / "lib/apksigner.jar")
    run(*signer, "sign", "--ks", key, "--ks-pass", "pass:android", "--out", BUILD / "Voron.apk", BUILD / "aligned.apk")
    run(*signer, "verify", BUILD / "Voron.apk")
    shutil.copy2(BUILD / "Voron.apk", OUTPUT / "Voron.apk")
    current_hashes = {str(p.relative_to(ROOT)).replace('\\', '/'): hashlib.sha256(p.read_bytes()).hexdigest()
                      for p in sorted((ROOT/'app').rglob('*')) if p.is_file()}
    assert current_hashes == source_hashes, 'App source changed during compilation'
    (OUTPUT/'last-build.json').write_text(json.dumps({
        'source_sha256': source_hashes,
        'apk_sha256': hashlib.sha256((OUTPUT/'Voron.apk').read_bytes()).hexdigest(),
        'target_sdk': 37, 'min_api': 31,
    }, indent=2)+'\n', encoding='utf-8')
    print(OUTPUT / "Voron.apk")

if __name__ == "__main__":
    main()
