package rfcore.daemon;

// 这是一个双向回调接口，将由 Manager App (Kotlin) 实现，由 Daemon (C++) 调用
interface IAuthCallback {
    /**
     * 当有底层权限请求被拦截时，Daemon 会调用此方法挂起请求，等待用户在屏幕上点击。
     * 返回值: 1 表示允许, 0 表示拒绝
     */
    int onAuthRequested(int uid, String processName, String capability);
}
