package com.darkxvenom.airbeats.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import com.darkxvenom.airbeats.constants.LiquidGlassKey
import com.darkxvenom.airbeats.utils.rememberPreference
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.remember

val LocalMenuState = compositionLocalOf { MenuState() }

@Stable
class MenuState(
    isVisible: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var isVisible by mutableStateOf(isVisible)
    var content by mutableStateOf(content)

    @OptIn(ExperimentalMaterial3Api::class)
    fun show(content: @Composable ColumnScope.() -> Unit) {
        isVisible = true
        this.content = content
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun dismiss() {
        isVisible = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetMenu(
    modifier: Modifier = Modifier,
    state: MenuState,
    background: Color = MaterialTheme.colorScheme.surface,
) {
    val focusManager = LocalFocusManager.current
    val (enableLiquidGlass) = rememberPreference(LiquidGlassKey, false)
    val isFrosted = isFrostedGlassUiEnabled()
    val backdrop = LocalBackdrop.current
    val layer = rememberGraphicsLayer()
    val luminanceAnimation = remember { Animatable(0.3f) }
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    if (state.isVisible) {
        val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        val containerColor = if (isFrosted || (enableLiquidGlass && backdrop != null)) {
            Color.Transparent
        } else {
            background
        }

        val sheetModifier = if (enableLiquidGlass && !isFrosted && backdrop != null) {
            modifier.fillMaxHeight().drawBackdropCustomShape(
                backdrop = backdrop,
                layer = layer,
                luminanceAnimation = luminanceAnimation.value,
                shape = sheetShape
            )
        } else {
            modifier
        }

        ModalBottomSheet(
            onDismissRequest = {
                focusManager.clearFocus()
                state.isVisible = false
            },
            containerColor = containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            scrimColor = if (isFrosted) Color.Black.copy(alpha = 0.50f) else androidx.compose.material3.BottomSheetDefaults.ScrimColor,
            shape = if (isFrosted) RoundedCornerShape(28.dp) else sheetShape,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                )
            },
            modifier = sheetModifier
        ) {
            if (isFrosted) {
                SettingsGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(28.dp),
                    containerColor = popupGlassContainerColor()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        state.content(this)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    state.content(this)
                }
            }
        }
    }
}




