#!/usr/bin/env python3
"""
Automated verification of merged AndroidManifest.xml and packaged APKs
for EterScreen Translator Manual and Full product flavors.
"""

import glob
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

MANUAL_MERGED_MANIFEST = (
    "app/build/intermediates/merged_manifests/manualDebug/processManualDebugManifest/AndroidManifest.xml"
)
FULL_MERGED_MANIFEST = (
    "app/build/intermediates/merged_manifests/fullDebug/processFullDebugManifest/AndroidManifest.xml"
)
MANUAL_APK = "app/build/outputs/apk/manual/debug/EterScreen-Translator-manual-debug.apk"
FULL_APK = "app/build/outputs/apk/full/debug/EterScreen-Translator-full-debug.apk"


def find_aapt2() -> str:
    sdk_root = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if sdk_root:
        candidates = sorted(glob.glob(os.path.join(sdk_root, "build-tools", "*", "aapt2")))
        if candidates:
            return candidates[-1]
    which = shutil.which("aapt2")
    if which:
        return which
    raise RuntimeError("aapt2 not found under ANDROID_SDK_ROOT / ANDROID_HOME or PATH")


def verify_merged_manifest(path: str, expected_package: str, expect_accessibility: bool) -> None:
    if not os.path.isfile(path):
        raise AssertionError(f"Merged manifest not found: {path}")

    tree = ET.parse(path)
    root = tree.getroot()
    pkg = root.attrib.get("package")
    if pkg != expected_package:
        raise AssertionError(f"{path}: expected package={expected_package}, got {pkg}")

    app = root.find("application")
    if app is None:
        raise AssertionError(f"{path}: missing <application>")

    services = app.findall("service")
    service_names = [s.attrib.get(f"{ANDROID_NS}name", "") for s in services]

    if "com.example.core.service.ScreenCaptureForegroundService" not in service_names:
        raise AssertionError(f"{path}: missing ScreenCaptureForegroundService")

    accessibility_services = []
    for svc in services:
        name = svc.attrib.get(f"{ANDROID_NS}name", "")
        perm = svc.attrib.get(f"{ANDROID_NS}permission", "")
        actions = [
            act.attrib.get(f"{ANDROID_NS}name", "")
            for flt in svc.findall("intent-filter")
            for act in flt.findall("action")
        ]
        metas = [m.attrib.get(f"{ANDROID_NS}name", "") for m in svc.findall("meta-data")]
        if (
            "AutoScrollAccessibilityService" in name
            or perm == "android.permission.BIND_ACCESSIBILITY_SERVICE"
            or "android.accessibilityservice.AccessibilityService" in actions
            or "android.accessibilityservice" in metas
        ):
            accessibility_services.append((name, perm, actions, metas))

    raw_text = open(path, encoding="utf-8").read()

    if expect_accessibility:
        if len(accessibility_services) != 1:
            raise AssertionError(
                f"{path}: Full edition must declare exactly 1 AccessibilityService, got {accessibility_services}"
            )
        name, perm, actions, metas = accessibility_services[0]
        if name != "com.example.core.service.AutoScrollAccessibilityService":
            raise AssertionError(f"{path}: unexpected accessibility service name {name}")
        if perm != "android.permission.BIND_ACCESSIBILITY_SERVICE":
            raise AssertionError(f"{path}: missing BIND_ACCESSIBILITY_SERVICE permission on service")
        if "android.accessibilityservice.AccessibilityService" not in actions:
            raise AssertionError(f"{path}: missing AccessibilityService intent-filter")
        if "android.accessibilityservice" not in metas:
            raise AssertionError(f"{path}: missing android.accessibilityservice meta-data")
    else:
        if accessibility_services:
            raise AssertionError(
                f"{path}: Manual edition MUST NOT declare any AccessibilityService, found {accessibility_services}"
            )
        for forbidden in (
            "AutoScrollAccessibilityService",
            "BIND_ACCESSIBILITY_SERVICE",
            "android.accessibilityservice.AccessibilityService",
            "accessibility_service_config",
        ):
            if forbidden in raw_text:
                raise AssertionError(f"{path}: Manual edition must not contain '{forbidden}'")


def run_aapt2(aapt2: str, *args: str) -> str:
    proc = subprocess.run([aapt2, *args], capture_output=True, text=True, check=True)
    return proc.stdout


def verify_packaged_apk(
    aapt2: str,
    apk_path: str,
    expected_package: str,
    expected_label: str,
    expect_accessibility: bool,
) -> None:
    if not os.path.isfile(apk_path):
        raise AssertionError(f"Packaged APK not found: {apk_path}")

    badging = run_aapt2(aapt2, "dump", "badging", apk_path)
    expected_pkg_token = f"package: name='{expected_package}'"
    if expected_pkg_token not in badging:
        raise AssertionError(f"{apk_path}: expected {expected_pkg_token} in badging:\n{badging[:400]}")

    expected_label_token = f"application-label:'{expected_label}'"
    if expected_label_token not in badging:
        raise AssertionError(f"{apk_path}: expected {expected_label_token} in badging")

    xmltree = run_aapt2(aapt2, "dump", "xmltree", apk_path, "--file", "AndroidManifest.xml")
    if "com.example.core.service.ScreenCaptureForegroundService" not in xmltree:
        raise AssertionError(f"{apk_path}: missing ScreenCaptureForegroundService in packaged AndroidManifest.xml")

    if expect_accessibility:
        for required in (
            "com.example.core.service.AutoScrollAccessibilityService",
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            "android.accessibilityservice.AccessibilityService",
            "android.accessibilityservice",
        ):
            if required not in xmltree:
                raise AssertionError(f"{apk_path}: Full APK missing '{required}' in packaged AndroidManifest.xml")
    else:
        for forbidden in (
            "AutoScrollAccessibilityService",
            "BIND_ACCESSIBILITY_SERVICE",
            "android.accessibilityservice.AccessibilityService",
            "android.accessibilityservice",
        ):
            if forbidden in xmltree:
                raise AssertionError(f"{apk_path}: Manual APK MUST NOT contain '{forbidden}' in packaged AndroidManifest.xml")


def main() -> int:
    aapt2 = find_aapt2()
    print(f"Using aapt2: {aapt2}")

    verify_merged_manifest(MANUAL_MERGED_MANIFEST, "com.eteroverseer.screentranslator", expect_accessibility=False)
    print(f"[OK] Merged manifest verified (manual): {MANUAL_MERGED_MANIFEST}")

    verify_merged_manifest(FULL_MERGED_MANIFEST, "com.eteroverseer.screentranslator.full", expect_accessibility=True)
    print(f"[OK] Merged manifest verified (full):   {FULL_MERGED_MANIFEST}")

    verify_packaged_apk(
        aapt2,
        MANUAL_APK,
        expected_package="com.eteroverseer.screentranslator",
        expected_label="EterScreen Translator",
        expect_accessibility=False,
    )
    print(f"[OK] Packaged APK verified (manual):    {MANUAL_APK}")

    verify_packaged_apk(
        aapt2,
        FULL_APK,
        expected_package="com.eteroverseer.screentranslator.full",
        expected_label="EterScreen Translator Full",
        expect_accessibility=True,
    )
    print(f"[OK] Packaged APK verified (full):      {FULL_APK}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        sys.exit(1)
