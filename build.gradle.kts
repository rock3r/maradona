import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import java.util.jar.JarFile
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
            // Sonatype Central Portal endpoint with manual promotion (`automaticRelease = false`)
            // so staged deployments can be verified via `scripts/central_portal_check.py validate`
            // before publishing.
            publishToMavenCentral(automaticRelease = false)

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

tasks.register("verifyMavenLocalPublication") {
    description =
        "Publishes Maradona library modules to mavenLocal and verifies that every " +
            "KMP component's artifacts, POM metadata, Gradle Module Metadata, and " +
            "bundled LICENSE/NOTICE files satisfy Sonatype Central requirements."
    group = "verification"

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
