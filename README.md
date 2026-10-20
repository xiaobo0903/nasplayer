# NAS播放器 构建与安装

## 方式一：Android Studio（最简单）
1. 用 Android Studio（Koala 及以上）打开本目录，等待 Gradle 同步完成
2. 菜单 Build → Build APK(s)，完成后点 locate
3. APK 位置：app/build/outputs/apk/debug/app-debug.apk

## 方式二：命令行（需 JDK 17 + Android SDK）
    ./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug

## 方式三：GitHub 自动构建（无需本地环境）
把本目录推送到一个 GitHub 仓库 → Actions → Build APK → 运行完成后在 Artifacts 下载 NasPlayer-apk

## 安装到长虹电视
- ADB：电视开启开发者选项和 USB/网络调试 →
      adb connect 电视IP:5555
      adb install -r app-debug.apk
- U 盘：把 apk 拷到 U 盘，电视文件管理器里点击安装（需允许"未知来源"）
- 局域网：电视装「当贝市场/文件管理」类应用，从手机或电脑推送 APK
