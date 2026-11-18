package com.example.appbuilder

enum class AppBuildLanguage(val label: String, val extension: String) {
    VISUAL_DESIGNER("Visual Designer", ".json"),
    STUDIOLANG("StudioLang (Direct Binary)", ".stl"),
    KOTLIN_COMPOSE("Kotlin / Compose", ".kt"),
    JAVA_ANDROID("Java (Android SDK)", ".java"),
    HTML5_JS("HTML5 + JS", ".html"),
    CPP_NDK("C / C++ (Android NDK)", ".cpp"),
    RUST_JNI("Rust (Safe JNI)", ".rs"),
    PYTHON_BRIDGE("Python (Android/Termux)", ".py"),
    CUSTOM_OS_KERNEL("Custom OS Kernel (x86_64/ARM64/RV64)", ".stl"),
    JSON_MANIFEST("JSON App Spec", ".json")
}

enum class OsTargetArch(val id: String, val label: String, val elfMachine: Int, val summary: String) {
    X86_64("x86_64", "PC / Laptop (x86_64 Multiboot2)", 62, "64-bit PC/Laptop bare-metal ELF64 + Multiboot2 kernel"),
    AARCH64("aarch64", "Mobile / ARM64 (AArch64 ELF64)", 183, "ARM64 smartphone, SBC, and Apple/Android SoC bare-metal ELF64"),
    RISCV64("riscv64", "Universal Embedded (RISC-V 64)", 243, "Open-ISA RV64IMAC bare-metal ELF64 kernel")
}

enum class UiElementKind(val label: String) {
    TEXT("Text"),
    BUTTON("Button"),
    INPUT("Input"),
    SWITCH("Switch"),
    CHECKBOX("Checkbox"),
    SLIDER("Slider"),
    CARD("Card"),
    LIST("List")
}

data class UiElementDesign(
    val id: String,
    val kind: UiElementKind,
    val label: String,
    val stateKey: String = "",
    val onTapCode: String = ""
)

data class AppScreenDesign(
    val id: String,
    val name: String,
    val elements: List<UiElementDesign> = emptyList()
)

data class AppStateVarDesign(
    val key: String,
    val type: String = "text",
    val initialValue: String = ""
)

data class AppComponentDesign(
    val kind: String, // Service, BroadcastReceiver, NotificationChannel
    val name: String,
    val detail: String
)

data class AppDesign(
    val appName: String = "My Studio App",
    val packageId: String = "com.example.studioapp",
    val versionName: String = "1.0.0",
    val versionCode: Int = 1,
    val minSdk: Int = 24,
    val targetSdk: Int = 36,
    val primaryColorHex: String = "#22D3EE",
    val buildLanguage: AppBuildLanguage = AppBuildLanguage.VISUAL_DESIGNER,
    val osTargetArch: OsTargetArch = OsTargetArch.X86_64,
    val customKotlinCode: String = DEFAULT_KOTLIN_APP,
    val customJavaCode: String = DEFAULT_JAVA_APP,
    val customHtml5Code: String = DEFAULT_HTML5_APP,
    val customJsCode: String = DEFAULT_JS_APP,
    val customCppCode: String = DEFAULT_CPP_NDK_APP,
    val customRustCode: String = DEFAULT_RUST_APP,
    val customPythonCode: String = DEFAULT_PYTHON_APP,
    val customOsKernelCode: String = DEFAULT_OS_KERNEL_STL,
    val permissions: List<String> = listOf("android.permission.INTERNET"),
    val stateVars: List<AppStateVarDesign> = listOf(
        AppStateVarDesign("status", "text", "Ready"),
        AppStateVarDesign("autoSync", "bool", "true")
    ),
    val screens: List<AppScreenDesign> = listOf(
        AppScreenDesign(
            id = "screen_home",
            name = "Home",
            elements = listOf(
                UiElementDesign("el_1", UiElementKind.TEXT, "Welcome to My Studio App"),
                UiElementDesign("el_2", UiElementKind.INPUT, "Enter task title", stateKey = "status"),
                UiElementDesign("el_3", UiElementKind.SWITCH, "Enable Auto Sync", stateKey = "autoSync"),
                UiElementDesign(
                    id = "el_4",
                    kind = UiElementKind.BUTTON,
                    label = "Run Workflow",
                    onTapCode = "log.info(\"Button tapped! Status = \" + status)"
                )
            )
        ),
        AppScreenDesign(
            id = "screen_details",
            name = "Settings",
            elements = listOf(
                UiElementDesign("el_5", UiElementKind.CARD, "App Configuration Active"),
                UiElementDesign("el_6", UiElementKind.BUTTON, "Back to Home", onTapCode = "ui.navigate(\"Home\")")
            )
        )
    ),
    val components: List<AppComponentDesign> = emptyList()
) {
    companion object {
        val DEFAULT_KOTLIN_APP = """
            |package com.example.studioapp
            |
            |import android.os.Bundle
            |import androidx.activity.ComponentActivity
            |import androidx.activity.compose.setContent
            |import androidx.activity.enableEdgeToEdge
            |import androidx.compose.foundation.layout.*
            |import androidx.compose.material3.*
            |import androidx.compose.runtime.*
            |import androidx.compose.ui.Modifier
            |import androidx.compose.ui.unit.dp
            |
            |class MainActivity : ComponentActivity() {
            |    override fun onCreate(savedInstanceState: Bundle?) {
            |        super.onCreate(savedInstanceState)
            |        enableEdgeToEdge()
            |        setContent { CustomKotlinApp() }
            |    }
            |}
            |
            |@Composable
            |fun CustomKotlinApp() {
            |    var count by remember { mutableIntStateOf(0) }
            |    MaterialTheme {
            |        Scaffold { padding ->
            |            Column(
            |                modifier = Modifier.padding(padding).padding(20.dp),
            |                verticalArrangement = Arrangement.spacedBy(12.dp)
            |            ) {
            |                Text("Custom Kotlin / Compose App", style = MaterialTheme.typography.headlineSmall)
            |                Text("Taps recorded: ${'$'}count")
            |                Button(onClick = { count++ }) { Text("Increment Counter") }
            |            }
            |        }
            |    }
            |}
        """.trimMargin()

        val DEFAULT_JAVA_APP = """
            |package com.example.studioapp;
            |
            |import android.app.Activity;
            |import android.os.Bundle;
            |import android.widget.Button;
            |import android.widget.LinearLayout;
            |import android.widget.TextView;
            |
            |public class MainActivity extends Activity {
            |    private int count = 0;
            |
            |    @Override
            |    protected void onCreate(Bundle savedInstanceState) {
            |        super.onCreate(savedInstanceState);
            |        LinearLayout root = new LinearLayout(this);
            |        root.setOrientation(LinearLayout.VERTICAL);
            |        root.setPadding(48, 96, 48, 48);
            |        TextView label = new TextView(this);
            |        label.setText("Java Android App: 0");
            |        Button btn = new Button(this);
            |        btn.setText("Increment");
            |        btn.setOnClickListener(v -> {
            |            count++;
            |            label.setText("Java Android App: " + count);
            |        });
            |        root.addView(label);
            |        root.addView(btn);
            |        setContentView(root);
            |    }
            |}
        """.trimMargin()

        val DEFAULT_HTML5_APP = """
            |<!DOCTYPE html>
            |<html>
            |<head>
            |  <meta charset="utf-8"/>
            |  <meta name="viewport" content="width=device-width,initial-scale=1"/>
            |  <title>My Studio HTML5 App</title>
            |  <style>
            |    body{margin:0;padding:20px;font-family:system-ui,sans-serif;background:#0B1220;color:#F8FAFC}
            |    .card{background:#131C31;border:1px solid #26334D;border-radius:14px;padding:16px;margin-top:12px}
            |    button{background:#22D3EE;color:#0B1220;border:none;border-radius:10px;padding:12px 18px;font-weight:700;cursor:pointer}
            |  </style>
            |</head>
            |<body>
            |  <h2>HTML5 + JS Android App</h2>
            |  <div class="card">
            |    <p id="status">Status: Ready</p>
            |    <button onclick="runCustomAction()">Run JS Action</button>
            |  </div>
            |  <script src="app.js"></script>
            |</body>
            |</html>
        """.trimMargin()

        val DEFAULT_JS_APP = """
            |let counter = 0;
            |function runCustomAction() {
            |  counter += 1;
            |  const el = document.getElementById('status');
            |  if (el) el.innerText = 'Executed HTML5/JS action #' + counter;
            |}
        """.trimMargin()

        val DEFAULT_CPP_NDK_APP = """
            |#include <jni.h>
            |#include <string>
            |#include <cstdint>
            |
            |extern "C" JNIEXPORT jstring JNICALL
            |Java_com_example_studioapp_MainActivity_studioStatus(JNIEnv* env, jobject) {
            |    std::string msg = "C++ NDK Native Engine Active";
            |    return env->NewStringUTF(msg.c_str());
            |}
        """.trimMargin()

        val DEFAULT_RUST_APP = """
            |use std::ffi::CString;
            |use std::os::raw::c_char;
            |
            |#[no_mangle]
            |pub extern "C" fn rust_safe_compute(x: i64, y: i64) -> i64 {
            |    x.saturating_mul(2).saturating_add(y)
            |}
        """.trimMargin()

        val DEFAULT_PYTHON_APP = """
            |def main():
            |    print("Studio Python Module Ready")
            |    return {"status": "ok", "engine": "python"}
            |
            |if __name__ == "__main__":
            |    main()
        """.trimMargin()

        val DEFAULT_OS_KERNEL_STL = """
            |#include <studio.h>
            |
            |# Swatantra StudioLang Custom OS Kernel (x86_64 / ARM64 / RISC-V)
            |# Compiles directly to ELF64 + Multiboot2 Bare-Metal Binary & Hex
            |def kernel_main():
            |    let arena = mem.arena_create(4096)
            |    let info = hw.cpu_info()
            |    mem.write_u32(arena, 0, 1398033457)
            |    let magic = mem.read_u32(arena, 0)
            |    os.kernel_log("SwatantraOS Booted: magic=" + magic)
            |    os.scheduler_tick(1)
            |    mem.arena_free(arena)
            |    return magic
            |
            |kernel_main()
        """.trimMargin()
    }
}
