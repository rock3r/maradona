import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import java.io.DataInputStream
import java.util.jar.JarFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.jvm.tasks.Jar
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.maven.publish) apply false
}

val publishGroupId: String = providers.gradleProperty("GROUP").get()
val publishVersion: String = providers.gradleProperty("VERSION_NAME").get()

group = publishGroupId
version = publishVersion

subprojects {
    group = publishGroupId
    version = publishVersion

    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        // Embed LICENSE and NOTICE into every published JAR / sources JAR / javadoc JAR.
        tasks.withType<Jar>().configureEach {
            from(listOf(rootProject.file("LICENSE"), rootProject.file("NOTICE"))) {
                into("META-INF")
            }
        }

        extensions.configure<MavenPublishBaseExtension> {
            // Sonatype Central Portal endpoint. Defaults to automatic promotion when
            // `-PcentralAutomaticRelease=true` is passed (used by the GitHub Release ->
            // Maven Central workflow), or manual promotion (`false`) when staging for
            // inspection via `scripts/central_portal_check.py`.
            val autoRelease =
                providers.gradleProperty("centralAutomaticRelease")
                    .map { it.equals("true", ignoreCase = true) }
                    .getOrElse(false)
            publishToMavenCentral(automaticRelease = autoRelease)

            configure(
                KotlinMultiplatform(
                    javadocJar = JavadocJar.Empty(),
                    sourcesJar = SourcesJar.Sources(),
                ),
            )

            // Only sign when an in-memory PGP key is supplied (`ORG_GRADLE_PROJECT_signingInMemoryKey`
            // or `-PsigningInMemoryKey=...`) so local `publishToMavenLocal` and
            // `verifyMavenLocalPublication` work without credentials.
            val hasSigningKey =
                providers.gradleProperty("signingInMemoryKey").orNull?.isNotBlank() == true
            if (hasSigningKey) {
                signAllPublications()
            }
        }
    }
}

val publishedLibraryProjects = listOf(
    ":maradona",
    ":maradona-material3",
    ":maradona-jewel",
)

data class ExpectedComponentSpec(
    val componentId: String,
    val primaryExtension: String,
    val forbidJewelDependency: Boolean,
)

val isMacOsHost = org.gradle.internal.os.OperatingSystem.current().isMacOsX
val expectedComponents: List<ExpectedComponentSpec> = buildList {
    for ((baseId, hasMobileTargets) in listOf("maradona" to true, "maradona-material3" to true, "maradona-jewel" to false)) {
        add(ExpectedComponentSpec(baseId, ".jar", forbidJewelDependency = hasMobileTargets))
        add(ExpectedComponentSpec("$baseId-desktop", ".jar", forbidJewelDependency = hasMobileTargets))
        if (hasMobileTargets) {
            add(ExpectedComponentSpec("$baseId-android", ".aar", forbidJewelDependency = true))
            if (isMacOsHost) {
                add(ExpectedComponentSpec("$baseId-iosarm64", ".klib", forbidJewelDependency = true))
                add(ExpectedComponentSpec("$baseId-iossimulatorarm64", ".klib", forbidJewelDependency = true))
            }
        }
    }
}

val mavenLocalRepoRoot: String = "${System.getProperty("user.home")}/.m2/repository"

val verifyApiDesign = tasks.register("verifyApiDesign") {
    description =
        "Verifies binary/source compatibility baselines, Desktop/Android ABI parity, " +
            "module boundary hygiene, and KDoc coverage across published Maradona modules."
    group = "verification"

    dependsOn(publishedLibraryProjects.map { "$it:checkKotlinAbi" })

    val projectRoot = rootProject.layout.projectDirectory.asFile

    doLast {
        val errors = mutableListOf<String>()

        val requiredAbiFiles = listOf(
            "maradona/api/desktop/maradona.api",
            "maradona/api/android/maradona.api",
            "maradona/api/maradona.klib.api",
            "maradona-material3/api/desktop/maradona-material3.api",
            "maradona-material3/api/android/maradona-material3.api",
            "maradona-material3/api/maradona-material3.klib.api",
            "maradona-jewel/api/maradona-jewel.api",
        )
        for (relPath in requiredAbiFiles) {
            val f = projectRoot.resolve(relPath)
            if (!f.isFile || f.length() <= 0L) {
                errors += "Missing or empty ABI baseline dump: $relPath"
            }
        }

        // Verify Desktop and Android JVM ABI dumps are byte-for-byte identical for shared KMP modules
        for (module in listOf("maradona", "maradona-material3")) {
            val desktopApi = projectRoot.resolve("$module/api/desktop/$module.api")
            val androidApi = projectRoot.resolve("$module/api/android/$module.api")
            if (desktopApi.isFile && androidApi.isFile) {
                val dText = desktopApi.readText()
                val aText = androidApi.readText()
                if (dText != aText) {
                    errors += "$module: Desktop ABI ($module/api/desktop/$module.api) differs from Android ABI ($module/api/android/$module.api)"
                }
            }
        }

        // Verify no internal implementation details leak into any public ABI dump
        val forbiddenInternalTokens = listOf(
            "dev/maradona/layout/dagre/",
            "dev.maradona.layout.dagre",
            "ViewportCullingSupport",
            "FormattedTextSpan",
            "parseFormattedLabelLines",
            "wrapFormattedLabelLines",
            "LayoutForExport",
            "PremeasuredFlowchartResult",
            "PremeasuredSequenceResult",
        )
        for (relPath in requiredAbiFiles) {
            val f = projectRoot.resolve(relPath)
            if (!f.isFile) continue
            f.useLines { lines ->
                lines.forEachIndexed { idx, line ->
                    if (line.contains("\$stableprop")) return@forEachIndexed
                    for (token in forbiddenInternalTokens) {
                        if (line.contains(token)) {
                            errors += "$relPath:${idx + 1}: leaks internal implementation symbol '$token'"
                        }
                    }
                }
            }
        }

        // Verify module boundary hygiene (no Jewel/AWT/Swing in :maradona or :maradona-material3;
        // no Material3 in :maradona)
        val nonDesktopAbiFiles = listOf(
            "maradona/api/desktop/maradona.api",
            "maradona/api/android/maradona.api",
            "maradona/api/maradona.klib.api",
            "maradona-material3/api/desktop/maradona-material3.api",
            "maradona-material3/api/android/maradona-material3.api",
            "maradona-material3/api/maradona-material3.klib.api",
        )
        for (relPath in nonDesktopAbiFiles) {
            val f = projectRoot.resolve(relPath)
            if (!f.isFile) continue
            val content = f.readText()
            for (forbidden in listOf("org/jetbrains/jewel", "org.jetbrains.jewel", "java/awt/", "javax/swing/")) {
                if (content.contains(forbidden)) {
                    errors += "$relPath: must not expose desktop/Jewel type '$forbidden'"
                }
            }
            if (relPath.startsWith("maradona/api/") &&
                (content.contains("androidx/compose/material3") || content.contains("androidx.compose.material3"))
            ) {
                errors += "$relPath: core :maradona module must not expose Material 3 types"
            }
        }

        // Verify package namespace boundaries in JVM ABI dumps
        val packageRules = listOf(
            "maradona/api/desktop/maradona.api" to Regex("""^public .* class dev/maradona/(export|layout|model|parse|render)/"""),
            "maradona-material3/api/desktop/maradona-material3.api" to Regex("""^public .* class dev/maradona/theme/material3/"""),
            "maradona-jewel/api/maradona-jewel.api" to Regex("""^public .* class dev/maradona/theme/jewel/"""),
        )
        for ((relPath, allowedClassRegex) in packageRules) {
            val f = projectRoot.resolve(relPath)
            if (!f.isFile) continue
            f.useLines { lines ->
                lines.forEachIndexed { idx, line ->
                    if (line.startsWith("public ") && " class " in line && !allowedClassRegex.containsMatchIn(line)) {
                        errors += "$relPath:${idx + 1}: public class outside module package boundary: $line"
                    }
                }
            }
        }

        // Verify KDoc presence on all primary public entry-point source files
        val publicEntryPointSources = listOf(
            "maradona/src/commonMain/kotlin/dev/maradona/render/MaradonaDiagram.kt" to listOf("fun MaradonaDiagram("),
            "maradona/src/commonMain/kotlin/dev/maradona/render/MaradonaTheme.kt" to listOf("class MaradonaTheme(", "val LocalMaradonaTheme"),
            "maradona/src/commonMain/kotlin/dev/maradona/parse/MaradonaParser.kt" to listOf("object MaradonaParser", "sealed interface MaradonaDiagramModel"),
            "maradona/src/commonMain/kotlin/dev/maradona/export/MaradonaSvgExporter.kt" to listOf("object MaradonaSvgExporter", "fun exportToSvg("),
            "maradona-material3/src/commonMain/kotlin/dev/maradona/theme/material3/Material3MaradonaTheme.kt" to listOf(
                "fun MaradonaTheme.Companion.fromMaterial3(",
                "fun rememberMaterial3MaradonaTheme(",
                "fun ProvideMaterial3MaradonaTheme(",
            ),
            "maradona-jewel/src/desktopMain/kotlin/dev/maradona/theme/jewel/JewelMaradonaTheme.kt" to listOf(
                "fun MaradonaTheme.Companion.fromJewel(",
                "fun rememberJewelMaradonaTheme(",
                "fun ProvideJewelMaradonaTheme(",
            ),
        )
        for ((relPath, declarations) in publicEntryPointSources) {
            val f = projectRoot.resolve(relPath)
            if (!f.isFile) {
                errors += "Missing expected public entry-point source file: $relPath"
                continue
            }
            val src = f.readText()
            if (!src.contains("/**")) {
                errors += "$relPath: missing KDoc comments on public entry points"
            }
            for (decl in declarations) {
                val idx = src.indexOf(decl)
                if (idx < 0) {
                    errors += "$relPath: missing expected public declaration '$decl'"
                } else {
                    val preceding = src.substring((idx - 600).coerceAtLeast(0), idx)
                    if (!preceding.contains("*/")) {
                        errors += "$relPath: declaration '$decl' is missing preceding KDoc comment"
                    }
                }
            }
        }

        if (errors.isNotEmpty()) {
            throw GradleException(
                "verifyApiDesign failed:\n" + errors.joinToString("\n") { "  - $it" },
            )
        }
        println("verifyApiDesign: all ABI baselines, package boundaries, and KDoc entry-point checks passed.")
    }
}

tasks.register("verifyMavenLocalPublication") {
    description =
        "Publishes Maradona library modules to mavenLocal and verifies that every " +
            "KMP component's artifacts, POM metadata, Gradle Module Metadata, and " +
            "bundled LICENSE/NOTICE files satisfy Sonatype Central requirements."
    group = "verification"

    dependsOn(verifyApiDesign)
    dependsOn(publishedLibraryProjects.map { "$it:publishToMavenLocal" })

    val groupPath = publishGroupId.replace('.', '/')
    val versionStr = publishVersion
    val components = expectedComponents
    val repoRoot = file(mavenLocalRepoRoot)

    doLast {
        val errors = mutableListOf<String>()

        fun firstDirectChild(parent: Element, tag: String): Element? {
            val children = parent.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node is Element && node.tagName == tag) return node
            }
            return null
        }

        fun firstDirectChildText(parent: Element, tag: String): String? =
            firstDirectChild(parent, tag)?.textContent?.trim()

        for (spec in components) {
            val artifactId = spec.componentId
            val moduleDir = repoRoot.resolve("$groupPath/$artifactId/$versionStr")
            if (!moduleDir.isDirectory) {
                errors += "$artifactId: expected mavenLocal directory $moduleDir does not exist"
                continue
            }
            val baseName = "$artifactId-$versionStr"
            val requiredFiles = listOf(
                "$baseName${spec.primaryExtension}",
                "$baseName-sources.jar",
                "$baseName-javadoc.jar",
                "$baseName.pom",
                "$baseName.module",
            )
            for (fileName in requiredFiles) {
                val f = moduleDir.resolve(fileName)
                if (!f.isFile || f.length() <= 0L) {
                    errors += "$artifactId: missing or empty $fileName in $moduleDir"
                }
            }

            // Check LICENSE and NOTICE inside sources jar (and .jar primary artifacts)
            val jarsToCheckLicense = buildList {
                add(moduleDir.resolve("$baseName-sources.jar"))
                add(moduleDir.resolve("$baseName-javadoc.jar"))
                if (spec.primaryExtension == ".jar") {
                    add(moduleDir.resolve("$baseName.jar"))
                }
            }
            for (jarFile in jarsToCheckLicense) {
                if (!jarFile.isFile) continue
                JarFile(jarFile).use { jf ->
                    if (jf.getJarEntry("META-INF/LICENSE") == null) {
                        errors += "$artifactId: ${jarFile.name} is missing META-INF/LICENSE"
                    }
                    if (jf.getJarEntry("META-INF/NOTICE") == null) {
                        errors += "$artifactId: ${jarFile.name} is missing META-INF/NOTICE"
                    }
                }
            }

            // Verify JVM 25 classfile major version (69) in desktop .jar and Android .aar (classes.jar),
            // and verify android:minSdkVersion="24" in Android .aar manifests.
            val primaryFile = moduleDir.resolve("$baseName${spec.primaryExtension}")
            if (primaryFile.isFile && spec.primaryExtension == ".jar" && artifactId.endsWith("-desktop")) {
                JarFile(primaryFile).use { jf ->
                    val majors = mutableSetOf<Int>()
                    val entries = jf.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (!entry.isDirectory && entry.name.endsWith(".class")) {
                            DataInputStream(jf.getInputStream(entry)).use { dis ->
                                if (dis.readInt() == 0xCAFEBABE.toInt()) {
                                    dis.readUnsignedShort() // minor
                                    majors += dis.readUnsignedShort()
                                }
                            }
                        }
                    }
                    if (majors != setOf(69)) {
                        errors += "$artifactId: expected Java 25 classfile major version [69] in ${primaryFile.name}, got $majors"
                    }
                }
            } else if (primaryFile.isFile && spec.primaryExtension == ".aar") {
                JarFile(primaryFile).use { aar ->
                    val manifestEntry = aar.getJarEntry("AndroidManifest.xml")
                    if (manifestEntry == null) {
                        errors += "$artifactId: ${primaryFile.name} is missing AndroidManifest.xml"
                    } else {
                        val manifestXml = aar.getInputStream(manifestEntry).bufferedReader().readText()
                        if (!manifestXml.contains("""android:minSdkVersion="24"""")) {
                            errors += "$artifactId: ${primaryFile.name} AndroidManifest.xml must declare android:minSdkVersion=\"24\", got:\n$manifestXml"
                        }
                    }
                    val classesEntry = aar.getJarEntry("classes.jar")
                    if (classesEntry == null) {
                        errors += "$artifactId: ${primaryFile.name} is missing classes.jar"
                    } else {
                        val majors = mutableSetOf<Int>()
                        ZipInputStream(aar.getInputStream(classesEntry)).use { zis ->
                            while (true) {
                                val entry = zis.nextEntry ?: break
                                if (!entry.isDirectory && entry.name.endsWith(".class")) {
                                    val dis = DataInputStream(zis)
                                    if (dis.readInt() == 0xCAFEBABE.toInt()) {
                                        dis.readUnsignedShort() // minor
                                        majors += dis.readUnsignedShort()
                                    }
                                }
                            }
                        }
                        if (majors != setOf(69)) {
                            errors += "$artifactId: expected Java 25 classfile major version [69] in ${primaryFile.name}!classes.jar, got $majors"
                        }
                    }
                }
            }

            val pomFile = moduleDir.resolve("$baseName.pom")
            if (pomFile.isFile) {
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pomFile)
                val root = doc.documentElement
                for (scalar in listOf("name", "description", "url")) {
                    if (firstDirectChildText(root, scalar).isNullOrBlank()) {
                        errors += "$artifactId: POM missing <$scalar>"
                    }
                }
                val licenses = firstDirectChild(root, "licenses")
                val license = licenses?.let { firstDirectChild(it, "license") }
                if (
                    license == null ||
                    firstDirectChildText(license, "name").isNullOrBlank() ||
                    firstDirectChildText(license, "url").isNullOrBlank()
                ) {
                    errors += "$artifactId: POM missing <licenses>/<license> name or url"
                }
                val scm = firstDirectChild(root, "scm")
                if (
                    scm == null ||
                    firstDirectChildText(scm, "url").isNullOrBlank() ||
                    firstDirectChildText(scm, "connection").isNullOrBlank()
                ) {
                    errors += "$artifactId: POM missing <scm> url or connection"
                }
                val developers = firstDirectChild(root, "developers")
                val developer = developers?.let { firstDirectChild(it, "developer") }
                if (
                    developer == null ||
                    firstDirectChildText(developer, "id").isNullOrBlank() ||
                    firstDirectChildText(developer, "name").isNullOrBlank()
                ) {
                    errors += "$artifactId: POM missing <developers>/<developer> id or name"
                }
                if (spec.forbidJewelDependency && pomFile.readText().contains("org.jetbrains.jewel")) {
                    errors += "$artifactId: POM must not depend on org.jetbrains.jewel"
                }
            }
        }

        if (errors.isNotEmpty()) {
            throw GradleException(
                "verifyMavenLocalPublication failed:\n" + errors.joinToString("\n") { "  - $it" },
            )
        }
        println(
            "verifyMavenLocalPublication: all ${components.size} KMP components verified Central-ready " +
                "(group=$publishGroupId, version=$versionStr).",
        )
    }
}
