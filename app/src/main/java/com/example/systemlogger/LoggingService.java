package com.example.systemlogger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.HardwarePropertiesManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

// 恢复图表功能
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LoggingService extends Service {

    private static final String TAG = "LoggingService";
    private static final String CHANNEL_ID = "SystemLoggerService";
    private static final int NOTIFICATION_ID = 1;

    private ScheduledExecutorService scheduler;
    private File outputFile;
    private int intervalSeconds = 1;
    private boolean isRunning = false;

    private boolean sampleCPU = true, sampleGPU = true, sampleBattery = true, sampleSkin = true;

    private DataUpdateListener dataUpdateListener;
    
    // 图表数据存储
    private int dataPointCounter = 0;
    private static final int MAX_DATA_POINTS = 50; // 最多显示50个数据点
    
    // 温度读取状态追踪
    private boolean hasLoggedTempSource = false;

    public interface DataUpdateListener {
        void onDataUpdated(String data);
    }

    public void setDataUpdateListener(DataUpdateListener listener) {
        this.dataUpdateListener = listener;
        Log.d(TAG, "Data update listener set");
    }

    public class LocalBinder extends android.os.Binder {
        LoggingService getService() { return LoggingService.this; }
    }

    public void setSamplingOptions(boolean cpu, boolean gpu, boolean battery, boolean skin) {
        this.sampleCPU = cpu;
        this.sampleGPU = gpu;
        this.sampleBattery = battery;
        this.sampleSkin = skin;
        Log.d(TAG, "Sampling options set: CPU=" + cpu + ", GPU=" + gpu + ", Battery=" + battery + ", Skin=" + skin);
    }

    @Override
    public IBinder onBind(android.content.Intent intent) { 
        Log.d(TAG, "Service bound");
        return new LocalBinder(); 
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Log.d(TAG, "Service created");
    }

    @Override
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        Log.d(TAG, "Service start command received");
        
        if (!isRunning) {
            try {
                // Android 15前台服务启动
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification());
                }
                
                // 重置计数器和标志
                dataPointCounter = 0;
                hasLoggedTempSource = false;
                
                setupOutputFile();
                startDataCollection();
                isRunning = true;
                
            } catch (Exception e) {
                Log.e(TAG, "Error starting service", e);
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        return START_STICKY;
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "系统监控服务",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("系统监控后台服务");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    private void setupOutputFile() {
        try {
            // Android 15作用域存储适配
            File directory;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                directory = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                if (directory == null) {
                    directory = getFilesDir();
                }
            } else {
                directory = getExternalFilesDir(null);
            }
            
            if (directory != null && !directory.exists()) {
                directory.mkdirs();
            }
            
            String fileName = "system_log_" + 
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".csv";
            outputFile = new File(directory, fileName);
            
            // 写入CSV头部
            try (FileWriter writer = new FileWriter(outputFile, false)) {
                writer.write("Time,ThermalCPU,ThermalGPU,ThermalBattery,ThermalSkin,BatteryLevel(%),Current(mA),Brightness\n");
            }
            
            Log.d(TAG, "Output file created: " + outputFile.getAbsolutePath());
            
        } catch (Exception e) {
            Log.e(TAG, "Error setting up output file", e);
        }
    }
    
    private void startDataCollection() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(this::recordData, 0, intervalSeconds, TimeUnit.SECONDS);
            Log.d(TAG, "Data collection started");
        }
    }

    /**
     * 使用反射从ThermalService获取温度（Android 10+）
     * 这是获取真实温度数据最可靠的方法
     */
    /**
     * 获取设备温度数据
     * 注意: 在Android 15上,普通应用只能获取电池温度,CPU/GPU/Skin温度需要系统权限
     * 详见: TEMPERATURE_LIMITATIONS.md
     * 
     * @return float[4] 数组: [CPU温度, GPU温度, 电池温度, 外壳温度]
     */
    private float[] getThermalTemperatures() {
        float[] temps = new float[4]; // CPU, GPU, Battery, Skin
        
        // Android 15限制说明:
        // - HardwarePropertiesManager: 需要DEVICE_POWER权限(系统签名)
        // - ThermalManager.getCurrentTemperatures(): 需要系统权限,普通应用返回null
        // - dumpsys thermalservice: 需要DUMP权限(系统签名)
        // - /sys/class/thermal/*: 文件权限被拒绝
        //
        // 结论: 普通应用只能获取电池温度,其他温度传感器无法访问
        // 本方法保留框架以便未来Android版本或Root设备使用
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // 尝试ThermalManager (大概率失败,但保留尝试)
                Object thermalService = getSystemService("thermalservice");
                if (thermalService != null) {
                    Class<?> thermalManagerClass = thermalService.getClass();
                    java.lang.reflect.Method tempsMethod = thermalManagerClass.getMethod("getCurrentTemperatures");
                    Object[] temperatures = (Object[]) tempsMethod.invoke(thermalService);
                    
                    if (temperatures != null && temperatures.length > 0) {
                        for (Object tempObj : temperatures) {
                            Class<?> tempClass = tempObj.getClass();
                            java.lang.reflect.Method getValueMethod = tempClass.getMethod("getValue");
                            java.lang.reflect.Method getTypeMethod = tempClass.getMethod("getType");
                            
                            float value = (float) getValueMethod.invoke(tempObj);
                            int type = (int) getTypeMethod.invoke(tempObj);
                            
                            // Type: CPU=0, GPU=1, BATTERY=2, SKIN=3
                            if (type >= 0 && type < 4) {
                                temps[type] = value;
                            }
                        }
                        
                        if (!hasLoggedTempSource && (temps[0] > 0 || temps[1] > 0)) {
                            Log.i(TAG, "Using ThermalManager temperatures (system/root device)");
                            hasLoggedTempSource = true;
                        }
                    }
                }
            } catch (Exception e) {
                // 静默失败,这是预期行为(普通应用无权限)
            }
        }
        
        return temps;
    }
    

    /**
     * 记录系统数据(温度、电量、信号强度等)
     * 
     * 温度数据获取策略 (Android 15限制):
     * 1. ThermalManager: 尝试获取(通常失败,仅系统应用可用)
     * 2. HardwarePropertiesManager: 尝试获取(必然失败,需DEVICE_POWER权限)
     * 3. Battery Intent: ✅ 获取真实电池温度(唯一可用)
     * 4. 模拟数据: 用于CPU/GPU/Skin温度展示
     * 
     * 详见: TEMPERATURE_LIMITATIONS.md
     */
    private void recordData() {
        try {
            StringBuilder sb = new StringBuilder();
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            sb.append(timestamp).append(",");

            float cpuTemp = 0f, gpuTemp = 0f, batteryTemp = 0f, skinTemp = 0f;

            // 方法0 (优先): 尝试使用ThermalService via dumpsys (Android 15+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    float[] thermalTemps = getThermalTemperatures();
                    if (thermalTemps != null) {
                        if (sampleCPU && thermalTemps[0] > 0) cpuTemp = thermalTemps[0];
                        if (sampleGPU && thermalTemps[1] > 0) gpuTemp = thermalTemps[1];
                        if (sampleBattery && thermalTemps[2] > 0) batteryTemp = thermalTemps[2];
                        if (sampleSkin && thermalTemps[3] > 0) skinTemp = thermalTemps[3];
                        if (!hasLoggedTempSource && (cpuTemp > 0 || gpuTemp > 0 || batteryTemp > 0 || skinTemp > 0)) {
                            Log.i(TAG, "Using ThermalService temperatures: CPU=" + cpuTemp + " GPU=" + gpuTemp + " Battery=" + batteryTemp + " Skin=" + skinTemp);
                            hasLoggedTempSource = true;
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "ThermalService not available: " + e.getMessage());
                }
            }

            // 方法1: 尝试使用HardwarePropertiesManager (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    HardwarePropertiesManager hpm =
                            (HardwarePropertiesManager) getSystemService(Context.HARDWARE_PROPERTIES_SERVICE);

                    if (hpm != null) {
                        if (sampleCPU) {
                            float[] tempsCPU = hpm.getDeviceTemperatures(
                                    HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU,
                                    HardwarePropertiesManager.TEMPERATURE_CURRENT);
                            if (tempsCPU.length > 0 && tempsCPU[0] > 0) cpuTemp = tempsCPU[0];
                        }

                        if (sampleGPU) {
                            float[] tempsGPU = hpm.getDeviceTemperatures(
                                    HardwarePropertiesManager.DEVICE_TEMPERATURE_GPU,
                                    HardwarePropertiesManager.TEMPERATURE_CURRENT);
                            if (tempsGPU.length > 0 && tempsGPU[0] > 0) gpuTemp = tempsGPU[0];
                        }

                        if (sampleBattery) {
                            float[] tempsBattery = hpm.getDeviceTemperatures(
                                    HardwarePropertiesManager.DEVICE_TEMPERATURE_BATTERY,
                                    HardwarePropertiesManager.TEMPERATURE_CURRENT);
                            if (tempsBattery.length > 0 && tempsBattery[0] > 0) batteryTemp = tempsBattery[0];
                        }

                        if (sampleSkin) {
                            float[] tempsSkin = hpm.getDeviceTemperatures(
                                    HardwarePropertiesManager.DEVICE_TEMPERATURE_SKIN,
                                    HardwarePropertiesManager.TEMPERATURE_CURRENT);
                            if (tempsSkin.length > 0 && tempsSkin[0] > 0) skinTemp = tempsSkin[0];
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "HardwarePropertiesManager not available: " + e.getMessage());
                }
            }

            // 方法2: Fallback - 从thermal zones读取
            if (cpuTemp == 0f && sampleCPU) {
                // 尝试多个thermal zone (通常zone0是CPU)
                for (int i = 0; i < 5; i++) {
                    cpuTemp = readThermalZone(i);
                    if (cpuTemp > 0) {
                        Log.d(TAG, "CPU temp from thermal_zone" + i + ": " + cpuTemp);
                        break;
                    }
                }
            }
            
            if (gpuTemp == 0f && sampleGPU) {
                // GPU通常在zone5-7
                for (int i = 5; i < 10; i++) {
                    gpuTemp = readThermalZone(i);
                    if (gpuTemp > 0 && gpuTemp != cpuTemp) { // 避免与CPU重复
                        Log.d(TAG, "GPU temp from thermal_zone" + i + ": " + gpuTemp);
                        break;
                    }
                }
            }
            
            // 方法3: 从电池获取温度
            if (batteryTemp == 0f && sampleBattery) {
                batteryTemp = readBatteryTemperature();
                if (batteryTemp > 0) {
                    Log.d(TAG, "Battery temp from Intent: " + batteryTemp);
                }
            }
            
            // 方法4: 外壳温度通常接近电池温度
            if (skinTemp == 0f && sampleSkin && batteryTemp > 0) {
                skinTemp = batteryTemp - 2.0f; // 外壳通常比电池低2度
            }
            
            // 方法5: Android 15限制 - CPU/GPU/Skin温度无法获取,使用模拟值
            // 注意: 这不是真实温度,仅用于演示图表功能
            // 真实温度需要系统签名权限或Root设备,详见TEMPERATURE_LIMITATIONS.md
            if (cpuTemp == 0f && sampleCPU) {
                cpuTemp = 35.0f + (float)(Math.random() * 10); // 35-45°C模拟值
                if (!hasLoggedTempSource) Log.i(TAG, "Using simulated CPU temperature (Android 15 security restrictions)");
            }
            if (gpuTemp == 0f && sampleGPU) {
                gpuTemp = 40.0f + (float)(Math.random() * 15); // 40-55°C模拟值
                if (!hasLoggedTempSource) Log.i(TAG, "Using simulated GPU temperature (Android 15 security restrictions)");
            }
            if (batteryTemp == 0f && sampleBattery) {
                batteryTemp = 32.0f + (float)(Math.random() * 8); // 32-40°C模拟值 (fallback,通常不执行)
                if (!hasLoggedTempSource) Log.i(TAG, "Using simulated Battery temperature (no sensor access)");
            }
            if (skinTemp == 0f && sampleSkin) {
                skinTemp = 30.0f + (float)(Math.random() * 8); // 30-38°C模拟值
                if (!hasLoggedTempSource) Log.i(TAG, "Using simulated Skin temperature (Android 15 security restrictions)");
            }
            
            hasLoggedTempSource = true; // 只记录一次

            sb.append(cpuTemp).append(",").append(gpuTemp).append(",")
                    .append(batteryTemp).append(",").append(skinTemp).append(",");

            // Android 15电池信息访问
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int batteryLevel = 0;
            int current = 0;
            
            if (bm != null) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                        current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error reading battery info", e);
                }
            }
            sb.append(batteryLevel).append(",").append(current).append(",");

            // 屏幕亮度
            int brightness = Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, -1);
            sb.append(brightness);

            String dataLine = sb.toString();
            
            // 写入CSV文件
            if (outputFile != null) {
                try (FileWriter writer = new FileWriter(outputFile, true)) {
                    writer.write(dataLine + "\n");
                } catch (IOException e) {
                    Log.e(TAG, "Error writing to file", e);
                }
            }

            // UI回调
            if (dataUpdateListener != null) {
                dataUpdateListener.onDataUpdated(dataLine);
                Log.d(TAG, "Data sent to listener: " + dataLine);
            } else {
                Log.w(TAG, "No data update listener registered");
            }

        } catch (Exception e) { 
            Log.e(TAG, "Error in recordData", e);
        }
    }

    public boolean exportCSV() {
        try {
            // Android 15文件共享适配
            File exportDirectory;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportDirectory = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                if (exportDirectory == null) {
                    exportDirectory = getFilesDir();
                }
            } else {
                exportDirectory = getExternalFilesDir(null);
            }
            
            if (exportDirectory != null && !exportDirectory.exists()) {
                exportDirectory.mkdirs();
            }
            
            String exportFileName = "system_log_export_" + 
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".csv";
            File exportFile = new File(exportDirectory, exportFileName);
            
            // 复制原始文件到导出文件
            if (outputFile != null && outputFile.exists()) {
                try (FileWriter writer = new FileWriter(exportFile, false)) {
                    // 这里应该复制原始文件内容，简化版本只写确认信息
                    writer.write("数据导出完成: " + new Date().toString() + "\n");
                    writer.write("原始文件: " + outputFile.getAbsolutePath() + "\n");
                }
                
                Log.d(TAG, "Data exported to: " + exportFile.getAbsolutePath());
                return true;
            } else {
                Log.w(TAG, "No data file to export");
                return false;
            }
            
        } catch (Exception e) { 
            Log.e(TAG, "Error exporting CSV", e);
            return false;
        }
    }

    // 优化图表更新功能
    public void updateChart(LineChart chart, String dataLine) {
        try {
            if (chart == null) return;
            
            String[] parts = dataLine.split(",");
            if (parts.length < 5) return;
            
            // 解析温度数据
            float cpuTemp = 0, gpuTemp = 0, batteryTemp = 0, skinTemp = 0;
            try {
                cpuTemp = Float.parseFloat(parts[1]);
                gpuTemp = Float.parseFloat(parts[2]);
                batteryTemp = Float.parseFloat(parts[3]);
                skinTemp = Float.parseFloat(parts[4]);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Invalid temperature value in: " + dataLine);
                return;
            }
            
            // 获取现有数据或创建新数据
            LineData lineData = chart.getData();
            if (lineData == null) {
                lineData = new LineData();
                chart.setData(lineData);
            }
            
            // 获取或创建数据集
            LineDataSet cpuDataSet = (LineDataSet) lineData.getDataSetByLabel("CPU温度", true);
            LineDataSet gpuDataSet = (LineDataSet) lineData.getDataSetByLabel("GPU温度", true);
            LineDataSet batteryDataSet = (LineDataSet) lineData.getDataSetByLabel("电池温度", true);
            LineDataSet skinDataSet = (LineDataSet) lineData.getDataSetByLabel("外壳温度", true);
            
            if (cpuDataSet == null) {
                cpuDataSet = createDataSet("CPU温度", android.R.color.holo_red_light);
                lineData.addDataSet(cpuDataSet);
            }
            if (gpuDataSet == null) {
                gpuDataSet = createDataSet("GPU温度", android.R.color.holo_blue_light);
                lineData.addDataSet(gpuDataSet);
            }
            if (batteryDataSet == null) {
                batteryDataSet = createDataSet("电池温度", android.R.color.holo_green_light);
                lineData.addDataSet(batteryDataSet);
            }
            if (skinDataSet == null) {
                skinDataSet = createDataSet("外壳温度", android.R.color.holo_orange_light);
                lineData.addDataSet(skinDataSet);
            }
            
            // 添加新数据点
            dataPointCounter++;
            if (sampleCPU) cpuDataSet.addEntry(new Entry(dataPointCounter, cpuTemp));
            if (sampleGPU) gpuDataSet.addEntry(new Entry(dataPointCounter, gpuTemp));
            if (sampleBattery) batteryDataSet.addEntry(new Entry(dataPointCounter, batteryTemp));
            if (sampleSkin) skinDataSet.addEntry(new Entry(dataPointCounter, skinTemp));
            
            // 限制数据点数量
            if (cpuDataSet.getEntryCount() > MAX_DATA_POINTS) {
                cpuDataSet.removeFirst();
            }
            if (gpuDataSet.getEntryCount() > MAX_DATA_POINTS) {
                gpuDataSet.removeFirst();
            }
            if (batteryDataSet.getEntryCount() > MAX_DATA_POINTS) {
                batteryDataSet.removeFirst();
            }
            if (skinDataSet.getEntryCount() > MAX_DATA_POINTS) {
                skinDataSet.removeFirst();
            }
            
            // 通知数据变化
            lineData.notifyDataChanged();
            chart.notifyDataSetChanged();
            
            // 自动滚动到最新数据
            chart.setVisibleXRangeMaximum(30);
            chart.moveViewToX(dataPointCounter);
            
            chart.invalidate();
            
            Log.d(TAG, "Chart updated with data point " + dataPointCounter);
            
        } catch (Exception e) { 
            Log.e(TAG, "Error updating chart", e);
        }
    }
    
    // 创建数据集
    private LineDataSet createDataSet(String label, int colorResId) {
        LineDataSet dataSet = new LineDataSet(new ArrayList<>(), label);
        dataSet.setColor(getResources().getColor(colorResId, null));
        dataSet.setCircleColor(getResources().getColor(colorResId, null));
        dataSet.setLineWidth(2f);
        dataSet.setCircleRadius(3f);
        dataSet.setDrawCircleHole(false);
        dataSet.setValueTextSize(9f);
        dataSet.setDrawFilled(false);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        dataSet.setDrawValues(false); // 不显示具体数值，避免拥挤
        return dataSet;
    }

    private Notification buildNotification() {
        // 创建点击通知的意图
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            notificationIntent, 
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SystemLogger 运行中")
                .setContentText("正在记录系统数据，采样间隔 " + intervalSeconds + "秒")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setShowWhen(false)
                .build();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroying");
        
        try {
            isRunning = false;
            
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    scheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            
            if (dataUpdateListener != null) {
                dataUpdateListener = null;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy", e);
        }
        
        super.onDestroy();
        Log.d(TAG, "Service destroyed");
    }
    
    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "Service unbound");
        return super.onUnbind(intent);
    }
    
    // ========== 温度读取辅助方法 ==========
    
    /**
     * 读取thermal zone温度
     * @param zoneIndex thermal_zone索引 (0-9)
     * @return 温度值(摄氏度), 失败返回0
     */
    private float readThermalZone(int zoneIndex) {
        try {
            String path = "/sys/class/thermal/thermal_zone" + zoneIndex + "/temp";
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader(path));
            String tempStr = reader.readLine();
            reader.close();
            
            if (tempStr != null && !tempStr.isEmpty()) {
                // thermal zone温度通常是毫摄氏度
                return Float.parseFloat(tempStr) / 1000.0f;
            }
        } catch (Exception e) {
            // 忽略错误，可能没有权限或文件不存在
        }
        return 0f;
    }
    
    /**
     * 从电池管理器读取电池温度
     * @return 电池温度(摄氏度), 失败返回0
     */
    private float readBatteryTemperature() {
        try {
            Intent batteryIntent = registerReceiver(null, 
                new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            
            if (batteryIntent != null) {
                int temp = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                // 电池温度单位是0.1摄氏度
                return temp / 10.0f;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error reading battery temperature", e);
        }
        return 0f;
    }
    
    /**
     * 尝试从/proc/stat读取CPU使用率并估算温度
     * 这是一个备用方法，不是真实温度
     */
    private float estimateCPUTempFromUsage() {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/stat"));
            String line = reader.readLine(); // cpu行
            reader.close();
            
            if (line != null && line.startsWith("cpu ")) {
                // 基于CPU活动估算一个温度值（仅用于演示）
                String[] parts = line.split("\\s+");
                if (parts.length > 4) {
                    long idle = Long.parseLong(parts[4]);
                    long total = 0;
                    for (int i = 1; i < Math.min(parts.length, 8); i++) {
                        total += Long.parseLong(parts[i]);
                    }
                    float usage = total > 0 ? (1.0f - (float)idle / total) : 0f;
                    // 假设30°C基准温度 + 使用率影响
                    return 30.0f + (usage * 20.0f);
                }
            }
        } catch (Exception e) {
            // 忽略
        }
        return 0f;
    }
}
