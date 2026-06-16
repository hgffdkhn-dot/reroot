package com.rfcore.manager

import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// =======================================================
// 🚨 极其重要：确保这里导入的包名与你的 AIDL 文件完全一致！
import rfcore.daemon.IRFCoreBootstrap
import rfcore.daemon.IRFCoreService
// =======================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RFCoreTestScreen()
                }
            }
        }
    }
}

// ==========================================
// 🚨 核心逻辑：两段式获取底层服务 (彻底解决崩溃白屏)
// ==========================================
fun connectToDaemon(): IRFCoreService? {
    try {
        Log.d("RFCore_App", "1. 开始通过反射调用 ServiceManager...")
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getServiceMethod = serviceManager.getMethod("getService", String::class.java)
        
        // 第一阶段：拿到我们在 C++ (main.cpp) 里注册的 "看门人"
        val rawBinder = getServiceMethod.invoke(null, "rfcore.bootstrap") as IBinder?
        
        if (rawBinder != null) {
            Log.d("RFCore_App", "2. 成功拿到原生 Binder，准备转换为看门人 Bootstrap")
            
            // 将原始句柄转换为【看门人】(千万不能直接转为 Service！)
            val bootstrap = IRFCoreBootstrap.Stub.asInterface(rawBinder)
            
            Log.d("RFCore_App", "3. 向看门人索要真正的干活 Worker 句柄...")
            
            // ⚠️⚠️⚠️ 终极警告 ⚠️⚠️⚠️
            // 这里的 getService() 必须替换为你在 IRFCoreBootstrap.aidl 中定义的方法名！
            // 如果你在 AIDL 里写的是 IBinder getCoreService(); 请改成 bootstrap.getCoreService()
            // 在 Kotlin 里它也可能被识别为属性，比如 bootstrap.service
            val workerBinder = bootstrap.getService() 
            
            Log.d("RFCore_App", "4. 成功拿到 Worker，正在强转为 IRFCoreService")
            // 第二阶段：将真正的 Worker 转换为我们在前台使用的核心接口
            return IRFCoreService.Stub.asInterface(workerBinder)
        } else {
            Log.e("RFCore_App", "ServiceManager 返回了 null，请确保你已经执行了 su -c setenforce 0")
        }
    } catch (e: Exception) {
        Log.e("RFCore_App", "连接底层发生致命崩溃", e)
        throw e // 直接抛出异常，让下方的 Toast 将详细死因显示在屏幕上！
    }
    return null
}

// ==========================================
// 界面：测试控制台
// ==========================================
@Composable
fun RFCoreTestScreen() {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "RFCore 终极测试控制台", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = {
            try {
                val service = connectToDaemon()
                if (service != null) {
                    // 1. 测试心跳 (AIDL 定义的 getStatus 在 Kotlin 中默认映射为 status 属性)
                    val status = service.status 
                    
                    // 2. 注入一条假数据 (用于打破数据库空白的假象)
                    val isSuccess = service.grantCapability(
                        10000, 
                        "com.test.dummy", 
                        "CAP_READ_FILE", 
                        1, 
                        0L
                    )
                    
                    Toast.makeText(context, "🎉 完美打通底层!\n状态码: $status\n假数据注入: $isSuccess", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "❌ 拿不到 Service，检查是否忘记开启宽容模式", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                // 如果还崩，这里会把你把异常名字 (如 NoSuchMethodError) 吐在屏幕上
                Toast.makeText(context, "💥 崩溃死因:\n${e.javaClass.simpleName}\n${e.message}", Toast.LENGTH_LONG).show()
            }
        }) {
            Text("点我连接底层 并 注入测试策略")
        }
    }
}
