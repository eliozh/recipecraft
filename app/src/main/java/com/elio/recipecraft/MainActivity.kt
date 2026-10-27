package com.elio.recipecraft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.elio.recipecraft.ui.AppNav
import com.elio.recipecraft.ui.library.TranslationHost
import com.elio.recipecraft.ui.theme.RecipeCraftTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RecipeCraftTheme {
                AppNav()
                TranslationHost()
            }
        }
    }
}
