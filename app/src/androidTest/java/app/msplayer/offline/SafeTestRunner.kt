package app.msplayer.offline

/** Instrumentation is deliberately restricted to the isolated development installation. */
class SafeTestRunner : androidx.test.runner.AndroidJUnitRunner() {
    override fun onStart() {
        check(targetContext.packageName == "app.msplayer.offline.debug") {
            "Refusing to run instrumentation against the everyday release installation"
        }
        super.onStart()
    }
}
