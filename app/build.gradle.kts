plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.parchipos.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.parchipos.app"
        minSdk = 24          // Android 7 — covers any phone still behind a counter
        targetSdk = 34
        versionCode = 18
        versionName = "0.10.0"
    }

    /*
     * Release signing is driven by environment variables so the keystore never
     * enters the repo. CI supplies them from secrets; locally they are simply
     * absent and the release build stays unsigned, which is a clearer failure
     * than silently shipping a debug-signed artifact as a release.
     */
    // Empty, not absent: the workflow always sets KEYSTORE_FILE, and it is ""
    // when there is no keystore secret. file("") throws, so treat blank as unset.
    val storeFilePath = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Shrinks and obfuscates. The app has no reflection or dynamic
            // class loading, so the only names that must survive are the
            // manifest-declared components, kept in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (storeFilePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // BuildConfig.VERSION_NAME: what the phone tells Parchi Cloud it runs.
    buildFeatures { buildConfig = true }
    // Robolectric tests open real screens, so they need the app's layouts and strings.
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    // Deliberately none. The printer socket is the JDK's own Socket and
    // storage is Android's own SQLite with org.json records, so there is no
    // dependency tree to age badly on a till that never updates.
    // Tests only; nothing here goes into the APK.
    testImplementation("junit:junit:4.13.2")
    // Real Android SQLite and org.json on the JVM, for the store and sync tests.
    testImplementation("org.robolectric:robolectric:4.14.1")
}

/*
 * Robolectric's Android runtime, fetched by Gradle (with its retries and its
 * cache) instead of by Robolectric in the middle of a test run, so a flaky
 * download can never fail a release build. Tests then run Robolectric offline.
 * Robolectric 4.14 needs both runtimes below; update them with Robolectric.
 */
// One configuration each: they are two versions of one module, and a shared
// configuration would keep only the newer.
val robolectricSdk34: Configuration by configurations.creating
val robolectricSdk35: Configuration by configurations.creating
dependencies {
    robolectricSdk34("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7") // the tests' @Config
    robolectricSdk35("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7") // the newest it knows, which loads the app package
}
val robolectricDir = layout.buildDirectory.dir("robolectric-runtime")
val copyRobolectricRuntime by tasks.registering(Copy::class) {
    from(robolectricSdk34, robolectricSdk35)
    into(robolectricDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricRuntime)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDir.get().asFile.absolutePath)
    // A failing test prints its whole exception, so a CI log is enough to diagnose it.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
