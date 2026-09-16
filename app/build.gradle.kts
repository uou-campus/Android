plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "site.uoucampus.app"
  compileSdk = 35

  defaultConfig {
    applicationId = "site.uoucampus.app"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"
  }

  buildFeatures { compose = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }

  /* 캠퍼스 그래프는 웹과 같은 파일을 쓴다. 편집 모드에서 고친 지도가 앱에도 그대로 실린다. 옆의 .ts 는 뺀다. */
  sourceSets["main"].assets.srcDir("../../Client/src/data")
  androidResources {
    ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:!*.ts"
  }
}

dependencies {
  val compose = platform("androidx.compose:compose-bom:2024.12.01")
  implementation(compose)
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.activity:activity-compose:1.9.3")
  /* 웹과 같은 OpenStreetMap 타일. 캠퍼스 안 보행로와 계단이 그려진 지도는 그쪽뿐이다. */
  implementation("org.osmdroid:osmdroid-android:6.1.20")
  /* 시간표 그림의 강의실 글자. 모델이 앱에 실려 있어 네트워크 없이 돈다. */
  implementation("com.google.mlkit:text-recognition-korean:16.0.1")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20240303")
}
