"""Audit a native APK without Android SDK, Node, or third-party Python packages.

The Gradle build enforces the same boundary. This independent parser provides a
reviewable report of DEX types/method references, packaged files and native code.
It detects the listed runtime families; it is not a proof against deliberately
renamed/obfuscated malicious code. Keep dependency/source review as a second gate.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile

RUNTIME_PREFIXES = (
    "Lcom/facebook/react/", "Lcom/facebook/hermes/", "Lcom/facebook/jsi/",
    "Lcom/facebook/jsc/", "Lexpo/", "Lorg/unimodules/",
    "Lcom/eclipsesource/v8/", "Lcom/caoccao/javet/", "Lapp/cash/quickjs/",
    "Lapp/cash/zipline/", "Lcom/dokar/quickjs/", "Lcom/quickjs/",
    "Lcom/whl/quickjs/", "Lorg/mozilla/javascript/", "Lorg/liquidplayer/",
    "Lorg/graalvm/polyglot/", "Lcom/oracle/truffle/js/", "Lcom/tencent/smtt/",
    "Lorg/xwalk/core/", "Lorg/apache/cordova/", "Lcom/getcapacitor/",
    "Lcom/taobao/weex/", "Ljavax/script/",
)
WEBVIEW_TYPES = (
    "Landroid/webkit/WebView;", "Landroid/webkit/WebViewClient;",
    "Landroid/webkit/WebChromeClient;", "Landroid/webkit/WebSettings;",
    "Landroid/webkit/JavascriptInterface;", "Landroid/webkit/JsResult;",
    "Landroid/webkit/JsPromptResult;", "Landroidx/webkit/",
)
# AndroidX Core's backward-compatible Linkify calls this static address parser.
# It neither constructs a WebView nor evaluates content; no other WebView API is allowed.
ALLOWED_WEBVIEW_METHOD = ("Landroid/webkit/WebView;", "findAddress")
ASSET_SUFFIXES = (".js", ".mjs", ".cjs", ".jsx", ".ts", ".tsx", ".bundle",
                  ".hbc", ".qjs", ".jsc", ".wasm", ".html", ".htm", ".js.map")
RUNTIME_NAME = re.compile(r"react[-_]?native|hermes|quickjs|j2v8|javet|rhino|javascriptcore|libjsc|libv8|libjsi|libnode|(?:^|[/_.:-])expo(?:[/_.:-]|$)|(?:^|[/_.:-])metro(?:[/_.:-]|$)", re.I)
ALLOWED_NATIVE_LIBRARIES = {"libandroidx.graphics.path.so"}
NATIVE_SYMBOLS = (b"Java_com_facebook_react_", b"Java_com_facebook_hermes_",
                  b"Java_com_eclipsesource_v8_", b"JS_NewRuntime", b"JS_Eval",
                  b"JSEvaluateScript", b"JSContextGroupCreate", b"v8::Isolate",
                  b"_ZN2v87Isolate", b"hermes::vm::")


def source_violations(contents: str) -> list[str]:
    references = [prefix[1:].replace("/", ".") for prefix in RUNTIME_PREFIXES]
    references += ["android.webkit.WebView", "android.webkit.WebSettings",
                   "android.webkit.WebChromeClient", "android.webkit.WebViewClient",
                   "android.webkit.JavascriptInterface", "androidx.webkit",
                   "javax.script", "evaluateJavascript", "addJavascriptInterface", "node_modules"]
    found = [value for value in references if value in contents]
    found += re.findall(r"\b(?:WebView|WebViewClient|WebChromeClient|WebSettings|JavascriptInterface)\b", contents)
    return sorted(set(found))


def excluded_dependency(coordinate: str) -> bool:
    return bool(RUNTIME_NAME.search(coordinate)) or any(value in coordinate.lower() for value in (
        "com.facebook.react", "host.exp.exponent", "org.mozilla", "androidx.webkit",
        "org.graalvm", "org.liquidplayer", "cordova", "capacitor", "zipline",
        "com.tencent.tbs", "org.xwalk"))


def audit_project(project: Path) -> dict:
    root = project.resolve(strict=True)
    violations, files = [], []
    for source_set in (root / "app/src").iterdir():
        if not source_set.is_dir() or "test" in source_set.name.lower():
            continue
        for source in source_set.rglob("*"):
            if not source.is_file():
                continue
            name = source.relative_to(root).as_posix()
            files.append(name)
            if not source.resolve().is_relative_to(root):
                violations.append(f"Source escapes standalone native project: {name}")
                continue
            if source.name.lower().endswith(ASSET_SUFFIXES) or RUNTIME_NAME.search(source.name):
                violations.append(f"Excluded source/asset: {name}")
            if source.suffix.lower() in (".kt", ".java", ".xml"):
                violations += [f"Excluded source reference: {name}: {value}" for value in source_violations(source.read_text(encoding="utf-8"))]
    for name in ("build.gradle.kts", "settings.gradle.kts", "app/build.gradle.kts"):
        contents = (root / name).read_text(encoding="utf-8")
        contents = re.sub(r"/\*.*?\*/|^\s*//.*$", "", contents, flags=re.S | re.M)
        if re.search(r"node_modules|[\"'](?:node|npx|npm|yarn|pnpm|bun)[\"']|com\.facebook\.react|expo[-.]|includeBuild\s*\([^)]*\.\.", contents):
            violations.append(f"Legacy/JavaScript build dependency: {name}")
    return {"project": project.as_posix(), "production_files": sorted(files), "violations": violations}


class Dex:
    """Read actual DEX indexes, not a concatenated binary substring guess."""
    def __init__(self, data: bytes):
        self.data = data
        if len(data) < 112 or not re.fullmatch(rb"dex\n0(?:3[5-9]|40)\x00", data[:8]):
            raise ValueError("Unsupported or missing DEX header")
        if self.u32(32) != len(data) or self.u32(36) != 112 or self.u32(40) != 0x12345678:
            raise ValueError("Malformed DEX size/header/endian marker")
        strings = []
        for offset in self.table(56, 60, 4):
            position = self.u32(offset)
            for _ in range(5):
                self.bounds(position, 1)
                value = data[position]
                position += 1
                if value < 128:
                    break
            else:
                raise ValueError("Invalid DEX string length")
            end = data.find(b"\0", position)
            if end < 0:
                raise ValueError("Unterminated DEX string")
            strings.append(data[position:end].decode("utf-8", errors="replace"))
        self.strings = strings
        self.types = [self.item(strings, self.u32(offset)) for offset in self.table(64, 68, 4)]
        self.classes = [self.item(self.types, self.u32(offset)) for offset in self.table(96, 100, 32)]
        self.methods = [(self.item(self.types, self.u16(offset)), self.item(strings, self.u32(offset + 4)))
                        for offset in self.table(88, 92, 8)]

    def bounds(self, offset, size):
        if offset < 0 or size < 0 or offset + size > len(self.data):
            raise ValueError("DEX table outside file")

    def u16(self, offset):
        self.bounds(offset, 2)
        return struct.unpack_from("<H", self.data, offset)[0]

    def u32(self, offset):
        self.bounds(offset, 4)
        return struct.unpack_from("<I", self.data, offset)[0]

    def table(self, count_at, offset_at, width):
        count, start = self.u32(count_at), self.u32(offset_at)
        self.bounds(start, count * width)
        if count and start < 112:
            raise ValueError("DEX table overlaps header")
        return range(start, start + count * width, width)

    @staticmethod
    def item(items, index):
        if index >= len(items):
            raise ValueError("Invalid DEX index")
        return items[index]


def audit_apk(path: Path) -> dict:
    violations, dex_reports, native_libraries = [], [], []
    runtime_types, webview_methods, platform_types = set(), set(), set()
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            violations.append("Duplicate ZIP entries")
        assets = sorted(name for name in names if name.lower().endswith(ASSET_SUFFIXES) or RUNTIME_NAME.search(name))
        violations += [f"Excluded packaged asset/library: {name}" for name in assets]
        dex_names = [name for name in names if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name)]
        if "classes.dex" not in dex_names:
            violations.append("Missing application classes.dex")
        for name in names:
            if name.endswith(".dex") and name not in dex_names:
                violations.append(f"Unexpected embedded DEX: {name}")
            if name.startswith("lib/") and not name.endswith("/"):
                native_libraries.append(name)
                if Path(name).name not in ALLOWED_NATIVE_LIBRARIES:
                    violations.append(f"Unreviewed native library: {name}")
                data = archive.read(name)
                for symbol in NATIVE_SYMBOLS:
                    if symbol in data:
                        violations.append(f"JavaScript engine native symbol: {name}: {symbol.decode()}")
        for name in dex_names:
            try:
                dex = Dex(archive.read(name))
            except ValueError as error:
                violations.append(f"{name}: {error}")
                continue
            found = {value for value in dex.types if value.lstrip("[").startswith(RUNTIME_PREFIXES)}
            # Reflection names are strings rather than type IDs. Full class names only;
            # Prism_javascript is a native syntax grammar, never a JavaScript engine.
            reflected = {value for value in dex.strings if any(value.startswith(prefix[1:].replace("/", ".")) for prefix in RUNTIME_PREFIXES)}
            runtime_types.update(found | reflected)
            for value in dex.types:
                if value.startswith(WEBVIEW_TYPES):
                    platform_types.add(value)
                    if value != "Landroid/webkit/WebView;":
                        violations.append(f"WebView rendering/bridge type: {name}: {value}")
            for owner, method in dex.methods:
                if owner.startswith(WEBVIEW_TYPES):
                    webview_methods.add(owner + "->" + method)
                    if (owner, method) != ALLOWED_WEBVIEW_METHOD:
                        violations.append(f"WebView rendering/bridge method: {name}: {owner}->{method}")
            if "android.webkit.WebView" in dex.strings:
                violations.append(f"Reflective WebView class name: {name}")
            dex_reports.append({"file": name, "defined_classes": len(dex.classes), "referenced_types": len(dex.types), "referenced_methods": len(dex.methods)})
        violations += [f"Excluded runtime type/reflection name: {value}" for value in sorted(runtime_types)]
    return {"apk": path.as_posix(), "bytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "excluded_runtime_assets": assets, "excluded_runtime_types": sorted(runtime_types),
            "native_libraries": sorted(native_libraries), "dex": dex_reports,
            "webview_platform_type_references": sorted(platform_types),
            "webview_method_references": sorted(webview_methods),
            "allowed_platform_reference_explanation": "AndroidX Linkify may reference static WebView.findAddress; this does not construct or render a WebView. Every other WebView API is rejected.",
            "violations": sorted(set(violations)), "passed": not violations}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--dependencies", type=Path, help="Gradle native-dependencies.txt report")
    parser.add_argument("--project", type=Path, help="Audit standalone production sources and build definitions too")
    args = parser.parse_args()
    try:
        result = audit_apk(args.apk)
        if args.project:
            project = audit_project(args.project)
            result["source_audit"] = project
            result["violations"] += project["violations"]
            result["passed"] = not result["violations"]
        if args.dependencies:
            entries = args.dependencies.read_text(encoding="utf-8").splitlines()
            rejected = [entry for entry in entries if excluded_dependency(entry)]
            result["resolved_dependencies"] = entries
            result["violations"] += [f"Excluded dependency: {entry}" for entry in rejected]
            result["passed"] = not result["violations"]
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        result = {"apk": args.apk.as_posix(), "passed": False, "violations": [str(error)]}
    output = json.dumps(result, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output + "\n", encoding="utf-8")
    print(output)
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
