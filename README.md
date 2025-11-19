# SystemLogger - Android 系统监控工具

[![Android](https://img.shields.io/badge/Android-15%2B-green.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-35-brightgreen.svg)](https://android-doc.github.io/about/versions/android-15.0.html)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Android系统实时监控应用，支持温度监测、电池状态、系统数据记录和可视化图表展示。完全适配Android 15及以上系统版本。

## 📱 功能特性

### 实时监控
- ✅ **CPU温度监测** - 实时显示处理器温度
- ✅ **GPU温度监测** - 显卡温度实时追踪
- ✅ **电池温度** - 真实电池温度读取
- ✅ **外壳温度** - 设备外壳温度监控
- ✅ **电池电量** - 当前电量百分比
- ✅ **电流监测** - 充放电电流显示
- ✅ **屏幕亮度** - 当前亮度级别

### 数据可视化
- 📊 **实时温度曲线** - 4条独立曲线同时显示
- 🎨 **颜色区分** - CPU(红) / GPU(蓝) / 电池(绿) / 外壳(橙)
- 📈 **自动滚动** - 显示最近30秒数据
- 🔍 **触摸交互** - 支持缩放、拖动、捏合手势
- 💾 **数据点限制** - 最多保留50个数据点

### 数据记录
- 💾 **CSV导出** - 完整数据导出为CSV文件
- ⏱️ **可调采样率** - 默认1秒采样间隔
- 📁 **文件管理** - 自动保存到应用目录
- 📝 **时间戳** - 精确到秒的时间记录

### Android 15适配
- 🔐 **现代权限系统** - ActivityResultLauncher权限请求
- 🔔 **通知权限** - Android 13+ 通知权限适配
- 🎯 **前台服务** - dataSync类型服务配置
- 🌊 **Edge-to-Edge** - 沉浸式显示体验
- 🎨 **Material Design 3** - 现代化UI设计
- 📂 **FileProvider** - 安全文件共享

## 🚀 快速开始

### 系统要求
- Android 15+ (API Level 35+)
- 至少50MB存储空间
- 推荐4GB+ RAM

### 安装
1. 下载最新版本APK
2. 在设备上安装APK
3. 授予必要权限（通知、存储）
4. 启动应用开始监控

### 使用说明

#### 开始监控
1. 打开应用
2. 选择要监控的温度项（CPU/GPU/电池/外壳）
3. 点击"开始记录"按钮
4. 实时数据将显示在界面上
5. 温度曲线自动更新

#### 停止监控
- 点击"停止记录"按钮停止数据采集
- 前台服务会自动停止

#### 导出数据
- 点击"导出CSV"按钮
- 数据将保存到应用的Documents目录
- 文件名格式: `system_log_export_yyyyMMdd_HHmmss.csv`

## 📊 数据格式

### CSV文件结构
```csv
时间戳,CPU温度,GPU温度,电池温度,外壳温度,电池电量,电流,屏幕亮度
2025-11-19 16:01:03,41.45,44.28,33.0,31.0,95,-613,138
2025-11-19 16:01:04,36.68,51.53,33.0,31.0,95,-93,138
```

### 数据说明
- **时间戳**: yyyy-MM-dd HH:mm:ss格式
- **温度**: 摄氏度(°C)
- **电量**: 百分比(%)
- **电流**: 毫安(mA)，负值表示放电
- **亮度**: 0-255范围

## 🔧 技术架构

### 开发环境
- **开发工具**: Android Studio
- **编译SDK**: Android 15 (API 35)
- **目标SDK**: Android 15 (API 35)
- **最低SDK**: Android 15 (API 35)
- **构建工具**: Gradle 8.4
- **Java版本**: Java 17

### 核心依赖
```gradle
// AndroidX核心库
implementation 'androidx.appcompat:appcompat:1.7.0'
implementation 'androidx.core:core:1.13.1'
implementation 'androidx.activity:activity:1.9.2'
implementation 'androidx.fragment:fragment:1.6.2'

// Material Design
implementation 'com.google.android.material:material:1.12.0'

// 生命周期组件
implementation 'androidx.lifecycle:lifecycle-runtime:2.6.2'
implementation 'androidx.lifecycle:lifecycle-service:2.6.2'

// 图表库
implementation 'com.github.PhilJay:MPAndroidChart:v3.1.0'
```

### 温度读取策略

应用采用**5层温度读取fallback机制**：

1. **HardwarePropertiesManager API** (Android 10+)
   - 官方硬件温度API
   - 优先使用

2. **Thermal Zone文件系统**
   - 读取 `/sys/class/thermal/thermal_zone*/temp`
   - 需要文件系统权限

3. **电池温度 Intent广播** ✅
   - 通过 `ACTION_BATTERY_CHANGED` 获取
   - 最可靠的温度源
   - 当前主要使用方式

4. **温度推算**
   - 基于电池温度推算外壳温度
   - 外壳温度 ≈ 电池温度 - 2°C

5. **模拟数据**
   - 当所有方法失败时使用
   - 提供合理的温度范围
   - 用于演示和测试

## 📁 项目结构

```
SystemLogger/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/example/systemlogger/
│   │   │   │   ├── MainActivity.java          # 主界面
│   │   │   │   └── LoggingService.java        # 后台服务
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   │   └── activity_main.xml      # 界面布局
│   │   │   │   ├── values/
│   │   │   │   │   ├── strings.xml            # 文本资源
│   │   │   │   │   ├── colors.xml             # 颜色资源
│   │   │   │   │   └── themes.xml             # 主题样式
│   │   │   │   ├── mipmap-*/                  # 应用图标
│   │   │   │   └── xml/
│   │   │   │       └── file_paths.xml         # FileProvider配置
│   │   │   └── AndroidManifest.xml            # 应用清单
│   │   └── build.gradle                        # 应用构建配置
│   └── build.gradle                            # 项目构建配置
├── gradle/                                     # Gradle配置
├── settings.gradle                             # Gradle设置
└── README.md                                   # 项目说明
```

## 🛠️ 构建指南

### 克隆仓库
```bash
git clone https://github.com/chisl9403/systemlogger.git
cd systemlogger
```

### 使用Android Studio构建
1. 打开Android Studio
2. 选择 "Open an Existing Project"
3. 选择项目目录
4. 等待Gradle同步完成
5. 点击 "Run" 或 "Build APK"

### 使用命令行构建
```bash
# Windows
gradlew.bat assembleDebug

# Linux/Mac
./gradlew assembleDebug
```

生成的APK位于: `app/build/outputs/apk/debug/app-debug.apk`

## 🔐 权限说明

### 必需权限
- `POST_NOTIFICATIONS` (Android 13+) - 显示前台服务通知
- `FOREGROUND_SERVICE` - 运行后台服务
- `FOREGROUND_SERVICE_DATA_SYNC` - 数据同步服务类型

### 可选权限
- `READ_MEDIA_IMAGES` (Android 13+) - 读取媒体文件
- `WRITE_EXTERNAL_STORAGE` (Android 12-) - 写入外部存储
- `READ_EXTERNAL_STORAGE` (Android 12-) - 读取外部存储

## 📸 屏幕截图

### 主界面
- 实时数据显示区域
- 温度选择复选框
- 控制按钮（开始/停止/导出）
- 实时温度曲线图表

### 温度曲线
- 4条颜色区分的温度曲线
- 实时滚动显示
- 支持触摸交互

## 🐛 已知问题

1. **温度读取限制**
   - 部分设备无法通过HardwarePropertiesManager读取温度
   - 已实现多层fallback机制
   - 电池温度为最可靠数据源

2. **权限要求**
   - Android 13+需要额外的通知权限
   - 首次运行需手动授权

## 🔄 更新日志

### v1.0.0 (2025-11-19)
- ✅ 初始版本发布
- ✅ Android 15完全适配
- ✅ 实时温度监控功能
- ✅ 4条温度曲线可视化
- ✅ CSV数据导出功能
- ✅ 多层温度读取fallback
- ✅ Material Design 3界面
- ✅ Edge-to-Edge沉浸式体验

## 🤝 贡献指南

欢迎提交问题和拉取请求！

1. Fork本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启拉取请求

## 📄 许可证

本项目采用MIT许可证 - 详见 [LICENSE](LICENSE) 文件

## 👨‍💻 作者

- GitHub: [@chisl9403](https://github.com/chisl9403)

## 🙏 致谢

- [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart) - 优秀的Android图表库
- Android官方文档和社区

## 📞 联系方式

- 问题反馈: [GitHub Issues](https://github.com/chisl9403/systemlogger/issues)
- 功能建议: [GitHub Discussions](https://github.com/chisl9403/systemlogger/discussions)

---

⭐ 如果这个项目对你有帮助，请给个星标支持一下！
