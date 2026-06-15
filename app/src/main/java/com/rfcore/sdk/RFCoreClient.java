package rfcore.sdk;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

import rfcore.daemon.IRFCoreBootstrap;
import rfcore.daemon.IRFCoreService;
import rfcore.daemon.CapabilityRequest;
import rfcore.daemon.CapabilityResult;
import rfcore.daemon.PolicyRecord;
import rfcore.daemon.AuditRecord;

public class RFCoreClient {
	private static RFCoreClient sInstance;
	private IRFCoreService mWorkerService;
	
	private RFCoreClient() {}
	
	public static synchronized RFCoreClient getInstance() {
		if (sInstance == null) {
			sInstance = new RFCoreClient();
		}
		return sInstance;
	}
	
	public boolean connect() {
		if (mWorkerService != null) {
			return true;
		}
		try {
			Class<?> smClass = Class.forName("android.os.ServiceManager");
			IBinder bootstrapBinder = (IBinder) smClass.getMethod("getService", String.class).invoke(null, "rfcore.bootstrap");
			if (bootstrapBinder == null) {
				return false;
			}
			IRFCoreBootstrap bootstrap = IRFCoreBootstrap.Stub.asInterface(bootstrapBinder);
			mWorkerService = bootstrap.getWorker();
			return mWorkerService != null;
			} catch (Exception e) {
			return false;
		}
	}
	
	public CapabilityResult requestCapabilitySync(String capability, String[] args, OutputStream outStream) {
		if (!connect()) return null;
		try {
			CapabilityRequest request = new CapabilityRequest();
			request.capability = capability;
			request.args = args;
			request.timeoutMs = 5000;
			
			ParcelFileDescriptor[] pipes = ParcelFileDescriptor.createPipe();
			request.stdoutFd = pipes[1];
			
			CapabilityResult result = mWorkerService.requestCapability(request);
			pipes[1].close();
			
			if (outStream != null) {
				try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipes[0])) {
					byte[] buffer = new byte[8192];
					int read;
					while ((read = in.read(buffer)) != -1) {
						outStream.write(buffer, 0, read);
					}
				}
				} else {
				pipes[0].close();
			}
			return result;
			} catch (Exception e) {
			return null;
		}
	}
	
	public boolean grantCapability(int uid, String packageName, String capability, int isGranted, long expiresAt) {
		if (!connect()) return false;
		try {
			return mWorkerService.grantCapability(uid, packageName, capability, isGranted, expiresAt);
			} catch (Exception e) {
			return false;
		}
	}
	
	public boolean revokeCapability(int uid, String capability) {
		if (!connect()) return false;
		try {
			return mWorkerService.revokeCapability(uid, capability);
			} catch (Exception e) {
			return false;
		}
	}
	
	public List<PolicyRecord> getPolicies() {
		if (!connect()) return null;
		try {
			return Arrays.asList(mWorkerService.getPolicies());
			} catch (Exception e) {
			return null;
		}
	}
	
	public List<AuditRecord> getAuditLogs(int limit, int offset) {
		if (!connect()) return null;
		try {
			return Arrays.asList(mWorkerService.getAuditLogs(limit, offset));
			} catch (Exception e) {
			return null;
		}
	}
}