package com.example.systemlogger;

import android.Manifest;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import com.github.mikephil.charting.charts.LineChart;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    
    private TextView textViewData;
    private CheckBox checkCPU, checkGPU, checkBattery, checkSkin;
    private Button buttonStart, buttonStop, buttonExport;
    private LineChart lineChart;

    private LoggingService loggingService;
    private boolean bound = false;
    
    // Android 15权限处理
    private ActivityResultLauncher<String[]> permissionLauncher;
    private ActivityResultLauncher<Intent> settingsLauncher;

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            LoggingService.LocalBinder binder = (LoggingService.LocalBinder) service;
            loggingService = binder.getService();
            bound = true;
            Log.d(TAG, "Service connected successfully");
            loggingService.setDataUpdateListener(data -> runOnUiThread(() -> {
                Log.d(TAG, "Received data update: " + data);
                textViewData.setText(data);
                if (lineChart != null) {
                    Log.d(TAG, "Updating chart with data");
                    loggingService.updateChart(lineChart, data);
                } else {
                    Log.e(TAG, "lineChart is null in data update callback");
                }
            }));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            Log.d(TAG, "Service disconnected");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Android 15 Edge-to-Edge支持
        enableEdgeToEdge();
        
        setContentView(R.layout.activity_main);

        // 初始化权限启动器
        initPermissionLaunchers();
        
        // 初始化UI组件
        initViews();
        
        // 请求权限
        requestAllPermissions();
        
        // 设置按钮点击事件
        setupClickListeners();
        
        Log.d(TAG, "MainActivity created successfully");
    }
    
    private void enableEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        }
    }
    
    private void initPermissionLaunchers() {
        // 现代权限请求方式
        permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                boolean allGranted = true;
                for (Boolean isGranted : result.values()) {
                    if (!isGranted) {
                        allGranted = false;
                        break;
                    }
                }
                
                if (!allGranted) {
                    showPermissionDeniedDialog();
                } else {
                    Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show();
                }
            }
        );
        
        // 设置页面启动器
        settingsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                // 从设置返回后重新检查权限
                checkAndRequestPermissions();
            }
        );
    }
    
    private void initViews() {
        textViewData = findViewById(R.id.textViewData);
        checkCPU = findViewById(R.id.checkCPU);
        checkGPU = findViewById(R.id.checkGPU);
        checkBattery = findViewById(R.id.checkBattery);
        checkSkin = findViewById(R.id.checkSkin);
        buttonStart = findViewById(R.id.buttonStart);
        buttonStop = findViewById(R.id.buttonStop);
        buttonExport = findViewById(R.id.buttonExport);
        lineChart = findViewById(R.id.lineChart);
        
        // 初始化图表
        setupChart();
        
        // 设置文本显示
        textViewData.setText(getString(R.string.app_name) + " - 准备就绪");
        
        // 设置按钮文本
        buttonStart.setText(R.string.start_monitoring);
        buttonStop.setText(R.string.stop_monitoring);
        buttonExport.setText(R.string.export_data);
        
        // 设置复选框文本
        checkCPU.setText(R.string.cpu_temp);
        checkGPU.setText(R.string.gpu_temp);
        checkBattery.setText(R.string.battery_temp);
        checkSkin.setText(R.string.skin_temp);
    }
    
    private void setupChart() {
        if (lineChart == null) {
            android.util.Log.e("MainActivity", "lineChart is null!");
            return;
        }
        
        android.util.Log.d("MainActivity", "Setting up chart...");
        
        // 设置图表描述
        lineChart.getDescription().setEnabled(true);
        lineChart.getDescription().setText("温度监控曲线");
        lineChart.getDescription().setTextSize(12f);
        
        // 启用触摸手势
        lineChart.setTouchEnabled(true);
        lineChart.setDragEnabled(true);
        lineChart.setScaleEnabled(true);
        lineChart.setPinchZoom(true);
        
        // 设置图表样式
        lineChart.setDrawGridBackground(false);
        lineChart.setBackgroundColor(getResources().getColor(android.R.color.white, null));
        
        // 设置X轴
        lineChart.getXAxis().setPosition(com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM);
        lineChart.getXAxis().setTextSize(10f);
        lineChart.getXAxis().setDrawGridLines(true);
        
        // 设置Y轴
        lineChart.getAxisLeft().setTextSize(10f);
        lineChart.getAxisLeft().setDrawGridLines(true);
        lineChart.getAxisRight().setEnabled(false);
        
        // 设置图例
        lineChart.getLegend().setEnabled(true);
        lineChart.getLegend().setTextSize(12f);
        
        // 初始化空数据
        lineChart.setData(new com.github.mikephil.charting.data.LineData());
        lineChart.invalidate();
        
        android.util.Log.d("MainActivity", "Chart setup complete");
    }
    
    private void requestAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkAndRequestPermissions();
        }
    }
    
    private void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        
        // Android 13+通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        
        // Android 12及以下存储权限
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        } else {
            // Android 13+媒体权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
        }
        
        // Android 15+特殊权限检查
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // 检查通知管理权限
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null && !notificationManager.areNotificationsEnabled()) {
                // 引导用户到设置页面
                showNotificationPermissionDialog();
                return;
            }
        }
        
        if (!permissionsToRequest.isEmpty()) {
            String[] permissions = permissionsToRequest.toArray(new String[0]);
            permissionLauncher.launch(permissions);
        }
    }
    
    private void showPermissionDeniedDialog() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.permission_request_title)
            .setMessage(R.string.permission_request_message)
            .setPositiveButton("去设置", (dialog, which) -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.fromParts("package", getPackageName(), null));
                settingsLauncher.launch(intent);
            })
            .setNegativeButton("取消", (dialog, which) -> {
                Toast.makeText(this, R.string.permission_denied_message, Toast.LENGTH_LONG).show();
            })
            .show();
    }
    
    private void showNotificationPermissionDialog() {
        new AlertDialog.Builder(this)
            .setTitle("需要通知权限")
            .setMessage("请在设置中启用通知权限，以便应用正常运行")
            .setPositiveButton("去设置", (dialog, which) -> {
                Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                settingsLauncher.launch(intent);
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (!allGranted) {
                showPermissionDeniedDialog();
            }
        }
    }
    
    private void setupClickListeners() {
        buttonStart.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(this, LoggingService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
                bindService(intent, connection, BIND_AUTO_CREATE);
                
                // 延迟设置采样选项，确保服务已连接
                v.postDelayed(() -> {
                    if (bound && loggingService != null) {
                        loggingService.setSamplingOptions(
                            checkCPU.isChecked(),
                            checkGPU.isChecked(),
                            checkBattery.isChecked(),
                            checkSkin.isChecked()
                        );
                    }
                }, 500);
                
                buttonStart.setEnabled(false);
                buttonStop.setEnabled(true);
                Toast.makeText(this, R.string.monitoring_running, Toast.LENGTH_SHORT).show();
                
            } catch (Exception e) {
                Log.e(TAG, "Error starting service", e);
                Toast.makeText(this, "启动监控服务失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        buttonStop.setOnClickListener(v -> {
            try {
                if (bound) {
                    unbindService(connection);
                    bound = false;
                }
                stopService(new Intent(this, LoggingService.class));
                
                buttonStart.setEnabled(true);
                buttonStop.setEnabled(false);
                textViewData.setText("监控已停止");
                Toast.makeText(this, R.string.monitoring_stopped, Toast.LENGTH_SHORT).show();
                
            } catch (Exception e) {
                Log.e(TAG, "Error stopping service", e);
                Toast.makeText(this, "停止监控服务失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        buttonExport.setOnClickListener(v -> {
            try {
                if (bound && loggingService != null) {
                    boolean success = loggingService.exportCSV();
                    if (success) {
                        Toast.makeText(this, R.string.export_success, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(this, "请先启动监控服务", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error exporting data", e);
                Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        
        // 初始状态
        buttonStop.setEnabled(false);
    }
    
    @Override
    protected void onDestroy() {
        try {
            if (bound) {
                unbindService(connection);
                bound = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy", e);
        }
        super.onDestroy();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // 重新检查权限状态
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkAndRequestPermissions();
        }
    }
}
