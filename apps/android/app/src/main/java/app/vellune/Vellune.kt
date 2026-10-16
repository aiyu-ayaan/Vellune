package app.vellune

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import app.vellune.core.Api
import app.vellune.core.LocalDb
import app.vellune.core.Session
import app.vellune.core.SyncEngine
import app.vellune.core.Workspace

/** Process-wide singletons: one device store, one session, one sync engine, one workspace. */
class Vellune : Application() {
    lateinit var db: LocalDb
    lateinit var api: Api
    lateinit var session: Session
    lateinit var sync: SyncEngine
    lateinit var workspace: Workspace

    override fun onCreate() {
        super.onCreate()
        db = LocalDb(this)
        api = Api(db)
        session = Session(api, db)
        sync = SyncEngine(api, db)
        workspace = Workspace(db, sync)
        session.onAccountSwitch = {
            sync.reset()
            workspace.reset()
        }
        // Back online: reconnect now instead of waiting out the backoff.
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                sync.nudge()
            }
        })
    }
}
