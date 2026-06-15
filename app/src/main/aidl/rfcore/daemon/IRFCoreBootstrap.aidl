package rfcore.daemon;

import rfcore.daemon.IRFCoreService;

interface IRFCoreBootstrap {
    @nullable IRFCoreService getWorker();
}
