package com.app.forge.template

/** File awal untuk tiap template proyek baru. */
internal object Starters {
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Proyek Android (Views Java/Kotlin atau Jetpack Compose) dengan package, SDK, dan label dari [spec]. */
    fun androidApp(spec: NewSpec, appName: String): Map<String, String> {
        val pkg = spec.pkg
        val kotlin = spec.kotlin || spec.compose
        val compile = maxOf(34, spec.targetSdk)
        val label = xml(appName.trim().ifEmpty { "Hello" })
        val gradleName = appName.replace(Regex("[^A-Za-z0-9_-]"), "-").ifEmpty { "Hello" }
        val dir = "app/src/main/java/" + pkg.replace('.', '/')
        val out = LinkedHashMap<String, String>()
        out["settings.gradle.kts"] = """
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "$gradleName"
include(":app")
""".trimIndent()
        out["build.gradle.kts"] = if (kotlin) """
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
""".trimIndent() else """
plugins {
    id("com.android.application") version "8.5.2" apply false
}
""".trimIndent()
        out["gradle.properties"] = "org.gradle.jvmargs=-Xmx2g\nandroid.useAndroidX=true\n" +
            (if (compile > 34) "android.suppressUnsupportedCompileSdk=$compile\n" else "")
        val plugins = if (kotlin) "    id(\"com.android.application\")\n    id(\"org.jetbrains.kotlin.android\")" else "    id(\"com.android.application\")"
        val composeBlock = if (spec.compose) """
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }""" else ""
        val kotlinOpts = if (kotlin) "\n    kotlinOptions { jvmTarget = \"17\" }" else ""
        val deps = if (spec.compose) """

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}""" else ""
        out["app/build.gradle.kts"] = """
plugins {
$plugins
}
android {
    namespace = "$pkg"
    compileSdk = $compile
    defaultConfig {
        applicationId = "$pkg"
        minSdk = ${spec.minSdk}
        targetSdk = ${spec.targetSdk}
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }$kotlinOpts$composeBlock
}$deps
""".trimIndent()
        val theme = if (spec.compose) " android:theme=\"@android:style/Theme.DeviceDefault.NoActionBar\"" else ""
        out["app/src/main/AndroidManifest.xml"] = """
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="$label"$theme>
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
""".trimIndent()
        when {
            spec.compose -> out["$dir/MainActivity.kt"] = """
package $pkg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Hello dari AppForge!", style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
    }
}
""".trimIndent()
            kotlin -> out["$dir/MainActivity.kt"] = """
package $pkg

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Hello dari AppForge!"
            textSize = 24f
            setPadding(48, 96, 48, 48)
        })
    }
}
""".trimIndent()
            else -> out["$dir/MainActivity.java"] = """
package $pkg;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView view = new TextView(this);
        view.setText("Hello dari AppForge!");
        view.setTextSize(24f);
        view.setPadding(48, 96, 48, 48);
        setContentView(view);
    }
}
""".trimIndent()
        }
        return out
    }

    val flutter = mapOf(
        "pubspec.yaml" to """
name: hello_flutter
description: Hello dari AppForge
publish_to: none
version: 1.0.0+1

environment:
  sdk: ">=3.0.0 <4.0.0"

dependencies:
  flutter:
    sdk: flutter

flutter:
  uses-material-design: true
""".trimIndent() + "\n",
        "lib/main.dart" to """
import 'package:flutter/material.dart';

void main() => runApp(const MyApp());

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      home: Scaffold(
        body: Center(
          child: Text('Hello dari AppForge!', style: TextStyle(fontSize: 24)),
        ),
      ),
    );
  }
}
""".trimIndent() + "\n"
    )

    val node = mapOf(
        "package.json" to """
{
  "name": "hello-node",
  "version": "1.0.0",
  "private": true,
  "scripts": {
    "build": "node build.js",
    "test": "node test.js"
  }
}
""".trimIndent() + "\n",
        "src/index.js" to """
function greet(name) {
  return "Halo, " + name + "!";
}

module.exports = { greet };
""".trimIndent() + "\n",
        "build.js" to """
const fs = require("fs");

fs.rmSync("dist", { recursive: true, force: true });
fs.cpSync("src", "dist", { recursive: true });
console.log("Build selesai -> dist/");
""".trimIndent() + "\n",
        "test.js" to """
const assert = require("assert");
const { greet } = require("./src/index");

assert.strictEqual(greet("AppForge"), "Halo, AppForge!");
console.log("Tes lulus");
""".trimIndent() + "\n"
    )

    val python = mapOf(
        "main.py" to """
def greet(name: str) -> str:
    return "Halo, " + name + "!"


if __name__ == "__main__":
    print(greet("dari AppForge"))
""".trimIndent() + "\n",
        "test_main.py" to """
from main import greet


def test_greet():
    assert greet("AppForge") == "Halo, AppForge!"
""".trimIndent() + "\n",
        "requirements.txt" to "# tambahkan dependensi di sini, satu per baris\n"
    )

    val custom = mapOf(
        "README.md" to "# Proyek kustom\n\nEdit .github/workflows/build.yml (menu Workflow > Edit build.yml) untuk mengatur perintah build.\nHasil build taruh di folder out/ supaya bisa diunduh.\n"
    )
}
