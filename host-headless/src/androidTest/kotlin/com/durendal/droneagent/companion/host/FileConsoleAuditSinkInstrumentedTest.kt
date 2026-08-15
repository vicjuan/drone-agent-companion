package com.durendal.droneagent.companion.host

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.durendal.droneagent.companion.console.server.FileConsoleAuditSink
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileConsoleAuditSinkInstrumentedTest {
    @Test
    fun auditSinkUsesAndroidSupportedPosixViewAndOwnerOnlyPermissions() {
        val context =
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .createDeviceProtectedStorageContext()
        val directory = File(context.cacheDir, "console-audit-instrumentation")
        check(directory.mkdirs() || directory.isDirectory)
        val path = File(directory, "events.jsonl").toPath()
        Files.deleteIfExists(path)

        try {
            FileConsoleAuditSink(path).close()

            val view =
                Files.getFileAttributeView(
                    path,
                    PosixFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            assertNotNull("Android app-private storage must expose the POSIX view", view)
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                checkNotNull(view).readAttributes().permissions(),
            )
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
