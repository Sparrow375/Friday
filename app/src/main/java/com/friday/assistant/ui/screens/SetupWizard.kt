package com.friday.assistant.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.with
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.friday.assistant.ui.theme.FridayTheme
import com.friday.assistant.ui.theme.NeonBlue
import com.friday.assistant.ui.theme.NeonCyan
import com.friday.assistant.ui.theme.ObsidianDark
import com.friday.assistant.ui.theme.SilverText

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun SetupWizard(
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    var currentStep by remember { mutableStateOf(1) }

    FridayTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = ObsidianDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Indicator
                Row(
                    modifier = Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(3) { index ->
                        val active = index + 1 == currentStep
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .height(4.dp)
                                .background(
                                    if (active) NeonBlue else Color.Gray.copy(alpha = 0.4f),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }
                }

                // Main Content Slider
                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = {
                        if (targetState > initialState) {
                            slideInHorizontally { it } with slideOutHorizontally { -it }
                        } else {
                            slideInHorizontally { -it } with slideOutHorizontally { it }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { step ->
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when (step) {
                            1 -> StepWelcome()
                            2 -> StepPermissions(context)
                            3 -> StepModels(onFinished)
                        }
                    }
                }

                // Bottom Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (currentStep > 1) {
                        TextButton(
                            onClick = { currentStep-- },
                            colors = ButtonDefaults.textButtonColors(contentColor = SilverText)
                        ) {
                            Text("Back", fontSize = 16.sp)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(48.dp))
                    }

                    if (currentStep < 3) {
                        Button(
                            onClick = { currentStep++ },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
                        ) {
                            Text("Next", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StepWelcome() {
    Text(
        text = "WELCOME TO FRIDAY",
        style = MaterialTheme.typography.displayLarge,
        color = NeonBlue,
        fontWeight = FontWeight.ExtraBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(bottom = 16.dp)
    )
    Text(
        text = "Your 100% offline, private, on-device AI voice agent.",
        style = MaterialTheme.typography.bodyLarge,
        color = SilverText,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
fun StepPermissions(context: Context) {
    Text(
        text = "GRANT PERMISSIONS",
        style = MaterialTheme.typography.headlineMedium,
        color = NeonCyan,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(bottom = 16.dp)
    )
    Text(
        text = "Friday requires specific access to run in the background, draw the overlay bubble, and capture speech inputs.",
        style = MaterialTheme.typography.bodyLarge,
        color = SilverText,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
    )

    Button(
        onClick = {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        },
        colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
    ) {
        Text("Grant Overlay Permission", color = Color.Black, fontWeight = FontWeight.Bold)
    }

    Spacer(modifier = Modifier.height(12.dp))

    Button(
        onClick = {
            val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            context.startActivity(intent)
        },
        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
    ) {
        Text("Set Default Digital Assistant", color = Color.Black, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StepModels(onFinished: () -> Unit) {
    Text(
        text = "VERIFY BRAIN FILES",
        style = MaterialTheme.typography.headlineMedium,
        color = NeonBlue,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(bottom = 16.dp)
    )
    Text(
        text = "Ensure you copy your Qwen2.5 GGUF and Whisper BIN files into the app's files directory so the offline brains can initialize.",
        style = MaterialTheme.typography.bodyLarge,
        color = SilverText,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
    )

    Button(
        onClick = onFinished,
        colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
    ) {
        Text("Get Started", color = Color.Black, fontWeight = FontWeight.Bold)
    }
}
