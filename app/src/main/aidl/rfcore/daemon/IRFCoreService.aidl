package rfcore.daemon;

import rfcore.daemon.CapabilityRequest;
import rfcore.daemon.CapabilityResult;
import rfcore.daemon.PolicyRecord;
import rfcore.daemon.AuditRecord;

// 🚨 新增：引入回调通讯合同
import rfcore.daemon.IAuthCallback;

interface IRFCoreService {
    CapabilityResult requestCapability(in CapabilityRequest request);
    boolean isCapabilityGranted(String capability);
    int getStatus();
    boolean grantCapability(int uid, String packageName, String capability, int isGranted, long expiresAt);
    boolean revokeCapability(int uid, String capability);
    PolicyRecord[] getPolicies();
    AuditRecord[] getAuditLogs(int limit, int offset);
    
    // 🚨 新增：让 Manager App 将实现好的回调注册给底层守护进程
    void registerAuthCallback(IAuthCallback callback);
}
