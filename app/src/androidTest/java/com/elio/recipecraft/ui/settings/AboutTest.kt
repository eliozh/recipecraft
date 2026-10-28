package com.elio.recipecraft.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elio.recipecraft.ui.theme.RecipeCraftTheme
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun setAbout(locale: Locale, onShowNotice: () -> Unit = {}) {
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val localized = context.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config) {
                RecipeCraftTheme {
                    Surface {
                        About(
                            versionName = "1.2.3",
                            onShowNotice = onShowNotice,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun englishShowsVersionDisclaimerAndAttribution() {
        var noticeRequested = false
        setAbout(Locale.ENGLISH) { noticeRequested = true }

        compose.onNodeWithText("About").assertIsDisplayed()
        compose.onNodeWithText("Version 1.2.3").assertIsDisplayed()
        compose.onNodeWithText("Calories are approximate and not medical advice.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("USDA FoodData Central", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Nutrition tables you import stay on this device.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("API keys are stored encrypted", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Full data notice").performScrollTo().performClick()
        assertTrue(noticeRequested)
    }

    @Test
    fun chineseShowsVersionDisclaimerAndAttribution() {
        setAbout(Locale.SIMPLIFIED_CHINESE)

        compose.onNodeWithText("关于").assertIsDisplayed()
        compose.onNodeWithText("版本 1.2.3").assertIsDisplayed()
        compose.onNodeWithText("热量为估算值，不构成医疗建议。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("USDA FoodData Central", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("你导入的营养成分表只保存在本设备上。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("API 密钥加密保存在本设备上", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("完整数据声明").performScrollTo().assertIsDisplayed()
    }
}
