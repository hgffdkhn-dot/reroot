package rfcore.daemon;

parcelable AuditRecord {
    int id;
    int uid;
    String capability;
    long timestamp;
    int status;
    String details;
}
