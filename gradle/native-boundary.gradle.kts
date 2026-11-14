import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

// This guard belongs to the independent native build, not to the legacy JS toolchain.
val enginePrefixes = listOf(
    "Lcom/facebook/react/", "Lcom/facebook/hermes/", "Lcom/facebook/jsi/", "Lcom/facebook/jsc/",
    "Lexpo/", "Lorg/unimodules/", "Lcom/eclipsesource/v8/", "Lcom/caoccao/javet/",
    "Lapp/cash/quickjs/", "Lapp/cash/zipline/", "Lcom/dokar/quickjs/", "Lcom/quickjs/",
    "Lcom/whl/quickjs/", "Lorg/mozilla/javascript/", "Lorg/liquidplayer/",
    "Lorg/graalvm/polyglot/", "Lcom/oracle/truffle/js/", "Lcom/tencent/smtt/",
    "Lorg/xwalk/core/", "Lorg/apache/cordova/", "Lcom/getcapacitor/", "Lcom/taobao/weex/", "Ljavax/script/"
)
val webTypes = listOf("Landroid/webkit/WebView;", "Landroid/webkit/WebViewClient;",
    "Landroid/webkit/WebChromeClient;", "Landroid/webkit/WebSettings;",
    "Landroid/webkit/JavascriptInterface;", "Landroid/webkit/JsResult;",
    "Landroid/webkit/JsPromptResult;", "Landroidx/webkit/")
val engineNames = Regex("react[-_]?native|hermes|quickjs|j2v8|javet|rhino|javascriptcore|libjsc|libv8|libjsi|libnode|(^|[/_.:-])expo([/_.:-]|$)|(^|[/_.:-])metro([/_.:-]|$)", RegexOption.IGNORE_CASE)
val assetExtensions = listOf(".js", ".mjs", ".cjs", ".jsx", ".ts", ".tsx", ".bundle", ".hbc", ".qjs", ".jsc", ".wasm", ".html", ".htm", ".js.map")
val nativeSymbols = listOf("Java_com_facebook_react_", "Java_com_facebook_hermes_",
    "Java_com_eclipsesource_v8_", "JS_NewRuntime", "JS_Eval", "JSEvaluateScript",
    "JSContextGroupCreate", "v8::Isolate", "_ZN2v87Isolate", "hermes::vm::")

class BoundaryDex(val bytes: ByteArray) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun bounds(start: Int, count: Int) {
        require(start >= 0 && count >= 0 && start.toLong() + count <= bytes.size) { "DEX index outside file" }
    }
    fun u32(offset: Int): Int { bounds(offset, 4); return buffer.getInt(offset) }
    fun u16(offset: Int): Int { bounds(offset, 2); return buffer.getShort(offset).toInt() and 65535 }
    private fun table(countAt: Int, startAt: Int, width: Int): List<Int> {
        val count = u32(countAt); val start = u32(startAt)
        require(count >= 0 && count.toLong() * width <= bytes.size) { "Invalid DEX table size" }
        bounds(start, count * width); require(count == 0 || start >= 112) { "DEX table overlaps header" }
        return List(count) { start + it * width }
    }
    val strings: List<String>
    val types: List<String>
    val methods: List<Pair<String,String>>
    init {
        require(bytes.size >= 112 && String(bytes, 0, 8, Charsets.ISO_8859_1).matches(Regex("dex\\n0(3[5-9]|40)\\u0000"))) { "Unsupported DEX header" }
        require(u32(32) == bytes.size && u32(36) == 112 && u32(40) == 0x12345678) { "Invalid DEX header" }
        strings = table(56, 60, 4).map { entry ->
            var position = u32(entry)
            var count = 0
            do { bounds(position, 1); val more = bytes[position++].toInt() and 128; count++; require(count <= 5) { "Invalid DEX string size" } } while (more != 0)
            val start = position
            while (true) { bounds(position, 1); if (bytes[position].toInt() == 0) break; position++ }
            String(bytes, start, position - start, Charsets.UTF_8)
        }
        types = table(64, 68, 4).map { strings[u32(it)] }
        methods = table(88, 92, 8).map { types[u16(it)] to strings[u32(it + 4)] }
        table(96, 100, 32).forEach { types[u32(it)] }
    }
}

val verifyNativeBoundary = tasks.register("verifyNativeBoundary") {
    group = "verification"
    description = "Reject JS engines, WebView rendering and dependencies on the legacy project."
    doLast {
        val violations = mutableListOf<String>()
        val nativeRoot = rootProject.projectDir.toPath().toRealPath()
        val production = file("src").listFiles().orEmpty().filter { it.isDirectory && !it.name.contains("test", true) }
        val files = production.flatMap { directory -> directory.walkTopDown().filter { it.isFile }.toList() }
        files.forEach { source ->
            val name = source.invariantSeparatorsPath
            if (!source.toPath().toRealPath().startsWith(nativeRoot)) violations += "Source escapes native project: $name"
            if (assetExtensions.any { name.lowercase().endsWith(it) } || engineNames.containsMatchIn(source.name)) violations += "Excluded native source/asset: $name"
            if (source.extension.lowercase() in setOf("kt", "java", "xml")) {
                val contents = source.readText()
                val banned = listOf("android.webkit.WebView", "android.webkit.WebSettings", "android.webkit.WebChromeClient",
                    "android.webkit.WebViewClient", "android.webkit.JavascriptInterface", "androidx.webkit",
                    "javax.script", "evaluateJavascript", "addJavascriptInterface", "node_modules") +
                    enginePrefixes.map { it.removePrefix("L").replace('/', '.') }
                banned.filter(contents::contains).forEach { violations += "Excluded source reference in $name: $it" }
                if (Regex("\\b(WebView|WebViewClient|WebChromeClient|WebSettings|JavascriptInterface)\\b").containsMatchIn(contents)) violations += "WebView rendering/bridge source in $name"
            }
        }
        listOf(rootProject.file("settings.gradle.kts"), rootProject.file("build.gradle.kts"), project.file("build.gradle.kts")).forEach { buildFile ->
            val text = buildFile.readText().replace(Regex("(?s)/\\*.*?\\*/|(?m)^\\s*//.*$"), "")
            if (Regex("node_modules|[\"'](node|npx|npm|yarn|pnpm|bun)[\"']|com\\.facebook\\.react|expo[-.]|includeBuild\\s*\\([^)]*\\.\\.").containsMatchIn(text)) violations += "Legacy/JS build dependency in ${buildFile.name}"
        }
        val dependencies = mutableSetOf<String>()
        configurations.filter { it.isCanBeResolved && it.name.endsWith("RuntimeClasspath") && !it.name.contains("test", true) }.forEach { configuration ->
            configuration.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
                val coordinate = artifact.moduleVersion.id.toString()
                dependencies += coordinate
                val lower = coordinate.lowercase()
                if (engineNames.containsMatchIn(coordinate) || listOf("com.facebook.react", "host.exp.exponent", "org.mozilla", "androidx.webkit", "org.graalvm", "org.liquidplayer", "cordova", "capacitor", "zipline", "com.tencent.tbs", "org.xwalk").any(lower::contains)) violations += "Excluded runtime dependency: $coordinate"
                if (artifact.file.toPath().toRealPath().startsWith(nativeRoot.resolve("node_modules"))) violations += "Legacy dependency artifact: $coordinate"
            }
        }
        val report = layout.buildDirectory.file("reports/native-boundary/native-dependencies.txt").get().asFile
        report.parentFile.mkdirs(); report.writeText(dependencies.sorted().joinToString("\n", postfix = "\n"))
        check(violations.isEmpty()) { "Native boundary failed:\n" + violations.distinct().joinToString("\n") }
        logger.lifecycle("Native boundary: ${files.size} production files and ${dependencies.size} resolved runtime dependencies verified.")
    }
}
tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach { dependsOn(verifyNativeBoundary) }

listOf("Debug", "Release").forEach { variant ->
    val verifyApk = tasks.register("verify${variant}NativeApk") {
        group = "verification"
        description = "Reject JavaScript runtimes and WebView rendering in the $variant APK."
        dependsOn("package$variant", verifyNativeBoundary)
        doLast {
            val apks = layout.buildDirectory.dir("outputs/apk/${variant.lowercase()}").get().asFile.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
            check(apks.isNotEmpty()) { "No $variant APK found for native audit" }
            apks.forEach { apk ->
                val violations = mutableListOf<String>()
                ZipFile(apk).use { archive ->
                    val entries = archive.entries().toList()
                    val names = entries.map { it.name }
                    if (names.size != names.distinct().size) violations += "Duplicate ZIP entries"
                    if ("classes.dex" !in names) violations += "Missing application classes.dex"
                    entries.filter { !it.isDirectory }.forEach { entry ->
                        val name = entry.name
                        if (assetExtensions.any { name.lowercase().endsWith(it) } || engineNames.containsMatchIn(name)) violations += "Excluded packaged file: $name"
                        if (name.endsWith(".dex")) {
                            if (!name.matches(Regex("classes([2-9]|[1-9][0-9]+)?\\.dex"))) violations += "Unexpected embedded DEX: $name"
                            val dex = BoundaryDex(archive.getInputStream(entry).use { it.readBytes() })
                            dex.types.filter { type -> enginePrefixes.any { type.trimStart('[').startsWith(it) } }.forEach { violations += "Runtime type: $it" }
                            dex.strings.filter { value -> enginePrefixes.any { value.startsWith(it.removePrefix("L").replace('/', '.')) } }.forEach { violations += "Reflective runtime name: $it" }
                            dex.types.filter { type -> webTypes.any(type::startsWith) && type != "Landroid/webkit/WebView;" }.forEach { violations += "WebView type: $it" }
                            dex.methods.filter { (owner, method) -> webTypes.any(owner::startsWith) && !(owner == "Landroid/webkit/WebView;" && method == "findAddress") }.forEach { violations += "WebView method: ${it.first}->${it.second}" }
                            if ("android.webkit.WebView" in dex.strings) violations += "Reflective WebView class"
                        }
                        if (name.startsWith("lib/")) {
                            if (name.substringAfterLast('/') != "libandroidx.graphics.path.so") violations += "Unreviewed native library: $name"
                            val content = archive.getInputStream(entry).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                            nativeSymbols.filter(content::contains).forEach { violations += "JS engine symbol in $name: $it" }
                        }
                    }
                }
                check(violations.isEmpty()) { "Native APK boundary failed for ${apk.name}:\n" + violations.distinct().joinToString("\n") }
                logger.lifecycle("Native APK boundary verified: ${apk.name}")
            }
        }
    }
    tasks.matching { it.name in setOf("assemble$variant", "install$variant", "test${variant}UnitTest", "connected${variant}AndroidTest") }.configureEach { dependsOn(verifyApk) }
}
