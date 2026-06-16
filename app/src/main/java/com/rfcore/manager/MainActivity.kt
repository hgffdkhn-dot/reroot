package com.rfcore.manager // ⚠️ 如果你的包名不同，请改回你原来的包名

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

// ⚠️ 极其重要：导入你生成的 AIDL 接口
import rfcore.daemon.IRFCoreBootstrap
import rfcore.daemon.IRFCoreService

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
// 🚨 核心修复 2：正确的反射获取系统底层服务逻辑
// ==========================================
fun connectToDaemon(): IRFCoreService? {
    try {
        Log.d("RFCore_App", "开始尝试通过反射获取底层服务...")
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getServiceMethod = serviceManager.getMethod("getService", String::class.java)
        
        // 1. 获取原生的 Binder 句柄 (我们在 main.cpp 里注册的名字)
        val rawBinder = getServiceMethod.invoke(null, "rfcore.bootstrap") as IBinder?
        
        if (rawBinder != null) {
            Log.d("RFCore_App", "拿到原生 Binder！")
            
            // 2. 【架构适配分支】：
            // 如果你在 C++ 层把 Bootstrap 作为马甲，Service 作为真正的工人，
            // 且在 AIDL 里写了类似 getCoreService() 的方法，请用下面这行：
            // val bootstrap = IRFCoreBootstrap.Stub.asInterface(rawBinder)
            // return IRFCoreService.Stub.asInterface(bootstrap.getCoreService())
            
            // 如果你其实在底层直接就是把 Service 转成了 Binder 交出去了，
            // 那么直接强转为 IRFCoreService 即可 (绝大多数情况是这种)：
            return IRFCoreService.Stub.asInterface(rawBinder)
        }
    } catch (e: Exception) {
        Log.e("RFCore_App", "连接底层发生致命崩溃: ${e.message}", e)
    }
    return null
}

// ==========================================
// 🚨 核心修复 3：增加手动测试和造数据的按钮
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
                    // 测试一：心跳检测
                    val status = service.status 
                    
                    // 测试二：向空白数据库注入一条“假数据”
                    // 参数：UID(10000), 包名, 权限名, 是否允许(1), 过期时间(0=永久)
                    val isSuccess = service.grantCapability(
                        10000, 
                        "com.test.dummy", 
                        "CAP_READ_FILE", 
                        1, 
                        0L
                    )
                    
                    Toast.makeText(context, "🎉 连接成功! 状态码:$status\n假数据注入:$isSuccess", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "❌ 未拿到 Service 对象，请查 Logcat", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "💥 发生崩溃: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }) {
            Text("点我连接底层 并 注入测试策略")
        }
    }
}
