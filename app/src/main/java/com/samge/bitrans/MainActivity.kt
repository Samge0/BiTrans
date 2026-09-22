package com.samge.bitrans

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samge.bitrans.ui.BiTransApp
import com.samge.bitrans.ui.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            com.samge.bitrans.ui.theme.BiTransTheme {
                val vm: MainViewModel = viewModel()
                val context = LocalContext.current
                BiTransApp(vm)
            }
        }
    }
}
