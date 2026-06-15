package rfcore.daemon;

import android.os.ParcelFileDescriptor;

parcelable CapabilityRequest {
    String capability;
    String[] args;
    long timeoutMs;
    @nullable ParcelFileDescriptor stdoutFd;
    @nullable ParcelFileDescriptor stderrFd;
    @nullable ParcelFileDescriptor stdinFd;
}
