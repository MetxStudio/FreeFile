package com.freefile.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.freefile.BuildConfig
import com.freefile.MainActivity
import com.freefile.R
import com.freefile.ui.theme.FreeFileTheme
import kotlinx.coroutines.delay

/**
 * 启动页：展示应用图标、自动同步的 versionName 与构建类型。
 *
 * versionName 通过 BuildConfig.VERSION_NAME 读取，修改 app/build.gradle.kts 中的
 * versionName 后无需改动本页；构建类型通过 BuildConfig.BUILD_TYPE 读取。
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启动页沉浸式展示，进入主界面后由 MainActivity 恢复正常系统栏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(
            WindowInsetsCompat.Type.systemBars()
        )

        setContent {
            FreeFileTheme {
                SplashContent(
                    versionName = BuildConfig.VERSION_NAME,
                    buildType = BuildConfig.BUILD_TYPE,
                    onFinish = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun SplashContent(
    versionName: String,
    buildType: String,
    onFinish: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    var exiting by remember { mutableStateOf(false) }

    val alpha by animateFloatAsState(
        targetValue = if (exiting) 0f else if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = if (exiting) 220 else 500),
        label = "splashAlpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.86f,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "splashScale"
    )

    LaunchedEffect(Unit) {
        visible = true
        delay(1100)
        exiting = true
        delay(220)
        onFinish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(156.dp)
                    .alpha(alpha)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.icon),
                    contentDescription = null,
                    modifier = Modifier.size(112.dp)
                )
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text = versionName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.alpha(alpha)
            )

            Spacer(Modifier.height(12.dp))

            BuildTypeBadge(
                buildType = buildType,
                modifier = Modifier.alpha(alpha)
            )
        }
    }
}

@Composable
private fun BuildTypeBadge(
    buildType: String,
    modifier: Modifier = Modifier
) {
    val isDebug = buildType.equals("debug", ignoreCase = true)
    val isDark = isSystemInDarkTheme()
    val badgeColor = when {
        isDebug && isDark -> Color(0xFFFBBF24)
        isDebug -> Color(0xFFB45309)
        isDark -> Color(0xFF4ADE80)
        else -> Color(0xFF15803D)
    }

    Surface(
        modifier = modifier,
        color = badgeColor.copy(alpha = 0.14f),
        contentColor = badgeColor,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = buildType.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
        )
    }
}
