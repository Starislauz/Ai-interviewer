package com.aiinterviewer.coach

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.aiinterviewer.coach.data.AppStore
import com.aiinterviewer.coach.ui.CoachApp
import com.aiinterviewer.coach.ui.CoachTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val store = AppStore(applicationContext)

        setContent {
            CoachTheme {
                val scope = rememberCoroutineScope()
                // The activity declares configChanges for rotation, so this is not
                // recreated when the phone is turned. In-progress answers survive.
                val controller = remember {
                    CoachController(
                        context = applicationContext,
                        store = store,
                        scope = scope,
                    )
                }
                LaunchedEffect(Unit) { controller.refresh() }
                CoachApp(controller)
            }
        }
    }
}
