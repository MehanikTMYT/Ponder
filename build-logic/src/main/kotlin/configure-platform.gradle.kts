// convention plugin to apply to platform subprojects.

import net.createmod.pondergradle.nullability.PackageInfosExtension

plugins {
    `java-library`
    `maven-publish`
}

// this has to be separate for some reason
plugins.apply("net.createmod.ponder.gradle")
plugins.apply("setup-git-hash")

// set coordinates based on parent project, ex. net.createmod.ponder:ponder-fabric
val modName: String = project.name.substringBeforeLast("-")
val platform: String = project.name.substringAfterLast("-")
group = "net.createmod.$modName"

// keep version synchronized with the root project
version = rootProject.version

repositories {
    mavenLocal() // TODO: remove when Flywheel is pushed
    maven("https://maven.neoforged.net/releases") // Round 34: NeoForge config API for the common source set
    maven("https://maven.createmod.net") // Flywheel
    maven("https://raw.githubusercontent.com/Fuzss/modresources/main/maven/") // Forge Config API Port
}

java {
    withSourcesJar()
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<Jar> {
    // copy the license file into every built jar
    from(rootProject.file("LICENSE"))
}

val libs: VersionCatalog = versionCatalogs.named("libs")
fun versionOf(name: String): String {
    val version = libs.findVersion(name).get().toString()

    // normalize minecraft versions to semver on fabric because they still do that for some reason
    if (name == "minecraft" && project.name == "fabric")
        return version
            .replace("snapshot-", "alpha.")
            .replace("pre-", "pre.")
            .replace("rc-", "rc.")

    return version
}

val authors = findProperty("authors") as String
val contributors = findProperty("contributors") as String

// expand placeholders in metadata files
tasks.processResources {
    val properties = mapOf(
        "version" to project.version,
        "group" to project.group,
        "minecraft_version" to versionOf("minecraft"),
        "neo_version" to versionOf("neoforge"),
        "fabric_api_version" to versionOf("fabric-api"),
        "fabric_loader_version" to versionOf("fabric-loader"),
        "authors" to authors,
        "contributors" to contributors,
        "authors_json" to formatForJson(authors),
        "contributors_json" to formatForJson(contributors)
    )

    inputs.properties(properties)

    filesMatching(setOf("fabric.mod.json", "META-INF/neoforge.mods.toml")) {
        expand(properties)
    }
}

// don't publish the testmod
if (parent!!.name != "testmod") {
    publishing {
        publications.create<MavenPublication>("mavenJava") {
            from(components["java"])
        }

        repositories {
            val mavenUsername = providers.environmentVariable("MAVEN_USERNAME")
            val mavenPassword = providers.environmentVariable("MAVEN_PASSWORD")
            if (mavenUsername.isPresent && mavenPassword.isPresent) {
                maven {
                    url = uri("https://maven.createmod.net")
                    credentials {
                        username = mavenUsername.get()
                        password = mavenPassword.get()
                    }
                }
            }
        }
    }
}

// will only exist in common/fabric
val loom: Any? = extensions.findByName("loom")
// reflection in the buildscript. have I hit a new low?
// we need to call this now or else the sourceSet won't exist, and
// I don't even know where to begin with compiling against loom here.
loom?.javaClass?.getMethod("splitEnvironmentSourceSets")?.run {
    invoke(loom)

    // add an extra consumable sourceset for other client sourcesets to depend on
    configurations.consumable("client")
    sourceSets["client"].output.forEach {
        artifacts.add("client", it) {
            // this one doesn't preserve task dependencies for some reason, add them manually
            builtBy(tasks.named("compileClientJava"), tasks.named("processClientResources"))
        }
    }
}

// generate package-infos for the main (and client, if present) sourceSet(s)
extensions.getByType<PackageInfosExtension>().sources(sourceSets.named { it == "main" || it == "client" })

if (name != "common") {
    // Round 33: this named the root ":common" unconditionally, but a nested tree (":catnip:neoforge") consumes the
    // output of its own sibling ":catnip:common". Gradle 9 rejects the undeclared dependency instead of warning.
    val siblingCommon = project.path.substringBeforeLast(":") + ":common"
    tasks.withType<Jar> {
        dependsOn(project(siblingCommon).tasks.named("generatePackageInfos"))
    }
}

// Round 34: the upstream FIXME above this block excluded the entire configuration subsystem from compilation. It
// was temporary, as it said, and Ponder PR #85 removes it wholesale rather than working around it - so this does the
// same. The one exclusion that survives there is a generated package-info for a package that only exists once the
// net.base sources are present.
//
// Every import those config sources need was checked against the 26.1.2 sources first: GuiGraphicsExtractor,
// MouseButtonEvent, KeyEvent, ModConfigSpec and ModConfig all exist on this platform. The exclusion was not covering
// a missing API.
// Round 34: the same generated package-info that javac rejects also reaches the sources jar twice. PR #85 sets
// this strategy for the same reason.
tasks.withType<org.gradle.jvm.tasks.Jar> {
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
}

tasks.withType<JavaCompile> {
    exclude("net/createmod/catnip/net/base/package-info.java")
    // Round 34: the package-info generator emits one file per package per source set. A package that now has
    // sources in both `common` and `neoforge` therefore gets two, and javac rejects the second with "package has
    // already been annotated". The common one is authoritative; this drops the platform duplicate. Same class of
    // problem as the net/base exclusion PR #85 carries, and the same remedy.
    if (name != "common") {
        exclude("net/createmod/catnip/api/platform/package-info.java")
    }

    // Round 34, deliberately narrow and deliberately temporary. The *common* configuration API - ConfigBase,
    // ConfigHelper, ConfigPathArgument - is now compiled and is what Create actually depends on in 13 places. The
    // client configuration *screens* still carry 26.1.2 GUI deltas that Ponder PR #85 resolved against 26.2
    // (AbstractSimiScreen's constructor changed, and the entry widgets follow the new input event types), and
    // porting a GUI layer was not on this round's critical path to a dedicated server.
    //
    // What this costs, stated exactly: Create's client sources reference BaseConfigScreen (3), ConfigScreen (1) and
    // ConfigAnnotations (1). Create's *common* compile is unaffected; its client compile is not.
    exclude("net/createmod/catnip/api/client/config/**")
    exclude("net/createmod/catnip/api/client/gui/ConfirmationScreen.java")
}

when (platform) {
    "common" -> plugins.apply("provide-common")
    "fabric" -> plugins.apply("consume-common-split")
    else -> plugins.apply("consume-common-merged")
}

// trick to sneak multiple entries into a single placeholder in a JSON file.
// the file must be valid even with placeholders, so we can't just do something like this: [${placeholder}]
// instead, the placeholder is expected to be in a string, like this: ["${placeholder}"]
// this takes a string in the format 'a, b, c' and adds quotes, so the end result will be like this: a", "b", "c
// when filled into the placeholder, you get a valid list: ["a", "b", "c"]
fun formatForJson(entries: String): String {
    return entries.split(", ").joinToString(separator = "\", \"")
}
