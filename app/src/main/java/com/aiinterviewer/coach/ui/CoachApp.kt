package com.aiinterviewer.coach.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aiinterviewer.coach.CoachController
import com.aiinterviewer.coach.data.CvImporter
import com.aiinterviewer.coach.data.QaTurn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TAB_TITLES = listOf("My CV", "Practice", "History", "Settings")

@Composable
fun CoachApp(controller: CoachController) {
    var tab by remember { mutableStateOf(0) }
    val context = LocalContext.current

    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            controller.startListening()
        } else {
            controller.reportError("Microphone permission is required to listen for questions.")
        }
    }

    fun toggleListening() {
        if (controller.listening) {
            controller.stopListening()
            return
        }
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            controller.startListening()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Header()
            TabRow(selectedTabIndex = tab) {
                TAB_TITLES.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) },
                    )
                }
            }
            if (controller.error.isNotBlank()) {
                ErrorBanner(controller.error, controller::dismissError)
            }
            when (tab) {
                0 -> CvTab(controller)
                1 -> PracticeTab(controller, ::toggleListening)
                2 -> HistoryTab(controller)
                else -> SettingsTab(controller)
            }
        }
    }
}

@Composable
private fun Header() {
    Surface(color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "Interview Coach",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Rehearse answers built from your own CV",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun BusyRow(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------------------- CV

@Composable
private fun CvTab(controller: CoachController) {
    val context = LocalContext.current
    var pasted by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            controller.importCv(uri, CvImporter.displayName(context, uri))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val cv = controller.settings.cvText

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (cv.isBlank()) "No CV saved" else "CV saved",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (cv.isBlank()) {
                    Text(
                        text = "Upload a PDF and the model reads it directly, or paste the text below.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        text = "${controller.settings.cvSourceName.ifBlank { "CV" }} - ${cv.length} characters",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Button(
            onClick = { picker.launch(arrayOf("application/pdf", "text/*")) },
            enabled = !controller.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Upload CV (PDF or text)")
        }

        OutlinedTextField(
            value = pasted,
            onValueChange = { pasted = it },
            label = { Text("Or paste your CV text") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                controller.savePastedCv(pasted)
                pasted = ""
            },
            enabled = pasted.isNotBlank() && !controller.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save pasted CV")
        }

        HorizontalDivider()

        OutlinedTextField(
            value = controller.settings.jobContext,
            onValueChange = controller::updateJobContext,
            label = { Text("Target role or job advert (optional)") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        if (controller.busy) {
            BusyRow(controller.status.ifBlank { "Working" })
        }

        if (cv.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "What the coach will use",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = cv,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            TextButton(onClick = controller::clearCv) { Text("Remove CV") }
        }
    }
}

// ---------------------------------------------------------------------- practice

@Composable
private fun PracticeTab(controller: CoachController, onToggleListening: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = controller.status.ifBlank { "Press Listen, speak the question, then press Answer." },
            style = MaterialTheme.typography.labelLarge,
        )

        // Step 1: listen. While this runs, the question field below fills itself in.
        Button(
            onClick = onToggleListening,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (controller.listening) "1. Stop listening" else "1. Listen for a question")
        }

        if (controller.listening) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Typing what it hears...",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Step 2: the question, typed by the microphone or by hand. Editable either way,
        // so a misheard word can be fixed before it is answered.
        OutlinedTextField(
            value = controller.questionText,
            onValueChange = controller::setQuestion,
            label = { Text("2. Question") },
            placeholder = { Text("Speak it above, or type it here") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { controller.ask(controller.questionText) },
                enabled = controller.questionText.isNotBlank() && !controller.busy,
                modifier = Modifier.weight(1f),
            ) {
                Text("3. Answer")
            }

            if (controller.busy) {
                FilledTonalButton(
                    onClick = controller::cancelAnswer,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Stop")
                }
            } else {
                FilledTonalButton(
                    onClick = controller::clearQuestion,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Clear")
                }
            }
        }

        if (controller.lastQuestion.isNotBlank()) {
            Text("Answered", style = MaterialTheme.typography.titleSmall)
            Text(controller.lastQuestion, style = MaterialTheme.typography.bodyMedium)
        }

        when {
            controller.answer.isNotBlank() -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Answer", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(controller.answer, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            controller.busy -> BusyRow("Waiting for the first words")
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ----------------------------------------------------------------------- history

@Composable
private fun HistoryTab(controller: CoachController) {
    val formatter = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }

    if (controller.history.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No answers saved yet.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(controller.history) { turn: QaTurn ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = formatter.format(Date(turn.createdAt)),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(turn.question, style = MaterialTheme.typography.titleSmall)
                        Text(turn.answer, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { controller.recallTurn(turn) }) {
                            Text("Load this answer")
                        }
                    }
                }
            }
        }
        TextButton(
            onClick = controller::clearHistory,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text("Clear history")
        }
    }
}

// ---------------------------------------------------------------------- settings

@Composable
private fun SettingsTab(controller: CoachController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = controller.settings.apiKey,
            onValueChange = controller::updateApiKey,
            label = { Text("DeepSeek API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = controller.settings.model,
            onValueChange = controller::updateModel,
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "Thinking mode is switched off, so the answer starts arriving sooner.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        Text("Cost", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Flash costs about \$0.15 per million input tokens and \$0.60 per " +
                "million output tokens off-peak, and about a fiftieth of that for cached " +
                "input. A practice session costs a fraction of a cent.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        Text("Where your data goes", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Your CV and each question are sent to DeepSeek to produce an answer. " +
                "The CV and the API key are stored only on this phone, inside the app's " +
                "private storage, and are never written anywhere else by this app.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
