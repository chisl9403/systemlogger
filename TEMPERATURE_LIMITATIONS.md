# Android 15 温度传感器访问限制

## 调研总结

### 测试设备
- 设备型号: Motorola edge 50 fusion
- Android版本: 15 (API 35)
- 安全补丁: 2024-10-01

### 温度数据可用性

#### ✅ 可访问的温度数据
1. **电池温度** (Battery Temperature)
   - API: `BroadcastReceiver` + `Intent.ACTION_BATTERY_CHANGED`
   - 权限: 无需特殊权限
   - 精度: 0.1°C
   - 实测值: ~32.0°C
   - **这是普通应用唯一能稳定获取的真实温度值**

2. **热状态等级** (Thermal Status)
   - API: `PowerManager.getCurrentThermalStatus()` (Android Q+)
   - 权限: 无需特殊权限
   - 返回值: 0-6 (THERMAL_STATUS_NONE 到 THERMAL_STATUS_SHUTDOWN)
   - **注意**: 只返回等级,不返回具体温度值

#### ❌ 无法访问的温度数据 (需要系统签名或特殊权限)

1. **HardwarePropertiesManager** (Android 10+)
   ```java
   HardwarePropertiesManager.getDeviceTemperatures(...)
   ```
   - 需要权限: `DEVICE_POWER` (signature|privileged)
   - 错误信息: "The caller is neither a device owner, nor holding the DEVICE_POWER permission"
   - 状态: ❌ 普通应用无法使用

2. **ThermalManager.getCurrentTemperatures()** (Android 11+)
   ```java
   ThermalManager.getCurrentTemperatures()
   ```
   - 需要权限: 系统应用或特权应用
   - 通过 `getSystemService("thermalservice")` 获取
   - 状态: ❌ 返回null或空数组,普通应用无法使用

3. **dumpsys thermalservice** (Shell命令)
   ```bash
   adb shell dumpsys thermalservice
   ```
   - 需要权限: `android.permission.DUMP` (signature|privileged|development)
   - Shell可执行,但应用内`Runtime.exec()`被拒绝
   - **实测数据显示设备确实有完整温度传感器**:
     - CPU: 37.444°C
     - GPU: 37.444°C
     - Battery: 32.0°C
     - Skin: 32.318°C
   - 状态: ❌ 仅调试模式可用,应用无法使用

4. **/sys/class/thermal/** (Thermal Zones)
   ```bash
   /sys/class/thermal/thermal_zone*/temp
   ```
   - 需要: 文件系统读权限
   - 错误: Permission denied
   - 状态: ❌ Android 15权限限制,普通应用无法访问

### 实现策略

#### 当前应用使用的方案
采用多层回退机制:

1. **优先级1**: ThermalManager (尝试但大概率失败)
2. **优先级2**: HardwarePropertiesManager (必然失败)
3. **优先级3**: Thermal Zones文件系统 (必然失败)
4. **优先级4**: 电池温度Intent ✅ **唯一可用**
5. **优先级5**: 模拟数据 (用于CPU/GPU/Skin温度展示)

#### 最终结果
- ✅ **电池温度**: 真实值 (~32°C)
- ⚠️ **CPU温度**: 模拟值 (35-45°C随机)
- ⚠️ **GPU温度**: 模拟值 (40-55°C随机)
- ⚠️ **外壳温度**: 模拟值 (30-38°C随机)

### 开发者选项

#### 通过ADB调试可以查看真实数据:
```bash
# 查看完整温度信息
adb shell dumpsys thermalservice

# 查看热状态
adb shell dumpsys thermalservice | grep "Current thermal status"

# 查看所有温度传感器
adb shell dumpsys thermalservice | grep "Temperature{"
```

#### Root设备可能的解决方案:
1. 将应用安装为系统应用
2. 使用 `su` 权限执行dumpsys
3. 修改 `/sys/class/thermal/` 文件权限

### 结论

**对于非Root的普通Android应用**:
- 在Android 15上,由于隐私和安全限制,**无法获取CPU/GPU/Skin的真实温度值**
- 只能获取电池温度和热状态等级
- Google有意限制温度数据访问,防止指纹识别和隐私泄露

**本应用采用的权衡方案**:
- 电池温度显示真实值
- 其他温度传感器使用合理范围的模拟值用于图表演示
- 通过热状态API可以知道设备是否过热,但无法获取精确温度

### 参考文档
- [Android HardwarePropertiesManager](https://developer.android.com/reference/android/os/HardwarePropertiesManager)
- [Android ThermalManager](https://developer.android.com/reference/android/os/PowerManager.OnThermalStatusChangedListener)
- [Android 15 Privacy Changes](https://developer.android.com/about/versions/15/behavior-changes-15)
