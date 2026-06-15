package rfcore.daemon;

import rfcore.daemon.CapabilityRequest;
import rfcore.daemon.CapabilityResult;
import rfcore.daemon.PolicyRecord;
import rfcore.daemon.AuditRecord;

interface IRFCoreService {
    CapabilityResult requestCapability(in CapabilityRequest request);
    boolean isCapabilityGranted(String capability);
    int getStatus();
    boolean grantCapability(int uid, String packageName, String capability, int isGranted, long expiresAt);
    boolean revokeCapability(int uid, String capability);
    PolicyRecord[] getPolicies();
    AuditRecord[] getAuditLogs(int limit, int offset);
}
