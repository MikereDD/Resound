package com.typezero.resound.core.io

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source characterizations, NOT Compose/provider execution. Keep all live publishers on one path. */
class NamingWiringTest {
    private fun source(path: String): String {
        val root = sequenceOf(File("src/main/kotlin"), File("app/src/main/kotlin"))
            .first { it.isDirectory }
        return File(root, "com/typezero/resound/$path").readText()
    }

    @Test fun everyProductPublicationUsesTheSharedPendingLifecycle() {
        val outputs = source("core/io/Outputs.kt")
        assertTrue(outputs.contains("val (uri, metadata) = publishPendingItem("))
        assertTrue(outputs.contains("put(MediaStore.Audio.Media.IS_PENDING, 1)"))
        assertTrue(outputs.contains("values.put(MediaStore.Audio.Media.IS_PENDING, 0)"))
        assertTrue(source("core/io/Commit.kt").contains("Outputs.publishNamedToMusic(context, copy, \"recording\", \"m4a\", naming)"))
        val editor = source("feature/edit/EditScreen.kt")
        assertEquals(2, Regex("Outputs\\.publishNamedToMusic\\(").findAll(editor).count())
        assertTrue(source("feature/timeline/TimelineScreen.kt").contains("Outputs.publishNamedToMusic(context, temp, \"mix\", \"m4a\", exportNaming)"))
    }

    @Test fun recordingIntentIsCapturedBeforeStartAndRecoveryKeepsDefaultNaming() {
        val editor = source("feature/edit/EditScreen.kt")
        val start = editor.substringAfter("fun startRecording()").substringBefore("fun publishExistingDraft")
        assertTrue(start.indexOf("recordingName = NamingRequest(namingPatterns.recording)") < start.indexOf("recorder.start("))
        assertTrue(editor.contains("publishDraft(context, drafts, admittedFile, capturedNaming)"))
        assertTrue(editor.contains("loadDraftPreview(admittedFile, lease) { publicationStatus }"))
        val recovery = source("core/io/Commit.kt").substringAfter("suspend fun publishDraftIfUnpublished(").substringBefore("/** Null when")
        assertTrue(recovery.contains("publishDraftBlocking(context, store, draft)"))
        assertFalse(recovery.contains("NamingPreferences"))
    }

    @Test fun durationReadsCompletedOutputAndRecentRequiresProviderConfirmedName() {
        val outputs = source("core/io/Outputs.kt")
        assertTrue(outputs.contains("extractor.setDataSource(temp.absolutePath)"))
        assertTrue(outputs.contains("val actualName = metadata?.first"))
        assertFalse(outputs.contains("return Published(uri, \"Music/\$SUBDIR/\$displayName\")"))
        val recent = source("feature/edit/RecentRecordings.kt")
        assertTrue(recent.contains("requireNotNull(publication.actualDisplayName)"))
        assertFalse(recent.contains("publication.displayPath.substringAfterLast"))
    }

    @Test fun optionalPanelsAndConvertPreviewUseTheSharedNamingEngine() {
        val panel = source("ui/components/NamingPanel.kt")
        assertTrue(panel.contains("mutableStateOf(false)"))
        assertTrue(panel.contains("PublicationNaming.resolve("))
        val controls = source("feature/edit/OpControls.kt")
        assertTrue(controls.contains("preview = convertNamingPreview"))
        assertTrue(controls.contains("preview(sel)"))
        assertTrue(source("feature/edit/EditScreen.kt").contains("NamingPreview(namingPatterns.exports, ext)"))
    }
}
