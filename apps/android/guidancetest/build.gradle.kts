plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * 受控测试 App：只用于验证只读引导/匹配/悬浮层，不参与正式产品分发。
 * 页面包含稳定 viewId、可移动按钮、滚动列表、WebView、弹窗、密码框、深色与旋转场景。
 */
android {
    namespace = "com.actionloop.guidancetest"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.actionloop.guidancetest"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 故意不引入第三方依赖：测试 App 必须能在离线环境构建，且不携带任何网络/统计逻辑。
    testImplementation(libs.junit)
}
