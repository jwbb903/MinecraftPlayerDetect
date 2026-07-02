# Minecraft Player Detect - Android App

## 项目结构

```
MinecraftPlayerDetect/
├── app/
│   ├── build.gradle.kts        # App 构建配置
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── assets/
│   │   │   │   └── best.tflite     # YOLO 检测模型
│   │   │   ├── java/com/minecraftdetect/
│   │   │   │   ├── MainActivity.kt         # 主界面 + 检测叠加层
│   │   │   │   ├── ScreenCaptureService.kt # MediaProjection 截屏服务
│   │   │   │   ├── PlayerDetector.kt       # TFLite 推理封装
│   │   │   │   └── NMS.kt                  # 非极大值抑制
│   │   │   └── res/
│   │   └── ...
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/
```

## 工作原理

1. **打开 App** → 点击「开始检测」
2. **系统弹窗**要求授权屏幕录制 → 同意
3. **ScreenCaptureService** 启动前台服务，每 200ms 截屏一次
4. **PlayerDetector** 将截屏缩放到 640×640 → NCHW 排列 → TFLite 推理
5. **NMS** 过滤重叠框 → 保留置信度 > 0.35 的检测结果
6. **OverlayView** 在原图上绘制绿色检测框 + 置信度标签

## 模型适配

| 参数 | 值 |
|---|---|
| 模型文件 | `app/src/main/assets/best.tflite` |
| 输入 | `[1, 3, 640, 640]` float32 (NCHW) |
| 输出 | `[1, 5, 8400]` float32 → `[cx, cy, w, h, confidence]` |
| 类别 | Player（单类别）|
| NMS | App 内实现（NMS.kt）|

## 编译方式

### 方式一：Android Studio（推荐）

1. 用 Android Studio 打开 `MinecraftPlayerDetect` 目录
2. 等待 Gradle 同步完成
3. 点击 Run ▶

### 方式二：命令行

```bash
# 需要安装 Android SDK
./gradlew assembleDebug
# APK 输出路径: app/build/outputs/apk/debug/app-debug.apk
```

## 调参

如需调整检测灵敏度，修改 NMS.kt 中的默认值：

```kotlin
confThreshold = 0.35f   // 置信度阈值（调低可检测更多但误报增加）
iouThreshold = 0.45f    // IOU 阈值（调低可减少重叠框）
```

如需调整截屏频率，修改 ScreenCaptureService.kt：

```kotlin
CAPTURE_INTERVAL_MS = 200L  // 毫秒，200ms = 5 FPS
```
