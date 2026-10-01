# Interview Coach

An Android app that rehearses interview answers built from your own CV.

You upload your CV once, the app turns it into a profile, and then you speak (or type)
interview questions. The answer is written back to you in your own voice, grounded in
your real experience, streaming in as it is generated.

## What this is, and what it is not

This is a **practice tool**. You speak questions out loud, it answers, you read the
answer, you keep the good ones and drill them.

It is deliberately **not** built for use during a live interview. There is no invisible
overlay, no hidden screen, no earpiece-only audio routing and no attempt to conceal the
app. That was a decision, not an oversight.

Worth knowing before you build: recording an interviewer without their consent is a
criminal offence in many jurisdictions, and employers increasingly treat this as a
hiring fraud matter. Practising with this app is legal and useful. Running it during a
real interview is the thing that gets people blacklisted.

## Requirements

- **Android Studio** (latest). This is not currently installed on this machine.
- **JDK 17** — bundled with Android Studio.
- A **DeepSeek API key** from <https://platform.deepseek.com> with some credit on it.
- A phone running **Android 8.0 (API 26)** or newer.

## Setup

### 1. Get the API key

Sign in at <https://platform.deepseek.com>, create an API key, and top up a small
amount of credit. A few dollars is far more than you will use.

### 2. Open the project

1. Install Android Studio.
2. **File > Open** and choose this folder: `C:\Users\HP\Desktop\Ai interviewer`
3. Let the Gradle sync finish. It downloads Gradle 9.6 and the Android SDK the first
   time, which takes a while.

**If Android Studio complains that the Gradle wrapper is missing:** it normally offers
to create one, so accept that prompt. If it does not, create an empty project anywhere
else with the same Studio version and copy `gradlew`, `gradlew.bat` and
`gradle/wrapper/gradle-wrapper.jar` from that project into this folder.

**If a dependency version fails to resolve:** put the cursor on the red line and press
**Alt+Enter**, then accept the suggested version. The only version I pinned by hand
without being able to test it is `androidx.activity:activity-compose:1.11.0` in
`app/build.gradle.kts`.

### 3. Build the APK

**Option A - build in the cloud, nothing to install (recommended).**

A workflow at `.github/workflows/build-apk.yml` builds the APK on GitHub's servers.

1. Push this folder to a private GitHub repository. Either use git, or drag the folder
   into the "uploading an existing file" page on github.com. Keep it **private**.
2. Open the repository's **Actions** tab. The build starts automatically; if not, press
   **Build APK** then **Run workflow**.
3. Wait for the green tick, then open the run and download the **app-debug-apk**
   artifact. That is a zip containing `app-debug.apk`.
4. Unzip it and copy `app-debug.apk` to your phone.

Nothing needs to be installed locally for this. Do not commit your API key or your CV;
the key is entered inside the app, so it is never part of the repository.

**Option B - Android Studio.**

Open the folder in Android Studio, let it sync (it generates the Gradle wrapper), then:

```powershell
.\gradlew.bat assembleDebug
```

The APK lands at `app\build\outputs\apk\debug\app-debug.apk`.

**Option C - command line only, no Studio.**

Requires a JDK 17, the Android SDK command line tools, and Gradle 9.6 installed
separately. Smaller download than Studio, but three separate installs and a
`local.properties` file pointing at the SDK.

### 4. Install it on your phone

Either copy `app-debug.apk` to the phone and tap it, or plug the phone in with USB
debugging on and run:

```powershell
.\gradlew.bat installDebug
```

When opening the APK on the phone for the first time, Android will ask you to allow
installing from this source. That is expected for any app not installed from the Play
Store.

### 5. First run

1. **Settings** tab: paste the API key. Tap off the field to let it save.
2. **My CV** tab: tap **Upload CV** and pick your CV PDF, or paste the text. Then
   optionally paste the job advert into the target role box.
3. **Practice** tab, then follow the three numbered steps on screen:
   - **1. Listen for a question** - grant the microphone permission the first time. The
     question field types itself out as you speak.
   - **2. Question** - read it. Fix any misheard word; the field is yours to edit.
   - **3. Answer** - only now does it ask DeepSeek. The answer streams in underneath.

Nothing is answered automatically. The microphone only fills the box and then stops;
generating the answer is always a deliberate press, which is what gives you the chance
to correct the transcript first.

## Design notes

**Thinking mode is off.** The request sends `"thinking": {"type": "disabled"}`. Thinking
mode generates hidden tokens before the first visible one, which would add seconds to
every answer. If a future API revision rejects that field, the client retries the same
request without it rather than failing.

**The CV sits at the top of the system prompt and never changes.** DeepSeek caches
identical prompt prefixes, and cached input is roughly fifty times cheaper than
uncached input and skips prefix processing entirely. So the CV is written first and the
question is the only thing that varies.

**Answering is manual.** The microphone fills an editable question field and stops
there. DeepSeek is called only when the user presses Answer. That ordering exists so a
misheard word can be corrected before it is answered, and so nothing is ever sent
without a deliberate press.

**No PDF library.** `CvImporter` renders PDF pages to JPEG with Android's built-in
`PdfRenderer` and sends them to DeepSeek Flash, which accepts images. The model returns
the CV as text. That removed an entire third-party dependency. Word documents are not
supported — export to PDF first.

**No HTTP or JSON library.** `DeepSeekClient` uses `HttpURLConnection` and Android's
built-in `org.json`. Fewer dependencies means fewer ways for a first build to fail.

**Cancelling actually cancels.** Coroutine cancellation cannot interrupt a blocking
socket read, so `DeepSeekClient.abort()` disconnects the socket. Without it, pressing
stop would leave the request running and still billing.

## Measured latency

The manual flow takes speech recognition off the clock entirely. By the time you press
Answer the transcript is already sitting on screen, so the only remaining wait is the
round trip to DeepSeek.

Measured on this machine, 1 October 2026, with `tools/api_check.py`:

| Stage | Measured |
|---|---|
| Press Answer to first token | 1.31 s |
| Full answer, 634 characters | 2.09 s |

That is slower than the 0.3 - 0.8 s the model itself needs; the difference is network
latency between here and the API. On mobile data expect a little more. It is still a
complete answer in about two seconds, with none of the dead air that the automatic
version would have produced during the question itself.

For reference, the earlier automatic design would have added silence detection and
speech recognition in front of that, roughly 0.8 - 2.5 s more.

## Files

```
app/src/main/java/com/aiinterviewer/coach/
  MainActivity.kt              entry point
  CoachController.kt           all state, CV import, question pipeline
  data/
    AppStore.kt                JSON-file persistence
    CvImporter.kt              PDF pages to JPEG, or read text directly
    DeepSeekClient.kt          streaming SSE client, no dependencies
    Prompt.kt                  the extraction and coaching prompts
  speech/
    QuestionListener.kt        SpeechRecognizer wrapper
  ui/
    CoachTheme.kt              colours
    CoachApp.kt                all four screens
```

## Known limitations

- Answers are saved to history, but there is no export yet.
- The recogniser occasionally delivers the same utterance twice, and there is a 2.5
  second guard against it. Saying the identical question twice within that window may
  only register once; type it in that case.
- If you edit the question while the microphone is still listening, the next words it
  hears are appended to your edit rather than replacing it. Stop listening first if you
  want to rewrite the whole thing.
- No LaTeX or markdown rendering. Answers are plain prose by design, because they are
  meant to be spoken.
- `SpeechRecognizer` quality varies a lot between phones. If recognition is poor, type
  the questions instead — the rest of the pipeline is identical.
