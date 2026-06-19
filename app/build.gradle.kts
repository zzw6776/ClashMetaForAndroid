import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    kotlin("android")
    kotlin("kapt")
    id("com.android.application")
}

dependencies {
    compileOnly(project(":hideapi"))

    implementation(project(":core"))
    implementation(project(":service"))
    implementation(project(":design"))
    implementation(project(":common"))

    implementation(libs.kotlin.coroutine)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.coordinator)
    implementation(libs.androidx.recyclerview)
    implementation(libs.google.material)
    implementation(libs.quickie.bundled)
    implementation(libs.androidx.activity.ktx)
}

tasks.getByName("clean", type = Delete::class) {
    delete(file("release"))
}

val geoFilesDownloadDir = "src/main/assets"
val geoFilesCacheTtlMillis = 24L * 60L * 60L * 1000L

task("downloadGeoFiles") {

    val geoFilesUrls = mapOf(
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb" to "geoip.metadb",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat" to "geosite.dat",
        // "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/country.mmdb" to "country.mmdb",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb" to "ASN.mmdb",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/BundleMRS.7z" to "BundleMRS.7z",
    )

    doLast {
        val now = System.currentTimeMillis()
        val outputFiles = geoFilesUrls.values.map { outputFileName ->
            file("$geoFilesDownloadDir/$outputFileName")
        }
        val cacheValid = outputFiles.all { outputFile ->
            outputFile.exists() && now - outputFile.lastModified() < geoFilesCacheTtlMillis
        }

        if (cacheValid) {
            println("Geo files cache is fresh; skip download.")
            return@doLast
        }

        geoFilesUrls.forEach { (downloadUrl, outputFileName) ->
            val url = URL(downloadUrl)
            val outputPath = file("$geoFilesDownloadDir/$outputFileName")
            val tempPath = outputPath.toPath().resolveSibling("$outputFileName.tmp")
            outputPath.parentFile.mkdirs()

            try {
                Files.deleteIfExists(tempPath)
                url.openStream().use { input ->
                    Files.copy(input, tempPath, StandardCopyOption.REPLACE_EXISTING)
                }

                if (!Files.exists(tempPath) || Files.size(tempPath) <= 0L) {
                    throw GradleException("Downloaded geo file is empty: $outputFileName")
                }

                try {
                    Files.move(
                        tempPath,
                        outputPath.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(tempPath, outputPath.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }

                println("$outputFileName downloaded to $outputPath")
            } catch (e: Exception) {
                Files.deleteIfExists(tempPath)
                throw e
            }
        }
    }
}

afterEvaluate {
    val downloadGeoFilesTask = tasks["downloadGeoFiles"]

    tasks.forEach {
        if (it.name.startsWith("assemble")) {
            it.dependsOn(downloadGeoFilesTask)
        }
    }
}

tasks.getByName("clean", type = Delete::class) {
    delete(file(geoFilesDownloadDir))
}
