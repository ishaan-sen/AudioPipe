package com.asdfg.soundmaster.audio

import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.asdfg.soundmaster.adb.ShellExecutor
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity

@RunWith(RobolectricTestRunner::class)
class SoundMasterServiceTest {

    private lateinit var controller: org.robolectric.android.controller.ServiceController<SoundMasterService>
    private lateinit var service: SoundMasterService

    @Before
    fun setUp() {
        controller = Robolectric.buildService(SoundMasterService::class.java)
        service = controller.get()
    }

    @After
    fun tearDown() {
        // controller.destroy() // Sometimes causes issues in Robolectric if not careful
    }

    @Test
    fun `test resources are accessible`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val string = context.getString(com.asdfg.soundmaster.R.string.service_notification_text, 0)
        assertNotNull(string)
        println("Resource found: $string")
    }

    @Test
    fun `test service creates successfully`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        println("Context package: ${context.packageName}")
        
        controller.create()
        assertNotNull(service)
    }

    @Test
    fun `test service onCreate initializes components`() {
        controller.create()
        // Check if things were initialized (indirectly via no crash)
        // In a real generic service test, further state inspection is needed.
        // For now, valid onCreate execution without crash is a good baseline.
    }
}
