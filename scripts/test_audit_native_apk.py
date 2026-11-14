"""Negative fixtures for the APK boundary parser; standard-library unittest only."""
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("native_audit", Path(__file__).with_name("audit-native-apk.py"))
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)


def fixture_dex(types=(), methods=(), extra_strings=()):
    """Minimal DEX indexes, used only to test structural boundary detection."""
    strings = list(dict.fromkeys([*types, *(name for _, name in methods), *extra_strings]))
    data = bytearray(112)
    data[:8] = b"dex\n035\0"
    def table(header, size, width):
        offset = len(data)
        data.extend(b"\0" * (size * width))
        struct.pack_into("<II", data, header, size, offset if size else 0)
        return offset
    string_table = table(56, len(strings), 4)
    type_table = table(64, len(types), 4)
    method_table = table(88, len(methods), 8)
    for index, value in enumerate(strings):
        encoded = value.encode()
        struct.pack_into("<I", data, string_table + 4 * index, len(data))
        size = len(value)
        while size > 127:
            data.append((size & 127) | 128)
            size >>= 7
        data.append(size)
        data.extend(encoded + b"\0")
    for index, value in enumerate(types):
        struct.pack_into("<I", data, type_table + 4 * index, strings.index(value))
    for index, (owner, name) in enumerate(methods):
        struct.pack_into("<HHI", data, method_table + 8 * index, types.index(owner), 0, strings.index(name))
    struct.pack_into("<III", data, 32, len(data), 112, 0x12345678)
    return bytes(data)


class NativeApkAuditTest(unittest.TestCase):
    def audit(self, dex=None, entries=None):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent) as temporary:
            path = Path(temporary) / "fixture.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("classes.dex", fixture_dex() if dex is None else dex)
                for name, data in (entries or {}).items():
                    archive.writestr(name, data)
            return audit.audit_apk(path)

    def test_native_syntax_grammar_is_not_an_engine(self):
        result = self.audit(fixture_dex(["Ldev/ccdesk/nativeapp/MainActivity;", "Lio/noties/prism4j/languages/Prism_javascript;"]))
        self.assertTrue(result["passed"], result["violations"])

    def test_package_prefix_keeps_its_boundary_and_does_not_match_exported(self):
        self.assertEqual([], audit.source_violations('<activity android:exported="true"/> // exported native icon'))
        self.assertEqual([], audit.source_violations('import io.noties.prism4j.languages.Prism_javascript'))
        self.assertIn("expo.", audit.source_violations('import expo.modules.kotlin.AppContext'))
        self.assertTrue(audit.source_violations('import com.facebook.react.ReactActivity'))
        self.assertTrue(audit.source_violations('import android.webkit.*; val view = WebView(context)'))

    def test_dependency_coordinates_include_transitive_react_android_and_expo(self):
        for coordinate in ("com.facebook.react:react-android:0.82.0", "host.exp.exponent:expo:1.0", "expo:expo-modules-core:1.0", "org.mozilla:rhino:1.0", "com.eclipsesource.j2v8:j2v8:1.0", "androidx.webkit:webkit:1.0"):
            with self.subTest(coordinate=coordinate):
                self.assertTrue(audit.excluded_dependency(coordinate))
        self.assertFalse(audit.excluded_dependency("androidx.core:core:1.16.0"))
        self.assertFalse(audit.excluded_dependency("org.jetbrains.kotlin:kotlin-android-extensions-runtime:1.4.0"))

    def test_all_runtime_families_and_reflection_names_are_rejected(self):
        for prefix in audit.RUNTIME_PREFIXES:
            with self.subTest(prefix=prefix):
                self.assertFalse(self.audit(fixture_dex([prefix + "Engine;"]))["passed"])
                reflected = prefix[1:].replace("/", ".") + "Engine"
                self.assertFalse(self.audit(fixture_dex(extra_strings=[reflected]))["passed"])

    def test_webview_static_address_parser_is_the_only_allowed_method(self):
        owner = "Landroid/webkit/WebView;"
        self.assertTrue(self.audit(fixture_dex([owner], [(owner, "findAddress")]))["passed"])
        for method in ("<init>", "loadUrl", "loadDataWithBaseURL", "evaluateJavascript", "addJavascriptInterface"):
            with self.subTest(method=method):
                self.assertFalse(self.audit(fixture_dex([owner], [(owner, method)]))["passed"])
        self.assertFalse(self.audit(fixture_dex(["Landroid/webkit/JavascriptInterface;"]))["passed"])
        self.assertFalse(self.audit(fixture_dex(extra_strings=["android.webkit.WebView"]))["passed"])

    def test_packaged_script_html_wasm_and_hidden_dex_are_rejected(self):
        for suffix in audit.ASSET_SUFFIXES:
            with self.subTest(suffix=suffix):
                self.assertFalse(self.audit(entries={"assets/content" + suffix: b"content"})["passed"])
        self.assertFalse(self.audit(entries={"assets/hidden.dex": fixture_dex()})["passed"])

    def test_native_library_requires_review_and_must_not_export_engine_symbols(self):
        name = "lib/arm64-v8a/libandroidx.graphics.path.so"
        self.assertTrue(self.audit(entries={name: b"\x7fELF native path geometry"})["passed"])
        self.assertFalse(self.audit(entries={"lib/arm64-v8a/libbusiness.so": b"\x7fELF"})["passed"])
        for symbol in audit.NATIVE_SYMBOLS:
            with self.subTest(symbol=symbol):
                self.assertFalse(self.audit(entries={name: b"\x7fELF" + symbol})["passed"])

    def test_malformed_dex_fails_closed_instead_of_reporting_clean(self):
        self.assertFalse(self.audit(b"not a dex")["passed"])
        broken = bytearray(fixture_dex(["Lsafe/Class;"]))
        struct.pack_into("<I", broken, 60, len(broken) + 100)
        self.assertFalse(self.audit(bytes(broken))["passed"])


if __name__ == "__main__":
    unittest.main()
