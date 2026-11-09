package com.hwangsihu.smt.engine

// The 2014 engines open their voice files by path. Instead of copies on disk they get the folder /voicefs, whose
// files are read straight from the APK or the voice pack: by libsmtemu for an emulated engine, and for a native
// one by libsmtvoicefs, which points the engine's fopen import at itself.
internal object VoiceFs {
    const val DIR = "/voicefs"

    // Called once the native engine library is loaded; false when its fopen could not be redirected.
    fun redirect(library: String): Boolean = runCatching {
        System.loadLibrary("smtvoicefs")
        nRedirect("lib$library.so")
    }.getOrDefault(false)

    fun serve(files: VoiceFiles): String = serve(files, ::nMount, ::nMountBytes, ::nUnmountAll)

    fun serve(
        files: VoiceFiles,
        mount: (String, Int, Long, Long) -> Unit,
        mountBytes: (String, ByteArray) -> Unit,
        unmountAll: () -> Unit,
    ): String {
        unmountAll()
        files.forEachFile(mount, mountBytes)
        return DIR
    }

    @JvmStatic
    private external fun nRedirect(library: String): Boolean

    @JvmStatic
    private external fun nMount(name: String, fd: Int, offset: Long, length: Long)

    @JvmStatic
    private external fun nMountBytes(name: String, data: ByteArray)

    @JvmStatic
    private external fun nUnmountAll()
}
