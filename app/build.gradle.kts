import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * 固定 release 签名。
 *
 * 之前 release 借用的是 debug keystore，那台机器上的 debug.keystore 一旦被 Android Studio
 * 重新生成，新包就再也盖不上旧包（INSTALL_FAILED_UPDATE_INCOMPATIBLE），只能卸载重装，
 * 而卸载会连登录态一起带走。签名一旦定下来就不能换，所以这里读一个独立生成的 jks。
 *
 * 配置文件缺失时不报错，回落到 debug 签名让构建能跑通，但会在构建日志里留下明显警告：
 * 静默回落比构建失败更危险——打出来的包看着正常，装的时候才发现签名不匹配。
 */
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun prop(key: String): String? = keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.kunplayer.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kunplayer.tv"
        minSdk = 23          // 小米电视4A：Android 6.0.1 / API 23
        targetSdk = 28       // 老设备上不引入分区存储、前台服务类型等新限制
        versionCode = 47
        versionName = "0.30.0"
    }

    signingConfigs {
        create("release") {
            val path = prop("storeFile")
            if (path != null) {
                storeFile = rootProject.file(path)
                storePassword = prop("storePassword")
                keyAlias = prop("keyAlias")
                keyPassword = prop("keyPassword")
                // v1 必须留着：小米电视4A 是 Android 6.0，只认 v2 的包它装不上
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            /**
             * 刻意开着 debuggable。
             *
             * 这不是上架包，是自己 adb 装到电视上的自用包，代价只是别人能用 run-as 读它的数据。
             * 收益是诊断链路完整：`adb exec-out run-as com.kunplayer.tv cat files/diag.log`
             * 能直接拿到焦点埋点、崩溃记录、SharedPreferences（迁移签名时也是这样把登录态搬过来的）。
             * 关掉它，这些文件就躺在 /data/data 里谁也读不到——等于白记。
             *
             * 上架或对外分发前必须改成 false。
             */
            isDebuggable = true
            signingConfig = if (prop("storeFile") != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "⚠️ 没有 keystore.properties，release 包回落到 debug 签名 —— " +
                        "这样的包和正式版签名不同，无法覆盖安装。"
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // targetSdk 28 是刻意的：这台电视是 Android 6，29+ 会引入分区存储与前台服务类型限制。
        // 这条检查会直接 abort release 构建，属于「政策提醒」而非代码缺陷，关掉它。
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.recyclerview)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // 只为了画一张登录二维码，见 ui/QrCode.kt 里为什么不自写编码器
    implementation(libs.zxing.core)
}
