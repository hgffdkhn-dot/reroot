package rfcore.daemon;

parcelable PolicyRecord {
    int uid;
    String packageName;
    String capability;
    int isGranted;
    long expiresAt;
}
