package com.samge.bitrans

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samge.bitrans.translate.TranslateConfig
import com.samge.bitrans.ui.BiTransApp
import com.samge.bitrans.ui.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // restore the overlay on cold start if it was enabled in settings
        // (the service dies with the process; the user expects it back)
        if (TranslateConfig.overlayEnabled(this)) {
            com.samge.bitrans.overlay.OverlayService.start(this)
        }
        setContent {
            com.samge.bitrans.ui.theme.BiTransTheme {
                val vm: MainViewModel = viewModel()
                BiTransApp(vm)
            }
        }
    }
}
