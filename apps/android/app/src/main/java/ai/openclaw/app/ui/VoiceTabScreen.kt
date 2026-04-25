package ai.openclaw.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.openclaw.app.MainViewModel
import ai.openclaw.app.voice.VoiceConversationEntry
import ai.openclaw.app.voice.VoiceConversationRole
import java.util.Locale
import kotlin.math.max

@Composable
fun VoiceTabScreen(viewModel: MainViewModel) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val activity = remember(context) { context.findActivity() }
  val listState = rememberLazyListState()

  val gatewayStatus by viewModel.statusText.collectAsState()
  val micEnabled by viewModel.micEnabled.collectAsState()
  val micCooldown by viewModel.micCooldown.collectAsState()
  val speakerEnabled by viewModel.speakerEnabled.collectAsState()
  val micStatusText by viewModel.micStatusText.collectAsState()
  val micLiveTranscript by viewModel.micLiveTranscript.collectAsState()
  val micQueuedMessages by viewModel.micQueuedMessages.collectAsState()
  val micConversation by viewModel.micConversation.collectAsState()
  val micInputLevel by viewModel.micInputLevel.collectAsState()
  val micIsSending by viewModel.micIsSending.collectAsState()
  val micSpeechDetected by viewModel.micSpeechDetected.collectAsState()
  val micDiagnosticsText by viewModel.micDiagnosticsText.collectAsState()

  var showVoiceDiagnostics by remember { mutableStateOf(false) }
  var speechDialogActive by remember { mutableStateOf(false) }

  val hasStreamingAssistant = micConversation.any { it.role == VoiceConversationRole.Assistant && it.isStreaming }
  val showThinkingBubble = micIsSending && !hasStreamingAssistant

  var hasMicPermission by remember { mutableStateOf(context.hasRecordAudioPermission()) }
  var pendingMicEnable by remember { mutableStateOf(false) }

  DisposableEffect(lifecycleOwner, context) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
          hasMicPermission = context.hasRecordAudioPermission()
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      // Stop TTS when leaving the voice screen
      viewModel.setVoiceScreenActive(false)
    }
  }

  val speechLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      speechDialogActive = false
      val items = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
      val text = items.firstOrNull()?.trim().orEmpty()
      if (result.resultCode == Activity.RESULT_OK && text.isNotEmpty()) {
        viewModel.submitVoiceTranscript(text)
      }
    }

  val requestMicPermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      hasMicPermission = granted
      if (granted && pendingMicEnable) {
        val localeTag = Locale.getDefault().toLanguageTag().ifBlank { "zh-CN" }
        val intent =
          Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话…")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
          }
        speechDialogActive = true
        speechLauncher.launch(intent)
      }
      pendingMicEnable = false
    }

  LaunchedEffect(micConversation.size, showThinkingBubble) {
    val total = micConversation.size + if (showThinkingBubble) 1 else 0
    if (total > 0) {
      listState.animateScrollToItem(total - 1)
    }
  }

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .background(mobileBackgroundGradient)
        .imePadding()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        .padding(horizontal = 20.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxWidth().weight(1f),
      contentPadding = PaddingValues(vertical = 4.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (micConversation.isEmpty() && !showThinkingBubble) {
        item {
          Box(
            modifier = Modifier.fillParentMaxHeight().fillMaxWidth(),
            contentAlignment = Alignment.Center,
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = mobileTextTertiary,
              )
              Text(
                "Tap the mic to start",
                style = mobileHeadline,
                color = mobileTextSecondary,
              )
              Text(
                "Each pause sends a turn automatically.",
                style = mobileCallout,
                color = mobileTextTertiary,
              )
            }
          }
        }
      }

      items(items = micConversation, key = { it.id }) { entry ->
        VoiceTurnBubble(entry = entry)
      }

      if (showThinkingBubble) {
        item {
          VoiceThinkingBubble()
        }
      }
    }

    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      if (!micLiveTranscript.isNullOrBlank()) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          color = mobileAccentSoft,
          border = BorderStroke(1.dp, mobileAccent.copy(alpha = 0.2f)),
        ) {
          Text(
            micLiveTranscript!!.trim(),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = mobileCallout,
            color = mobileText,
          )
        }
      }

      // Mic button with input-reactive ring + speaker toggle
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Speaker toggle
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
          IconButton(
            onClick = { viewModel.setSpeakerEnabled(!speakerEnabled) },
            modifier = Modifier.size(48.dp),
            colors =
              IconButtonDefaults.iconButtonColors(
                containerColor = if (speakerEnabled) mobileSurface else mobileDangerSoft,
              ),
          ) {
            Icon(
              imageVector = if (speakerEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
              contentDescription = if (speakerEnabled) "Mute speaker" else "Unmute speaker",
              modifier = Modifier.size(22.dp),
              tint = if (speakerEnabled) mobileTextSecondary else mobileDanger,
            )
          }
          Text(
            if (speakerEnabled) "Speaker" else "Muted",
            style = mobileCaption2,
            color = if (speakerEnabled) mobileTextTertiary else mobileDanger,
          )
        }

        // Dynamic pulse ring + mic button.
        // Pulses respond to both live input level and a breathing beat, so users can tell
        // the mic is alive even when level callbacks are sparse on some devices.
        Box(
          modifier = Modifier.padding(horizontal = 16.dp).size(90.dp),
          contentAlignment = Alignment.Center,
        ) {
          VoiceMicPulse(
            modifier = Modifier.fillMaxSize(),
            isActive = speechDialogActive || (micEnabled && micSpeechDetected),
            level = micInputLevel,
          )
          Button(
            onClick = {
              if (micCooldown || speechDialogActive) return@Button
              viewModel.setMicEnabled(false)
              if (hasMicPermission) {
                val localeTag = Locale.getDefault().toLanguageTag().ifBlank { "zh-CN" }
                val intent =
                  Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话…")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                  }
                speechDialogActive = true
                speechLauncher.launch(intent)
              } else {
                pendingMicEnable = true
                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
              }
            },
            enabled = !micCooldown,
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(60.dp),
            colors =
              ButtonDefaults.buttonColors(
                containerColor = if (micCooldown) mobileTextSecondary else if (speechDialogActive) mobileDanger else mobileAccent,
                contentColor = Color.White,
                disabledContainerColor = mobileTextSecondary,
                disabledContentColor = Color.White.copy(alpha = 0.5f),
              ),
          ) {
            Icon(
              imageVector = if (speechDialogActive) Icons.Default.MicOff else Icons.Default.Mic,
              contentDescription = if (speechDialogActive) "Speech recognition in progress" else "Tap to speak",
              modifier =
                Modifier
                  .size(24.dp)
                  .graphicsLayer {
                    val bump = if (speechDialogActive || (micEnabled && micSpeechDetected)) (1f + micInputLevel.coerceIn(0f, 1f) * 0.18f) else 1f
                    scaleX = bump
                    scaleY = bump
                  },
            )
          }
        }

        // Invisible spacer to balance the row (matches speaker column width)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Box(modifier = Modifier.size(48.dp))
          Spacer(modifier = Modifier.height(4.dp))
          Text("", style = mobileCaption2)
        }
      }

      // Status + labels
      val queueCount = micQueuedMessages.size
      val stateText =
        when {
          queueCount > 0 -> "$queueCount queued"
          micIsSending -> "Sending"
          micCooldown -> "Cooldown"
          speechDialogActive -> "Listening (system dialog)"
          micEnabled && micSpeechDetected -> "Hearing voice"
          micEnabled && micStatusText.isNotBlank() -> micStatusText
          micEnabled -> "Listening"
          else -> "Mic off"
        }
      val stateColor =
        when {
          speechDialogActive -> mobileAccent
          micEnabled && micSpeechDetected -> mobileAccent
          micEnabled -> mobileSuccess
          micIsSending -> mobileAccent
          else -> mobileTextSecondary
        }
      Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (micEnabled) mobileSuccessSoft else mobileSurface,
        border = BorderStroke(1.dp, if (micEnabled) mobileSuccess.copy(alpha = 0.3f) else mobileBorder),
      ) {
        Text(
          "$gatewayStatus · $stateText",
          style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
          color = stateColor,
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
      }

      Button(
        onClick = { showVoiceDiagnostics = !showVoiceDiagnostics },
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = mobileSurface, contentColor = mobileTextSecondary),
      ) {
        Text(if (showVoiceDiagnostics) "Hide voice diagnostics" else "Show voice diagnostics", style = mobileCaption1)
      }

      if (showVoiceDiagnostics) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp),
          color = mobileCardSurface,
          border = BorderStroke(1.dp, mobileBorderStrong),
        ) {
          Text(
            micDiagnosticsText,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = mobileCaption1,
            color = mobileTextSecondary,
          )
        }
      }

      if (!hasMicPermission) {
        val showRationale =
          if (activity == null) {
            false
          } else {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
          }
        Text(
          if (showRationale) {
            "Microphone permission is required for voice mode."
          } else {
            "Microphone blocked. Open app settings to enable it."
          },
          style = mobileCaption1,
          color = mobileWarning,
          textAlign = TextAlign.Center,
        )
        Button(
          onClick = { openAppSettings(context) },
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(containerColor = mobileSurfaceStrong, contentColor = mobileText),
        ) {
          Text("Open settings", style = mobileCallout.copy(fontWeight = FontWeight.SemiBold))
        }
      }
    }
  }
}

@Composable
private fun VoiceTurnBubble(entry: VoiceConversationEntry) {
  val isUser = entry.role == VoiceConversationRole.User
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth(0.90f),
      shape = RoundedCornerShape(12.dp),
      color = if (isUser) mobileAccentSoft else mobileCardSurface,
      border = BorderStroke(1.dp, if (isUser) mobileAccent else mobileBorderStrong),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Text(
          if (isUser) "You" else "OpenClaw",
          style = mobileCaption2.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
          color = if (isUser) mobileAccent else mobileTextSecondary,
        )
        Text(
          if (entry.isStreaming && entry.text.isBlank()) "Listening response…" else entry.text,
          style = mobileCallout,
          color = mobileText,
        )
      }
    }
  }
}

@Composable
private fun VoiceMicPulse(
  modifier: Modifier = Modifier,
  isActive: Boolean,
  level: Float,
) {
  val pulse = rememberInfiniteTransition(label = "voiceMicPulse")
  val beat by
    pulse.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(animation = tween(durationMillis = 900), repeatMode = RepeatMode.Restart),
      label = "voiceMicPulseBeat",
    )

  val clampedLevel = level.coerceIn(0f, 1f)
  val visualLevel = if (isActive) max(clampedLevel, 0.06f) else 0f

  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    repeat(3) { index ->
      val phase = (beat + index * 0.24f) % 1f
      val scale = if (isActive) 0.72f + phase * (0.78f + visualLevel * 0.46f) else 0.74f
      val alpha = if (isActive) (1f - phase) * (0.10f + visualLevel * 0.30f) else 0f
      Box(
        modifier =
          Modifier
            .size(70.dp)
            .graphicsLayer {
              scaleX = scale
              scaleY = scale
            }
            .background(mobileAccent.copy(alpha = alpha.coerceIn(0f, 0.42f)), CircleShape),
      )
    }
  }
}

@Composable
private fun VoiceThinkingBubble() {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
    Surface(
      modifier = Modifier.fillMaxWidth(0.68f),
      shape = RoundedCornerShape(12.dp),
      color = mobileCardSurface,
      border = BorderStroke(1.dp, mobileBorderStrong),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ThinkingDots(color = mobileTextSecondary)
        Text("OpenClaw is thinking…", style = mobileCallout, color = mobileTextSecondary)
      }
    }
  }
}

@Composable
private fun ThinkingDots(color: Color) {
  Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
    ThinkingDot(alpha = 0.38f, color = color)
    ThinkingDot(alpha = 0.62f, color = color)
    ThinkingDot(alpha = 0.90f, color = color)
  }
}

@Composable
private fun ThinkingDot(alpha: Float, color: Color) {
  Surface(
    modifier = Modifier.size(6.dp).alpha(alpha),
    shape = CircleShape,
    color = color,
  ) {}
}

private fun Context.hasRecordAudioPermission(): Boolean {
  return (
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED
    )
}

private fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }

private fun openAppSettings(context: Context) {
  val intent =
    Intent(
      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
      Uri.fromParts("package", context.packageName, null),
    )
  context.startActivity(intent)
}
