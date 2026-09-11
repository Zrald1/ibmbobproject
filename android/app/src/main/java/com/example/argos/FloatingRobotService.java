package com.example.argos;

import java.io.File;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.ForegroundColorSpan;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;

public class FloatingRobotService extends Service {

    private static boolean nativeLoaded = false;
    private boolean serviceInitialized = false;
    private volatile boolean serviceDestroyed = false;

    static {
        try {
            System.loadLibrary("argos");
            nativeLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            android.util.Log.e("ArgosNative", "Native engine unavailable; chat will be disabled", e);
        }
    }

    private WindowManager windowManager;
    private WebView robotWebView;
    private LinearLayout bubbleOverlay;
    private ScrollView convoScroll;
    private LinearLayout convoLayout;
    private EditText inputEdit;
    private ImageButton sendBtn;
    private boolean bubbleVisible = false;
    private boolean chatInProgress = false;

    private WindowManager.LayoutParams robotParams;
    private WindowManager.LayoutParams bubbleParams;
    private int layoutType;

    // Drag state
    private boolean isDragging = false;
    private float dragStartRawX = 0;
    private float dragStartRawY = 0;
    private int dragStartWindowX = 0;
    private int dragStartWindowY = 0;

    // Robot position tracking
    private float robotScreenX = 0;
    private float robotScreenY = 0;
    private float robotSize = 100;
    private float currentScale = 1.0f;
    private int screenWidth = 1080;
    private int screenHeight = 1920;
    // Guard: ignore JS position updates until Java has set the initial
    // centered position, otherwise the robot flashes at (0,0) = top-left.
    private boolean positionInitialized = false;

    // ── Freeze auto-restart ──
    // The JS animation loop sends a liveness heartbeat (JSBridge.onDiag) every
    // few seconds. If those stop arriving while Argos is idle, the WebView is
    // genuinely wedged and gets reloaded.
    private float m_lastPosX = -1f, m_lastPosY = -1f;
    private long m_lastMoveTime = System.currentTimeMillis();
    private long m_lastAliveTime = System.currentTimeMillis();
    private final android.os.Handler m_freezeHandler =
        new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable m_freezeCheck;
    private static final long FREEZE_TIMEOUT_MS = 30000;
    // Keyboard detection
    private boolean keyboardVisible = false;
    private float savedRobotY = 0;
    private int keyboardTopY = 0;  // Y coordinate of the top of the keyboard
    private android.view.ViewTreeObserver.OnGlobalLayoutListener keyboardListener;

    // Awareness overlay — small floating text near robot
    private TextView awarenessLabel;
    private WindowManager.LayoutParams awarenessParams;
    private android.os.Handler awarenessHandler = new android.os.Handler();

    // Backend connection
    private String backendUrl = ""; // e.g. "https://your-backend.example.com"
    private boolean voicePipelineActive = false;
    private boolean isRecording = false;
    private android.media.AudioRecord m_audioRecord = null;

    // Conversation history — sent with each /api/chat request so the AI has
    // context of the ongoing conversation. Without this, every message is
    // treated independently and Argus appears to "stop responding" after the
    // first exchange because it has no memory of what was said.
    private java.util.List<org.json.JSONObject> conversationHistory = new java.util.ArrayList<>();
    private static final int MAX_HISTORY_MESSAGES = 20; // keep last 20 messages to avoid token bloat

    // Pipeline generation counter — incremented each time the pipeline is
    // interrupted. Responses from a stale generation are discarded so that
    // an interrupted request doesn't clobber a newer one.
    private int pipelineGeneration = 0;
    private long lastTapTime = 0;
    // Double-tap window. 300ms was tight enough that a slightly slower first
    // attempt registered as two single taps (toggling the chat bubble) instead
    // of starting voice recording — which is why the first try appeared to do
    // nothing while the second worked. 450ms is a much more forgiving window.
    private static final int DOUBLE_TAP_THRESHOLD_MS = 450;
    private static final int SILENCE_THRESHOLD = 800; // amplitude threshold for silence
    private static final int SILENCE_DURATION_MS = 4000; // 4 seconds of silence auto-stops recording
    private static final int MAX_RECORDING_DURATION_MS = 30000; // max 30 seconds
    // Watchdog: if the voice pipeline (thinking → response) takes longer than
    // this, reset the robot to idle and inform the user so the 3D object is
    // never permanently frozen in the thinking state while waiting on a hung
    // or slow backend.
    private static final int VOICE_PIPELINE_TIMEOUT_MS = 45000; // 45s safety net
    private final android.os.Handler voiceWatchdogHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    // ── Live transcription (real-time partial results on speech bubble) ──
    // Uses Android SpeechRecognizer with EXTRA_PARTIAL_RESULTS to show the user
    // what they're saying in real-time on the speech bubble, instead of waiting
    // for the backend to process the entire recording after it stops.
    private android.speech.SpeechRecognizer liveTranscriptRecognizer;
    private android.speech.RecognitionListener liveTranscriptListener;
    private String liveTranscriptFinal = "";   // accumulated final segments
    private String liveTranscriptPartial = "";  // current partial segment
    private boolean liveTranscriptActive = false;
    private boolean liveTranscriptAvailable = true; // false if SpeechRecognizer unsupported
    // Signal used to wait for onResults/onError to fire after stopListening(),
    // so getLiveTranscriptText() captures the final segment instead of racing.
    private java.util.concurrent.CountDownLatch liveTranscriptDoneSignal = null;

    // Scheduled tasks
    private static int taskCounter = 1000;
    private java.util.List<String> scheduledTaskList = new java.util.ArrayList<>();
    private boolean standbyMode = false;
    // ── Wake word detection ("Argos") ──
    // Uses Android SpeechRecognizer for hands-free voice activation.
    // When the user says "Argos", voice recording starts automatically.
    private android.speech.RecognitionListener wakeWordListener;
    private android.speech.SpeechRecognizer wakeWordRecognizer;
    private boolean wakeWordListening = false;
    private boolean wakeWordEnabled = true;
    private final android.os.Handler wakeWordHandler = new android.os.Handler();
    // Restart delay after a wake-word recognizer error. The SpeechRecognizer
    // times out after a few seconds of silence, so this loops forever; 1s was
    // aggressive enough that the constant stop/start binder traffic to the
    // recognition service showed up as main-thread jank (the 3D robot
    // stuttering). 3s keeps it responsive without the churn.
    private static final int WAKE_WORD_RESTART_DELAY_MS = 3000;
    private static final String WAKE_WORD = "argos";
    // Argos file manager — dedicated folder for AI-created notes/files
    private ArgosFileManager fileManager;
    // Hand tracking camera — lets user grab/drag the 3D robot with their hand
    private HandTrackingCamera handTrackingCamera;
    private View longPressMenu;
    private WindowManager.LayoutParams longPressMenuParams;
    private View longPressDismissOverlay;
    private WindowManager.LayoutParams longPressDismissParams;
    private LinearLayout tasksOverlay;
    private ScrollView tasksScroll;
    private WindowManager.LayoutParams tasksParams;
    private LinearLayout privacyOverlay;
    private WindowManager.LayoutParams privacyParams;
    private LinearLayout settingsOverlay;
    private WindowManager.LayoutParams settingsParams;

    // Thought bubble — periodic AI-generated comment near robot
    private TextView thoughtBubble;
    private WindowManager.LayoutParams thoughtParams;
    private android.os.Handler thoughtHandler = new android.os.Handler();
    private Runnable thoughtRunnable;
    private static final int THOUGHT_INTERVAL_DEFAULT_MS = 60000; // default 60 seconds
    private static final int THOUGHT_INTERVAL_MIN_MS = 10000;    // min 10 seconds
    private static final int THOUGHT_INTERVAL_MAX_MS = 120000;   // max 2 minutes
    private int thoughtIntervalMs = THOUGHT_INTERVAL_DEFAULT_MS; // adjustable by user
    private static final int THOUGHT_DISPLAY_MS = 8000;   // show for 8 seconds
    private String lastThoughtApp = "";
    private int thoughtCount = 0;

    private static FloatingRobotService instance;

    // Screen state — track whether the screen is on so we don't show
    // speech bubbles or talk when the phone is asleep / in pocket
    private boolean screenOn = true;
    private android.content.BroadcastReceiver screenReceiver;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        serviceDestroyed = false;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Load backend URL from manifest metadata
        try {
            android.content.pm.ServiceInfo info = getPackageManager().getServiceInfo(
                new android.content.ComponentName(this, FloatingRobotService.class),
                android.content.pm.PackageManager.GET_META_DATA);
            if (info.metaData != null) {
                String url = info.metaData.getString("argos.backend_url");
                if (url != null && !url.isEmpty()) {
                    setBackendUrl(url);
                }
            }
        } catch (Exception e) {
            android.util.Log.w("ArgosBackend", "Could not load backend URL: " + e.getMessage());
        }

        // Load saved tasks from local storage
        loadTasksLocally();

        // Initialize the Argos file manager (dedicated Documents/Argos folder)
        fileManager = new ArgosFileManager(this);
        // Sync scheduled tasks to the folder so the user can view them
        fileManager.saveScheduledTasks(scheduledTaskList);

        // Register screen on/off receiver to detect when the phone is asleep
        screenOn = true;
        screenReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction() != null) {
                    if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                        screenOn = false;
                        // Immediately hide any visible thought bubble
                        if (thoughtBubble != null && thoughtBubble.getParent() != null) {
                            try { windowManager.removeView(thoughtBubble); } catch (Exception e) {}
                        }
                        // Stop TTS if speaking — don't talk while screen is off
                        if (m_tts != null && m_tts.isSpeaking()) {
                            m_tts.stop();
                        }
                        // Stop wake word detection while screen is off (battery)
                        if (wakeWordRecognizer != null) {
                            try { wakeWordRecognizer.cancel(); } catch (Exception e) {}
                            wakeWordListening = false;
                        }
                        // Tell the JS robot to stop moving (go idle)
                        if (robotWebView != null) {
                            robotWebView.evaluateJavascript(
                                "if(window.ArgosJS){ArgosJS.setStandby(true);}", null);
                        }
                        android.util.Log.i("Argos", "Screen OFF — suppressing speech bubbles, TTS, and movement");
                    } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                        screenOn = true;
                        // If user wasn't in standby mode, resume robot movement
                        if (!standbyMode && robotWebView != null) {
                            robotWebView.evaluateJavascript(
                                "if(window.ArgosJS){ArgosJS.setStandby(false);}", null);
                        }
                        // Resume wake word detection
                        if (wakeWordEnabled && !wakeWordListening && !isRecording && !voicePipelineActive) {
                            wakeWordHandler.postDelayed(() -> startWakeWordDetection(), 1000);
                        }
                        android.util.Log.i("Argos", "Screen ON — resuming normal behavior");
                    }
                }
            }
        };
        android.content.IntentFilter screenFilter = new android.content.IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, screenFilter);

        // Start wake word detection — listens for "Argos" to trigger voice recording
        // Delayed start to let the service fully initialize
        wakeWordHandler.postDelayed(() -> startWakeWordDetection(), 3000);

    }

    private void createFloatingWindow() {
        DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        // Robot size — 22% of screen width (bigger, pure head design)
        robotSize = screenWidth * 0.22f;
        // Window size — square-ish around head: ~1.4x robot size wide, ~1.3x tall
        int robotWindowWidth = (int) (robotSize * 1.4f);
        int robotWindowHeight = (int) (robotSize * 1.3f);

        // WebView for Three.js robot rendering
        robotWebView = new WebView(this);
        robotWebView.setBackgroundColor(0x00000000);
        robotWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        robotWebView.getSettings().setJavaScriptEnabled(true);
        robotWebView.getSettings().setDomStorageEnabled(true);
        robotWebView.getSettings().setAllowFileAccess(true);
        robotWebView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        // Allow MediaPipe WASM + ES modules to load from CDN
        robotWebView.getSettings().setAllowContentAccess(true);
        robotWebView.getSettings().setBlockNetworkLoads(false);
        robotWebView.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
        // Enable mixed content (needed if loading HTTP resources, though we use HTTPS CDN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            robotWebView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        robotWebView.addJavascriptInterface(new RobotJSBridge(), "JSBridge");
        robotWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Initialize robot state in JS
                view.evaluateJavascript("ArgosJS.setScreenSize(" + screenWidth + "," + screenHeight + ");", null);
                view.evaluateJavascript("ArgosJS.setPosition(" + robotScreenX + "," + robotScreenY + ");", null);
                view.evaluateJavascript("ArgosJS.setRobotSize(" + robotSize + ");", null);
            }
        });
        // Allow camera access from WebView (for MediaPipe hand tracking)
        robotWebView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onPermissionRequest(android.webkit.PermissionRequest request) {
                // Grant all requested permissions (camera, etc.)
                request.grant(request.getResources());
            }

            // Surface the page's console output in logcat (tag "ArgosJS").
            // Setting a WebChromeClient suppresses the default console
            // logging, so without this the robot's JS diagnostics are invisible.
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                android.util.Log.i("ArgosJS", cm.message() + " @" + cm.lineNumber());
                return true;
            }
        });
        // Load the Three.js robot scene from assets
        robotWebView.loadUrl("file:///android_asset/argos_robot.html");
        // Watch for a wedged animation loop and reload the page if needed.
        startFreezeWatchdog();

        layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
            WindowManager.LayoutParams.TYPE_PHONE;

        // Small overlay window — tight around robot
        robotParams = new WindowManager.LayoutParams(
            robotWindowWidth,
            robotWindowHeight,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        robotParams.gravity = Gravity.TOP | Gravity.START;
        // Center initially
        robotParams.x = (screenWidth - robotWindowWidth) / 2;
        robotParams.y = (int) (screenHeight * 0.35f - robotWindowHeight / 2.0f);
        robotScreenX = robotParams.x + robotWindowWidth / 2.0f;
        robotScreenY = robotParams.y + robotWindowHeight / 2.0f;
        positionInitialized = true; // allow JS position updates now

        windowManager.addView(robotWebView, robotParams);

        // Keyboard detection — move robot above keyboard when typing
        keyboardListener = () -> {
            android.graphics.Rect r = new android.graphics.Rect();
            robotWebView.getWindowVisibleDisplayFrame(r);
            int visibleHeight = r.bottom - r.top;
            int keyboardHeight = screenHeight - visibleHeight;
            boolean isVisible = keyboardHeight > screenHeight * 0.15; // 15% threshold

            if (isVisible && !keyboardVisible) {
                // Keyboard just appeared
                keyboardVisible = true;
                savedRobotY = robotScreenY;
                keyboardTopY = r.bottom; // top of keyboard = bottom of visible frame
                // Move robot above keyboard immediately
                float safeY = keyboardTopY - robotSize * 0.8f;
                robotScreenY = safeY;
                robotWebView.evaluateJavascript(
                    "if(window.ArgosJS){ArgosJS.setKeyboardVisible(true," + keyboardTopY + ");}", null);
            } else if (!isVisible && keyboardVisible) {
                // Keyboard just hidden
                keyboardVisible = false;
                keyboardTopY = 0;
                robotWebView.evaluateJavascript(
                    "if(window.ArgosJS){ArgosJS.setKeyboardVisible(false,0);}", null);
            }
        };
        robotWebView.getViewTreeObserver().addOnGlobalLayoutListener(keyboardListener);

        // Touch listener — handle drag + long-press in Java, taps handled by JS bridge
        android.os.Handler longPressHandler = new android.os.Handler();
        Runnable[] longPressRunnable = {null};
        boolean[] javaLongPressFired = {false};

        robotWebView.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            float rawX = event.getRawX();
            float rawY = event.getRawY();

            if (action == MotionEvent.ACTION_DOWN) {
                dragStartRawX = rawX;
                dragStartRawY = rawY;
                dragStartWindowX = robotParams.x;
                dragStartWindowY = robotParams.y;
                isDragging = false;
                javaLongPressFired[0] = false;
                // Start Java-side long-press timer (600ms) — more reliable than HTML setTimeout
                longPressRunnable[0] = () -> {
                    if (!isDragging) {
                        javaLongPressFired[0] = true;
                        showLongPressMenu();
                    }
                };
                longPressHandler.postDelayed(longPressRunnable[0], 600);
            } else if (action == MotionEvent.ACTION_MOVE) {
                float dx = rawX - dragStartRawX;
                float dy = rawY - dragStartRawY;
                if (Math.abs(dx) > 15 || Math.abs(dy) > 15) {
                    isDragging = true;
                    // Cancel long-press — user is dragging
                    if (longPressRunnable[0] != null) {
                        longPressHandler.removeCallbacks(longPressRunnable[0]);
                        longPressRunnable[0] = null;
                    }
                    // Move window to follow finger
                    robotParams.x = dragStartWindowX + (int) dx;
                    robotParams.y = dragStartWindowY + (int) dy;
                    // Clamp Y to stay above keyboard
                    if (keyboardVisible && keyboardTopY > 0) {
                        int maxWinY = keyboardTopY - robotParams.height;
                        if (robotParams.y > maxWinY) robotParams.y = maxWinY;
                    }
                    try {
                        windowManager.updateViewLayout(robotWebView, robotParams);
                    } catch (Exception e) {}
                    // Update robot screen position
                    float newScreenX = robotParams.x + robotParams.width / 2.0f;
                    float newScreenY = robotParams.y + robotParams.height / 2.0f;
                    robotScreenX = newScreenX;
                    robotScreenY = newScreenY;
                    // Notify JS of drag state
                    robotWebView.evaluateJavascript("ArgosJS.setDragging(true);", null);
                    robotWebView.evaluateJavascript("ArgosJS.setPosition(" + newScreenX + "," + newScreenY + ");", null);
                }
            } else if (action == MotionEvent.ACTION_UP) {
                // Cancel long-press timer
                if (longPressRunnable[0] != null) {
                    longPressHandler.removeCallbacks(longPressRunnable[0]);
                    longPressRunnable[0] = null;
                }
                if (javaLongPressFired[0]) {
                    // Long-press already fired — consume the event, don't pass to WebView
                    return true;
                }
                if (isDragging) {
                    float newScreenX = robotParams.x + robotParams.width / 2.0f;
                    float newScreenY = robotParams.y + robotParams.height / 2.0f;
                    robotScreenX = newScreenX;
                    robotScreenY = newScreenY;
                    isDragging = false;
                    robotWebView.evaluateJavascript("ArgosJS.setDragging(false);", null);
                    robotWebView.evaluateJavascript("ArgosJS.setPosition(" + newScreenX + "," + newScreenY + ");", null);
                }
            } else if (action == MotionEvent.ACTION_CANCEL) {
                if (longPressRunnable[0] != null) {
                    longPressHandler.removeCallbacks(longPressRunnable[0]);
                    longPressRunnable[0] = null;
                }
                // BUG FIX: ACTION_CANCEL used to only cancel the long-press
                // timer. If the gesture was cancelled mid-drag, isDragging
                // stayed TRUE on both the Java and the JS side, and the JS walk
                // gate (WALKING && !isDragging) could never pass again — Argos
                // permanently stopped roaming. Reset the drag state here.
                if (isDragging) {
                    isDragging = false;
                    if (robotWebView != null) {
                        robotWebView.evaluateJavascript("ArgosJS.setDragging(false);", null);
                    }
                }
                javaLongPressFired[0] = false;
            }
            return false; // Let WebView handle touch for tap detection
        });

        // Speech bubble overlay — hidden by default
        bubbleOverlay = createSpeechBubble();

        bubbleParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        );
        // MUST use same gravity as robot (TOP|START) so x/y are from top-left corner
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = 0;
        bubbleParams.y = 120;

        bubbleOverlay.setVisibility(View.GONE);
        windowManager.addView(bubbleOverlay, bubbleParams);

        // Touch listener on bubble — dismiss on outside touch
        bubbleOverlay.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                hideBubble();
                return true;
            }
            return false;
        });
    }

    // Legacy: called from C++ via JNI with robot position "x,y,size" (no longer used — JS bridge handles position now)
    public void onRobotPosition(String posStr) {
        try {
            String[] parts = posStr.split(",");
            if (parts.length < 3) return;
            final float x = Float.parseFloat(parts[0]);
            final float y = Float.parseFloat(parts[1]);
            final float size = Float.parseFloat(parts[2]);

            robotScreenX = x;
            robotScreenY = y;
            robotSize = size;

            // Skip if dragging — Java controls position during drag
            if (isDragging) return;

            // Update window size to match robot
            final int winW = (int) (size * 2.0f);
            final int winH = (int) (size * 1.7f);

            android.os.Handler handler = new android.os.Handler(getMainLooper());
            handler.post(() -> {
                if (robotWebView == null || robotParams == null) return;
                robotParams.width = winW;
                robotParams.height = winH;
                // Position window so robot center maps to (x, y)
                robotParams.x = (int) (x - winW / 2.0f);
                robotParams.y = (int) (y - winH / 2.0f);
                try {
                    windowManager.updateViewLayout(robotWebView, robotParams);
                } catch (Exception e) {}
            });
        } catch (Exception e) {}
    }

    private LinearLayout createSpeechBubble() {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackgroundColor(Color.rgb(25, 25, 30));
        bubble.setPadding(28, 24, 28, 24);
        bubble.setVisibility(View.GONE);

        // Set a max width
        float density = getResources().getDisplayMetrics().density;
        int maxWidth = (int) (320 * density);
        LinearLayout.LayoutParams bParams = new LinearLayout.LayoutParams(
            maxWidth, LinearLayout.LayoutParams.WRAP_CONTENT);
        bubble.setLayoutParams(bParams);

        // Title bar with close button
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleBarParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleBarParams.bottomMargin = 16;

        TextView title = new TextView(this);
        title.setText("Ask Argos");
        title.setTextColor(Color.rgb(0, 200, 255));
        title.setTextSize(20f);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleBar.addView(title, titleParams);

        ImageButton closeBtn = new ImageButton(this);
        closeBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        closeBtn.setBackgroundColor(Color.TRANSPARENT);
        closeBtn.setPadding(8, 8, 8, 8);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
            (int) (40 * density), (int) (40 * density));
        closeBtn.setOnClickListener(v -> hideBubble());
        titleBar.addView(closeBtn, closeParams);

        bubble.addView(titleBar, titleBarParams);

        // Neon blue divider
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(0, 180, 255));
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 3);
        divParams.bottomMargin = 16;
        bubble.addView(divider, divParams);

        // Conversation scroll area
        convoScroll = new ScrollView(this);
        convoScroll.setBackgroundColor(Color.rgb(20, 20, 25));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (int) (200 * density));
        convoLayout = new LinearLayout(this);
        convoLayout.setOrientation(LinearLayout.VERTICAL);
        convoLayout.setPadding(20, 20, 20, 20);
        convoScroll.addView(convoLayout);
        bubble.addView(convoScroll, scrollParams);

        // Input bar
        LinearLayout inputBar = new LinearLayout(this);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setBackgroundColor(Color.rgb(30, 30, 35));
        inputBar.setPadding(12, 12, 12, 12);
        LinearLayout.LayoutParams inputBarParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputBarParams.topMargin = 12;
        bubble.addView(inputBar, inputBarParams);

        inputEdit = new EditText(this);
        inputEdit.setHint("Message Argos...");
        inputEdit.setHintTextColor(Color.rgb(100, 100, 110));
        inputEdit.setTextColor(Color.WHITE);
        inputEdit.setBackgroundColor(Color.rgb(40, 40, 48));
        inputEdit.setPadding(24, 16, 24, 16);
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        inputBar.addView(inputEdit, editParams);

        sendBtn = new ImageButton(this);
        sendBtn.setImageResource(android.R.drawable.ic_menu_send);
        sendBtn.setBackgroundColor(Color.rgb(0, 120, 215));
        sendBtn.setPadding(24, 24, 24, 24);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        btnParams.setMargins(12, 0, 0, 0);
        inputBar.addView(sendBtn, btnParams);

        sendBtn.setOnClickListener(v -> sendMessage());
        inputEdit.setOnEditorActionListener((v, actionId, event) -> {
            sendMessage();
            return true;
        });

        return bubble;
    }

    // Called from C++ via JNI when robot head is tapped
    public void onHeadTap() {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            // If TTS is speaking, stop it (like desktop Caps Lock behavior)
            if (m_tts != null && m_tts.isSpeaking()) {
                m_tts.stop();
                return;
            }
            if (bubbleVisible) {
                hideBubble();
            } else {
                showBubble();
            }
        });
    }

    // Called from C++ via JNI when robot body is tapped (walk)
    public void onBodyTap() {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            if (bubbleVisible) {
                hideBubble();
            }
        });
    }

    private void showBubble() {
        // Don't show the chat bubble in standby mode — the robot is sleeping
        if (standbyMode) return;
        // Position bubble above robot's head
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        updateBubblePosition();

        bubbleOverlay.setVisibility(View.VISIBLE);
        bubbleVisible = true;
        // Remove FLAG_NOT_FOCUSABLE so EditText can receive focus and keyboard
        // Keep FLAG_WATCH_OUTSIDE_TOUCH so clicking outside dismisses
        bubbleParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        bubbleParams.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        try {
            windowManager.updateViewLayout(bubbleOverlay, bubbleParams);
        } catch (Exception e) {}
        // Focus the input field to bring up keyboard
        inputEdit.requestFocus();
        android.os.Handler h = new android.os.Handler(getMainLooper());
        h.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(inputEdit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        }, 100);
    }

    // Reposition the speech bubble so it stays on top of the robot's head.
    // Called every frame from onRobotPosition when the bubble is visible, so
    // the bubble follows the robot as it roams edge-to-edge.
    private void updateBubblePosition() {
        if (bubbleOverlay == null || bubbleParams == null) return;
        float density = getResources().getDisplayMetrics().density;
        int bubbleWidth = (int) (320 * density); // matches maxWidth in createSpeechBubble
        // Center the bubble horizontally over the robot
        int targetX = (int) (robotScreenX - bubbleWidth / 2.0f);
        // Clamp so the bubble doesn't go off-screen left/right
        targetX = Math.max(0, Math.min(targetX, screenWidth - bubbleWidth));
        // Position above the robot's head (robotSize accounts for depth scale)
        float effectiveSize = robotSize * currentScale;
        int targetY = (int) Math.max(0, robotScreenY - effectiveSize * 1.8f);
        bubbleParams.x = targetX;
        bubbleParams.y = targetY;
    }

    // Reposition the thought bubble so it stays on top of the robot's head.
    // Called every frame from onRobotPosition so the thought bubble follows
    // the robot as it roams edge-to-edge — just like the speech bubble.
    private void updateThoughtBubblePosition() {
        if (thoughtBubble == null || thoughtParams == null) return;
        float density = getResources().getDisplayMetrics().density;
        // Estimate thought bubble width (it's WRAP_CONTENT, max ~70% screen)
        int bubbleWidth = (int) (screenWidth * 0.6f);
        // Center the bubble horizontally over the robot
        int targetX = (int) (robotScreenX - bubbleWidth / 2.0f);
        // Clamp so the bubble doesn't go off-screen left/right
        targetX = Math.max(10, Math.min(targetX, screenWidth - bubbleWidth - 10));
        // Position above the robot's head
        float effectiveSize = robotSize * currentScale;
        int targetY = (int) Math.max(10, robotScreenY - effectiveSize * 2.2f);
        // If too close to top, show below robot instead
        if (targetY < 50) {
            targetY = (int) (robotScreenY + effectiveSize + 20);
        }
        thoughtParams.x = targetX;
        thoughtParams.y = targetY;
    }

    private void hideBubble() {
        // Hide keyboard first
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && inputEdit != null) {
            imm.hideSoftInputFromWindow(inputEdit.getWindowToken(), 0);
        }
        bubbleOverlay.setVisibility(View.GONE);
        bubbleVisible = false;
        // Add FLAG_NOT_FOCUSABLE back so touches pass through
        bubbleParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        try {
            windowManager.updateViewLayout(bubbleOverlay, bubbleParams);
        } catch (Exception e) {}
    }

    private void sendMessage() {
        if (chatInProgress) return;
        String text = inputEdit.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;

        // Handle /tasks command
        if (text.equals("/tasks") || text.equals("/reminders")) {
            inputEdit.setText("");
            addMessage("You: " + text, Color.rgb(200, 200, 210));
            if (scheduledTaskList.isEmpty()) {
                addMessage("Argos: No scheduled tasks. Tell me to schedule something like 'remind me at 8am to check emails'", Color.rgb(100, 200, 255));
            } else {
                StringBuilder sb = new StringBuilder("Argos: Scheduled tasks:\n");
                for (String task : scheduledTaskList) {
                    sb.append("  ⏰ ").append(task).append("\n");
                }
                addMessage(sb.toString().trim(), Color.rgb(100, 200, 255));
            }
            return;
        }

        chatInProgress = true;
        inputEdit.setText("");
        sendBtn.setEnabled(false);

        addMessage("You: " + text, Color.rgb(200, 200, 210));

        sendNativeChat(text);
        notifyRobotThinking();
    }

    private void notifyRobotThinking() {
        if (robotWebView != null) {
            robotWebView.evaluateJavascript("if(window.ArgosJS){ArgosJS.setThinking(true);}", null);
        }
    }

    private void addMessage(String text, int color) {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            TextView tv = new TextView(this);
            // Parse basic markdown: **bold**, `code`, *italic*
            CharSequence styled = parseMarkdown(text, color);
            tv.setText(styled);
            tv.setTextColor(color);
            tv.setTextSize(15f);
            tv.setPadding(0, 12, 0, 12);
            convoLayout.addView(tv);
            convoScroll.post(() -> convoScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    // Parse basic markdown into styled SpannableStringBuilder
    private CharSequence parseMarkdown(String text, int baseColor) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int i = 0;
        while (i < text.length()) {
            // **bold**
            if (i + 1 < text.length() && text.charAt(i) == '*' && text.charAt(i + 1) == '*') {
                int end = text.indexOf("**", i + 2);
                if (end > 0) {
                    String bold = text.substring(i + 2, end);
                    int start = sb.length();
                    sb.append(bold);
                    sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            // `code`
            if (text.charAt(i) == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > 0) {
                    String code = text.substring(i + 1, end);
                    int start = sb.length();
                    sb.append(code);
                    sb.setSpan(new TypefaceSpan("monospace"), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(Color.rgb(120, 220, 120)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            // *italic* (single asterisk, not double)
            if (text.charAt(i) == '*' && (i + 1 >= text.length() || text.charAt(i + 1) != '*')) {
                int end = text.indexOf('*', i + 1);
                if (end > 0 && text.charAt(end - 1) != '*') {
                    String italic = text.substring(i + 1, end);
                    int start = sb.length();
                    sb.append(italic);
                    sb.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            sb.append(text.charAt(i));
            i++;
        }
        return sb;
    }

    // Called from C++ via JNI — shows tool execution status in bubble
    public void onToolStatus(final String status) {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            int count = convoLayout.getChildCount();
            // Find existing tool status view
            for (int i = count - 1; i >= 0; i--) {
                View v = convoLayout.getChildAt(i);
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    if (tv.getTag() != null && tv.getTag().equals("tool_status")) {
                        // Update existing status
                        tv.setText(status);
                        convoScroll.post(() -> convoScroll.fullScroll(ScrollView.FOCUS_DOWN));
                        return;
                    }
                }
            }
            // Create new tool status view
            TextView tv = new TextView(this);
            tv.setText(status);
            tv.setTextColor(Color.rgb(255, 200, 80));
            tv.setTextSize(13f);
            tv.setPadding(0, 6, 0, 6);
            tv.setTag("tool_status");
            convoLayout.addView(tv);
            convoScroll.post(() -> convoScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    // Called from C++ via JNI — shows chat metrics (time, chars)
    public void onChatMetrics(final String metrics) {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            // Parse metrics: "1234ms|567chars"
            String[] parts = metrics.split("\\|");
            String timeStr = parts.length > 0 ? parts[0] : "";
            String charStr = parts.length > 1 ? parts[1] : "";

            TextView tv = new TextView(this);
            tv.setText("⏱ " + timeStr + "  📝 " + charStr);
            tv.setTextColor(Color.rgb(100, 100, 120));
            tv.setTextSize(11f);
            tv.setPadding(0, 4, 0, 8);
            tv.setTag("metrics");
            convoLayout.addView(tv);
            convoScroll.post(() -> convoScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    // Called from C++ via JNI — shows AI reasoning/thoughts in bubble
    public void onChatThoughts(final String thoughts) {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            int count = convoLayout.getChildCount();
            // Find existing thoughts view or create new one
            for (int i = count - 1; i >= 0; i--) {
                View v = convoLayout.getChildAt(i);
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    if (tv.getTag() != null && tv.getTag().equals("thoughts")) {
                        tv.setText("💭 " + thoughts);
                        convoScroll.post(() -> convoScroll.fullScroll(ScrollView.FOCUS_DOWN));
                        return;
                    }
                }
            }
            // Create new thoughts view
            TextView tv = new TextView(this);
            tv.setText("💭 " + thoughts);
            tv.setTextColor(Color.rgb(180, 180, 200));
            tv.setTextSize(13f);
            tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.ITALIC);
            tv.setPadding(0, 8, 0, 8);
            tv.setTag("thoughts");
            convoLayout.addView(tv);
            convoScroll.post(() -> convoScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    // Strip [TOOL:...] tags so only natural-language text is shown or spoken.
    // Handles three cases:
    //   1. EXPRSEQ carries a nested JSON array — stripped first (non-greedy to
    //      the closing "]]"), otherwise the leftover JSON would be read aloud.
    //   2. Complete [TOOL:...] tags.
    //   3. Bare/unclosed tag syntax the model sometimes emits without brackets
    //      (e.g. "TOOL:EXPR:HAPPY") — otherwise TTS literally says
    //      "TOOL colon EXPR colon HAPPY".
    private String stripToolTags(String text) {
        if (text == null) return "";
        String t = text;
        // Strip chat role labels ("You:", "User:", "Argos:", "Assistant:").
        // These come from the conversation-bubble formatting and were being
        // spoken aloud literally as "You colon …". Must happen before the
        // whitespace collapse below, which destroys the line anchors.
        t = t.replaceAll("(?im)^\\s*(you|user|argos|assistant|system)\\s*:\\s*", " ");
        t = t.replaceAll("(?i)\\b(you|user|argos|assistant|system)\\s*:\\s*", " ");
        return t
            .replaceAll("(?s)\\[TOOL:EXPRSEQ:\\[.*?\\]\\]", " ")
            .replaceAll("\\[TOOL:[^\\]]*\\]", " ")
            .replaceAll("(?i)\\[?TOOL:[^\\]\\s]*\\]?", " ")
            // Residual tag tokens that lost their brackets, e.g. "EXPR:HAPPY".
            // This is the specific case that made TTS say "EXPR colon HAPPY".
            .replaceAll("\\b[A-Z][A-Z0-9_]{1,}\\s*:\\s*[A-Za-z0-9_./\\-]*", " ")
            // Lone tag keywords (in case a tag was split across the text)
            .replaceAll("(?i)\\b(EXPRSEQ|EXPR|TOOL|HAND|EXPRSEQ)\\b", " ")
            // Stray brackets left over from malformed tags
            .replaceAll("[\\[\\]{}]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    // Pick a natural hand gesture for the reply's expression so the neon hands
    // always match the face, even when the AI omits a [TOOL:HAND:...] tag.
    private String defaultGestureForReply(String reply) {
        if (reply == null) return "REST";
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("\\[TOOL:EXPR:([^\\]]+)\\]").matcher(reply);
        if (!m.find()) return "REST";
        switch (m.group(1).trim().toUpperCase()) {
            case "HAPPY": return "WAVE";
            case "LAUGHING": return "BELLY";
            case "LOVE": return "HEART";
            case "EXCITED": case "STAR_EYES": return "RAISED";
            case "SURPRISED": case "HIDING_EYES": return "CHEEKS";
            case "SCARED": return "TREMBLE";
            case "SAD": case "SLEEPING": return "DOWN";
            case "ANGRY": return "FIST";
            case "CONFUSED": return "SCRATCH";
            case "THINKING": return "THINK";
            case "WINK": return "PEACE";
            case "DIZZY": return "SHRUG";
            default: return "REST";
        }
    }

    // Called from C++ via JNI
    public void onChatResponse(final String response) {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            chatInProgress = false;
            sendBtn.setEnabled(true);
            // Notify JS: robot is talking
            if (robotWebView != null) {
                robotWebView.evaluateJavascript("if(window.ArgosJS){ArgosJS.setThinking(false);ArgosJS.setTalking(true);}", null);
            }
            // Strip [TOOL:...] tags from displayed response
            String clean = stripToolTags(response);
            // Execute any tool commands in the response.
            // Off the UI thread — tools can block for seconds (OCR, content
            // resolvers, disk I/O) and would otherwise freeze the 3D robot.
            final String toolsResponse = response;
            new Thread(() -> executeToolTags(toolsResponse), "argos-tools").start();
            // Fallback: if no EXPR tag was found, set NEUTRAL so robot always reacts
            if (!response.contains("[TOOL:EXPR:") && !response.contains("[TOOL:EXPRSEQ:")) {
                if (robotWebView != null) {
                    robotWebView.evaluateJavascript(
                        "if(window.ArgosJS){ArgosJS.setExpression('NEUTRAL');}", null);
                }
            }
            // Fallback: if no HAND tag was found, pick a gesture that matches
            // the reply's expression so the hands always react.
            if (!response.contains("[TOOL:HAND:")) {
                if (robotWebView != null) {
                    robotWebView.evaluateJavascript(
                        "if(window.ArgosJS){ArgosJS.setHandGesture('" + defaultGestureForReply(response) + "');}", null);
                }
            }
            // Remove tool status views
            int count = convoLayout.getChildCount();
            for (int i = count - 1; i >= 0; i--) {
                View v = convoLayout.getChildAt(i);
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    if (tv.getTag() != null && tv.getTag().equals("tool_status")) {
                        convoLayout.removeViewAt(i);
                    }
                }
            }
            if (clean.isEmpty()) {
                // Update existing Argos message or add new one
                boolean updated = false;
                count = convoLayout.getChildCount();
                for (int i = count - 1; i >= 0; i--) {
                    View v = convoLayout.getChildAt(i);
                    if (v instanceof TextView) {
                        TextView tv = (TextView) v;
                        if (tv.getTag() != null && tv.getTag().equals("thoughts")) {
                            continue;
                        }
                        String current = tv.getText().toString();
                        if (current.startsWith("Argos: ")) {
                            tv.setText("Argos: (tool executed, see results above)");
                            updated = true;
                            break;
                        }
                    }
                }
                if (!updated) {
                    addMessage("Argos: (tool executed, see results above)", Color.rgb(100, 200, 255));
                }
            } else {
                // Update existing Argos message with final clean response
                boolean updated = false;
                count = convoLayout.getChildCount();
                for (int i = count - 1; i >= 0; i--) {
                    View v = convoLayout.getChildAt(i);
                    if (v instanceof TextView) {
                        TextView tv = (TextView) v;
                        if (tv.getTag() != null && tv.getTag().equals("thoughts")) {
                            continue;
                        }
                        String current = tv.getText().toString();
                        if (current.startsWith("Argos: ")) {
                            tv.setText("Argos: " + clean);
                            convoScroll.post(() -> convoScroll.fullScroll(ScrollView.FOCUS_DOWN));
                            updated = true;
                            break;
                        }
                    }
                }
                if (!updated) {
                    addMessage("Argos: " + clean, Color.rgb(100, 200, 255));
                }
            }

            // TTS — speak the AI reply aloud so the user hears it.
            // The UtteranceProgressListener (set up in initTTS) calls
            // onVoiceTtsFinished() when speech completes, which resets the
            // robot to idle. If TTS is unavailable/suppressed, we clear the
            // talking state manually so the robot doesn't freeze.
            if (!clean.isEmpty()) {
                String ttsResult = ttsSpeakJava(clean);
                // If TTS didn't actually start, reset talking state now
                if (ttsResult == null || !ttsResult.contains("\"status\":\"speaking\"")) {
                    if (robotWebView != null) {
                        final android.os.Handler delayHandler = new android.os.Handler(getMainLooper());
                        delayHandler.postDelayed(() -> {
                            if (robotWebView != null) {
                                robotWebView.evaluateJavascript("if(window.ArgosJS){ArgosJS.setTalking(false);}", null);
                            }
                        }, 2000);
                    }
                }
            } else {
                // No spoken text (tool-only response) — clear talking state
                if (robotWebView != null) {
                    final android.os.Handler delayHandler = new android.os.Handler(getMainLooper());
                    delayHandler.postDelayed(() -> {
                        if (robotWebView != null) {
                            robotWebView.evaluateJavascript("if(window.ArgosJS){ArgosJS.setTalking(false);}", null);
                        }
                    }, 1000);
                }
            }
        });
    }

    // Called from C++ via JNI
    public void onChatError(final String error) {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            chatInProgress = false;
            sendBtn.setEnabled(true);
            // Notify JS: stop thinking
            if (robotWebView != null) {
                robotWebView.evaluateJavascript("if(window.ArgosJS){ArgosJS.setThinking(false);}", null);
            }
            addMessage("Error: " + error, Color.rgb(255, 100, 100));
        });
    }

    // Called from C++ via JNI
    public void onChatStream(final String delta) {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            int count = convoLayout.getChildCount();
            for (int i = count - 1; i >= 0; i--) {
                View v = convoLayout.getChildAt(i);
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    if (tv.getTag() != null && tv.getTag().equals("thoughts")) {
                        // Thoughts view found — add response after it
                        break;
                    }
                    String current = tv.getText().toString();
                    if (current.startsWith("Argos: ")) {
                        tv.setText("Argos: " + delta);
                        convoScroll.post(() -> convoScroll.fullScroll(ScrollView.FOCUS_DOWN));
                        return;
                    }
                }
            }
            addMessage("Argos: " + delta, Color.rgb(100, 200, 255));
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Start as foreground service to keep alive
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "argos_robot", "Argos Robot", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Notification notification = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, "argos_robot")
                .setContentTitle("Argos is running")
                .setContentText("Robot is floating on screen")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
        } else {
            notification = new Notification.Builder(this)
                .setContentTitle("Argos is running")
                .setContentText("Robot is floating on screen")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification);
        }

        if (!serviceInitialized) {
            serviceInitialized = true;
            new Thread(() -> {
                SecurityChecker.SecurityResult secResult = SecurityChecker.check(this);
                if (!secResult.isSecure()) {
                    android.util.Log.w("ArgosSecurity", "Security risk detected (score " + secResult.riskScore + "): " + secResult.getSummary());
                    // Don't stop the service — only hook frameworks block, and
                    // isSecure() already handles that. Risk signals are advisory.
                }
                new android.os.Handler(getMainLooper()).post(() -> {
                    if (serviceDestroyed || windowManager == null || robotWebView != null) return;
                    try {
                        createFloatingWindow();
                        // Warm the TTS engine now instead of lazily on the first
                        // reply. TextToSpeech loads asynchronously and can take
                        // well over the old 500ms wait — which is exactly why the
                        // FIRST spoken reply was silent while the second worked.
                        initTTS();
                        // Screen-context "thought" generation is disabled: it
                        // periodically read the current app/screen and generated
                        // a comment, which was a recurring source of lag.
                        // startThoughtTimer();
                    } catch (RuntimeException e) {
                        android.util.Log.e("ArgosService", "Could not create overlay", e);
                        stopSelf();
                    }
                });
            }, "argos-service-security-check").start();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        serviceDestroyed = true;
        super.onDestroy();
        stopThoughtTimer();
        // Stop wake word detection
        stopWakeWordDetection();
        // Stop live transcription
        if (liveTranscriptRecognizer != null) {
            try { liveTranscriptRecognizer.destroy(); } catch (Exception e) {}
            liveTranscriptRecognizer = null;
        }
        // Stop camera hand tracking
        if (handTrackingCamera != null) {
            handTrackingCamera.destroy();
            handTrackingCamera = null;
        }
        // Unregister screen receiver
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (Exception e) {}
            screenReceiver = null;
        }
        if (nativeLoaded) {
            try {
                nativeDestroy();
            } catch (UnsatisfiedLinkError e) {
                nativeLoaded = false;
            }
        }
        if (thoughtBubble != null) {
            try { windowManager.removeView(thoughtBubble); } catch (Exception e) {}
            thoughtBubble = null;
        }
        if (awarenessLabel != null) {
            try { windowManager.removeView(awarenessLabel); } catch (Exception e) {}
            awarenessLabel = null;
        }
        if (robotWebView != null) {
            try { windowManager.removeView(robotWebView); } catch (Exception e) {}
            robotWebView.destroy();
            robotWebView = null;
        }
        if (bubbleOverlay != null) {
            try { windowManager.removeView(bubbleOverlay); } catch (Exception e) {}
            bubbleOverlay = null;
        }
        instance = null;
    }

    public static FloatingRobotService getInstance() {
        return instance;
    }

    // ── Proactive Screen Awareness ──
    // Called by ArgosAccessibilityService when user switches to a different app

    // Called by ArgosAccessibilityService when a blocked (privacy) app is opened
    public void onBlockedAppOpened(final String appLabel) {
        awarenessHandler.post(() -> {
            // Tell the 3D robot to hide its eyes with hands
            if (robotWebView != null) {
                robotWebView.evaluateJavascript(
                    "if(window.ArgosJS){ArgosJS.hideEyes();}", null);
            }
            // Show a thought bubble
            showThoughtBubble("🙈 I'm not looking!");
        });
    }

    // Called by ArgosAccessibilityService when leaving a blocked app
    public void onBlockedAppClosed() {
        awarenessHandler.post(() -> {
            // Tell the 3D robot to show its eyes again
            if (robotWebView != null) {
                robotWebView.evaluateJavascript(
                    "if(window.ArgosJS){ArgosJS.showEyes();}", null);
            }
        });
    }

    public void onAppChanged(final String appLabel) {
        awarenessHandler.post(() -> {
            // Screen-awareness dance trigger REMOVED. This fired on every app
            // switch and pushed Argos into the DANCING state, which is held for
            // ACTIVITY_TIMEOUT (30s) and suppresses roaming — the "Argos stops
            // for ~30s" bug. App changes no longer hijack the robot.
            // robotOnUserActivity();

            // Don't show awareness while chat bubble is open, in standby mode,
            // or if the screen is off (phone asleep)
            if (bubbleVisible || standbyMode || !screenOn) return;
            if (awarenessLabel == null) {
                awarenessLabel = new TextView(this);
                awarenessLabel.setTextColor(Color.rgb(255, 255, 255));
                awarenessLabel.setTextSize(15f);
                awarenessLabel.setPadding(28, 18, 28, 18);
                awarenessLabel.setMaxWidth((int) (screenWidth * 0.7f));
                // Rounded speech bubble background — same style as thought bubble
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(Color.argb(230, 20, 25, 40));
                bg.setCornerRadius(24f);
                bg.setStroke(2, Color.rgb(0, 200, 255));
                awarenessLabel.setBackground(bg);

                awarenessParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                );
                awarenessParams.gravity = Gravity.TOP | Gravity.START;
            }

            awarenessLabel.setText("👀 " + appLabel);

            // Position above the robot
            awarenessParams.x = (int) Math.max(10, robotScreenX - screenWidth * 0.15f);
            awarenessParams.y = (int) Math.max(10, robotScreenY - robotSize * 2.5f);
            // If too close to top, show below robot
            if (awarenessParams.y < 50) {
                awarenessParams.y = (int) (robotScreenY + robotSize + 20);
            }

            try {
                if (awarenessLabel.getWindowToken() == null) {
                    windowManager.addView(awarenessLabel, awarenessParams);
                } else {
                    windowManager.updateViewLayout(awarenessLabel, awarenessParams);
                }
                awarenessLabel.setVisibility(View.VISIBLE);
            } catch (Exception e) {
                // Ignore — might fail if window is already removed
            }

            // Auto-hide after 3 seconds
            awarenessHandler.removeCallbacksAndMessages(null);
            awarenessHandler.postDelayed(() -> {
                if (awarenessLabel != null) {
                    awarenessLabel.setVisibility(View.GONE);
                }
            }, 3000);
        });
    }

    // ── Periodic Thought Bubble ──
    // Every 60 seconds, Argos generates a short comment about what the user is doing

    private void startThoughtTimer() {
        // Load saved interval from prefs
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("argos_prefs", MODE_PRIVATE);
            thoughtIntervalMs = prefs.getInt("thought_interval_ms", THOUGHT_INTERVAL_DEFAULT_MS);
        } catch (Exception e) {}
        thoughtRunnable = new Runnable() {
            @Override
            public void run() {
                generateThought();
                thoughtHandler.postDelayed(this, thoughtIntervalMs);
            }
        };
        // First thought after 15 seconds, then at user-set interval
        thoughtHandler.postDelayed(thoughtRunnable, 15000);
    }

    private void stopThoughtTimer() {
        if (thoughtRunnable != null) {
            thoughtHandler.removeCallbacks(thoughtRunnable);
            thoughtRunnable = null;
        }
    }

    // Update thought interval and restart timer
    private void setThoughtInterval(int newIntervalMs) {
        if (newIntervalMs < THOUGHT_INTERVAL_MIN_MS) newIntervalMs = THOUGHT_INTERVAL_MIN_MS;
        if (newIntervalMs > THOUGHT_INTERVAL_MAX_MS) newIntervalMs = THOUGHT_INTERVAL_MAX_MS;
        thoughtIntervalMs = newIntervalMs;
        // Save to prefs
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("argos_prefs", MODE_PRIVATE);
            prefs.edit().putInt("thought_interval_ms", newIntervalMs).apply();
        } catch (Exception e) {}
        // Restart timer with new interval
        stopThoughtTimer();
        thoughtRunnable = new Runnable() {
            @Override
            public void run() {
                generateThought();
                thoughtHandler.postDelayed(this, thoughtIntervalMs);
            }
        };
        thoughtHandler.postDelayed(thoughtRunnable, thoughtIntervalMs);
    }

    private void generateThought() {
        // Don't show thought if chat bubble is open, chat in progress, in standby,
        // or if the screen is off (phone asleep / in pocket)
        if (bubbleVisible || chatInProgress || standbyMode || !screenOn) return;
        // Privacy: don't generate app-aware thoughts if full privacy is on
        if (ArgosAccessibilityService.isFullPrivacy()) {
            // Still show a generic thought bubble every minute
            showThoughtBubble(getGenericThought());
            return;
        }

        String currentAppRaw = ArgosAccessibilityService.getCurrentAppLabel();
        if (currentAppRaw == null || currentAppRaw.isEmpty()) currentAppRaw = "the home screen";
        final String currentApp = currentAppRaw;

        // If same app as last thought, vary the prompt
        String prompt;
        thoughtCount++;
        if (currentApp.equals(lastThoughtApp) && thoughtCount % 3 != 0) {
            // Same app — generate a different kind of comment
            prompt = "You are Argos, a cute robot companion floating on the user's screen. " +
                    "The user has been using " + currentApp + " for a while. " +
                    "Say something brief, funny, or helpful about it (max 15 words, 1 sentence). " +
                    "Be casual and friendly. Don't use emojis. Vary your style.";
        } else {
            lastThoughtApp = currentApp;
            prompt = "You are Argos, a cute robot companion floating on the user's screen. " +
                    "The user just opened " + currentApp + ". " +
                    "Say something brief, funny, or helpful about it (max 15 words, 1 sentence). " +
                    "Be casual and friendly. Don't use emojis.";
        }

        // Call backend in background thread
        new Thread(() -> {
            try {
                String thought = callBackendForThought(prompt);
                if (thought != null && !thought.isEmpty()) {
                    showThoughtBubble(thought);
                } else {
                    // Backend failed — use local fallback thought
                    showThoughtBubble(getLocalThought(currentApp));
                }
            } catch (Exception e) {
                // Backend failed — use local fallback thought
                showThoughtBubble(getLocalThought(currentApp));
            }
        }).start();
    }

    // Generic thought when privacy mode is on (no app awareness)
    private String getGenericThought() {
        String[] thoughts = {
            "Just keeping an eye on things...",
            "Ready when you need me!",
            "Floating around, here if you need help.",
            "Tap my head to talk to me!",
            "Long-press me for options.",
            "I'm here. What's on your mind?",
            "Watching the clock for you.",
            "Bored. Talk to me!",
            "Your friendly neighborhood robot.",
            "Need anything? Just tap me."
        };
        return thoughts[(int) (Math.random() * thoughts.length)];
    }

    // Local fallback thoughts when backend is unavailable or fails
    private String getLocalThought(String currentApp) {
        if (currentApp == null || currentApp.isEmpty()) currentApp = "your phone";
        String[] thoughts = {
            "Looks like you're using " + currentApp + ".",
            "Enjoying " + currentApp + "?",
            "I see you're on " + currentApp + ". Need help?",
            "Tap me if you need anything while using " + currentApp + ".",
            "Just floating here while you use " + currentApp + ".",
            "Need a hand with " + currentApp + "?",
            "I'm here if you need help with " + currentApp + ".",
            "Watching you use " + currentApp + ". Cool!",
            "Tap my head to chat about " + currentApp + ".",
            "Long-press me for more options!"
        };
        return thoughts[(int) (Math.random() * thoughts.length)];
    }

    private String callBackendForThought(String prompt) {
        try {
            if (backendUrl == null || backendUrl.isEmpty()) return null;

            java.net.URL url = new java.net.URL(backendUrl + "/api/thought");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Argos-Android/3.29.5");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);

            // Build JSON body
            String escapedPrompt = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
            String body = "{\"prompt\":\"" + escapedPrompt + "\"}";

            conn.setDoOutput(true);
            java.io.OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) return null;

            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            conn.disconnect();

            // Parse JSON response — extract "thought" field
            String response = sb.toString();
            int thoughtIdx = response.indexOf("\"thought\"");
            if (thoughtIdx < 0) return null;
            int start = response.indexOf("\"", thoughtIdx + 8) + 1;
            int end = start;
            while (end < response.length()) {
                if (response.charAt(end) == '\\') { end += 2; continue; }
                if (response.charAt(end) == '"') break;
                end++;
            }
            String content = response.substring(start, end)
                .replace("\\n", " ")
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .trim();
            return content;
        } catch (Exception e) {
            return null;
        }
    }

    private void showThoughtBubble(final String text) {
        thoughtHandler.post(() -> {
            // Don't show if chat bubble is open, in standby mode (sleeping),
            // or if the screen is off (phone asleep / in pocket)
            if (bubbleVisible || chatInProgress || standbyMode || !screenOn) return;

            if (thoughtBubble == null) {
                thoughtBubble = new TextView(this);
                thoughtBubble.setTextColor(Color.rgb(255, 255, 255));
                thoughtBubble.setTextSize(14f);
                thoughtBubble.setPadding(24, 16, 24, 16);
                thoughtBubble.setMaxWidth((int) (screenWidth * 0.7f));
                thoughtBubble.setBackgroundColor(Color.argb(220, 25, 25, 35));
                // Rounded background
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(Color.argb(220, 25, 25, 35));
                bg.setCornerRadius(20f);
                bg.setStroke(2, Color.rgb(0, 180, 255));
                thoughtBubble.setBackground(bg);

                thoughtParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                );
                thoughtParams.gravity = Gravity.TOP | Gravity.START;
            }

            // Clean for both display and speech — the raw thought can carry
            // role labels / tool tags that should never be shown or spoken.
            final String displayText = stripToolTags(text);
            thoughtBubble.setText("💬 " + displayText);

            // Auto-TTS: speak the thought aloud (like desktop)
            ttsSpeakJava(displayText);

            // Position above the robot — uses the same positioning logic as
            // the speech bubble so it stays glued to the robot's head.
            updateThoughtBubblePosition();

            try {
                if (thoughtBubble.getWindowToken() == null) {
                    windowManager.addView(thoughtBubble, thoughtParams);
                } else {
                    windowManager.updateViewLayout(thoughtBubble, thoughtParams);
                }
                thoughtBubble.setVisibility(View.VISIBLE);
            } catch (Exception e) {}

            // Auto-hide after THOUGHT_DISPLAY_MS
            thoughtHandler.removeCallbacks(thoughtHideRunnable);
            thoughtHandler.postDelayed(thoughtHideRunnable, THOUGHT_DISPLAY_MS);
        });
    }

    private Runnable thoughtHideRunnable = () -> {
        if (thoughtBubble != null) {
            thoughtBubble.setVisibility(View.GONE);
        }
    };

    // ── Browser / Screen interaction via Accessibility Service ──
    // Called from C++ via JNI to interact with the user's browser and screen

    // Returns the current app the user is looking at (for AI context)
    public String getCurrentAppContext() {
        String label = ArgosAccessibilityService.getCurrentAppLabel();
        String history = ArgosAccessibilityService.getAppHistory();
        if (label == null || label.isEmpty()) {
            if (history != null && !history.isEmpty()) return "recently: " + history;
            return "";
        }
        if (history != null && !history.isEmpty() && !history.equals(label)) {
            return label + " (recently used: " + history + ")";
        }
        return label;
    }

    public String openUrlJava(String url) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled. Please enable Argos in Accessibility Settings.\"}";
        return svc.openUrl(url);
    }

    public String getScreenTextJava() {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled. Please enable Argos in Accessibility Settings.\"}";
        return svc.getScreenText();
    }

    public String getActiveAppJava() {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.getActiveApp();
    }

    public String clickTextJava(String text) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.clickText(text);
    }

    public String typeTextJava(String text) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.typeText(text);
    }

    public String scrollScreenJava(int direction) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.scrollScreen(direction);
    }

    // ── UI Inspection & Automation bridge methods ──

    public String getUITreeJava(int maxDepth) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.getUITree(maxDepth);
    }

    public String performUIActionJava(int elementId, String action, String extra) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.performUIAction(elementId, action, extra);
    }

    public String takeScreenshotJava(String savePath) {
        // Trigger 180-degree turn animation — robot looks at screen
        robotLookAtScreen();
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.takeScreenshot(savePath);
    }

    public String observeScreenJava() {
        // Trigger 180-degree turn animation — robot looks at screen
        robotLookAtScreen();
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.observeScreen();
    }

    public String getNotificationsJava() {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.getNotifications();
    }

    public String replyToNotificationJava(int index, String message) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.replyToNotification(index, message);
    }

    // ── Gesture-based UI automation bridge methods ──

    public String clickAtPointJava(int x, int y) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.clickAtPoint(x, y);
    }

    public String longPressAtPointJava(int x, int y) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.longPressAtPoint(x, y);
    }

    public String swipeJava(int x1, int y1, int x2, int y2, int durationMs) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.swipe(x1, y1, x2, y2, durationMs);
    }

    public String swipeUpJava() {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.swipeUp();
    }

    public String swipeDownJava() {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.swipeDown();
    }

    public String swipeLeftJava() {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.swipeLeft();
    }

    public String swipeRightJava() {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.swipeRight();
    }

    public String smartClickJava(int x, int y) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.smartClick(x, y);
    }

    public String smartLongPressJava(int x, int y) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.smartLongPress(x, y);
    }

    public String getClickableElementsJava() {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.getClickableElements();
    }

    public String getScreenSizeJava() {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.getScreenSize();
    }

    // ── Phone Automation bridge methods (Play Store Compliant) ──

    public String dialPhoneNumberJava(String number) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.dialPhoneNumber(number);
    }

    public String sendSmsJava(String packed) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        int pipe = packed.indexOf('|');
        if (pipe < 0) return "{\"error\":\"Invalid format: need number|message\"}";
        String number = packed.substring(0, pipe).trim();
        String message = packed.substring(pipe + 1).trim();
        return svc.sendSmsViaIntent(number, message);
    }

    public String searchContactsJava(String query) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.searchContacts(query);
    }

    public String createCalendarEventJava(String packed) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        String[] parts = packed.split("\\|", 4);
        String title = parts.length > 0 ? parts[0].trim() : "Event";
        String desc = parts.length > 1 ? parts[1].trim() : "";
        long start = parts.length > 2 ? Long.parseLong(parts[2].trim()) : 0;
        long end = parts.length > 3 ? Long.parseLong(parts[3].trim()) : 0;
        return svc.createCalendarEvent(title, desc, start, end);
    }

    public String readCalendarEventsJava(int daysAhead) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.readCalendarEvents(daysAhead);
    }

    public String setTimerJava(String packed) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        int pipe = packed.indexOf('|');
        int seconds = pipe > 0 ? Integer.parseInt(packed.substring(0, pipe).trim()) : Integer.parseInt(packed);
        String label = pipe > 0 ? packed.substring(pipe + 1).trim() : "Argos Timer";
        return svc.setTimer(seconds, label);
    }

    public String setAlarmJava(String packed) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        int pipe = packed.indexOf('|');
        String timePart = pipe > 0 ? packed.substring(0, pipe).trim() : packed;
        String label = pipe > 0 ? packed.substring(pipe + 1).trim() : "Argos Alarm";
        int colon = timePart.indexOf(':');
        int hour = colon > 0 ? Integer.parseInt(timePart.substring(0, colon).trim()) : Integer.parseInt(timePart);
        int minute = colon > 0 ? Integer.parseInt(timePart.substring(colon + 1).trim()) : 0;
        return svc.setAlarm(hour, minute, label);
    }

    public String getBatteryStatusJava() {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.getBatteryStatus();
    }

    public String toggleFlashlightJava(String mode) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        boolean on = mode.equalsIgnoreCase("on") || mode.equalsIgnoreCase("true") || mode.equals("1");
        return svc.toggleFlashlight(on);
    }

    public String openMapsJava(String query) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.openMaps(query);
    }

    public String startNavigationJava(String destination) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.startNavigation(destination);
    }

    public String shareContentJava(String text) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.shareContent(text);
    }

    public String playMusicJava(String query) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.playMusic(query);
    }

    public String openSettingsJava(String settingType) {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.openSettings(settingType);
    }

    public String readClipboardJava() {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.readClipboard();
    }

    public String getLocationJava() {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.getLocation();
    }

    public String goBackJava() {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.goBack();
    }

    public String goHomeJava() {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.goHome();
    }

    public String openRecentsJava() {
        ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
        if (svc == null) return "{\"error\":\"Accessibility service not enabled.\"}";
        return svc.openRecents();
    }

    // ── Voice / Audio bridge methods ──

    private android.speech.tts.TextToSpeech m_tts = null;
    private boolean m_ttsReady = false;
    // Text of the utterance currently being spoken — used by onRangeStart() to
    // map the reported [start,end) character range back to the spoken word.
    private String m_lastSpokenText = null;
    // Text queued while the TTS engine is still initialising (common on the
    // very first use) — spoken from onInit() as soon as it is ready.
    private String m_pendingSpeech = null;
    // Safety net: some TTS engines never call onDone/onError. Without this the
    // robot would stay in the talking state forever and stop roaming.
    private final android.os.Handler ttsSafetyHandler =
        new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable ttsSafetyRunnable = null;

    // Re-armable fallback that finishes the talking state if the TTS engine
    // goes quiet without reporting completion.
    private void scheduleTtsCompletionFallback(final int textLen) {
        if (ttsSafetyRunnable != null) ttsSafetyHandler.removeCallbacks(ttsSafetyRunnable);
        // ~90 ms per character (well above normal speech rate) + 4 s slack
        final long delay = Math.min(180000L, 4000L + textLen * 90L);
        ttsSafetyRunnable = () -> {
            if (m_tts != null && m_tts.isSpeaking()) {
                scheduleTtsCompletionFallback(textLen); // still talking — check again
                return;
            }
            onVoiceTtsFinished();
        };
        ttsSafetyHandler.postDelayed(ttsSafetyRunnable, delay);
    }

    // Record audio from microphone, returns 16-bit PCM at 16kHz mono as byte[]
    public byte[] recordAudioJava(int durationSeconds) {
        try {
            final int sampleRate = 16000;
            final int channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO;
            final int audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT;
            int minBuf = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            int bufferSize = Math.max(minBuf, sampleRate * durationSeconds * 2); // 2 bytes per sample

            if (m_audioRecord != null) {
                try { m_audioRecord.release(); } catch (Exception e) {}
                m_audioRecord = null;
            }

            m_audioRecord = new android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, bufferSize);

            if (m_audioRecord.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                return new byte[0];
            }

            int totalSamples = sampleRate * durationSeconds;
            byte[] audioData = new byte[totalSamples * 2]; // 16-bit = 2 bytes

            m_audioRecord.startRecording();
            int totalRead = 0;
            int toRead = audioData.length;
            while (totalRead < toRead) {
                int read = m_audioRecord.read(audioData, totalRead, toRead - totalRead);
                if (read <= 0) break;
                totalRead += read;
            }
            m_audioRecord.stop();
            m_audioRecord.release();
            m_audioRecord = null;

            if (totalRead < audioData.length) {
                byte[] trimmed = new byte[totalRead];
                System.arraycopy(audioData, 0, trimmed, 0, totalRead);
                return trimmed;
            }
            return audioData;
        } catch (Exception e) {
            if (m_audioRecord != null) {
                try { m_audioRecord.release(); } catch (Exception ex) {}
                m_audioRecord = null;
            }
            android.util.Log.e("ArgosAudio", "recordAudioJava failed: " + e.getMessage());
            return new byte[0];
        }
    }

    // Initialize TTS engine
    private void initTTS() {
        if (m_tts != null) return;
        m_tts = new android.speech.tts.TextToSpeech(this, new android.speech.tts.TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    m_tts.setLanguage(java.util.Locale.US);
                    m_ttsReady = true;
                    // Track actual speech start/stop so the 3D robot's talking
                    // animation is tied to real TTS playback — not a fixed 5s
                    // timer. This prevents the robot from freezing in the
                    // talking (or thinking) state after the reply finishes
                    // or when TTS fails silently.
                    m_tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                        @Override
                        public void onStart(String utteranceId) {
                            android.os.Handler h = new android.os.Handler(getMainLooper());
                            h.post(() -> {
                                // Only drive the talking animation from the
                                // voice pipeline — non-voice TTS (thought
                                // bubbles, reminders) manages its own robot
                                // state and shouldn't be overridden here.
                                if (voicePipelineActive && robotWebView != null) {
                                    robotWebView.evaluateJavascript(
                                        "if(window.ArgosJS){ArgosJS.setThinking(false);ArgosJS.setTalking(true);}", null);
                                }
                            });
                        }

                        @Override
                        public void onDone(String utteranceId) {
                            android.os.Handler h = new android.os.Handler(getMainLooper());
                            h.post(() -> onVoiceTtsFinished());
                        }

                        @Override
                        public void onError(String utteranceId) {
                            android.os.Handler h = new android.os.Handler(getMainLooper());
                            h.post(() -> onVoiceTtsFinished());
                        }

                        // Word-by-word timing (API 26+, Google TTS English).
                        // Feeds each spoken word to the 3D robot so its hand
                        // gestures are timed to the actual speech instead of a
                        // fixed loop. Engines that don't supply ranges simply
                        // never call this — the robot then keeps its gesture.
                        @Override
                        public void onRangeStart(String utteranceId, int start, int end, int frame) {
                            if (robotWebView == null) return;
                            final String word = (m_lastSpokenText != null &&
                                    start >= 0 && end <= m_lastSpokenText.length() && start < end)
                                    ? m_lastSpokenText.substring(start, end) : "";
                            final int s = start, e = end;
                            android.os.Handler h = new android.os.Handler(getMainLooper());
                            h.post(() -> {
                                if (robotWebView == null) return;
                                robotWebView.evaluateJavascript(
                                    "if(window.ArgosJS&&ArgosJS.onSpeakWord){ArgosJS.onSpeakWord(" +
                                    s + "," + e + ",'" + word.replace("'", "\\'") + "');}", null);
                            });
                        }
                    });
                    android.util.Log.i("ArgosTTS", "TTS initialized successfully");
                    // Flush any reply that was queued while the engine was
                    // loading — this is what makes the FIRST spoken reply work.
                    if (m_pendingSpeech != null) {
                        final String pending = m_pendingSpeech;
                        m_pendingSpeech = null;
                        try {
                            m_lastSpokenText = pending;
                            m_tts.speak(pending, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null,
                                "argos_tts_pending_" + System.currentTimeMillis());
                            scheduleTtsCompletionFallback(pending.length());
                        } catch (Exception e) {}
                    }
                } else {
                    android.util.Log.e("ArgosTTS", "TTS init failed with status: " + status);
                }
            }
        });
    }

    // Called when TTS finishes or errors — resets the robot from talking to
    // idle and clears the voice pipeline state so the robot is ready for the
    // next interaction (and isn't frozen in the talking/thinking pose).
    // The TTS listener is global, so this fires for ALL TTS usage (voice
    // pipeline, text chat, reminders). We only touch voicePipelineActive and
    // wake word detection when the voice pipeline is actually active, so
    // non-voice TTS (reminders, etc.) doesn't spuriously reset pipeline state.
    private void onVoiceTtsFinished() {
        // Cancel the completion fallback — speech really did finish.
        if (ttsSafetyRunnable != null) {
            ttsSafetyHandler.removeCallbacks(ttsSafetyRunnable);
            ttsSafetyRunnable = null;
        }
        if (robotWebView != null) {
            robotWebView.evaluateJavascript(
                "if(window.ArgosJS){ArgosJS.setTalking(false);}", null);
        }
        if (voicePipelineActive) {
            voicePipelineActive = false;
            voiceWatchdogHandler.removeCallbacksAndMessages(null);
            resumeWakeWordDetection();
        }
    }

    // Speak text using Android TextToSpeech
    // Suppresses speech when the screen is off (phone asleep) unless forced
    public String ttsSpeakJava(String text) {
        return ttsSpeakJava(text, false);
    }

    public String ttsSpeakJava(String text, boolean force) {
        try {
            // Don't talk when screen is off unless this is a forced reminder
            if (!force && !screenOn) {
                android.util.Log.i("ArgosTTS", "Screen off — suppressing TTS");
                return "{\"status\":\"suppressed\",\"reason\":\"screen_off\"}";
            }
            if (text == null || text.trim().isEmpty()) {
                return "{\"status\":\"skipped\",\"reason\":\"empty_text\"}";
            }
            // Strip [TOOL:...] tags — these are machine instructions for tool
            // execution (e.g. [TOOL:EXPR:HAPPY], [TOOL:OPEN:whatsapp]) and
            // should never be spoken aloud. Only the natural-language reply
            // portion of the AI response should be read by TTS.
            // stripToolTags() also removes nested EXPRSEQ arrays and bare
            // "TOOL:..." syntax, so nothing with a colon leaks into speech.
            String cleanText = stripToolTags(text);
            if (cleanText.isEmpty()) {
                // The response was entirely tool tags with no spoken text
                return "{\"status\":\"skipped\",\"reason\":\"no_spoken_text\"}";
            }
            if (m_tts == null || !m_ttsReady) {
                initTTS();
                // NO Thread.sleep here. This runs on the main thread and the
                // old 500ms sleep froze the whole UI (including the 3D robot)
                // on every reply. The pending-speech queue below handles the
                // not-ready case instead.
            }
            if (m_tts == null || !m_ttsReady) {
                // Engine still loading — this is the common FIRST-use case and
                // was why the first reply came out silent while the second
                // worked. Queue the text so onInit() speaks it the moment the
                // engine is ready, and report "speaking" so the caller does not
                // tear the voice pipeline down.
                m_pendingSpeech = cleanText;
                m_lastSpokenText = cleanText;
                scheduleTtsCompletionFallback(cleanText.length());
                return "{\"status\":\"speaking\",\"text\":\"(queued)\"}";
            }
            m_lastSpokenText = cleanText;
            int result = m_tts.speak(cleanText, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "argos_tts_" + System.currentTimeMillis());
            if (result == android.speech.tts.TextToSpeech.SUCCESS) {
                // Arm the completion fallback in case the engine never reports done
                scheduleTtsCompletionFallback(cleanText.length());
                return "{\"status\":\"speaking\",\"text\":\"" + cleanText.replace("\"", "\\\"") + "\"}";
            }
            return "{\"error\":\"TTS speak failed\"}";
        } catch (Exception e) {
            return "{\"error\":\"TTS error: " + e.getMessage() + "\"}";
        }
    }

    // Stop TTS playback
    public String ttsStopJava() {
        try {
            if (m_tts != null) {
                m_tts.stop();
            }
            return "{\"status\":\"stopped\"}";
        } catch (Exception e) {
            return "{\"error\":\"TTS stop error: " + e.getMessage() + "\"}";
        }
    }

    // Check if TTS is currently speaking
    public String ttsIsSpeakingJava() {
        try {
            if (m_tts != null && m_tts.isSpeaking()) {
                return "{\"speaking\":true}";
            }
            return "{\"speaking\":false}";
        } catch (Exception e) {
            return "{\"speaking\":false}";
        }
    }

    // ── System tool bridge methods ──

    // Open a file, folder, or URL on Android
    public String openFileJava(String path) {
        try {
            if (path == null || path.isEmpty()) return "{\"error\":\"open needs a path\"}";
            // If it looks like a URL, open in browser
            if (path.startsWith("http://") || path.startsWith("https://")) {
                return openUrlJava(path);
            }
            // Try to open as a file/folder via Intent
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            android.net.Uri uri = android.net.Uri.parse(path);
            String mime = getMimeType(path);
            if (mime != null) {
                intent.setDataAndType(uri, mime);
            } else {
                intent.setData(uri);
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            return "{\"status\":\"opened\",\"path\":\"" + path.replace("\"", "\\\"") + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }

    private String getMimeType(String path) {
        String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
        switch (ext) {
            case "txt": return "text/plain";
            case "html": case "htm": return "text/html";
            case "pdf": return "application/pdf";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "mp4": return "video/mp4";
            case "mp3": return "audio/mpeg";
            case "json": return "application/json";
            case "xml": return "application/xml";
            case "csv": return "text/csv";
            case "doc": case "docx": return "application/msword";
            case "xls": case "xlsx": return "application/vnd.ms-excel";
            default: return null;
        }
    }

    // Write text to a file
    public String writeFileJava(String args) {
        try {
            // args format: "filepath|content"
            int pipe = args.indexOf('|');
            if (pipe < 0) return "{\"error\":\"write needs: filepath|content\"}";
            String filePath = args.substring(0, pipe);
            String content = args.substring(pipe + 1);
            java.io.File file = new java.io.File(filePath);
            java.io.File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
            return "{\"status\":\"written\",\"path\":\"" + filePath.replace("\"", "\\\"") + "\",\"bytes\":" + content.length() + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }

    // Run/launch an app by package name
    public String runAppJava(String packageName) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.Intent intent = pm.getLaunchIntentForPackage(packageName);
            if (intent == null) {
                return "{\"error\":\"App not found: " + packageName + "\"}";
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return "{\"status\":\"launched\",\"package\":\"" + packageName + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }

    // Copy text to clipboard
    public String clipboardJava(String text) {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("Argos", text);
            clipboard.setPrimaryClip(clip);
            return "{\"status\":\"copied\",\"length\":" + text.length() + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }

    // Set system media volume (0-100)
    public String setVolumeJava(int level) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            int maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            int targetVol = (int) Math.round(maxVol * (level / 100.0));
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVol, 0);
            return "{\"status\":\"set\",\"level\":" + level + ",\"actual\":" + targetVol + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }

    // Show a system notification
    public String notifyJava(String message) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    "argos_notify", "Argos Notifications", NotificationManager.IMPORTANCE_DEFAULT);
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(channel);
            }
            Notification notification;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notification = new Notification.Builder(this, "argos_notify")
                    .setContentTitle("Argos")
                    .setContentText(message)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build();
            } else {
                notification = new Notification.Builder(this)
                    .setContentTitle("Argos")
                    .setContentText(message)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build();
            }
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(2, notification);
            return "{\"status\":\"notified\"}";
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }

    // ── Config loading for thought bubble API ──

    private String getConfigValue(String key) {
        try {
            java.io.File configFile = new java.io.File(getFilesDir(), "argos_config.txt");
            if (!configFile.exists()) return null;
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(configFile));
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0 && line.substring(0, eq).trim().equals(key)) {
                    reader.close();
                    return line.substring(eq + 1).trim();
                }
            }
            reader.close();
        } catch (Exception e) {}
        return null;
    }

    // Called from C++ via JNI to perform HTTP POST (handles HTTPS automatically)
    public String httpPostJava(String url, String headers, String body, boolean stream) {
        try {
            java.net.URL urlObj = new java.net.URL(url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) urlObj.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Argos-Android/3.29.5");
            if (stream) {
                conn.setRequestProperty("Accept", "text/event-stream");
            }
            // Parse custom headers
            for (String line : headers.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String key = line.substring(0, colon).trim();
                    String val = line.substring(colon + 1).trim();
                    if (!key.isEmpty()) {
                        conn.setRequestProperty(key, val);
                    }
                }
            }
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);

            // Send body
            java.io.OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();

            int status = conn.getResponseCode();
            android.util.Log.i("Argos", "Java HTTP POST " + url + " -> " + status);

            // Read response
            java.io.InputStream is;
            if (status >= 200 && status < 300) {
                is = conn.getInputStream();
            } else {
                is = conn.getErrorStream();
                if (is == null) is = conn.getInputStream();
            }

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            is.close();
            conn.disconnect();

            String response = new String(baos.toByteArray(), "UTF-8");
            if (status < 200 || status >= 300) {
                return "[Error: HTTP " + status + ": " + response.substring(0, Math.min(200, response.length())) + "]";
            }
            return response;
        } catch (Exception e) {
            android.util.Log.e("Argos", "Java HTTP error: " + e.getMessage());
            return "[Error: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "]";
        }
    }

    private void sendNativeChat(String text) {
        if (!nativeLoaded) {
            chatInProgress = false;
            if (sendBtn != null) sendBtn.setEnabled(true);
            addMessage("Argos: The local AI engine is unavailable in this build. Please reinstall the complete APK.", Color.rgb(255, 150, 100));
            return;
        }
        try {
            nativeSendChat(text);
        } catch (UnsatisfiedLinkError e) {
            nativeLoaded = false;
            chatInProgress = false;
            if (sendBtn != null) sendBtn.setEnabled(true);
            addMessage("Argos: The local AI engine could not be loaded.", Color.rgb(255, 150, 100));
        }
    }

    // Native methods (only chat/agent — rendering is now in WebView/Three.js)
    private native void nativeInit(float screenWidth, float screenHeight);
    private native void nativeSendChat(String message);
    private native void nativeResume();
    private native void nativePause();
    private native void nativeDestroy();
    // Robot control (called from C++ tool execution)
    private native void nativeRobotExpression(String expression);
    private native void nativeRobotMoveTo(float x, float y);
    private native void nativeRobotSetState(String state);
    private native String nativeRobotGetPosition();

    // ── AI Robot Control (called from C++ via JNI) ──

    // Set robot expression from AI
    public void robotSetExpression(final String expression) {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            if (robotWebView != null) {
                robotWebView.evaluateJavascript("if(window.ArgosJS){ArgosJS.setExpression('" + expression + "');}", null);
            }
        });
    }

    // Move robot to position from AI
    public void robotMoveTo(final float x, final float y) {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            if (robotWebView != null) {
                robotWebView.evaluateJavascript("if(window.ArgosJS){ArgosJS.moveTo(" + x + "," + y + ");}", null);
            }
        });
    }

    // Get robot position (returns JSON string)
    public String robotGetPosition() {
        return "{\"x\":" + robotScreenX + ",\"y\":" + robotScreenY + ",\"size\":" + robotSize + ",\"scale\":" + currentScale + ",\"screenW\":" + screenWidth + ",\"screenH\":" + screenHeight + "}";
    }

    // Set robot zoom scale from AI (1.0=normal, 0.5=far/small, 1.5=close/big)
    public void robotSetZoom(final float scale) {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            if (robotWebView != null) {
                robotWebView.evaluateJavascript("if(window.ArgosJS){ArgosJS.setZoom(" + scale + ");}", null);
            }
        });
    }

    // Reset robot zoom to auto depth
    public void robotResetZoom() {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            if (robotWebView != null) {
                robotWebView.evaluateJavascript("if(window.ArgosJS){ArgosJS.resetZoom();}", null);
            }
        });
    }

    // Blink/teleport robot to position from AI
    public void robotBlinkTo(final float x, final float y) {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            if (robotWebView != null) {
                robotWebView.evaluateJavascript("if(window.ArgosJS){ArgosJS.blinkTo(" + x + "," + y + ");}", null);
            }
        });
    }

    // Set robot state from AI
    public void robotSetState(final String state) {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            if (robotWebView != null) {
                robotWebView.evaluateJavascript("if(window.ArgosJS){ArgosJS.setState('" + state + "');}", null);
            }
        });
    }

    // ── Task Scheduling and Tool Execution ──

    // Main-thread-safe JS evaluation. executeToolTags() now runs on a
    // background thread (many tools do PackageManager / ContentResolver /
    // file I/O / screenshot+OCR work), and WebView methods may only be called
    // from the thread that created the WebView.
    private void evalJs(final String js) {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            if (robotWebView != null) {
                robotWebView.evaluateJavascript(js, null);
            }
        });
    }

    // Parse and execute [TOOL:...] tags from AI response
    // NOTE: Most tools are now handled by the C++ tool loop (agent_client_core.cpp)
    // which executes tools and feeds results back to the AI for multi-step reasoning.
    // Only Java-only tools (EXPR, EXPRSEQ, LOOK, PASTE, COPY, SCHEDULE, OPEN, TYPE)
    // are handled here since they need direct access to the Android UI/WebView.
    //
    // MUST be called OFF the main thread: several tools block for seconds
    // (svc.observeScreen() takes a screenshot + runs OCR, listInstalledApps()
    // and the calendar/contacts queries hit ContentResolver, and the file tools
    // do disk I/O). Running them on the UI thread froze the WebView, which is
    // exactly what made Argos appear "stuck" right after a task.
    private void executeToolTags(String response) {
        java.util.regex.Pattern toolPattern = java.util.regex.Pattern.compile("\\[TOOL:([^\\]]+)\\]");
        java.util.regex.Matcher matcher = toolPattern.matcher(response);
        while (matcher.find()) {
            String tool = matcher.group(1).trim();
            // Only execute Java-only tools; skip tools handled by C++ loop
            if (tool.startsWith("EXPR:") || tool.startsWith("EXPRSEQ:") ||
                tool.startsWith("HAND:") ||
                tool.equals("LOOK") || tool.startsWith("PASTE:") ||
                tool.startsWith("COPY:") || tool.startsWith("SCHEDULE:") ||
                tool.startsWith("OPEN:") || tool.startsWith("TYPE:") ||
                tool.startsWith("BROWSER:") || tool.startsWith("SEARCH:") ||
                tool.equals("LIST_APPS") ||
                tool.startsWith("WRITE_FILE:") || tool.startsWith("READ_FILE:") ||
                tool.startsWith("LIST_FILES") || tool.startsWith("DELETE_FILE:") ||
                tool.startsWith("DELETE_FOLDER:") ||
                tool.startsWith("APPEND_FILE:") || tool.startsWith("EDIT_FILE:") ||
                tool.startsWith("CREATE_FOLDER:") || tool.startsWith("MOVE_FILE:") ||
                tool.startsWith("COPY_FILE:") || tool.startsWith("FILE_TREE") ||
                tool.startsWith("SEARCH_FILES:") || tool.startsWith("SEARCH_CONTENT:") ||
                tool.startsWith("FILE_VERSIONS:") || tool.startsWith("RESTORE_FILE:")) {
                executeTool(tool);
            }
        }
    }

    // Execute a single tool command
    private void executeTool(String tool) {
        if (tool.startsWith("PASTE:")) {
            String text = tool.substring(6).trim();
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                String result = svc.copyAndPaste(text);
                addMessage("📋 Pasted: " + text.substring(0, Math.min(50, text.length())) + "...", Color.rgb(100, 255, 100));
            } else {
                addMessage("📋 Accessibility not enabled — cannot paste", Color.rgb(255, 150, 100));
            }
        } else if (tool.startsWith("COPY:")) {
            String text = tool.substring(5).trim();
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                svc.copyToClipboard(text);
                addMessage("📋 Copied to clipboard", Color.rgb(100, 255, 100));
            }
        } else if (tool.startsWith("SCHEDULE:")) {
            // Format: SCHEDULE:HH:MM:task description
            String rest = tool.substring(9).trim();
            int colonIdx = rest.indexOf(':');
            if (colonIdx > 0) {
                String timeStr = rest.substring(0, colonIdx).trim();
                String taskDesc = rest.substring(colonIdx + 1).trim();
                scheduleTask(timeStr, taskDesc);
            }
        } else if (tool.startsWith("OPEN:")) {
            String pkg = tool.substring(5).trim();
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                svc.openApp(pkg);
                addMessage("📱 Opening app: " + pkg, Color.rgb(100, 255, 100));
            }
        } else if (tool.startsWith("BROWSER:")) {
            String url = tool.substring(8).trim();
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                String result = svc.openUrl(url);
                addMessage("🌐 Opening browser: " + url, Color.rgb(100, 255, 100));
            } else {
                addMessage("🌐 Accessibility not enabled", Color.rgb(255, 150, 100));
            }
        } else if (tool.startsWith("SEARCH:")) {
            String query = tool.substring(7).trim();
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                try {
                    String encoded = java.net.URLEncoder.encode(query, "UTF-8");
                    svc.openUrl("https://www.google.com/search?q=" + encoded);
                } catch (java.io.UnsupportedEncodingException e) {
                    svc.openUrl("https://www.google.com/search?q=" + query.replace(" ", "+"));
                }
                addMessage("🔍 Searching: " + query, Color.rgb(100, 255, 100));
            } else {
                addMessage("🔍 Accessibility not enabled", Color.rgb(255, 150, 100));
            }
        } else if (tool.equals("LIST_APPS")) {
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                String result = svc.listInstalledApps();
                addMessage("📱 Installed apps: " + result.substring(0, Math.min(200, result.length())) + "...", Color.rgb(100, 255, 100));
            } else {
                addMessage("📱 Accessibility not enabled", Color.rgb(255, 150, 100));
            }
        } else if (tool.startsWith("TYPE:")) {
            String text = tool.substring(5).trim();
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                svc.typeText(text);
                addMessage("⌨️ Typed: " + text.substring(0, Math.min(50, text.length())), Color.rgb(100, 255, 100));
            }
        } else if (tool.startsWith("EXPR:")) {
            // Single expression: EXPR:HAPPY or EXPR:SURPRISED
            String expr = tool.substring(5).trim();
            evalJs("if(window.ArgosJS){ArgosJS.setExpression('" + expr + "');}");
        } else if (tool.startsWith("EXPRSEQ:")) {
            // Expression sequence: EXPRSEQ:[{"expr":"HAPPY","duration":1.5},{"expr":"EXCITED","duration":1.0}]
            String seqJson = tool.substring(8).trim();
            robotPlayExpressionSequence(seqJson);
        } else if (tool.startsWith("HAND:")) {
            // Hand gesture: HAND:WAVE, HAND:POINT, HAND:HEART, etc.
            String gesture = tool.substring(5).trim();
            evalJs("if(window.ArgosJS){ArgosJS.setHandGesture('" + gesture + "');}");
        } else if (tool.equals("LOOK")) {
            // Trigger 180-degree turn to look at screen
            robotLookAtScreen();
        } else if (tool.startsWith("DIAL:")) {
            String number = tool.substring(5).trim();
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                String result = svc.dialPhoneNumber(number);
                addMessage("📞 Dialing: " + number, Color.rgb(100, 255, 100));
            } else {
                addMessage("📞 Accessibility not enabled", Color.rgb(255, 150, 100));
            }
        } else if (tool.startsWith("SMS:")) {
            // Format: SMS:number|message
            String rest = tool.substring(4).trim();
            int pipeIdx = rest.indexOf('|');
            if (pipeIdx > 0) {
                String number = rest.substring(0, pipeIdx).trim();
                String message = rest.substring(pipeIdx + 1).trim();
                ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
                if (svc != null) {
                    svc.sendSmsViaIntent(number, message);
                    addMessage("💬 SMS ready: " + number, Color.rgb(100, 255, 100));
                } else {
                    addMessage("💬 Accessibility not enabled", Color.rgb(255, 150, 100));
                }
            }
        } else if (tool.startsWith("CONTACTS:")) {
            String query = tool.substring(9).trim();
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                String result = svc.searchContacts(query);
                addMessage("👤 Contacts: " + result, Color.rgb(100, 255, 100));
            } else {
                addMessage("👤 Accessibility not enabled", Color.rgb(255, 150, 100));
            }
        } else if (tool.startsWith("CALENDAR_NEW:")) {
            // Format: CALENDAR_NEW:title|description|startMillis|endMillis
            String rest = tool.substring(13).trim();
            String[] parts = rest.split("\\|", 4);
            String title = parts.length > 0 ? parts[0].trim() : "Event";
            String desc = parts.length > 1 ? parts[1].trim() : "";
            long start = parts.length > 2 ? Long.parseLong(parts[2].trim()) : 0;
            long end = parts.length > 3 ? Long.parseLong(parts[3].trim()) : 0;
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                svc.createCalendarEvent(title, desc, start, end);
                addMessage("📅 Calendar event: " + title, Color.rgb(100, 255, 100));
            }
        } else if (tool.startsWith("CALENDAR_READ:")) {
            String daysStr = tool.substring(14).trim();
            int days = daysStr.isEmpty() ? 7 : Integer.parseInt(daysStr);
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                String result = svc.readCalendarEvents(days);
                addMessage("📅 Calendar: " + result, Color.rgb(100, 255, 100));
            }
        } else if (tool.startsWith("TIMER:")) {
            // Format: TIMER:seconds|label
            String rest = tool.substring(6).trim();
            int pipeIdx = rest.indexOf('|');
            int seconds = pipeIdx > 0 ? Integer.parseInt(rest.substring(0, pipeIdx).trim()) : Integer.parseInt(rest);
            String label = pipeIdx > 0 ? rest.substring(pipeIdx + 1).trim() : "Argos Timer";
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                svc.setTimer(seconds, label);
                addMessage("⏱️ Timer set: " + seconds + "s", Color.rgb(100, 255, 100));
            }
        } else if (tool.startsWith("ALARM_INTENT:")) {
            // Format: ALARM_INTENT:hour:minute|label
            String rest = tool.substring(13).trim();
            int pipeIdx = rest.indexOf('|');
            String timePart = pipeIdx > 0 ? rest.substring(0, pipeIdx).trim() : rest;
            String label = pipeIdx > 0 ? rest.substring(pipeIdx + 1).trim() : "Argos Alarm";
            int colonIdx = timePart.indexOf(':');
            int hour = colonIdx > 0 ? Integer.parseInt(timePart.substring(0, colonIdx).trim()) : Integer.parseInt(timePart);
            int minute = colonIdx > 0 ? Integer.parseInt(timePart.substring(colonIdx + 1).trim()) : 0;
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                svc.setAlarm(hour, minute, label);
                addMessage("⏰ Alarm set: " + String.format("%02d:%02d", hour, minute), Color.rgb(100, 255, 100));
            }
        } else if (tool.equals("BATTERY")) {
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                String result = svc.getBatteryStatus();
                addMessage("🔋 Battery: " + result, Color.rgb(100, 255, 100));
            }
        } else if (tool.startsWith("FLASHLIGHT:")) {
            String mode = tool.substring(11).trim().toLowerCase();
            boolean on = mode.equals("on") || mode.equals("true") || mode.equals("1");
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                svc.toggleFlashlight(on);
                addMessage("🔦 Flashlight " + (on ? "on" : "off"), Color.rgb(100, 255, 100));
            }
        } else if (tool.startsWith("MAPS:")) {
            String query = tool.substring(5).trim();
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                svc.openMaps(query);
                addMessage("🗺️ Maps: " + query, Color.rgb(100, 255, 100));
            }
        } else if (tool.startsWith("NAVIGATE:")) {
            String dest = tool.substring(9).trim();
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                svc.startNavigation(dest);
                addMessage("🧭 Navigating to: " + dest, Color.rgb(100, 255, 100));
            }
        } else if (tool.startsWith("SHARE:")) {
            String text = tool.substring(6).trim();
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                svc.shareContent(text);
                addMessage("📤 Share sheet opened", Color.rgb(100, 255, 100));
            }
        } else if (tool.startsWith("MUSIC:")) {
            String query = tool.substring(6).trim();
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                svc.playMusic(query);
                addMessage("🎵 Playing: " + query, Color.rgb(100, 255, 100));
            }
        } else if (tool.startsWith("SETTINGS:")) {
            String type = tool.substring(9).trim();
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                svc.openSettings(type);
                addMessage("⚙️ Settings: " + type, Color.rgb(100, 255, 100));
            }
        } else if (tool.equals("CLIPBOARD_READ")) {
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                String result = svc.readClipboard();
                addMessage("📋 Clipboard: " + result, Color.rgb(100, 255, 100));
            }
        } else if (tool.equals("LOCATION")) {
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                String result = svc.getLocation();
                addMessage("📍 Location: " + result, Color.rgb(100, 255, 100));
            }
        } else if (tool.equals("BACK")) {
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                svc.goBack();
                addMessage("⬅️ Back", Color.rgb(100, 255, 100));
            }
        } else if (tool.equals("HOME")) {
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                svc.goHome();
                addMessage("🏠 Home", Color.rgb(100, 255, 100));
            }
        } else if (tool.equals("RECENTS")) {
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                svc.openRecents();
                addMessage("📋 Recents", Color.rgb(100, 255, 100));
            }
        } else if (tool.equals("SCREEN_TEXT")) {
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                String result = svc.getScreenText();
                addMessage("📱 Screen text: " + result.substring(0, Math.min(200, result.length())), Color.rgb(100, 255, 100));
            }
        } else if (tool.startsWith("SCREEN_CLICK:")) {
            String text = tool.substring(13).trim();
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                String result = svc.clickText(text);
                addMessage("👆 Clicked: " + text, Color.rgb(100, 255, 100));
            }
        } else if (tool.startsWith("SCREEN_SCROLL:")) {
            String dir = tool.substring(14).trim();
            int direction = dir.toLowerCase().equals("down") ? 1 : 0;
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                svc.scrollScreen(direction);
                addMessage("📜 Scrolled " + dir, Color.rgb(100, 255, 100));
            }
        } else if (tool.equals("SCREEN_OBSERVE")) {
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                String result = svc.observeScreen();
                addMessage("👁️ Observed screen: " + result.substring(0, Math.min(200, result.length())), Color.rgb(100, 255, 100));
            }
        } else if (tool.equals("SCREEN_ELEMENTS")) {
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                String result = svc.getClickableElements();
                addMessage("🔍 Elements: " + result.substring(0, Math.min(200, result.length())), Color.rgb(100, 255, 100));
            }
        } else if (tool.equals("SCREEN_ACTIVE")) {
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                String result = svc.getActiveApp();
                addMessage("📱 Active app: " + result, Color.rgb(100, 255, 100));
            }
        } else if (tool.equals("NOTIFICATIONS")) {
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                String result = svc.getNotifications();
                addMessage("🔔 Notifications: " + result.substring(0, Math.min(200, result.length())), Color.rgb(100, 255, 100));
            }
        } else if (tool.startsWith("NOTIFICATION_REPLY:")) {
            // Format: NOTIFICATION_REPLY:index|message
            String rest = tool.substring(19).trim();
            int pipeIdx = rest.indexOf('|');
            if (pipeIdx > 0) {
                int index = Integer.parseInt(rest.substring(0, pipeIdx).trim());
                String message = rest.substring(pipeIdx + 1).trim();
                ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
                if (svc != null) {
                    svc.replyToNotification(index, message);
                    addMessage("🔔 Replied to notification " + index, Color.rgb(100, 255, 100));
                }
            }
        } else if (tool.startsWith("SWIPE:")) {
            String dir = tool.substring(6).trim().toLowerCase();
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                switch (dir) {
                    case "up": svc.swipeUp(); break;
                    case "down": svc.swipeDown(); break;
                    case "left": svc.swipeLeft(); break;
                    case "right": svc.swipeRight(); break;
                }
                addMessage("👋 Swiped " + dir, Color.rgb(100, 255, 100));
            }
        } else if (tool.startsWith("TAP:")) {
            // Format: TAP:x,y
            String rest = tool.substring(4).trim();
            String[] coords = rest.split(",");
            if (coords.length == 2) {
                int x = Integer.parseInt(coords[0].trim());
                int y = Integer.parseInt(coords[1].trim());
                ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
                if (svc != null) {
                    svc.clickAtPoint(x, y);
                    addMessage("👆 Tapped at " + x + "," + y, Color.rgb(100, 255, 100));
                }
            }
        } else if (tool.startsWith("SMART_CLICK:")) {
            // Format: SMART_CLICK:x,y
            String rest = tool.substring(12).trim();
            String[] coords = rest.split(",");
            if (coords.length == 2) {
                int x = Integer.parseInt(coords[0].trim());
                int y = Integer.parseInt(coords[1].trim());
                ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
                if (svc != null) {
                    svc.smartClick(x, y);
                    addMessage("👆 Smart clicked at " + x + "," + y, Color.rgb(100, 255, 100));
                }
            }
        } else if (tool.startsWith("WRITE_FILE:")) {
            // Format: WRITE_FILE:path|content  (supports subfolders)
            String rest = tool.substring(11).trim();
            int pipeIdx = rest.indexOf('|');
            if (pipeIdx > 0) {
                String filepath = rest.substring(0, pipeIdx).trim();
                String content = rest.substring(pipeIdx + 1).trim();
                try {
                    String path = fileManager.writeFile(filepath, content, false);
                    if (path != null) {
                        addMessage("📝 Saved: " + filepath + " (" + content.length() + " chars)", Color.rgb(100, 255, 100));
                    } else {
                        addMessage("📝 Failed to write: " + filepath, Color.rgb(255, 150, 100));
                    }
                } catch (SecurityException e) {
                    addMessage("🚫 Access denied: " + filepath + " (outside Argus folder)", Color.rgb(255, 100, 100));
                }
            }
        } else if (tool.startsWith("APPEND_FILE:")) {
            // Format: APPEND_FILE:path|content  (supports subfolders)
            String rest = tool.substring(12).trim();
            int pipeIdx = rest.indexOf('|');
            if (pipeIdx > 0) {
                String filepath = rest.substring(0, pipeIdx).trim();
                String content = rest.substring(pipeIdx + 1).trim();
                try {
                    String path = fileManager.writeFile(filepath, content, true);
                    if (path != null) {
                        addMessage("📝 Appended to: " + filepath, Color.rgb(100, 255, 100));
                    } else {
                        addMessage("📝 Failed to append to: " + filepath, Color.rgb(255, 150, 100));
                    }
                } catch (SecurityException e) {
                    addMessage("🚫 Access denied: " + filepath + " (outside Argus folder)", Color.rgb(255, 100, 100));
                }
            }
        } else if (tool.startsWith("EDIT_FILE:")) {
            // Format: EDIT_FILE:path|old_text|new_text  (find-and-replace)
            String rest = tool.substring(10).trim();
            // Split into 3 parts: path|old_text|new_text
            // We need to find the first and second pipe, but the new_text may contain pipes
            int firstPipe = rest.indexOf('|');
            if (firstPipe > 0) {
                String filepath = rest.substring(0, firstPipe).trim();
                String remaining = rest.substring(firstPipe + 1);
                int secondPipe = remaining.indexOf('|');
                if (secondPipe >= 0) {
                    String oldText = remaining.substring(0, secondPipe);
                    String newText = remaining.substring(secondPipe + 1);
                    try {
                        int count = fileManager.editFile(filepath, oldText, newText);
                        if (count > 0) {
                            addMessage("✏️ Edited: " + filepath + " (" + count + " replacement(s))", Color.rgb(100, 255, 100));
                        } else if (count == 0) {
                            addMessage("✏️ No matches found in: " + filepath, Color.rgb(255, 180, 80));
                        } else {
                            addMessage("✏️ Failed to edit: " + filepath, Color.rgb(255, 150, 100));
                        }
                    } catch (SecurityException e) {
                        addMessage("🚫 Access denied: " + filepath + " (outside Argus folder)", Color.rgb(255, 100, 100));
                    }
                }
            }
        } else if (tool.startsWith("READ_FILE:")) {
            // Format: READ_FILE:path  (supports subfolders)
            String filepath = tool.substring(10).trim();
            try {
                String content = fileManager.readFile(filepath);
                if (content != null) {
                    String preview = content.length() > 300 ? content.substring(0, 300) + "..." : content;
                    addMessage("📄 " + filepath + ": " + preview, Color.rgb(100, 255, 100));
                } else {
                    addMessage("📄 File not found: " + filepath, Color.rgb(255, 150, 100));
                }
            } catch (SecurityException e) {
                addMessage("🚫 Access denied: " + filepath + " (outside Argus folder)", Color.rgb(255, 100, 100));
            }
        } else if (tool.startsWith("LIST_FILES")) {
            // Format: LIST_FILES or LIST_FILES:folderpath
            String folderPath = tool.length() > 10 ? tool.substring(10).replace(":", "").trim() : "";
            if (folderPath.isEmpty()) folderPath = "";
            try {
                java.util.List<ArgosFileManager.FileInfo> files = fileManager.listFiles(folderPath);
                if (files.isEmpty()) {
                    addMessage("📂 No files in " + (folderPath.isEmpty() ? "Argus folder" : folderPath), Color.rgb(100, 255, 100));
                } else {
                    StringBuilder sb = new StringBuilder("📂 " + (folderPath.isEmpty() ? "Argus folder" : folderPath) + " (" + files.size() + " items):\n");
                    for (ArgosFileManager.FileInfo fi : files) {
                        sb.append("  ").append(fi.getTypeIcon()).append(" ")
                          .append(fi.name);
                        if (fi.isDirectory) {
                            sb.append("/");
                        } else {
                            sb.append(" (").append(fi.getFormattedSize()).append(")");
                        }
                        sb.append("\n");
                    }
                    addMessage(sb.toString().trim(), Color.rgb(100, 255, 100));
                }
            } catch (SecurityException e) {
                addMessage("🚫 Access denied: " + folderPath, Color.rgb(255, 100, 100));
            }
        } else if (tool.startsWith("FILE_TREE")) {
            // Format: FILE_TREE or FILE_TREE:folderpath
            String folderPath = tool.length() > 10 ? tool.substring(10).replace(":", "").trim() : "";
            try {
                java.util.List<ArgosFileManager.FileInfo> tree = fileManager.fileTree(folderPath);
                if (tree.isEmpty()) {
                    addMessage("📂 No files found.", Color.rgb(100, 255, 100));
                } else {
                    StringBuilder sb = new StringBuilder("📂 Argus Files Tree:\n");
                    for (ArgosFileManager.FileInfo fi : tree) {
                        for (int d = 0; d < fi.depth; d++) sb.append("  ");
                        sb.append(fi.getTypeIcon()).append(" ").append(fi.name);
                        if (fi.isDirectory) sb.append("/");
                        sb.append("\n");
                    }
                    addMessage(sb.toString().trim(), Color.rgb(100, 255, 100));
                }
            } catch (SecurityException e) {
                addMessage("🚫 Access denied", Color.rgb(255, 100, 100));
            }
        } else if (tool.startsWith("CREATE_FOLDER:")) {
            String folderPath = tool.substring(14).trim();
            try {
                if (fileManager.createFolder(folderPath)) {
                    addMessage("📁 Created folder: " + folderPath, Color.rgb(100, 255, 100));
                } else {
                    addMessage("📁 Could not create folder: " + folderPath, Color.rgb(255, 150, 100));
                }
            } catch (SecurityException e) {
                addMessage("🚫 Access denied: " + folderPath, Color.rgb(255, 100, 100));
            }
        } else if (tool.startsWith("MOVE_FILE:")) {
            // Format: MOVE_FILE:old_path|new_path
            String rest = tool.substring(10).trim();
            int pipeIdx = rest.indexOf('|');
            if (pipeIdx > 0) {
                String oldPath = rest.substring(0, pipeIdx).trim();
                String newPath = rest.substring(pipeIdx + 1).trim();
                try {
                    if (fileManager.moveFile(oldPath, newPath)) {
                        addMessage("📦 Moved: " + oldPath + " → " + newPath, Color.rgb(100, 255, 100));
                    } else {
                        addMessage("📦 Could not move: " + oldPath, Color.rgb(255, 150, 100));
                    }
                } catch (SecurityException e) {
                    addMessage("🚫 Access denied (outside Argus folder)", Color.rgb(255, 100, 100));
                }
            }
        } else if (tool.startsWith("COPY_FILE:")) {
            // Format: COPY_FILE:src_path|dest_path
            String rest = tool.substring(10).trim();
            int pipeIdx = rest.indexOf('|');
            if (pipeIdx > 0) {
                String srcPath = rest.substring(0, pipeIdx).trim();
                String destPath = rest.substring(pipeIdx + 1).trim();
                try {
                    if (fileManager.copyFile(srcPath, destPath)) {
                        addMessage("📋 Copied: " + srcPath + " → " + destPath, Color.rgb(100, 255, 100));
                    } else {
                        addMessage("📋 Could not copy: " + srcPath, Color.rgb(255, 150, 100));
                    }
                } catch (SecurityException e) {
                    addMessage("🚫 Access denied (outside Argus folder)", Color.rgb(255, 100, 100));
                }
            }
        } else if (tool.startsWith("SEARCH_FILES:")) {
            String query = tool.substring(13).trim();
                java.util.List<ArgosFileManager.FileInfo> results = fileManager.searchFiles(query);
                if (results.isEmpty()) {
                    addMessage("🔍 No files matching: " + query, Color.rgb(100, 255, 100));
                } else {
                    StringBuilder sb = new StringBuilder("🔍 Found " + results.size() + " file(s):\n");
                    for (ArgosFileManager.FileInfo fi : results) {
                        sb.append("  • ").append(fi.relativePath).append("\n");
                    }
                    addMessage(sb.toString().trim(), Color.rgb(100, 255, 100));
                }
        } else if (tool.startsWith("SEARCH_CONTENT:")) {
            String query = tool.substring(15).trim();
                java.util.List<ArgosFileManager.SearchResult> results = fileManager.searchContent(query);
                if (results.isEmpty()) {
                    addMessage("🔍 No content matching: " + query, Color.rgb(100, 255, 100));
                } else {
                    StringBuilder sb = new StringBuilder("🔍 Found in " + results.size() + " file(s):\n");
                    for (ArgosFileManager.SearchResult sr : results) {
                        String linePreview = sr.matchingLine.length() > 80 ?
                            sr.matchingLine.substring(0, 80) + "..." : sr.matchingLine;
                        sb.append("  • ").append(sr.filePath).append(": \"").append(linePreview).append("\"\n");
                    }
                    addMessage(sb.toString().trim(), Color.rgb(100, 255, 100));
                }
        } else if (tool.startsWith("FILE_VERSIONS:")) {
            // List version history for a file
            String filepath = tool.substring(14).trim();
            try {
                java.util.List<ArgosFileManager.VersionInfo> versions = fileManager.listVersions(filepath);
                if (versions.isEmpty()) {
                    addMessage("📜 No version history for: " + filepath, Color.rgb(100, 255, 100));
                } else {
                    StringBuilder sb = new StringBuilder("📜 Version history for " + filepath + ":\n");
                    for (int i = 0; i < versions.size(); i++) {
                        ArgosFileManager.VersionInfo vi = versions.get(i);
                        sb.append("  ").append(i + 1).append(". ").append(vi.getFormattedDate())
                          .append(" (").append(vi.size).append(" bytes)\n");
                    }
                    addMessage(sb.toString().trim(), Color.rgb(100, 255, 100));
                }
            } catch (SecurityException e) {
                addMessage("🚫 Access denied: " + filepath, Color.rgb(255, 100, 100));
            }
        } else if (tool.startsWith("RESTORE_FILE:")) {
            // Restore a file from a backup version
            String backupName = tool.substring(13).trim();
            String path = fileManager.restoreVersion(backupName);
            if (path != null) {
                addMessage("♻️ Restored: " + backupName, Color.rgb(100, 255, 100));
            } else {
                addMessage("♻️ Could not restore: " + backupName, Color.rgb(255, 150, 100));
            }
        } else if (tool.startsWith("DELETE_FILE:")) {
            // Destructive operation — show approval card
            String filepath = tool.substring(12).trim();
            try {
                // Check path safety first (getFileInfo catches SecurityException internally)
                if (!fileManager.isPathSafe(filepath)) {
                    addMessage("🚫 Access denied: " + filepath + " (outside Argus folder)", Color.rgb(255, 100, 100));
                } else {
                    ArgosFileManager.FileInfo info = fileManager.getFileInfo(filepath);
                    if (info == null) {
                        addMessage("🗑 File not found: " + filepath, Color.rgb(255, 150, 100));
                    } else {
                    // Show approval card
                    showFileApprovalCard("Delete File?",
                        "Argus wants to delete: " + filepath + " (" + info.getFormattedSize() + ")\n" +
                        "A backup will be saved before deletion.",
                        () -> {
                            if (fileManager.deleteFile(filepath)) {
                                addMessage("🗑 Deleted: " + filepath + " (backup saved)", Color.rgb(100, 255, 100));
                            } else {
                                addMessage("🗑 Could not delete: " + filepath, Color.rgb(255, 150, 100));
                            }
                        });
                    }
                }
            } catch (SecurityException e) {
                addMessage("🚫 Access denied: " + filepath + " (outside Argus folder)", Color.rgb(255, 100, 100));
            }
        } else if (tool.startsWith("DELETE_FOLDER:")) {
            // Destructive operation — show approval card
            String folderPath = tool.substring(14).trim();
            try {
                if (fileManager.isPathSafe(folderPath)) {
                    showFileApprovalCard("Delete Folder?",
                        "Argus wants to delete folder: " + folderPath + "\n" +
                        "Only empty folders can be deleted.",
                        () -> {
                            if (fileManager.deleteFolder(folderPath)) {
                                addMessage("🗑 Deleted folder: " + folderPath, Color.rgb(100, 255, 100));
                            } else {
                                addMessage("🗑 Could not delete folder (not empty or doesn't exist)", Color.rgb(255, 150, 100));
                            }
                        });
                } else {
                    addMessage("🚫 Access denied: " + folderPath, Color.rgb(255, 100, 100));
                }
            } catch (SecurityException e) {
                addMessage("🚫 Access denied: " + folderPath, Color.rgb(255, 100, 100));
            }
        }
    }

    // ── File Approval Card UI ──
    // Shows a confirmation card overlay when the AI wants to perform a
    // destructive file operation (delete, overwrite). The user must approve
    // before the action is executed. Follows the InnerZero/OneClaw pattern.
    private View approvalCardView;
    private WindowManager.LayoutParams approvalCardParams;

    private void showFileApprovalCard(String title, String description, Runnable onApprove) {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            // Remove any existing approval card
            if (approvalCardView != null) {
                try { windowManager.removeView(approvalCardView); } catch (Exception e) {}
            }

            float density = getResources().getDisplayMetrics().density;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(Color.argb(245, 20, 20, 28));
            card.setPadding(28, 24, 28, 24);

            android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
            cardBg.setColor(Color.argb(245, 20, 20, 28));
            cardBg.setCornerRadius(20f);
            cardBg.setStroke(2, Color.rgb(255, 180, 80)); // orange border = warning
            card.setBackground(cardBg);

            // Title
            TextView titleView = new TextView(this);
            titleView.setText("⚠ " + title);
            titleView.setTextColor(Color.rgb(255, 180, 80));
            titleView.setTextSize(16f);
            titleView.setTypeface(null, android.graphics.Typeface.BOLD);
            card.addView(titleView);

            // Description
            TextView descView = new TextView(this);
            descView.setText(description);
            descView.setTextColor(Color.rgb(220, 220, 230));
            descView.setTextSize(14f);
            descView.setPadding(0, 12, 0, 16);
            card.addView(descView);

            // Button row
            LinearLayout btnRow = new LinearLayout(this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.END);

            // Deny button
            TextView denyBtn = new TextView(this);
            denyBtn.setText("✕ Deny");
            denyBtn.setTextColor(Color.rgb(200, 200, 210));
            denyBtn.setTextSize(14f);
            denyBtn.setPadding(32, 16, 32, 16);
            android.graphics.drawable.GradientDrawable denyBg = new android.graphics.drawable.GradientDrawable();
            denyBg.setColor(Color.argb(60, 100, 100, 120));
            denyBg.setCornerRadius(10f);
            denyBtn.setBackground(denyBg);
            denyBtn.setOnClickListener(v -> {
                addMessage("🚫 File operation denied by user", Color.rgb(200, 200, 210));
                try { windowManager.removeView(card); } catch (Exception e) {}
                approvalCardView = null;
            });
            btnRow.addView(denyBtn);

            // Spacer
            View spacer = new View(this);
            LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams((int)(12 * density), 0);
            btnRow.addView(spacer);

            // Approve button
            TextView approveBtn = new TextView(this);
            approveBtn.setText("✓ Approve");
            approveBtn.setTextColor(Color.rgb(255, 255, 255));
            approveBtn.setTextSize(14f);
            approveBtn.setTypeface(null, android.graphics.Typeface.BOLD);
            approveBtn.setPadding(32, 16, 32, 16);
            android.graphics.drawable.GradientDrawable approveBg = new android.graphics.drawable.GradientDrawable();
            approveBg.setColor(Color.rgb(255, 100, 80));
            approveBg.setCornerRadius(10f);
            approveBtn.setBackground(approveBg);
            approveBtn.setOnClickListener(v -> {
                try { windowManager.removeView(card); } catch (Exception e) {}
                approvalCardView = null;
                if (onApprove != null) onApprove.run();
            });
            btnRow.addView(approveBtn);

            card.addView(btnRow);

            // Position near top of screen, centered horizontally
            approvalCardParams = new WindowManager.LayoutParams(
                (int)(320 * density), WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            );
            approvalCardParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            approvalCardParams.y = (int)(80 * density);

            approvalCardView = card;
            try {
                windowManager.addView(card, approvalCardParams);
            } catch (Exception e) {
                android.util.Log.w("ArgosApproval", "Could not show approval card: " + e.getMessage());
                // Fallback: just execute the operation (better than blocking the AI)
                if (onApprove != null) onApprove.run();
            }

            // Auto-deny after 30 seconds if no response
            handler.postDelayed(() -> {
                if (approvalCardView == card) {
                    try { windowManager.removeView(card); } catch (Exception e) {}
                    approvalCardView = null;
                    addMessage("⏰ File operation timed out (no response)", Color.rgb(200, 200, 210));
                }
            }, 30000);
        });
    }

    // Schedule a task at a specific time
    // timeStr formats: "8:00", "08:00", "8am", "12pm", "8:00am", "20:00"
    private void scheduleTask(String timeStr, String taskDesc) {
        try {
            int hour, minute;
            boolean isPM = timeStr.toLowerCase().contains("pm");
            boolean isAM = timeStr.toLowerCase().contains("am");

            // Remove am/pm suffix
            String cleanTime = timeStr.toLowerCase().replace("am", "").replace("pm", "").trim();

            if (cleanTime.contains(":")) {
                String[] parts = cleanTime.split(":");
                hour = Integer.parseInt(parts[0].trim());
                minute = Integer.parseInt(parts[1].trim());
            } else {
                hour = Integer.parseInt(cleanTime.trim());
                minute = 0;
            }

            // Convert 12-hour to 24-hour
            if (isPM && hour < 12) hour += 12;
            if (isAM && hour == 12) hour = 0;

            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                addMessage("⚠️ Invalid time: " + timeStr, Color.rgb(255, 150, 100));
                return;
            }

            // Calculate alarm time
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, hour);
            cal.set(java.util.Calendar.MINUTE, minute);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);

            // If time has passed today, schedule for tomorrow
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
            }

            int taskId = taskCounter++;
            String displayTime = String.format("%02d:%02d", hour, minute);
            String taskEntry = displayTime + " - " + taskDesc;
            scheduledTaskList.add(taskEntry);
            saveTasksLocally();

            // Set alarm — use exact if permitted, otherwise fall back to inexact
            android.app.AlarmManager alarmMgr = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            Intent intent = new Intent(this, ScheduledTaskReceiver.class);
            intent.putExtra("task_text", taskDesc);
            intent.putExtra("task_id", taskId);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, taskId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmMgr.canScheduleExactAlarms()) {
                    alarmMgr.setExact(android.app.AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pendingIntent);
                } else {
                    // No exact alarm permission — use inexact alarm as fallback
                    alarmMgr.set(android.app.AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pendingIntent);
                }
            } else {
                alarmMgr.setExact(android.app.AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pendingIntent);
            }

            String dayStr = (cal.get(java.util.Calendar.DAY_OF_YEAR) ==
                java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)) ? "today" : "tomorrow";
            addMessage("⏰ Scheduled for " + displayTime + " " + dayStr + ": " + taskDesc, Color.rgb(100, 255, 100));

        } catch (Exception e) {
            addMessage("⚠️ Could not parse time: " + timeStr + " — " + e.getMessage(), Color.rgb(255, 150, 100));
        }
    }

    // Called by ScheduledTaskReceiver when a task fires
    public void onScheduledTaskFired(String taskText) {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            addMessage("⏰ Reminder: " + taskText, Color.rgb(255, 200, 50));
            // Speak the reminder — force=true so it fires even when screen is off
            ttsSpeakJava("Reminder: " + taskText, true);
            // Show thought bubble (will only show if screen is on)
            showThoughtBubble("⏰ " + taskText);
            // Trigger the AI with the task and the user's full schedule
            // so the AI can provide context-aware help
            String scheduleContext = getScheduleForAI();
            String aiPrompt = "A scheduled reminder just fired: '" + taskText + "'.\n\n"
                + "Here is the user's full schedule:\n" + scheduleContext + "\n\n"
                + "Briefly remind the user about this task and mention any upcoming tasks if relevant. Keep it short and helpful.";
            sendNativeChat(aiPrompt);
            notifyRobotThinking();
        });
    }

    // Get list of scheduled tasks for display
    public java.util.List<String> getScheduledTasks() {
        return new java.util.ArrayList<>(scheduledTaskList);
    }

    // ── Long Press Menu & Task Management ──

    // Show the long-press popup menu near the robot (grid layout)
    private void showLongPressMenu() {
        // If already showing, hide it
        if (longPressMenu != null && longPressMenu.getParent() != null) {
            hideLongPressMenu();
            return;
        }
        if (settingsOverlay != null && settingsOverlay.getParent() != null) {
            hideSettingsOverlay();
            return;
        }

        // Use a LinearLayout with rows of action buttons
        LinearLayout menuContainer = new LinearLayout(this);
        menuContainer.setOrientation(LinearLayout.VERTICAL);
        menuContainer.setPadding(20, 20, 20, 20);

        // Black background with neon blue border
        android.graphics.drawable.GradientDrawable menuBg = new android.graphics.drawable.GradientDrawable();
        menuBg.setColor(Color.argb(240, 10, 12, 20));
        menuBg.setCornerRadius(20f);
        menuBg.setStroke(2, Color.rgb(0, 229, 255));
        menuContainer.setBackground(menuBg);

        // Button background style
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setColor(Color.argb(255, 20, 22, 35));
        btnBg.setCornerRadius(14f);
        btnBg.setStroke(2, Color.rgb(0, 229, 255));

        // Button dimensions
        int btnW = (int) (screenWidth * 0.20);
        int btnH = (int) (screenWidth * 0.14);
        if (btnW < 150) btnW = 150;
        if (btnH < 110) btnH = 110;
        int gap = 16;

        // ── ROW: Settings (left) + Standby (right) ──
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams topRowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        topRowParams.setMargins(0, 0, 0, gap);

        // Settings button (left)
        LinearLayout settingsBtn = createRadialButton("⚙", "Settings", btnBg);
        settingsBtn.setOnClickListener(v -> {
            hideLongPressMenu();
            showSettingsOverlay();
        });
        LinearLayout.LayoutParams settingsBtnParams = new LinearLayout.LayoutParams(btnW, btnH);
        settingsBtnParams.setMargins(0, 0, gap / 2, 0);
        topRow.addView(settingsBtn, settingsBtnParams);

        // Standby button (right)
        LinearLayout standbyBtn = createRadialButton(
            standbyMode ? "▶" : "⏸",
            standbyMode ? "Activate" : "Standby",
            btnBg);
        standbyBtn.setOnClickListener(v -> {
            toggleStandby();
            TextView standbyLabel = (TextView) standbyBtn.getChildAt(1);
            if (standbyMode) {
                standbyLabel.setText("Activate");
                ((TextView) standbyBtn.getChildAt(0)).setText("▶");
            } else {
                standbyLabel.setText("Standby");
                ((TextView) standbyBtn.getChildAt(0)).setText("⏸");
            }
            hideLongPressMenu();
        });
        LinearLayout.LayoutParams standbyBtnParams = new LinearLayout.LayoutParams(btnW, btnH);
        standbyBtnParams.setMargins(gap / 2, 0, 0, 0);
        topRow.addView(standbyBtn, standbyBtnParams);

        menuContainer.addView(topRow, topRowParams);

        // ── EXIT button (bottom-center, full width) ──
        TextView exitBtn = new TextView(this);
        exitBtn.setText("✕ EXIT");
        exitBtn.setTextColor(Color.rgb(255, 80, 80));
        exitBtn.setTextSize(13f);
        exitBtn.setGravity(Gravity.CENTER);
        exitBtn.setPadding(24, 14, 24, 14);
        android.graphics.drawable.GradientDrawable exitBg = new android.graphics.drawable.GradientDrawable();
        exitBg.setColor(Color.argb(255, 20, 22, 35));
        exitBg.setCornerRadius(12f);
        exitBg.setStroke(1, Color.rgb(255, 80, 80));
        exitBtn.setBackground(exitBg);
        exitBtn.setOnClickListener(v -> {
            hideLongPressMenu();
            stopSelf();
            android.content.Intent stopIntent = new Intent(this, FloatingRobotService.class);
            stopService(stopIntent);
        });
        LinearLayout.LayoutParams exitParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        menuContainer.addView(exitBtn, exitParams);

        longPressMenu = menuContainer;

        // Container size — wrap content
        int containerW = btnW * 2 + gap + 40; // 2 buttons + gap + padding
        int containerH = btnH + gap + 60; // 1 row + exit + padding

        // Layout params — position centered on robot
        longPressMenuParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        longPressMenuParams.gravity = Gravity.TOP | Gravity.START;
        // Center the menu on the robot
        longPressMenuParams.x = (int) (robotScreenX - containerW / 2f);
        longPressMenuParams.y = (int) (robotScreenY - containerH / 2f);

        // Clamp to screen bounds — ensure nothing is cut off
        if (longPressMenuParams.x < 10) longPressMenuParams.x = 10;
        if (longPressMenuParams.x + containerW > screenWidth - 10)
            longPressMenuParams.x = screenWidth - containerW - 10;
        if (longPressMenuParams.y < 10) longPressMenuParams.y = 10;
        if (longPressMenuParams.y + containerH > screenHeight - 10)
            longPressMenuParams.y = screenHeight - containerH - 10;

        // ── Full-screen dismiss overlay: tap outside the menu to close it ──
        // Use a flag to ignore the initial touch that triggered the long-press.
        // Without this, the ACTION_UP from the long-press immediately closes the menu.
        final boolean[] dismissReady = {false};
        View dismissOverlay = new View(this);
        dismissOverlay.setBackgroundColor(Color.argb(80, 0, 0, 0)); // slight dim
        dismissOverlay.setOnTouchListener((v, event) -> {
            if (!dismissReady[0]) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN ||
                event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                hideLongPressMenu();
                return true;
            }
            return false;
        });
        // Enable dismiss after a short delay so the long-press touch finishes
        android.os.Handler dismissHandler = new android.os.Handler();
        dismissHandler.postDelayed(() -> dismissReady[0] = true, 350);
        WindowManager.LayoutParams dismissParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        dismissParams.gravity = Gravity.TOP | Gravity.START;
        // Store the dismiss overlay so hideLongPressMenu can remove it
        longPressDismissOverlay = dismissOverlay;
        longPressDismissParams = dismissParams;

        try {
            windowManager.addView(dismissOverlay, dismissParams);
            windowManager.addView(longPressMenu, longPressMenuParams);
        } catch (Exception e) {
            android.util.Log.e("Argos", "showLongPressMenu failed: " + e.getMessage());
        }
    }

    // Helper: create a radial menu button — pure black bg, neon blue text
    private LinearLayout createRadialButton(String icon, String label, android.graphics.drawable.GradientDrawable bg) {
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.VERTICAL);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(16, 20, 16, 20);
        btn.setBackground(bg);

        TextView iconView = new TextView(this);
        iconView.setText(icon);
        iconView.setTextColor(Color.rgb(0, 229, 255));
        iconView.setTextSize(26f);
        iconView.setGravity(Gravity.CENTER);
        btn.addView(iconView);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(Color.rgb(0, 229, 255));
        labelView.setTextSize(14f);
        labelView.setGravity(Gravity.CENTER);
        labelView.setMaxLines(2);
        labelView.setLetterSpacing(0.05f);
        btn.addView(labelView);

        return btn;
    }

    private void hideLongPressMenu() {
        if (longPressMenu != null) {
            try { windowManager.removeView(longPressMenu); } catch (Exception e) {}
        }
        if (longPressDismissOverlay != null) {
            try { windowManager.removeView(longPressDismissOverlay); } catch (Exception e) {}
            longPressDismissOverlay = null;
        }
    }

    // ── Hand Tracking Mode (Web-based MediaPipe) ──
    // Uses MediaPipe Hand Landmarker running in the WebView's JS engine.
    // The webcam is accessed via getUserMedia() and processed in WASM.
    // Pinch gesture (thumb + index) grabs the robot, move hand to drag.

    private void startCameraHandTracking() {
        // Check camera permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(android.Manifest.permission.CAMERA) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            addMessage("Camera permission needed for hand tracking", Color.rgb(255, 150, 100));
            Intent permIntent = new Intent(this, MainActivity.class);
            permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            permIntent.putExtra("request_permission", android.Manifest.permission.CAMERA);
            startActivity(permIntent);
            return;
        }

        // Check device has a camera
        if (!HandTrackingCamera.hasCamera(this)) {
            addMessage("No camera detected on this device", Color.rgb(255, 150, 100));
            return;
        }

        // Toggle: if already active, stop; otherwise start
        if (handTrackingCamera != null && handTrackingCamera.isActive()) {
            handTrackingCamera.stop();
            handTrackingCamera = null;
            addMessage("✋ Hand tracking off", Color.rgb(180, 180, 180));
            return;
        }

        // Stop any existing JS-based hand tracking in the WebView
        if (robotWebView != null) {
            robotWebView.evaluateJavascript(
                "if(window.ArgosHandTracking){ArgosHandTracking.stop();}", null);
        }

        // Start native hand tracking using CameraX + MediaPipe
        // This is more reliable than WebView-based getUserMedia in an overlay service
        handTrackingCamera = new HandTrackingCamera(this, windowManager, layoutType,
            new HandTrackingCamera.HandTrackingCallback() {
                @Override
                public void onHandGrab(float normalizedX, float normalizedY) {
                    android.os.Handler h = new android.os.Handler(getMainLooper());
                    h.post(() -> {
                        if (robotWebView != null) {
                            robotWebView.evaluateJavascript(
                                "if(window.ArgosJS){ArgosJS.setExpression('SURPRISED');}", null);
                        }
                    });
                }

                @Override
                public void onHandDrag(float normalizedX, float normalizedY) {
                    android.os.Handler h = new android.os.Handler(getMainLooper());
                    h.post(() -> {
                        // Move robot to hand position (normalized 0-1 → screen pixels)
                        float screenX = normalizedX * screenWidth;
                        float screenY = normalizedY * screenHeight;
                        robotScreenX = screenX;
                        robotScreenY = screenY;
                        if (robotParams != null && robotWebView != null) {
                            float size = robotSize * currentScale;
                            int winW = (int) (size * 2.0f);
                            int winH = (int) (size * 1.7f);
                            robotParams.x = (int) Math.max(0, Math.min(screenWidth - winW, screenX - winW / 2.0f));
                            robotParams.y = (int) Math.max(0, Math.min(screenHeight - winH, screenY - winH / 2.0f));
                            try {
                                windowManager.updateViewLayout(robotWebView, robotParams);
                            } catch (Exception e) {}
                            // Update JS position so it doesn't fight us
                            robotWebView.evaluateJavascript(
                                "if(window.ArgosJS){ArgosJS.setPosition(" + screenX + "," + screenY + ");}", null);
                        }
                        // Move thought bubble too if visible
                        if (thoughtBubble != null && thoughtBubble.getVisibility() == View.VISIBLE) {
                            updateThoughtBubblePosition();
                            try {
                                windowManager.updateViewLayout(thoughtBubble, thoughtParams);
                            } catch (Exception e) {}
                        }
                    });
                }

                @Override
                public void onHandRelease(float normalizedX, float normalizedY) {
                    android.os.Handler h = new android.os.Handler(getMainLooper());
                    h.post(() -> {
                        if (robotWebView != null) {
                            robotWebView.evaluateJavascript(
                                "if(window.ArgosJS){ArgosJS.setExpression('HAPPY');}", null);
                        }
                    });
                }

                @Override
                public void onHandTrackingError(String message) {
                    android.os.Handler h = new android.os.Handler(getMainLooper());
                    h.post(() -> {
                        addMessage("📷 Hand tracking error: " + message, Color.rgb(255, 150, 100));
                    });
                }

                @Override
                public void onCameraReady() {
                    android.os.Handler h = new android.os.Handler(getMainLooper());
                    h.post(() -> {
                        addMessage("✋ Hand tracking on — pinch to drag Argos!", Color.rgb(0, 255, 136));
                    });
                }
            });

        handTrackingCamera.start();
    }

    private void stopCameraHandTracking() {
        if (handTrackingCamera != null) {
            handTrackingCamera.stop();
            handTrackingCamera = null;
        }
        // Also stop any JS-based hand tracking
        if (robotWebView != null) {
            robotWebView.evaluateJavascript(
                "if(window.ArgosHandTracking){ArgosHandTracking.stop();}", null);
        }
    }

    // Toggle standby mode
    private void toggleStandby() {
        standbyMode = !standbyMode;
        if (robotWebView != null) {
            robotWebView.evaluateJavascript(
                "if(window.ArgosJS){ArgosJS.setStandby(" + standbyMode + ");}", null);
        }
        if (standbyMode) {
            // Hide any open bubble before entering standby
            if (bubbleVisible) hideBubble();
            addMessage("Argos: Standby mode enabled. I won't move on my own, but you can still drag me. Double-tap to wake me up.", Color.rgb(100, 200, 255));
        } else {
            addMessage("Argos: Standby mode disabled. I'm free to roam again!", Color.rgb(100, 200, 255));
        }
    }

    // Show the tasks management overlay
    // Color scheme: black background, white text, neon blue titles
    private void showTasksOverlay() {
        if (tasksOverlay != null && tasksOverlay.getParent() != null) {
            hideTasksOverlay();
            return;
        }

        // Three-color palette: black bg, white text, neon blue title
        final int NEON_BLUE = Color.rgb(0, 229, 255);
        final int WHITE = Color.rgb(255, 255, 255);

        tasksOverlay = new LinearLayout(this);
        tasksOverlay.setOrientation(LinearLayout.VERTICAL);
        tasksOverlay.setPadding(30, 30, 30, 30);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.argb(255, 0, 0, 0)); // pure black
        bg.setCornerRadius(24f);
        bg.setStroke(2, NEON_BLUE);
        tasksOverlay.setBackground(bg);

        // Title row
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("📋 Scheduled Tasks");
        title.setTextColor(NEON_BLUE);
        title.setTextSize(18f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleParams);
        titleRow.addView(title);

        TextView closeBtn = new TextView(this);
        closeBtn.setText("✕");
        closeBtn.setTextColor(WHITE);
        closeBtn.setTextSize(18f);
        closeBtn.setPadding(20, 0, 0, 0);
        closeBtn.setOnClickListener(v -> hideTasksOverlay());
        titleRow.addView(closeBtn);
        tasksOverlay.addView(titleRow);

        // Scrollable task list
        tasksScroll = new ScrollView(this);
        LinearLayout taskList = new LinearLayout(this);
        taskList.setOrientation(LinearLayout.VERTICAL);
        taskList.setPadding(0, 16, 0, 16);

        if (scheduledTaskList.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No scheduled tasks.\n\nTell me things like:\n• \"remind me at 8am to check emails\"\n• \"schedule 12pm lunch break\"\n• \"at 3pm remind me to call mom\"");
            empty.setTextColor(WHITE);
            empty.setTextSize(14f);
            empty.setPadding(0, 16, 0, 16);
            taskList.addView(empty);
        } else {
            for (int i = 0; i < scheduledTaskList.size(); i++) {
                final int idx = i;
                String task = scheduledTaskList.get(i);

                LinearLayout taskRow = new LinearLayout(this);
                taskRow.setOrientation(LinearLayout.HORIZONTAL);
                taskRow.setGravity(Gravity.CENTER_VERTICAL);
                taskRow.setPadding(12, 16, 12, 16);

                android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
                rowBg.setColor(Color.argb(40, 0, 229, 255));
                rowBg.setCornerRadius(12f);
                taskRow.setBackground(rowBg);

                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.setMargins(0, 6, 0, 6);
                taskRow.setLayoutParams(rowParams);

                TextView taskText = new TextView(this);
                taskText.setText("⏰ " + task);
                taskText.setTextColor(WHITE);
                taskText.setTextSize(14f);
                LinearLayout.LayoutParams ttParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                taskText.setLayoutParams(ttParams);
                taskRow.addView(taskText);

                // Simulate button
                TextView simBtn = new TextView(this);
                simBtn.setText("▶ Test");
                simBtn.setTextColor(NEON_BLUE);
                simBtn.setTextSize(13f);
                simBtn.setPadding(20, 0, 0, 0);
                simBtn.setOnClickListener(v -> simulateTask(idx));
                taskRow.addView(simBtn);

                // Delete button
                TextView delBtn = new TextView(this);
                delBtn.setText("🗑");
                delBtn.setTextColor(WHITE);
                delBtn.setTextSize(15f);
                delBtn.setPadding(20, 0, 0, 0);
                delBtn.setOnClickListener(v -> {
                    scheduledTaskList.remove(idx);
                    saveTasksLocally();
                    hideTasksOverlay();
                    showTasksOverlay();
                });
                taskRow.addView(delBtn);

                taskList.addView(taskRow);
            }
        }
        tasksScroll.addView(taskList);
        tasksOverlay.addView(tasksScroll);

        // Add task input row
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setPadding(0, 16, 0, 0);

        final EditText taskInput = new EditText(this);
        taskInput.setHint("e.g. 8am check emails");
        taskInput.setHintTextColor(Color.argb(120, 255, 255, 255));
        taskInput.setTextColor(WHITE);
        taskInput.setTextSize(14f);
        taskInput.setBackgroundColor(Color.argb(40, 0, 229, 255));
        taskInput.setPadding(16, 12, 16, 12);
        taskInput.setSingleLine(true);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        taskInput.setLayoutParams(inputParams);
        inputRow.addView(taskInput);

        TextView addBtn = new TextView(this);
        addBtn.setText("➕ Add");
        addBtn.setTextColor(NEON_BLUE);
        addBtn.setTextSize(14f);
        addBtn.setPadding(20, 12, 20, 12);
        addBtn.setOnClickListener(v -> {
            String text = taskInput.getText().toString().trim();
            if (!text.isEmpty()) {
                parseAndScheduleTask(text);
                taskInput.setText("");
                hideTasksOverlay();
                showTasksOverlay();
            }
        });
        inputRow.addView(addBtn);
        tasksOverlay.addView(inputRow);

        // Simulate all button
        if (!scheduledTaskList.isEmpty()) {
            TextView simAllBtn = new TextView(this);
            simAllBtn.setText("▶ Simulate All Tasks");
            simAllBtn.setTextColor(NEON_BLUE);
            simAllBtn.setTextSize(14f);
            simAllBtn.setPadding(0, 16, 0, 0);
            simAllBtn.setOnClickListener(v -> {
                for (int i = 0; i < scheduledTaskList.size(); i++) {
                    simulateTask(i);
                }
            });
            tasksOverlay.addView(simAllBtn);
        }

        // Layout params
        tasksParams = new WindowManager.LayoutParams(
            (int) (screenWidth * 0.85),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        tasksParams.gravity = Gravity.CENTER;
        tasksParams.x = 0;
        tasksParams.y = 0;

        try {
            windowManager.addView(tasksOverlay, tasksParams);
        } catch (Exception e) {}
    }

    private void hideTasksOverlay() {
        if (tasksOverlay != null) {
            try { windowManager.removeView(tasksOverlay); } catch (Exception e) {}
        }
    }

    // ── Notes Overlay ──
    // Shows files created by Argos AI in the dedicated Argos folder

    private LinearLayout notesOverlay;
    private WindowManager.LayoutParams notesParams;

    private void showNotesOverlay() {
        if (notesOverlay != null && notesOverlay.getParent() != null) {
            hideNotesOverlay();
            return;
        }

        notesOverlay = new LinearLayout(this);
        notesOverlay.setOrientation(LinearLayout.VERTICAL);
        notesOverlay.setPadding(30, 30, 30, 30);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.argb(245, 15, 18, 30));
        bg.setCornerRadius(24f);
        bg.setStroke(2, Color.rgb(0, 255, 136));
        notesOverlay.setBackground(bg);

        // Title row
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("📝 Argos Notes");
        title.setTextColor(Color.rgb(0, 255, 136));
        title.setTextSize(18f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleParams);
        titleRow.addView(title);

        TextView closeBtn = new TextView(this);
        closeBtn.setText("✕");
        closeBtn.setTextColor(Color.rgb(180, 180, 180));
        closeBtn.setTextSize(18f);
        closeBtn.setPadding(20, 0, 0, 0);
        closeBtn.setOnClickListener(v -> hideNotesOverlay());
        titleRow.addView(closeBtn);
        notesOverlay.addView(titleRow);

        // Folder path display
        TextView pathLabel = new TextView(this);
        pathLabel.setText("Folder: " + fileManager.getFolderPath());
        pathLabel.setTextColor(Color.rgb(120, 120, 140));
        pathLabel.setTextSize(11f);
        pathLabel.setPadding(0, 8, 0, 12);
        notesOverlay.addView(pathLabel);

        // Scrollable file list
        ScrollView notesScroll = new ScrollView(this);
        LinearLayout fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        fileList.setPadding(0, 8, 0, 8);

        java.util.List<ArgosFileManager.FileInfo> files = fileManager.listFiles();
        if (files.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No files yet.\n\nAsk Argos to create notes, lists, or any text file.\nExample: \"write a shopping list\" or \"save my ideas to a file\"");
            empty.setTextColor(Color.rgb(160, 160, 180));
            empty.setTextSize(14f);
            empty.setPadding(0, 16, 0, 16);
            fileList.addView(empty);
        } else {
            for (ArgosFileManager.FileInfo fi : files) {
                LinearLayout fileRow = new LinearLayout(this);
                fileRow.setOrientation(LinearLayout.HORIZONTAL);
                fileRow.setGravity(Gravity.CENTER_VERTICAL);
                fileRow.setPadding(12, 16, 12, 16);

                android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
                rowBg.setColor(Color.argb(40, 0, 255, 136));
                rowBg.setCornerRadius(12f);
                fileRow.setBackground(rowBg);

                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.setMargins(0, 6, 0, 6);
                fileRow.setLayoutParams(rowParams);

                // File info text
                TextView fileText = new TextView(this);
                fileText.setText("📄 " + fi.name + "\n   " + fi.getFormattedSize() + "  •  " + fi.getFormattedDate());
                fileText.setTextColor(Color.rgb(220, 220, 240));
                fileText.setTextSize(13f);
                LinearLayout.LayoutParams ftParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                fileText.setLayoutParams(ftParams);
                fileRow.addView(fileText);

                // View button — read file content
                TextView viewBtn = new TextView(this);
                viewBtn.setText("👁");
                viewBtn.setTextColor(Color.rgb(100, 200, 255));
                viewBtn.setTextSize(16f);
                viewBtn.setPadding(20, 0, 12, 0);
                viewBtn.setOnClickListener(v -> {
                    String content = fileManager.readFile(fi.name);
                    if (content != null) {
                        hideNotesOverlay();
                        showFileContentOverlay(fi.name, content);
                    }
                });
                fileRow.addView(viewBtn);

                // Delete button
                TextView delBtn = new TextView(this);
                delBtn.setText("🗑");
                delBtn.setTextColor(Color.rgb(255, 100, 100));
                delBtn.setTextSize(15f);
                delBtn.setPadding(12, 0, 0, 0);
                delBtn.setOnClickListener(v -> {
                    fileManager.deleteFile(fi.name);
                    hideNotesOverlay();
                    showNotesOverlay();
                });
                fileRow.addView(delBtn);

                fileList.addView(fileRow);
            }
        }
        notesScroll.addView(fileList);
        notesOverlay.addView(notesScroll);

        // Open folder button
        TextView openBtn = new TextView(this);
        openBtn.setText("📂 Open in File Manager");
        openBtn.setTextColor(Color.rgb(0, 255, 136));
        openBtn.setTextSize(14f);
        openBtn.setGravity(Gravity.CENTER);
        openBtn.setPadding(0, 16, 0, 0);
        openBtn.setOnClickListener(v -> {
            hideNotesOverlay();
            openArgosFolder();
        });
        notesOverlay.addView(openBtn);

        // Layout params
        notesParams = new WindowManager.LayoutParams(
            (int) (screenWidth * 0.85),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        notesParams.gravity = Gravity.CENTER;
        notesParams.x = 0;
        notesParams.y = 0;

        try {
            windowManager.addView(notesOverlay, notesParams);
        } catch (Exception e) {}
    }

    private void hideNotesOverlay() {
        if (notesOverlay != null) {
            try { windowManager.removeView(notesOverlay); } catch (Exception e) {}
        }
    }

    // Show a file's content in a scrollable overlay
    private void showFileContentOverlay(String filename, String content) {
        final LinearLayout contentOverlay = new LinearLayout(this);
        contentOverlay.setOrientation(LinearLayout.VERTICAL);
        contentOverlay.setPadding(30, 30, 30, 30);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.argb(248, 15, 18, 30));
        bg.setCornerRadius(24f);
        bg.setStroke(2, Color.rgb(0, 200, 255));
        contentOverlay.setBackground(bg);

        // Title
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("📄 " + filename);
        title.setTextColor(Color.rgb(0, 200, 255));
        title.setTextSize(16f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleParams);
        titleRow.addView(title);

        TextView closeBtn = new TextView(this);
        closeBtn.setText("✕");
        closeBtn.setTextColor(Color.rgb(180, 180, 180));
        closeBtn.setTextSize(18f);
        closeBtn.setPadding(20, 0, 0, 0);
        closeBtn.setOnClickListener(v -> {
            try { windowManager.removeView(contentOverlay); } catch (Exception e) {}
            showNotesOverlay();
        });
        titleRow.addView(closeBtn);
        contentOverlay.addView(titleRow);

        // Scrollable content
        ScrollView scroll = new ScrollView(this);
        TextView contentText = new TextView(this);
        contentText.setText(content);
        contentText.setTextColor(Color.rgb(220, 220, 240));
        contentText.setTextSize(13f);
        contentText.setPadding(0, 16, 0, 16);
        scroll.addView(contentText);
        contentOverlay.addView(scroll);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            (int) (screenWidth * 0.85),
            (int) (screenHeight * 0.6),
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.CENTER;
        params.x = 0;
        params.y = 0;

        try {
            windowManager.addView(contentOverlay, params);
        } catch (Exception e) {}
    }

    // Open the Argos folder in the system file manager
    private void openArgosFolder() {
        try {
            File folder = new File(fileManager.getFolderPath());
            Intent intent = new Intent(Intent.ACTION_VIEW);
            android.net.Uri uri;

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                // Use FileProvider for Android 7+
                uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", folder);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = android.net.Uri.fromFile(folder);
            }

            intent.setDataAndType(uri, "resource/folder");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // Try to open with a file manager
            try {
                startActivity(intent);
            } catch (Exception e) {
                // Fallback: open with ACTION_GET_CONTENT
                intent.setAction(Intent.ACTION_GET_CONTENT);
                intent.setDataAndType(uri, "*/*");
                startActivity(intent);
            }

            addMessage("📂 Opened Argos folder", Color.rgb(100, 255, 100));
        } catch (Exception e) {
            // If no file manager available, just show the path
            addMessage("📂 Folder location: " + fileManager.getFolderPath(), Color.rgb(100, 255, 100));
        }
    }

    // ── Settings Overlay ──
    // Contains Privacy settings + Speech bubble interval slider
    // Color scheme: black background, white text, neon blue titles

    private void showSettingsOverlay() {
        if (settingsOverlay != null && settingsOverlay.getParent() != null) {
            hideSettingsOverlay();
            return;
        }

        // Three-color palette: black bg, white text, neon blue title
        final int NEON_BLUE = Color.rgb(0, 229, 255);
        final int WHITE = Color.rgb(255, 255, 255);

        settingsOverlay = new LinearLayout(this);
        settingsOverlay.setOrientation(LinearLayout.VERTICAL);
        settingsOverlay.setPadding(30, 30, 30, 30);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.argb(255, 0, 0, 0)); // pure black
        bg.setCornerRadius(24f);
        bg.setStroke(2, NEON_BLUE);
        settingsOverlay.setBackground(bg);

        float density = getResources().getDisplayMetrics().density;

        // Title row
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("⚙ Settings");
        title.setTextColor(NEON_BLUE);
        title.setTextSize(18f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleParams);
        titleRow.addView(title);

        TextView closeBtn = new TextView(this);
        closeBtn.setText("✕");
        closeBtn.setTextColor(WHITE);
        closeBtn.setTextSize(18f);
        closeBtn.setPadding(20, 0, 0, 0);
        closeBtn.setOnClickListener(v -> hideSettingsOverlay());
        titleRow.addView(closeBtn);
        settingsOverlay.addView(titleRow);

        // ── Section 1: Speech Bubble Interval ──
        TextView bubbleSection = new TextView(this);
        bubbleSection.setText("💬 Speech Bubble");
        bubbleSection.setTextColor(NEON_BLUE);
        bubbleSection.setTextSize(15f);
        bubbleSection.setTypeface(bubbleSection.getTypeface(), android.graphics.Typeface.BOLD);
        bubbleSection.setPadding(0, 20, 0, 8);
        settingsOverlay.addView(bubbleSection);

        TextView bubbleDesc = new TextView(this);
        bubbleDesc.setText("How often Argos shows a thought bubble.\nRange: 10 seconds to 2 minutes.");
        bubbleDesc.setTextColor(WHITE);
        bubbleDesc.setTextSize(12f);
        bubbleDesc.setPadding(0, 0, 0, 12);
        settingsOverlay.addView(bubbleDesc);

        // Current interval display
        final TextView intervalValue = new TextView(this);
        int currentSec = thoughtIntervalMs / 1000;
        intervalValue.setText("Every " + (currentSec < 60 ? currentSec + " seconds" : (currentSec / 60) + " min " + (currentSec % 60) + " sec"));
        intervalValue.setTextColor(WHITE);
        intervalValue.setTextSize(16f);
        intervalValue.setTypeface(intervalValue.getTypeface(), android.graphics.Typeface.BOLD);
        intervalValue.setPadding(0, 0, 0, 8);
        settingsOverlay.addView(intervalValue);

        // Slider (SeekBar) — range 10-120 seconds
        android.widget.SeekBar intervalSlider = new android.widget.SeekBar(this);
        intervalSlider.setMax(110); // 0-110 maps to 10-120 seconds
        intervalSlider.setProgress((thoughtIntervalMs / 1000) - 10); // offset by 10
        intervalSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                int seconds = progress + 10; // 10-120
                if (seconds < 60) {
                    intervalValue.setText("Every " + seconds + " seconds");
                } else {
                    int min = seconds / 60;
                    int sec = seconds % 60;
                    intervalValue.setText("Every " + min + " min " + (sec > 0 ? sec + " sec" : ""));
                }
            }
            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                int seconds = seekBar.getProgress() + 10;
                setThoughtInterval(seconds * 1000);
            }
        });
        settingsOverlay.addView(intervalSlider);

        // Divider
        View divider1 = new View(this);
        divider1.setBackgroundColor(NEON_BLUE);
        LinearLayout.LayoutParams div1Params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        div1Params.setMargins(0, 20, 0, 20);
        settingsOverlay.addView(divider1, div1Params);

        // ── Section 2: Privacy ──
        TextView privacySection = new TextView(this);
        privacySection.setText("🔒 Privacy");
        privacySection.setTextColor(NEON_BLUE);
        privacySection.setTextSize(15f);
        privacySection.setTypeface(privacySection.getTypeface(), android.graphics.Typeface.BOLD);
        privacySection.setPadding(0, 0, 0, 8);
        settingsOverlay.addView(privacySection);

        TextView privacyDesc = new TextView(this);
        privacyDesc.setText("Control what Argos can see and access on your device.");
        privacyDesc.setTextColor(WHITE);
        privacyDesc.setTextSize(12f);
        privacyDesc.setPadding(0, 0, 0, 12);
        settingsOverlay.addView(privacyDesc);

        // Full Privacy toggle row
        LinearLayout fullPrivacyRow = new LinearLayout(this);
        fullPrivacyRow.setOrientation(LinearLayout.HORIZONTAL);
        fullPrivacyRow.setGravity(Gravity.CENTER_VERTICAL);
        fullPrivacyRow.setPadding(12, 16, 12, 16);

        android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
        rowBg.setColor(Color.argb(40, 0, 229, 255));
        rowBg.setCornerRadius(12f);
        fullPrivacyRow.setBackground(rowBg);

        LinearLayout.LayoutParams fpRowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fpRowParams.setMargins(0, 6, 0, 6);
        fullPrivacyRow.setLayoutParams(fpRowParams);

        TextView fpLabel = new TextView(this);
        fpLabel.setText("🚫 Full Privacy Mode\n(Eyes closed — no app awareness)");
        fpLabel.setTextColor(WHITE);
        fpLabel.setTextSize(14f);
        LinearLayout.LayoutParams fpLabelParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        fpLabel.setLayoutParams(fpLabelParams);
        fullPrivacyRow.addView(fpLabel);

        final TextView fpToggle = new TextView(this);
        boolean fpEnabled = ArgosAccessibilityService.isFullPrivacy();
        fpToggle.setText(fpEnabled ? "ON" : "OFF");
        fpToggle.setTextColor(fpEnabled ? NEON_BLUE : WHITE);
        fpToggle.setTextSize(16f);
        fpToggle.setTypeface(fpToggle.getTypeface(), android.graphics.Typeface.BOLD);
        fpToggle.setPadding(20, 0, 0, 0);
        fpToggle.setOnClickListener(v -> {
            boolean newVal = !ArgosAccessibilityService.isFullPrivacy();
            ArgosAccessibilityService.setFullPrivacy(newVal);
            fpToggle.setText(newVal ? "ON" : "OFF");
            fpToggle.setTextColor(newVal ? NEON_BLUE : WHITE);
            if (newVal) {
                if (robotWebView != null) {
                    robotWebView.evaluateJavascript(
                        "if(window.ArgosJS){ArgosJS.setExpression('SLEEPING');}", null);
                }
                showThoughtBubble("Privacy mode — my eyes are closed");
            } else {
                if (robotWebView != null) {
                    robotWebView.evaluateJavascript(
                        "if(window.ArgosJS){ArgosJS.setExpression('NEUTRAL');}", null);
                }
                showThoughtBubble("I can see again!");
            }
        });
        fullPrivacyRow.addView(fpToggle);
        settingsOverlay.addView(fullPrivacyRow);

        // Per-app privacy button
        TextView perAppBtn = new TextView(this);
        perAppBtn.setText("📱 Per-App Privacy →");
        perAppBtn.setTextColor(WHITE);
        perAppBtn.setTextSize(14f);
        perAppBtn.setPadding(12, 16, 12, 16);
        android.graphics.drawable.GradientDrawable perAppBg = new android.graphics.drawable.GradientDrawable();
        perAppBg.setColor(Color.argb(25, 0, 229, 255));
        perAppBg.setCornerRadius(12f);
        perAppBg.setStroke(1, NEON_BLUE);
        perAppBtn.setBackground(perAppBg);
        perAppBtn.setOnClickListener(v -> {
            hideSettingsOverlay();
            showPrivacyOverlay();
        });
        LinearLayout.LayoutParams perAppParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        perAppParams.setMargins(0, 6, 0, 6);
        settingsOverlay.addView(perAppBtn, perAppParams);

        // Layout params
        settingsParams = new WindowManager.LayoutParams(
            (int) (screenWidth * 0.85),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        settingsParams.gravity = Gravity.TOP | Gravity.START;
        // Position at top-left of screen
        settingsParams.x = (int) (screenWidth * 0.075);
        settingsParams.y = 60;

        try {
            windowManager.addView(settingsOverlay, settingsParams);
        } catch (Exception e) {}
    }

    private void hideSettingsOverlay() {
        if (settingsOverlay != null) {
            try { windowManager.removeView(settingsOverlay); } catch (Exception e) {}
        }
    }

    // ── Privacy Overlay ──
    // Color scheme: black background, white text, neon blue titles

    private void showPrivacyOverlay() {
        if (privacyOverlay != null && privacyOverlay.getParent() != null) {
            hidePrivacyOverlay();
            return;
        }

        // Three-color palette: black bg, white text, neon blue title
        final int NEON_BLUE = Color.rgb(0, 229, 255);
        final int WHITE = Color.rgb(255, 255, 255);

        privacyOverlay = new LinearLayout(this);
        privacyOverlay.setOrientation(LinearLayout.VERTICAL);
        privacyOverlay.setPadding(30, 30, 30, 30);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.argb(255, 0, 0, 0)); // pure black
        bg.setCornerRadius(24f);
        bg.setStroke(2, NEON_BLUE);
        privacyOverlay.setBackground(bg);

        // Title row
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("🔒 Privacy Settings");
        title.setTextColor(NEON_BLUE);
        title.setTextSize(18f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleParams);
        titleRow.addView(title);

        TextView closeBtn = new TextView(this);
        closeBtn.setText("✕");
        closeBtn.setTextColor(WHITE);
        closeBtn.setTextSize(18f);
        closeBtn.setPadding(20, 0, 0, 0);
        closeBtn.setOnClickListener(v -> hidePrivacyOverlay());
        titleRow.addView(closeBtn);
        privacyOverlay.addView(titleRow);

        // Description
        TextView desc = new TextView(this);
        desc.setText("Control what Argos can see and access on your device.");
        desc.setTextColor(WHITE);
        desc.setTextSize(13f);
        desc.setPadding(0, 12, 0, 16);
        privacyOverlay.addView(desc);

        // Full Privacy toggle row
        LinearLayout fullPrivacyRow = new LinearLayout(this);
        fullPrivacyRow.setOrientation(LinearLayout.HORIZONTAL);
        fullPrivacyRow.setGravity(Gravity.CENTER_VERTICAL);
        fullPrivacyRow.setPadding(12, 16, 12, 16);

        android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
        rowBg.setColor(Color.argb(40, 0, 229, 255));
        rowBg.setCornerRadius(12f);
        fullPrivacyRow.setBackground(rowBg);

        LinearLayout.LayoutParams fpRowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fpRowParams.setMargins(0, 6, 0, 6);
        fullPrivacyRow.setLayoutParams(fpRowParams);

        TextView fpLabel = new TextView(this);
        fpLabel.setText("🚫 Full Privacy Mode\n(Eyes closed — no app awareness, no screen reading)");
        fpLabel.setTextColor(WHITE);
        fpLabel.setTextSize(14f);
        LinearLayout.LayoutParams fpLabelParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        fpLabel.setLayoutParams(fpLabelParams);
        fullPrivacyRow.addView(fpLabel);

        final TextView fpToggle = new TextView(this);
        boolean fpEnabled = ArgosAccessibilityService.isFullPrivacy();
        fpToggle.setText(fpEnabled ? "ON" : "OFF");
        fpToggle.setTextColor(fpEnabled ? NEON_BLUE : WHITE);
        fpToggle.setTextSize(16f);
        fpToggle.setTypeface(fpToggle.getTypeface(), android.graphics.Typeface.BOLD);
        fpToggle.setPadding(20, 0, 0, 0);
        fpToggle.setOnClickListener(v -> {
            boolean newVal = !ArgosAccessibilityService.isFullPrivacy();
            ArgosAccessibilityService.setFullPrivacy(newVal);
            fpToggle.setText(newVal ? "ON" : "OFF");
            fpToggle.setTextColor(newVal ? NEON_BLUE : WHITE);
            if (newVal) {
                // Close eyes on 3D robot
                if (robotWebView != null) {
                    robotWebView.evaluateJavascript(
                        "if(window.ArgosJS){ArgosJS.setExpression('SLEEPING');}", null);
                }
                showThoughtBubble("Privacy mode — my eyes are closed");
                addMessage("Argos: Full privacy enabled. I can't see your screen or know what apps you're using.", NEON_BLUE);
            } else {
                // Open eyes
                if (robotWebView != null) {
                    robotWebView.evaluateJavascript(
                        "if(window.ArgosJS){ArgosJS.setExpression('NEUTRAL');}", null);
                }
                showThoughtBubble("I can see again!");
                addMessage("Argos: Privacy mode disabled. I'm aware of my surroundings again.", NEON_BLUE);
            }
        });
        fullPrivacyRow.addView(fpToggle);
        privacyOverlay.addView(fullPrivacyRow);

        // Section divider
        TextView sectionLabel = new TextView(this);
        sectionLabel.setText("Per-App Privacy");
        sectionLabel.setTextColor(NEON_BLUE);
        sectionLabel.setTextSize(15f);
        sectionLabel.setTypeface(sectionLabel.getTypeface(), android.graphics.Typeface.BOLD);
        sectionLabel.setPadding(0, 20, 0, 8);
        privacyOverlay.addView(sectionLabel);

        TextView sectionDesc = new TextView(this);
        sectionDesc.setText("Select apps where Argos should not see screen content or send awareness notifications.");
        sectionDesc.setTextColor(WHITE);
        sectionDesc.setTextSize(12f);
        sectionDesc.setPadding(0, 0, 0, 12);
        privacyOverlay.addView(sectionDesc);

        // Scrollable app list
        ScrollView appScroll = new ScrollView(this);
        LinearLayout appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        appList.setPadding(0, 8, 0, 8);

        // Get list of installed apps
        android.content.pm.PackageManager pm = getPackageManager();
        java.util.List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(0);
        java.util.List<android.content.pm.ApplicationInfo> userApps = new java.util.ArrayList<>();
        for (android.content.pm.ApplicationInfo ai : apps) {
            if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) {
                userApps.add(ai);
            }
        }
        // Sort by name
        java.util.Collections.sort(userApps, (a, b) -> {
            String na = pm.getApplicationLabel(a).toString();
            String nb = pm.getApplicationLabel(b).toString();
            return na.compareToIgnoreCase(nb);
        });

        // Show recently used apps first (from app history), then the rest
        java.util.Set<String> shownPackages = new java.util.HashSet<>();
        java.util.LinkedList<String> recentApps = ArgosAccessibilityService.getAppHistoryList();

        // Add recently used apps first
        for (String appLabel : recentApps) {
            // Find matching package
            for (android.content.pm.ApplicationInfo ai : userApps) {
                String label = pm.getApplicationLabel(ai).toString();
                if (label.equals(appLabel) && !shownPackages.contains(ai.packageName)) {
                    addPrivacyAppRow(appList, ai.packageName, label, pm);
                    shownPackages.add(ai.packageName);
                    break;
                }
            }
        }

        // Add remaining user apps
        for (android.content.pm.ApplicationInfo ai : userApps) {
            if (!shownPackages.contains(ai.packageName)) {
                String label = pm.getApplicationLabel(ai).toString();
                addPrivacyAppRow(appList, ai.packageName, label, pm);
                shownPackages.add(ai.packageName);
            }
        }

        appScroll.addView(appList);
        // Limit scroll height
        appScroll.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int) (screenHeight * 0.35)));
        privacyOverlay.addView(appScroll);

        // Layout params
        privacyParams = new WindowManager.LayoutParams(
            (int) (screenWidth * 0.85),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        privacyParams.gravity = Gravity.CENTER;
        privacyParams.x = 0;
        privacyParams.y = 0;

        try {
            windowManager.addView(privacyOverlay, privacyParams);
        } catch (Exception e) {}
    }

    // Add a single app row to the privacy overlay
    // Color scheme: black bg, white text, neon blue accent
    private void addPrivacyAppRow(LinearLayout parent, final String packageName, String label,
                                   android.content.pm.PackageManager pm) {
        final int NEON_BLUE = Color.rgb(0, 229, 255);
        final int WHITE = Color.rgb(255, 255, 255);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(12, 14, 12, 14);

        android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
        rowBg.setColor(Color.argb(25, 0, 229, 255));
        rowBg.setCornerRadius(10f);
        row.setBackground(rowBg);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, 4, 0, 4);
        row.setLayoutParams(rowParams);

        TextView appLabel = new TextView(this);
        appLabel.setText(label);
        appLabel.setTextColor(WHITE);
        appLabel.setTextSize(13f);
        appLabel.setSingleLine(true);
        appLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        appLabel.setLayoutParams(labelParams);
        row.addView(appLabel);

        final TextView toggle = new TextView(this);
        boolean blocked = ArgosAccessibilityService.isAppBlocked(packageName);
        toggle.setText(blocked ? "🔒" : "👁");
        toggle.setTextColor(blocked ? NEON_BLUE : WHITE);
        toggle.setTextSize(18f);
        toggle.setPadding(20, 0, 0, 0);
        toggle.setOnClickListener(v -> {
            boolean isBlocked = ArgosAccessibilityService.isAppBlocked(packageName);
            if (isBlocked) {
                ArgosAccessibilityService.unblockApp(packageName);
                toggle.setText("👁");
                toggle.setTextColor(WHITE);
            } else {
                ArgosAccessibilityService.blockApp(packageName);
                toggle.setText("🔒");
                toggle.setTextColor(NEON_BLUE);
            }
        });
        row.addView(toggle);
        parent.addView(row);
    }

    private void hidePrivacyOverlay() {
        if (privacyOverlay != null) {
            try { windowManager.removeView(privacyOverlay); } catch (Exception e) {}
        }
    }

    // Save tasks to SharedPreferences so they persist across restarts
    private void saveTasksLocally() {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("argos_tasks", MODE_PRIVATE);
            android.content.SharedPreferences.Editor ed = prefs.edit();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < scheduledTaskList.size(); i++) {
                if (i > 0) sb.append("\n");
                sb.append(scheduledTaskList.get(i));
            }
            ed.putString("task_list", sb.toString());
            ed.apply();
        } catch (Exception e) {
            android.util.Log.w("ArgosTasks", "Failed to save tasks: " + e.getMessage());
        }
        // Also save to the Argos folder so the user can view them in a file manager
        if (fileManager != null) {
            try {
                fileManager.saveScheduledTasks(scheduledTaskList);
            } catch (Exception e) {
                android.util.Log.w("ArgosTasks", "Failed to save tasks to file: " + e.getMessage());
            }
        }
    }

    // Load tasks from SharedPreferences on service start
    private void loadTasksLocally() {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("argos_tasks", MODE_PRIVATE);
            String saved = prefs.getString("task_list", "");
            if (!saved.isEmpty()) {
                String[] items = saved.split("\n");
                for (String item : items) {
                    if (!item.trim().isEmpty()) {
                        scheduledTaskList.add(item.trim());
                    }
                }
                android.util.Log.i("ArgosTasks", "Loaded " + scheduledTaskList.size() + " tasks from storage");
            }
        } catch (Exception e) {
            android.util.Log.w("ArgosTasks", "Failed to load tasks: " + e.getMessage());
        }
    }

    // Get the user's schedule as a string for the AI to read
    // This is called when a scheduled task fires, so the AI can see all tasks
    private String getScheduleForAI() {
        if (scheduledTaskList.isEmpty()) return "No scheduled tasks.";
        StringBuilder sb = new StringBuilder("User's scheduled tasks:\n");
        for (String task : scheduledTaskList) {
            sb.append("  - ").append(task).append("\n");
        }
        return sb.toString().trim();
    }

    // Parse a user-entered task string and schedule it
    // Formats: "8am check emails", "12pm lunch break", "8:00 do something", "20:00 task"
    // Also: "check emails at 8am", "remind me at 3pm to call mom", "check emails" (no time = reminder only)
    private void parseAndScheduleTask(String input) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            addMessage("⚠️ Please enter a task description.", Color.rgb(255, 150, 100));
            return;
        }

        // Try multiple patterns to extract time
        // Pattern 1: Time at the beginning: "8am check emails", "8:00 do something"
        java.util.regex.Pattern p1 = java.util.regex.Pattern.compile(
            "^(\\d{1,2}[:.]\\d{2}\\s*[ap]?m?|\\d{1,2}\\s*[ap]m)\\s+(.+)", java.util.regex.Pattern.CASE_INSENSITIVE);
        // Pattern 2: Time anywhere in the string: "check emails at 8am", "remind me at 3pm to call mom"
        java.util.regex.Pattern p2 = java.util.regex.Pattern.compile(
            "^(.+?)\\s+(?:at\\s+)?(\\d{1,2}[:.]\\d{2}\\s*[ap]?m?|\\d{1,2}\\s*[ap]m)\\s*(.*)", java.util.regex.Pattern.CASE_INSENSITIVE);
        // Pattern 3: 24-hour time at start: "20:00 task", "14:30 do something"
        java.util.regex.Pattern p3 = java.util.regex.Pattern.compile(
            "^(\\d{1,2}:\\d{2})\\s+(.+)", java.util.regex.Pattern.CASE_INSENSITIVE);

        java.util.regex.Matcher m1 = p1.matcher(trimmed);
        java.util.regex.Matcher m3 = p3.matcher(trimmed);

        String timeStr = null;
        String taskDesc = null;

        if (m1.matches()) {
            timeStr = m1.group(1).trim();
            taskDesc = m1.group(2).trim();
        } else if (m3.matches()) {
            timeStr = m3.group(1).trim();
            taskDesc = m3.group(2).trim();
        } else {
            java.util.regex.Matcher m2 = p2.matcher(trimmed);
            if (m2.matches()) {
                String before = m2.group(1).trim();
                timeStr = m2.group(2).trim();
                String after = m2.group(3).trim();
                // Combine: "remind me at 3pm to call mom" → "remind me to call mom"
                taskDesc = after.isEmpty() ? before : (before + " " + after).trim();
            }
        }

        if (timeStr != null && !timeStr.isEmpty()) {
            scheduleTask(timeStr, taskDesc);
        } else {
            // No time found — save as a reminder without an alarm
            String taskEntry = "📋 " + trimmed;
            scheduledTaskList.add(taskEntry);
            saveTasksLocally();
            addMessage("📋 Saved reminder: " + trimmed, Color.rgb(100, 255, 100));
        }
    }

    // Simulate a scheduled task firing immediately (for testing)
    private void simulateTask(int index) {
        if (index < 0 || index >= scheduledTaskList.size()) return;
        String task = scheduledTaskList.get(index);
        // Extract task description after the time
        String[] parts = task.split(" - ", 2);
        String taskDesc = parts.length > 1 ? parts[1] : task;

        addMessage("🧪 Simulating task: " + taskDesc, Color.rgb(255, 200, 50));
        onScheduledTaskFired(taskDesc);
    }

    // ── Voice Pipeline: Tap to Record, Tap to Think ──

    // Start voice recording with silence detection
    private void startVoiceRecording() {
        // Check mic permission at runtime (safety net)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            addMessage("Voice: Microphone permission not granted. Please enable it in Settings.", Color.rgb(255, 100, 100));
            showThoughtBubble("I can't hear you — please grant microphone permission");
            isRecording = false;
            return;
        }

        // No backend — proceed with recording directly
        beginRecording();
    }

    // Actual recording start
    private void beginRecording() {
        isRecording = true;
        voicePipelineActive = false;
        // Reset live transcription state
        liveTranscriptFinal = "";
        liveTranscriptPartial = "";
        // Pause wake word detection while recording (avoid feedback loop)
        if (wakeWordRecognizer != null) {
            try { wakeWordRecognizer.cancel(); } catch (Exception e) {}
            wakeWordListening = false;
        }

        // Show recording animation
        robotStartRecording();

        // Start live transcription (SpeechRecognizer with partial results)
        // This shows the user's speech in real-time on the speech bubble.
        startLiveTranscription();

        // Start recording in background thread with silence detection
        // (AudioRecord runs as a fallback — if SpeechRecognizer fails, the
        // audio is still sent to the backend for transcription.)
        new Thread(() -> {
            try {
                final int sampleRate = 16000;
                final int channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO;
                final int audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT;
                int minBuf = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
                int bufferSize = Math.max(minBuf, sampleRate * 2 * 2); // 2 seconds buffer

                if (m_audioRecord != null) {
                    try { m_audioRecord.release(); } catch (Exception e) {}
                    m_audioRecord = null;
                }

                m_audioRecord = new android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    sampleRate, channelConfig, audioFormat, bufferSize);

                if (m_audioRecord.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                    android.os.Handler mainHandler = new android.os.Handler(getMainLooper());
                    mainHandler.post(() -> {
                        isRecording = false;
                        robotSetState("idle");
                        addMessage("Voice: Mic not available", Color.rgb(255, 100, 100));
                    });
                    return;
                }

                // Use a dynamic byte array to collect audio
                java.io.ByteArrayOutputStream audioBuffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[320]; // 10ms at 16kHz 16-bit mono
                long recordingStart = System.currentTimeMillis();
                long lastSoundTime = recordingStart;
                boolean silenceDetected = false;

                m_audioRecord.startRecording();

                while (isRecording) {
                    int read = m_audioRecord.read(chunk, 0, chunk.length);
                    if (read <= 0) break;

                    audioBuffer.write(chunk, 0, read);

                    // Calculate amplitude for silence detection
                    long sum = 0;
                    for (int i = 0; i < read - 1; i += 2) {
                        short sample = (short) ((chunk[i] & 0xFF) | (chunk[i + 1] << 8));
                        sum += Math.abs(sample);
                    }
                    long avgAmplitude = sum / (read / 2);

                    // Track last time we heard sound above threshold
                    if (avgAmplitude > SILENCE_THRESHOLD) {
                        lastSoundTime = System.currentTimeMillis();
                    }

                    long elapsed = System.currentTimeMillis() - recordingStart;
                    long silenceElapsed = System.currentTimeMillis() - lastSoundTime;

                    // Auto-stop after 4 seconds of silence (only after user has started speaking)
                    if (silenceElapsed >= SILENCE_DURATION_MS && elapsed > 2000) {
                        silenceDetected = true;
                        break;
                    }

                    // Max recording duration safety
                    if (elapsed >= MAX_RECORDING_DURATION_MS) {
                        break;
                    }
                }

                // Stop and release audio record
                try { m_audioRecord.stop(); } catch (Exception e) {}
                try { m_audioRecord.release(); } catch (Exception e) {}
                m_audioRecord = null;

                final byte[] pcmData = audioBuffer.toByteArray();
                final boolean wasSilence = silenceDetected;

                android.os.Handler mainHandler = new android.os.Handler(getMainLooper());
                mainHandler.post(() -> {
                    // Stop live transcription and process the result once the
                    // final text is available (onResults has fired).
                    stopLiveTranscription(() -> {
                        String liveText = getLiveTranscriptText();

                        if (wasSilence) {
                            addMessage("(auto: silence detected)", Color.rgb(150, 150, 160));
                        }

                        if (liveText != null && !liveText.trim().isEmpty()) {
                            // Live transcription succeeded — send text to /api/chat
                            // (skip audio upload, faster + no AssemblyAI cost)
                            addMessage("You: " + liveText, Color.rgb(200, 200, 210));
                            processTextFromVoice(liveText);
                        } else {
                            // Live transcription failed — fall back to audio upload
                            processVoiceRecording(pcmData);
                        }
                    });
                });

            } catch (Exception e) {
                android.util.Log.e("ArgosAudio", "Recording failed: " + e.getMessage());
                if (m_audioRecord != null) {
                    try { m_audioRecord.release(); } catch (Exception ex) {}
                    m_audioRecord = null;
                }
                android.os.Handler mainHandler = new android.os.Handler(getMainLooper());
                mainHandler.post(() -> {
                    isRecording = false;
                    voicePipelineActive = false;
                    robotSetState("idle");
                    addMessage("Voice: Recording error", Color.rgb(255, 100, 100));
                    resumeWakeWordDetection();
                });
            }
        }).start();
    }

    // Stop recording manually (user tapped again) and trigger AI thinking
    private void stopRecordingAndThink() {
        isRecording = false; // This will cause the recording loop to exit
        // The recording thread will stop live transcription and call processVoiceRecording
    }

    // Interrupt the active voice pipeline (e.g. when the user double-taps to
    // start a new question while Argos is still thinking). This resets the
    // pipeline state so a new recording can begin immediately. The in-flight
    // backend request will still complete in the background but its result
    // will be ignored since voicePipelineActive is already false.
    private void interruptVoicePipeline() {
        voicePipelineActive = false;
        pipelineGeneration++; // invalidate any in-flight response
        cancelVoiceWatchdog();
        // Stop TTS if Argos is speaking the previous reply
        if (m_tts != null && m_tts.isSpeaking()) {
            m_tts.stop();
        }
        // Explicitly clear talking + thinking, then go idle
        if (robotWebView != null) {
            robotWebView.evaluateJavascript(
                "if(window.ArgosJS){ArgosJS.setTalking(false);ArgosJS.setThinking(false);ArgosJS.setState('idle');}", null);
        } else {
            robotSetState("idle");
        }
    }

    // ── Freeze auto-restart ──
    // Runs every 10s. Fires only when the JS has stopped sending its liveness
    // heartbeat — i.e. the animation loop is genuinely wedged — rather than
    // merely when the robot is standing still (normal while idle or dancing).
    // A blunt "no movement" check here caused needless WebView reloads.
    private void startFreezeWatchdog() {
        m_lastAliveTime = System.currentTimeMillis();
        m_freezeCheck = new Runnable() {
            @Override
            public void run() {
                try {
                    boolean busy = voicePipelineActive || standbyMode || isDragging || !screenOn;
                    if (!busy && robotWebView != null &&
                        System.currentTimeMillis() - m_lastAliveTime > FREEZE_TIMEOUT_MS) {
                        android.util.Log.w("Argos", "Robot JS heartbeat lost — reloading WebView");
                        m_lastAliveTime = System.currentTimeMillis();
                        robotWebView.reload();
                    }
                } catch (Exception e) {}
                m_freezeHandler.postDelayed(this, 10000);
            }
        };
        m_freezeHandler.postDelayed(m_freezeCheck, 10000);
    }

    // Start a watchdog timer that resets the robot if the voice pipeline
    // (thinking → backend response) takes too long. This prevents the 3D
    // robot from being permanently frozen in the thinking pose when the
    // backend hangs or is unreachable. The watchdog is cancelled when a
    // response arrives (success or error) or when the pipeline is interrupted.
    private void startVoiceWatchdog(final int generation) {
        voiceWatchdogHandler.removeCallbacksAndMessages(null);
        voiceWatchdogHandler.postDelayed(() -> {
            // Only fire if this generation is still active (not interrupted)
            if (generation == pipelineGeneration && voicePipelineActive) {
                voicePipelineActive = false;
                pipelineGeneration++; // invalidate any late response
                addMessage("⚠ Argos took too long to respond — please try again.", Color.rgb(255, 150, 100));
                robotSetState("idle");
                if (m_tts != null && m_tts.isSpeaking()) {
                    m_tts.stop();
                }
                resumeWakeWordDetection();
            }
        }, VOICE_PIPELINE_TIMEOUT_MS);
    }

    // Cancel the voice pipeline watchdog (called when a response arrives or
    // the pipeline is interrupted).
    private void cancelVoiceWatchdog() {
        voiceWatchdogHandler.removeCallbacksAndMessages(null);
    }

    // ── Wake Word Detection ("Argos") ──
    // Continuously listens for the word "Argos" using Android's SpeechRecognizer.
    // When detected, automatically starts voice recording — no tapping needed.

    private void startWakeWordDetection() {
        if (!wakeWordEnabled) return;
        if (wakeWordListening) return;
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) return;

        try {
            if (wakeWordRecognizer == null) {
                wakeWordRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
                wakeWordListener = new android.speech.RecognitionListener() {
                    @Override
                    public void onReadyForSpeech(android.os.Bundle params) {}
                    @Override
                    public void onBeginningOfSpeech() {}
                    @Override
                    public void onRmsChanged(float rmsdB) {}
                    @Override
                    public void onBufferReceived(byte[] buffer) {}
                    @Override
                    public void onEndOfSpeech() {}
                    @Override
                    public void onError(int error) {
                        // Restart listening after a brief delay (errors are normal —
                        // SpeechRecognizer times out after a few seconds of silence)
                        wakeWordListening = false;
                        if (wakeWordEnabled && !isRecording && !voicePipelineActive && !standbyMode && screenOn) {
                            wakeWordHandler.postDelayed(() -> restartWakeWordListening(),
                                WAKE_WORD_RESTART_DELAY_MS);
                        }
                    }
                    @Override
                    public void onResults(android.os.Bundle results) {
                        wakeWordListening = false;
                        // Check if "argos" was detected
                        java.util.ArrayList<String> matches =
                            results.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches != null) {
                            for (String match : matches) {
                                String lower = match.toLowerCase().trim();
                                if (lower.contains(WAKE_WORD) || lower.contains("argos") ||
                                    lower.contains("argus") || lower.contains("argo")) {
                                    // Wake word detected! Start voice recording
                                    android.os.Handler h = new android.os.Handler(getMainLooper());
                                    h.post(() -> {
                                        if (!isRecording && !voicePipelineActive && !standbyMode && screenOn) {
                                            addMessage("🎤 Wake word detected — listening...", Color.rgb(0, 255, 136));
                                            if (backendUrl != null && !backendUrl.isEmpty()) {
                                                startVoiceRecording();
                                            }
                                        }
                                    });
                                    return;
                                }
                            }
                        }
                        // No wake word — restart listening
                        if (wakeWordEnabled && !isRecording && !voicePipelineActive && !standbyMode && screenOn) {
                            wakeWordHandler.postDelayed(() -> restartWakeWordListening(),
                                WAKE_WORD_RESTART_DELAY_MS);
                        }
                    }
                    @Override
                    public void onPartialResults(android.os.Bundle partialResults) {
                        // Check partial results for faster wake word detection
                        java.util.ArrayList<String> partial =
                            partialResults.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                        if (partial != null) {
                            for (String match : partial) {
                                String lower = match.toLowerCase().trim();
                                if (lower.contains(WAKE_WORD) || lower.contains("argos")) {
                                    // Wake word detected in partial results!
                                    if (wakeWordRecognizer != null) {
                                        try { wakeWordRecognizer.stopListening(); } catch (Exception e) {}
                                    }
                                    wakeWordListening = false;
                                    android.os.Handler h = new android.os.Handler(getMainLooper());
                                    h.post(() -> {
                                        if (!isRecording && !voicePipelineActive && !standbyMode && screenOn) {
                                            addMessage("🎤 Argos! Listening...", Color.rgb(0, 255, 136));
                                            if (backendUrl != null && !backendUrl.isEmpty()) {
                                                startVoiceRecording();
                                            }
                                        }
                                    });
                                    return;
                                }
                            }
                        }
                    }
                    @Override
                    public void onEvent(int eventType, android.os.Bundle params) {}
                };
                wakeWordRecognizer.setRecognitionListener(wakeWordListener);
            }

            // Start listening
            android.os.Bundle intent = new android.os.Bundle();
            android.content.Intent listenIntent = new android.content.Intent(
                android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            listenIntent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            listenIntent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            listenIntent.putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            listenIntent.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            listenIntent.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            // Shorter silence endpoint to restart faster
            listenIntent.putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000);
            listenIntent.putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500);

            wakeWordRecognizer.startListening(listenIntent);
            wakeWordListening = true;
            android.util.Log.i("ArgosWakeWord", "Wake word listening started");
        } catch (Exception e) {
            android.util.Log.w("ArgosWakeWord", "Failed to start: " + e.getMessage());
            wakeWordListening = false;
            // Retry after delay
            wakeWordHandler.postDelayed(() -> restartWakeWordListening(),
                WAKE_WORD_RESTART_DELAY_MS * 3);
        }
    }

    private void restartWakeWordListening() {
        if (!wakeWordEnabled || isRecording || voicePipelineActive || standbyMode || !screenOn) {
            return;
        }
        if (wakeWordRecognizer != null) {
            try { wakeWordRecognizer.cancel(); } catch (Exception e) {}
        }
        wakeWordListening = false;
        startWakeWordDetection();
    }

    private void stopWakeWordDetection() {
        wakeWordEnabled = false;
        wakeWordHandler.removeCallbacksAndMessages(null);
        if (wakeWordRecognizer != null) {
            try {
                wakeWordRecognizer.stopListening();
                wakeWordRecognizer.cancel();
                wakeWordRecognizer.destroy();
            } catch (Exception e) {}
            wakeWordRecognizer = null;
        }
        wakeWordListening = false;
    }

    // Called after voice pipeline completes to resume wake word listening
    private void resumeWakeWordDetection() {
        wakeWordEnabled = true;
        if (!wakeWordListening && !isRecording && !voicePipelineActive && screenOn) {
            wakeWordHandler.postDelayed(() -> startWakeWordDetection(), 500);
        }
    }

    // ── Live Transcription (real-time partial results on speech bubble) ──

    // Start SpeechRecognizer with partial results for live transcription display
    private void startLiveTranscription() {
        if (!liveTranscriptAvailable) return;
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            liveTranscriptAvailable = false;
            return;
        }

        try {
            // Stop any existing recognizer
            if (liveTranscriptRecognizer != null) {
                try { liveTranscriptRecognizer.destroy(); } catch (Exception e) {}
                liveTranscriptRecognizer = null;
            }

            liveTranscriptRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
            liveTranscriptListener = new android.speech.RecognitionListener() {
                @Override
                public void onReadyForSpeech(android.os.Bundle params) {}

                @Override
                public void onBeginningOfSpeech() {}

                @Override
                public void onRmsChanged(float rmsdB) {}

                @Override
                public void onBufferReceived(byte[] buffer) {}

                @Override
                public void onEndOfSpeech() {
                    // User stopped speaking — the recording loop's silence detection
                    // will handle the actual stop. We just note it.
                }

                @Override
                public void onError(int error) {
                    // Errors are normal (timeout, no speech, etc.)
                    // Don't set liveTranscriptAvailable=false — it may work on retry
                    liveTranscriptActive = false;
                    // Signal any waiting stopLiveTranscription() call
                    if (liveTranscriptDoneSignal != null) liveTranscriptDoneSignal.countDown();
                }

                @Override
                public void onResults(android.os.Bundle results) {
                    // Final results — accumulate the text
                    java.util.ArrayList<String> matches =
                        results.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String text = matches.get(0).trim();
                        if (!text.isEmpty()) {
                            liveTranscriptFinal = (liveTranscriptFinal + " " + text).trim();
                            liveTranscriptPartial = "";
                            updateLiveTranscriptionBubble();
                        }
                    }
                    liveTranscriptActive = false;
                    // Signal any waiting stopLiveTranscription() call
                    if (liveTranscriptDoneSignal != null) liveTranscriptDoneSignal.countDown();
                }

                @Override
                public void onPartialResults(android.os.Bundle partialResults) {
                    // Partial results — show live transcription on the speech bubble
                    java.util.ArrayList<String> partial =
                        partialResults.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                    if (partial != null && !partial.isEmpty()) {
                        liveTranscriptPartial = partial.get(0).trim();
                        updateLiveTranscriptionBubble();
                    }
                }

                @Override
                public void onEvent(int eventType, android.os.Bundle params) {}
            };

            liveTranscriptRecognizer.setRecognitionListener(liveTranscriptListener);

            // Configure intent for free-form speech with partial results
            android.content.Intent intent = new android.content.Intent(
                android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            // Use a longer silence duration to avoid cutting off the user mid-sentence
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000);

            liveTranscriptActive = true;
            liveTranscriptRecognizer.startListening(intent);
        } catch (Exception e) {
            android.util.Log.w("ArgosLiveTranscript", "Failed to start: " + e.getMessage());
            liveTranscriptAvailable = false;
            liveTranscriptActive = false;
        }
    }

    // Stop live transcription and clean up.
    // Takes a callback that is invoked once the final transcription result
    // is available (onResults/onError has fired), or after a short timeout.
    // This fixes a race condition where getLiveTranscriptText() was called
    // before onResults() had a chance to deliver the final segment.
    private void stopLiveTranscription(Runnable onComplete) {
        liveTranscriptActive = false;
        if (liveTranscriptRecognizer != null) {
            // Capture the current recognizer so we only destroy THIS one
            // (not a new one created by a subsequent startLiveTranscription)
            final android.speech.SpeechRecognizer recognizerToDestroy = liveTranscriptRecognizer;
            // Set up the done signal — onResults/onError will count it down
            liveTranscriptDoneSignal = new java.util.concurrent.CountDownLatch(1);
            try {
                // stopListening triggers onResults with the final text
                recognizerToDestroy.stopListening();
            } catch (Exception e) {}

            // Wait for onResults/onError on a background thread (can't block
            // the main thread because onResults runs on the main thread).
            // Then destroy the recognizer and invoke the callback.
            new Thread(() -> {
                try {
                    liveTranscriptDoneSignal.await(800, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {}
                liveTranscriptDoneSignal = null;
                // Destroy on the MAIN thread — SpeechRecognizer methods may only
                // be called from the thread that created it, and destroy() from
                // a worker thread throws (leaking the bound recognition service).
                new android.os.Handler(getMainLooper()).post(() -> {
                    if (liveTranscriptRecognizer == recognizerToDestroy) {
                        try { recognizerToDestroy.destroy(); } catch (Exception e) {}
                        liveTranscriptRecognizer = null;
                    }
                    hideLiveTranscriptionBubble();
                    if (onComplete != null) onComplete.run();
                });
            }).start();
        } else {
            // No recognizer was active — invoke callback immediately
            hideLiveTranscriptionBubble();
            if (onComplete != null) onComplete.run();
        }
    }

    // Get the final transcribed text (final segments + any remaining partial)
    private String getLiveTranscriptText() {
        String text = liveTranscriptFinal.trim();
        if (text.isEmpty()) {
            text = liveTranscriptPartial.trim();
        } else if (!liveTranscriptPartial.trim().isEmpty()) {
            text = text + " " + liveTranscriptPartial.trim();
        }
        return text;
    }

    // Show/update the live transcription on the speech bubble
    private void updateLiveTranscriptionBubble() {
        android.os.Handler h = new android.os.Handler(getMainLooper());
        h.post(() -> {
            String display = getLiveTranscriptText();
            if (display.isEmpty()) return;

            // Show on thought bubble with a microphone icon to indicate live transcription
            // Reuse the thought bubble infrastructure but bypass the normal guards
            if (thoughtBubble == null) {
                thoughtBubble = new TextView(this);
                thoughtBubble.setTextColor(Color.rgb(255, 255, 255));
                thoughtBubble.setTextSize(14f);
                thoughtBubble.setPadding(24, 16, 24, 16);
                thoughtBubble.setMaxWidth((int) (screenWidth * 0.7f));
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(Color.argb(220, 25, 25, 35));
                bg.setCornerRadius(20f);
                bg.setStroke(2, Color.rgb(0, 255, 136)); // green border for live transcription
                thoughtBubble.setBackground(bg);

                thoughtParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                );
                thoughtParams.gravity = Gravity.TOP | Gravity.START;
            }

            // Green border for live transcription (override the blue)
            android.graphics.drawable.GradientDrawable liveBg = new android.graphics.drawable.GradientDrawable();
            liveBg.setColor(Color.argb(220, 25, 25, 35));
            liveBg.setCornerRadius(20f);
            liveBg.setStroke(2, Color.rgb(0, 255, 136));
            thoughtBubble.setBackground(liveBg);

            thoughtBubble.setText("🎤 " + display);

            // Position above the robot
            updateThoughtBubblePosition();

            try {
                if (thoughtBubble.getWindowToken() == null) {
                    windowManager.addView(thoughtBubble, thoughtParams);
                } else {
                    windowManager.updateViewLayout(thoughtBubble, thoughtParams);
                }
                thoughtBubble.setVisibility(View.VISIBLE);
            } catch (Exception e) {}

            // Don't auto-hide during live transcription — keep it visible
            thoughtHandler.removeCallbacks(thoughtHideRunnable);
        });
    }

    // Hide the live transcription bubble
    private void hideLiveTranscriptionBubble() {
        android.os.Handler h = new android.os.Handler(getMainLooper());
        h.post(() -> {
            if (thoughtBubble != null) {
                thoughtBubble.setVisibility(View.GONE);
            }
        });
    }

    // Send transcribed text to /api/chat (used when live transcription succeeds)
    private void processTextFromVoice(String text) {
        isRecording = false;
        voicePipelineActive = true;
        robotStopRecording();

        if (text == null || text.trim().isEmpty()) {
            voicePipelineActive = false;
            robotSetState("idle");
            addMessage("Voice: No speech detected", Color.rgb(255, 100, 100));
            resumeWakeWordDetection();
            return;
        }

        final String message = text.trim();
        // Show thinking state so the user knows Argos is processing the request
        robotSetState("thinking");
        final int myGeneration = pipelineGeneration;
        // Start watchdog — if the backend doesn't respond in time, reset the
        // robot so it isn't frozen in the thinking pose indefinitely.
        startVoiceWatchdog(myGeneration);
        new Thread(() -> {
            try {
                final String response = sendTextToBackend(message);
                android.os.Handler mainHandler = new android.os.Handler(getMainLooper());
                mainHandler.post(() -> {
                    // Discard stale response if the pipeline was interrupted
                    if (myGeneration != pipelineGeneration) return;
                    try {
                        org.json.JSONObject json = new org.json.JSONObject(response);
                        // Backend /api/chat returns {"detail": "..."} on error (via ApiError),
                        // but some flows use {"error": "..."}. Handle both.
                        String errMsg = null;
                        if (json.has("detail")) errMsg = json.optString("detail");
                        else if (json.has("error")) errMsg = json.optString("error");
                        if (errMsg != null && !errMsg.isEmpty()) {
                            cancelVoiceWatchdog();
                            voicePipelineActive = false;
                            addMessage("Argos error: " + errMsg, Color.rgb(255, 100, 100));
                            robotSetState("idle");
                            resumeWakeWordDetection();
                            return;
                        }
                        String reply = json.optString("response", "");
                        if (reply.isEmpty()) reply = json.optString("reply", "");
                        if (reply.isEmpty()) {
                            // Backend returned 2xx but no reply — surface it so the user
                            // isn't left waiting in silence.
                            cancelVoiceWatchdog();
                            voicePipelineActive = false;
                            addMessage("Argos: (no reply from AI — please try again)", Color.rgb(255, 180, 80));
                            robotSetState("idle");
                            resumeWakeWordDetection();
                        } else {
                            // displayAndSpeakVoiceReply starts TTS; the
                            // UtteranceProgressListener resets the robot to
                            // idle and clears voicePipelineActive when speech
                            // finishes. Do NOT call robotSetState("idle")
                            // here — that would immediately override the
                            // talking animation and freeze the 3D robot.
                            cancelVoiceWatchdog();
                            displayAndSpeakVoiceReply(reply, message);
                        }
                    } catch (Exception e) {
                        cancelVoiceWatchdog();
                        voicePipelineActive = false;
                        addMessage("Voice parse error: " + e.getMessage(), Color.rgb(255, 100, 100));
                        robotSetState("idle");
                        resumeWakeWordDetection();
                    }
                });
            } catch (Exception e) {
                android.os.Handler mainHandler = new android.os.Handler(getMainLooper());
                mainHandler.post(() -> {
                    if (myGeneration != pipelineGeneration) return;
                    cancelVoiceWatchdog();
                    voicePipelineActive = false;
                    addMessage("Argos error: " + e.getMessage(), Color.rgb(255, 100, 100));
                    robotSetState("idle");
                    resumeWakeWordDetection();
                });
            }
        }).start();
    }

    // Process an AI reply from the voice pipeline.
    // Strips [TOOL:...] tags for display, executes Java-only tool tags
    // (EXPR, HAND, etc.) so the robot reacts, sets fallback expression/gesture
    // if tags are missing, displays the clean text, and speaks it via TTS.
    // This mirrors what onChatResponse does for the text-chat path.
    private void displayAndSpeakVoiceReply(String rawReply, String userMessage) {
        // Strip [TOOL:...] tags from displayed response
        String clean = stripToolTags(rawReply);
        // Collapse whitespace left behind by removed tags
        clean = clean.replaceAll("\\s+", " ").trim();

        // Execute tool tags (EXPR, HAND, OPEN, etc.) so the robot reacts.
        // Off the UI thread — tools can block for seconds (OCR, content
        // resolvers, disk I/O) and would otherwise freeze the 3D robot.
        final String toolsReply = rawReply;
        new Thread(() -> executeToolTags(toolsReply), "argos-tools").start();

        // Fallback: if no EXPR tag was found, set NEUTRAL so robot always reacts
        if (!rawReply.contains("[TOOL:EXPR:") && !rawReply.contains("[TOOL:EXPRSEQ:")) {
            if (robotWebView != null) {
                robotWebView.evaluateJavascript(
                    "if(window.ArgosJS){ArgosJS.setExpression('NEUTRAL');}", null);
            }
        }
        // Fallback: if no HAND tag was found, pick a gesture that matches the
        // reply's expression so the hands always react.
        if (!rawReply.contains("[TOOL:HAND:")) {
            if (robotWebView != null) {
                robotWebView.evaluateJavascript(
                    "if(window.ArgosJS){ArgosJS.setHandGesture('" + defaultGestureForReply(rawReply) + "');}", null);
            }
        }

        // Notify JS: robot is talking.
        // The talking state is now cleared by the TTS UtteranceProgressListener
        // (onDone/onError) via onVoiceTtsFinished(), NOT a fixed 5s timer.
        // This keeps the talking animation in sync with actual speech — the
        // robot stops talking exactly when TTS finishes, however long or short
        // the reply is. If TTS is unavailable/suppressed (screen off), we
        // fall back to clearing talking state immediately below.
        if (robotWebView != null) {
            robotWebView.evaluateJavascript("if(window.ArgosJS){ArgosJS.setThinking(false);ArgosJS.setTalking(true);}", null);
        }

        if (clean.isEmpty()) {
            addMessage("Argos: (tool executed, see results above)", Color.rgb(0, 200, 255));
        } else {
            addMessage("Argos: " + clean, Color.rgb(0, 200, 255));
        }

        // TTS — ttsSpeakJava strips tool tags internally, but pass clean text
        // so we don't rely on that and avoid empty-speech edge cases.
        // The UtteranceProgressListener (set up in initTTS) will call
        // onVoiceTtsFinished() when speech completes, which resets the robot
        // to idle and clears the voice pipeline.
        if (!clean.isEmpty()) {
            String ttsResult = ttsSpeakJava(clean);
            // If TTS didn't actually start (suppressed/not-ready/empty), the
            // listener won't fire — reset the talking state and pipeline now
            // so the robot doesn't freeze in the talking pose forever.
            if (ttsResult == null || (!ttsResult.contains("\"status\":\"speaking\""))) {
                onVoiceTtsFinished();
            }
        } else {
            // No spoken text (tool-only response) — no TTS will fire, so
            // reset the talking state and pipeline immediately.
            onVoiceTtsFinished();
        }

        // Add user message and AI reply to conversation history
        if (userMessage != null && !userMessage.trim().isEmpty()) {
            addToConversationHistory("user", userMessage);
        }
        addToConversationHistory("assistant", rawReply);
    }

    // Add a message to the conversation history (keeps last MAX_HISTORY_MESSAGES)
    private void addToConversationHistory(String role, String content) {
        try {
            org.json.JSONObject msg = new org.json.JSONObject();
            msg.put("role", role);
            msg.put("content", content);
            conversationHistory.add(msg);
            // Trim to last MAX_HISTORY_MESSAGES to avoid unbounded token growth
            while (conversationHistory.size() > MAX_HISTORY_MESSAGES) {
                conversationHistory.remove(0);
            }
        } catch (Exception e) {}
    }

    // Clear conversation history (e.g. when starting a completely new session)
    private void clearConversationHistory() {
        conversationHistory.clear();
    }

    // Send text to backend /api/chat endpoint.
    // Returns the raw response body. Throws Exception with a meaningful message
    // (including the backend's error detail) for non-2xx HTTP responses so the
    // caller can surface the failure instead of silently showing no reply.
    private String sendTextToBackend(String message) throws Exception {
        java.net.URL url = new java.net.URL(backendUrl + "/api/chat");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "Argos-Android/3.29.6");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);

        // Build JSON request body
        org.json.JSONObject jsonBody = new org.json.JSONObject();
        jsonBody.put("message", message);
        // Send conversation history so the AI has context of the ongoing
        // conversation. Without this, every message is independent and Argus
        // appears to "stop responding" after the first exchange.
        org.json.JSONArray historyArray = new org.json.JSONArray();
        for (org.json.JSONObject msg : conversationHistory) {
            historyArray.put(msg);
        }
        jsonBody.put("history", historyArray);
        // Include screen context if available
        String screenCtx = getCurrentScreenContext();
        if (screenCtx != null && !screenCtx.isEmpty()) {
            jsonBody.put("screen_context", screenCtx);
        }

        java.io.OutputStream os = conn.getOutputStream();
        os.write(jsonBody.toString().getBytes("UTF-8"));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        java.io.InputStream is;
        if (code >= 400) {
            is = conn.getErrorStream();
        } else {
            is = conn.getInputStream();
        }
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        if (is != null) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
            is.close();
        }
        conn.disconnect();
        String body = baos.toString("UTF-8");

        if (code >= 400) {
            // The backend /api/chat endpoint returns errors as {"detail": "..."}
            // (ApiError struct). Older/other endpoints may use {"error": "..."}.
            // Extract whichever is present so the user sees the real reason.
            String reason = body;
            try {
                org.json.JSONObject errJson = new org.json.JSONObject(body);
                if (errJson.has("detail")) reason = errJson.optString("detail");
                else if (errJson.has("error")) reason = errJson.optString("error");
            } catch (Exception e) {
                // Body wasn't JSON — use raw body (truncated) as the reason
                if (reason.length() > 200) reason = reason.substring(0, 200);
            }
            throw new Exception("HTTP " + code + ": " + reason);
        }
        return body;
    }

    // Get current screen context for the AI (empty if privacy mode)
    private String getCurrentScreenContext() {
        try {
            if (ArgosAccessibilityService.isFullPrivacy()) return null;
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                String ctx = svc.getScreenContextForAI();
                return (ctx != null && !ctx.isEmpty()) ? ctx : null;
            }
        } catch (Exception e) {}
        return null;
    }

    // Process the recorded audio: stop recording animation, show thinking, upload to backend
    private void processVoiceRecording(byte[] pcmData) {
        isRecording = false;
        voicePipelineActive = true;

        // Stop recording animation, show thinking
        robotStopRecording();

        if (pcmData == null || pcmData.length < 320) {
            voicePipelineActive = false;
            robotSetState("idle");
            addMessage("Voice: No audio captured", Color.rgb(255, 100, 100));
            resumeWakeWordDetection();
            return;
        }

        // Convert PCM to WAV and upload to backend
        final byte[] wavData = pcmToWav(pcmData, 16000, 1, 16);
        // Show thinking state so the user knows Argos is processing
        robotSetState("thinking");
        final int myGeneration = pipelineGeneration;
        // Start watchdog — if the backend doesn't respond in time, reset the
        // robot so it isn't frozen in the thinking pose indefinitely.
        startVoiceWatchdog(myGeneration);
        new Thread(() -> {
            try {
                final String response = uploadVoiceToBackend(wavData);
                android.os.Handler mainHandler = new android.os.Handler(getMainLooper());
                mainHandler.post(() -> {
                    // Discard stale response if the pipeline was interrupted
                    if (myGeneration != pipelineGeneration) return;
                    try {
                        org.json.JSONObject json = new org.json.JSONObject(response);
                        // voice_pipeline returns {"error": "..."} for transcription/LLM
                        // failures (200 body), and credit/multipart errors come through
                        // uploadVoiceToBackend as thrown exceptions. Also handle "detail"
                        // for safety.
                        String errMsg = null;
                        if (json.has("error")) errMsg = json.optString("error");
                        else if (json.has("detail")) errMsg = json.optString("detail");
                        if (errMsg != null && !errMsg.isEmpty()) {
                            cancelVoiceWatchdog();
                            voicePipelineActive = false;
                            addMessage("Argos error: " + errMsg, Color.rgb(255, 100, 100));
                            robotSetState("idle");
                            resumeWakeWordDetection();
                            return;
                        }
                        String transcribed = json.optString("transcribed", "");
                        String reply = json.optString("response", "");
                        if (reply.isEmpty()) reply = json.optString("reply", "");

                        if (!transcribed.isEmpty()) {
                            addMessage("You: " + transcribed, Color.rgb(200, 200, 210));
                        }
                        if (reply.isEmpty()) {
                            cancelVoiceWatchdog();
                            voicePipelineActive = false;
                            addMessage("Argos: (no reply from AI — please try again)", Color.rgb(255, 180, 80));
                            robotSetState("idle");
                            resumeWakeWordDetection();
                        } else {
                            // displayAndSpeakVoiceReply starts TTS; the
                            // UtteranceProgressListener resets the robot to
                            // idle and clears voicePipelineActive when speech
                            // finishes. Do NOT call robotSetState("idle")
                            // here — that would immediately override the
                            // talking animation and freeze the 3D robot.
                            cancelVoiceWatchdog();
                            displayAndSpeakVoiceReply(reply, transcribed);
                        }
                    } catch (Exception e) {
                        cancelVoiceWatchdog();
                        voicePipelineActive = false;
                        addMessage("Voice parse error: " + e.getMessage(), Color.rgb(255, 100, 100));
                        robotSetState("idle");
                        resumeWakeWordDetection();
                    }
                });
            } catch (Exception e) {
                android.os.Handler mainHandler = new android.os.Handler(getMainLooper());
                mainHandler.post(() -> {
                    if (myGeneration != pipelineGeneration) return;
                    cancelVoiceWatchdog();
                    voicePipelineActive = false;
                    addMessage("Argos error: " + e.getMessage(), Color.rgb(255, 100, 100));
                    robotSetState("idle");
                    resumeWakeWordDetection();
                });
            }
        }).start();
    }

    // Start listening animation
    private void robotStartListening() {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            if (robotWebView != null) {
                robotWebView.evaluateJavascript("if(window.ArgosJS){ArgosJS.startListening();}", null);
            }
        });
    }

    // Stop listening, start thinking
    private void robotStopListening() {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            if (robotWebView != null) {
                robotWebView.evaluateJavascript("if(window.ArgosJS){ArgosJS.stopListening();}", null);
            }
        });
    }

    // Start recording animation (red pulsing glow)
    private void robotStartRecording() {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            if (robotWebView != null) {
                robotWebView.evaluateJavascript("if(window.ArgosJS){ArgosJS.startRecording();}", null);
            }
        });
    }

    // Stop recording animation, switch to thinking
    private void robotStopRecording() {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            if (robotWebView != null) {
                robotWebView.evaluateJavascript("if(window.ArgosJS){ArgosJS.stopRecording();}", null);
            }
        });
    }

    // Turn 180 degrees to look at screen (show back to user)
    private void robotLookAtScreen() {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            if (robotWebView != null) {
                robotWebView.evaluateJavascript("if(window.ArgosJS){ArgosJS.lookAtScreen();}", null);
            }
        });
    }

    // Notify robot of user activity — triggers dance if not in standby
    private void robotOnUserActivity() {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            if (robotWebView != null && !standbyMode) {
                robotWebView.evaluateJavascript("if(window.ArgosJS){ArgosJS.onUserActivity();}", null);
            }
        });
    }

    // Play a sequence of expressions for AI communication
    private void robotPlayExpressionSequence(String sequenceJson) {
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.post(() -> {
            if (robotWebView != null) {
                robotWebView.evaluateJavascript(
                    "if(window.ArgosJS){ArgosJS.playExpressionSequence('" + sequenceJson + "');}", null);
            }
        });
    }

    // Convert raw 16-bit PCM to WAV format
    private byte[] pcmToWav(byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
        int dataSize = pcmData.length;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int chunkSize = 36 + dataSize;

        byte[] wav = new byte[44 + dataSize];
        int p = 0;
        // RIFF header
        wav[p++] = 'R'; wav[p++] = 'I'; wav[p++] = 'F'; wav[p++] = 'F';
        wav[p++] = (byte)(chunkSize & 0xff); wav[p++] = (byte)((chunkSize >> 8) & 0xff);
        wav[p++] = (byte)((chunkSize >> 16) & 0xff); wav[p++] = (byte)((chunkSize >> 24) & 0xff);
        wav[p++] = 'W'; wav[p++] = 'A'; wav[p++] = 'V'; wav[p++] = 'E';
        // fmt chunk
        wav[p++] = 'f'; wav[p++] = 'm'; wav[p++] = 't'; wav[p++] = ' ';
        wav[p++] = 16; wav[p++] = 0; wav[p++] = 0; wav[p++] = 0; // subchunk size
        wav[p++] = 1; wav[p++] = 0; // audio format = PCM
        wav[p++] = (byte)channels; wav[p++] = 0;
        wav[p++] = (byte)(sampleRate & 0xff); wav[p++] = (byte)((sampleRate >> 8) & 0xff);
        wav[p++] = (byte)((sampleRate >> 16) & 0xff); wav[p++] = (byte)((sampleRate >> 24) & 0xff);
        wav[p++] = (byte)(byteRate & 0xff); wav[p++] = (byte)((byteRate >> 8) & 0xff);
        wav[p++] = (byte)((byteRate >> 16) & 0xff); wav[p++] = (byte)((byteRate >> 24) & 0xff);
        wav[p++] = (byte)blockAlign; wav[p++] = 0;
        wav[p++] = (byte)bitsPerSample; wav[p++] = 0;
        // data chunk
        wav[p++] = 'd'; wav[p++] = 'a'; wav[p++] = 't'; wav[p++] = 'a';
        wav[p++] = (byte)(dataSize & 0xff); wav[p++] = (byte)((dataSize >> 8) & 0xff);
        wav[p++] = (byte)((dataSize >> 16) & 0xff); wav[p++] = (byte)((dataSize >> 24) & 0xff);
        // PCM data
        System.arraycopy(pcmData, 0, wav, p, dataSize);
        return wav;
    }

    // Upload WAV audio to backend /api/voice endpoint
    private String uploadVoiceToBackend(byte[] wavData) throws Exception {
        String boundary = "----ArgosBoundary" + System.currentTimeMillis();
        String lineEnd = "\r\n";
        String twoHyphens = "--";

        // Capture screen context for the AI — what the user is looking at
        // This gives the AI context about the current app and screen content
        // Privacy is respected: blocked apps and full privacy mode return empty context
        String screenContext = "";
        try {
            ArgosAccessibilityService svc = ArgosAccessibilityService.getInstance();
            if (svc != null) {
                screenContext = svc.getScreenContextForAI();
            }
        } catch (Exception e) {}

        java.net.URL url = new java.net.URL(backendUrl + "/api/voice");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("User-Agent", "Argos-Android/3.29.5");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);

        java.io.OutputStream os = conn.getOutputStream();
        // Write file part
        os.write((twoHyphens + boundary + lineEnd).getBytes());
        os.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"".getBytes());
        os.write(lineEnd.getBytes());
        os.write("Content-Type: audio/wav".getBytes());
        os.write(lineEnd.getBytes());
        os.write(lineEnd.getBytes());
        os.write(wavData);
        os.write(lineEnd.getBytes());

        // Write screen context part — gives AI awareness of what user is looking at
        if (screenContext != null && !screenContext.isEmpty()) {
            os.write((twoHyphens + boundary + lineEnd).getBytes());
            os.write("Content-Disposition: form-data; name=\"screen_context\"".getBytes());
            os.write(lineEnd.getBytes());
            os.write(lineEnd.getBytes());
            os.write(screenContext.getBytes("UTF-8"));
            os.write(lineEnd.getBytes());
        }

        // Write conversation history part — gives AI context of the ongoing
        // conversation so it doesn't treat each voice message independently.
        if (!conversationHistory.isEmpty()) {
            org.json.JSONArray historyArray = new org.json.JSONArray();
            for (org.json.JSONObject msg : conversationHistory) {
                historyArray.put(msg);
            }
            os.write((twoHyphens + boundary + lineEnd).getBytes());
            os.write("Content-Disposition: form-data; name=\"history\"".getBytes());
            os.write(lineEnd.getBytes());
            os.write(lineEnd.getBytes());
            os.write(historyArray.toString().getBytes("UTF-8"));
            os.write(lineEnd.getBytes());
        }

        os.write((twoHyphens + boundary + twoHyphens + lineEnd).getBytes());
        os.flush();
        os.close();

        int responseCode = conn.getResponseCode();
        java.io.InputStream is;
        if (responseCode >= 200 && responseCode < 300) {
            is = conn.getInputStream();
        } else {
            is = conn.getErrorStream();
            if (is == null) is = conn.getInputStream();
        }
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        if (is != null) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            is.close();
        }
        conn.disconnect();
        String body = baos.toString("UTF-8");

        if (responseCode >= 400) {
            // Credit/multipart errors come back as {"detail": "..."} (ApiError).
            // Some flows may use {"error": "..."} — handle both.
            String reason = body;
            try {
                org.json.JSONObject errJson = new org.json.JSONObject(body);
                if (errJson.has("detail")) reason = errJson.optString("detail");
                else if (errJson.has("error")) reason = errJson.optString("error");
            } catch (Exception e) {
                if (reason.length() > 200) reason = reason.substring(0, 200);
            }
            throw new Exception("HTTP " + responseCode + ": " + reason);
        }
        return body;
    }

    // Set backend URL (can be called from settings or preferences)
    public void setBackendUrl(String url) {
        if (url != null && !url.endsWith("/")) {
            this.backendUrl = url;
        } else if (url != null) {
            this.backendUrl = url.substring(0, url.length() - 1);
        }
    }

    // ── JavaScript Bridge ──
    // Called from Three.js via JavascriptInterface
    private class RobotJSBridge {
        @JavascriptInterface
        public void onHeadTap() {
            android.os.Handler handler = new android.os.Handler(getMainLooper());
            handler.post(() -> {
                long now = System.currentTimeMillis();
                long elapsed = now - lastTapTime;
                lastTapTime = now;

                // ── Standby mode: double-tap to wake, ignore single taps ──
                if (standbyMode) {
                    if (elapsed < DOUBLE_TAP_THRESHOLD_MS) {
                        // Double tap — wake up from standby
                        lastTapTime = 0;
                        toggleStandby();
                    }
                    // Single tap in standby is ignored — user must double-tap
                    return;
                }

                // If TTS is speaking, stop it
                if (m_tts != null && m_tts.isSpeaking()) {
                    m_tts.stop();
                    return;
                }

                // If recording, single tap stops recording and triggers AI thinking
                if (isRecording) {
                    stopRecordingAndThink();
                    return;
                }

                // If voice pipeline is processing (thinking/waiting for reply),
                // a double-tap interrupts it and starts a new recording so the
                // user can continue the conversation without waiting for the
                // previous reply to finish. A single tap is ignored to avoid
                // accidental interruptions.
                if (voicePipelineActive) {
                    if (elapsed < DOUBLE_TAP_THRESHOLD_MS) {
                        // Double tap — interrupt pipeline and start new recording
                        lastTapTime = 0;
                        interruptVoicePipeline();
                        if (bubbleVisible) hideBubble();
                        if (backendUrl != null && !backendUrl.isEmpty()) {
                            startVoiceRecording();
                        }
                    }
                    return;
                }

                // Check for double tap (two consecutive taps within threshold)
                if (elapsed < DOUBLE_TAP_THRESHOLD_MS) {
                    // Double tap — start voice recording (primary interaction)
                    lastTapTime = 0; // reset
                    if (bubbleVisible) hideBubble();
                    if (backendUrl != null && !backendUrl.isEmpty()) {
                        startVoiceRecording();
                    } else {
                        // No backend — show chat bubble as fallback
                        if (bubbleVisible) hideBubble();
                        else showBubble();
                    }
                    return;
                }

                // Single tap — wait briefly to see if a second tap comes
                handler.postDelayed(() -> {
                    // If lastTapTime was reset by double tap, skip
                    if (lastTapTime == 0) return;
                    // If a second tap came in, this was handled as double tap
                    if (System.currentTimeMillis() - lastTapTime < DOUBLE_TAP_THRESHOLD_MS) return;

                    // Single tap confirmed — toggle chat bubble (text input, secondary)
                    if (bubbleVisible) {
                        hideBubble();
                    } else {
                        showBubble();
                    }
                }, DOUBLE_TAP_THRESHOLD_MS + 50);
            });
        }

        @JavascriptInterface
        public void onBodyTap() {
            android.os.Handler handler = new android.os.Handler(getMainLooper());
            handler.post(() -> {
                // Ignore body tap in standby mode — robot is sleeping
                if (standbyMode) return;
                // Spin then walk — trigger via JS
                if (robotWebView != null) {
                    robotWebView.evaluateJavascript("if(window.ArgosJS){ArgosJS.setState('spinning');}", null);
                }
            });
        }

        @JavascriptInterface
        public void onLongPress(float x, float y) {
            android.os.Handler handler = new android.os.Handler(getMainLooper());
            handler.post(() -> {
                showLongPressMenu();
            });
        }

        // Robot-side diagnostics → logcat (tag "ArgosDiag") and a liveness
        // signal for the freeze watchdog. The JS sends this every few seconds,
        // so a *missing* heartbeat means the animation loop is genuinely dead —
        // much more precise than "the robot hasn't moved", which is normal
        // while idle or dancing.
        @JavascriptInterface
        public void onDiag(String msg) {
            m_lastAliveTime = System.currentTimeMillis();
            android.util.Log.i("ArgosDiag", msg);
        }

        @JavascriptInterface
        public void onRobotPosition(float x, float y, float size) {
            // Ignore position updates until the initial centered position has
            // been set — otherwise the robot jumps to (0,0) = top-left corner.
            if (!positionInitialized) return;
            // Freeze detection: any real movement resets the timer.
            if (Math.abs(x - m_lastPosX) > 2f || Math.abs(y - m_lastPosY) > 2f) {
                m_lastPosX = x;
                m_lastPosY = y;
                m_lastMoveTime = System.currentTimeMillis();
            }
            robotScreenX = x;
            // Clamp Y to stay above keyboard if keyboard is visible
            if (keyboardVisible && keyboardTopY > 0) {
                float maxY = keyboardTopY - size * 0.5f;
                if (y > maxY) y = maxY;
            }
            robotScreenY = y;
            // size from JS is effective size (includes 3D depth scale)
            currentScale = size / robotSize;
            final int winW = (int) (size * 2.0f);
            final int winH = (int) (size * 1.7f);
            final float finalY = y;
            android.os.Handler handler = new android.os.Handler(getMainLooper());
            handler.post(() -> {
                if (robotWebView == null || robotParams == null || isDragging) return;
                robotParams.width = winW;
                robotParams.height = winH;
                robotParams.x = (int) (x - winW / 2.0f);
                // Clamp window Y to stay above keyboard
                int winY = (int) (finalY - winH / 2.0f);
                if (keyboardVisible && keyboardTopY > 0) {
                    int maxWinY = keyboardTopY - winH;
                    if (winY > maxWinY) winY = maxWinY;
                }
                robotParams.y = winY;
                try {
                    windowManager.updateViewLayout(robotWebView, robotParams);
                } catch (Exception e) {}
                // Keep the speech bubble stuck on top of the robot's head
                // as it moves around the screen.
                if (bubbleVisible && bubbleOverlay != null) {
                    updateBubblePosition();
                    try {
                        windowManager.updateViewLayout(bubbleOverlay, bubbleParams);
                    } catch (Exception e) {}
                }
                // Keep the thought bubble following the robot too
                if (thoughtBubble != null && thoughtBubble.getVisibility() == View.VISIBLE) {
                    updateThoughtBubblePosition();
                    try {
                        windowManager.updateViewLayout(thoughtBubble, thoughtParams);
                    } catch (Exception e) {}
                }
            });
        }

        @JavascriptInterface
        public void onDragEnd() {
            // BUG FIX: this used to be a no-op, so the JS-side stale-drag
            // watchdog (and any touchcancel) could not clear Java's drag flag.
            // A stuck isDragging permanently blocks roaming.
            android.os.Handler h = new android.os.Handler(getMainLooper());
            h.post(() -> { isDragging = false; });
        }

        @JavascriptInterface
        public void onTouchDown(float x, float y) {
            // Handled in touch listener
        }

        @JavascriptInterface
        public void onTouchMove(float x, float y, boolean dragging) {
            // Handled in touch listener
        }

        // ── Hand tracking callbacks (from MediaPipe Web in WebView) ──
        @JavascriptInterface
        public void onHandGrab(float normalizedX, float normalizedY) {
            android.os.Handler handler = new android.os.Handler(getMainLooper());
            handler.post(() -> {
                if (robotWebView != null) {
                    robotWebView.evaluateJavascript(
                        "if(window.ArgosJS){ArgosJS.setExpression('SURPRISED');}", null);
                }
            });
        }

        @JavascriptInterface
        public void onHandDrag(float normalizedX, float normalizedY) {
            android.os.Handler handler = new android.os.Handler(getMainLooper());
            handler.post(() -> {
                // Move robot to hand position (normalized 0-1 → screen pixels)
                float screenX = normalizedX * screenWidth;
                float screenY = normalizedY * screenHeight;
                robotScreenX = screenX;
                robotScreenY = screenY;
                if (robotParams != null && robotWebView != null) {
                    float size = robotSize * currentScale;
                    int winW = (int) (size * 2.0f);
                    int winH = (int) (size * 1.7f);
                    robotParams.x = (int) Math.max(0, Math.min(screenWidth - winW, screenX - winW / 2.0f));
                    robotParams.y = (int) Math.max(0, Math.min(screenHeight - winH, screenY - winH / 2.0f));
                    try {
                        windowManager.updateViewLayout(robotWebView, robotParams);
                    } catch (Exception e) {}
                    // Update JS position so it doesn't fight us
                    robotWebView.evaluateJavascript(
                        "if(window.ArgosJS){ArgosJS.setPosition(" + screenX + "," + screenY + ");}", null);
                }
                // Move thought bubble too if visible
                if (thoughtBubble != null && thoughtBubble.getVisibility() == View.VISIBLE) {
                    updateThoughtBubblePosition();
                    try {
                        windowManager.updateViewLayout(thoughtBubble, thoughtParams);
                    } catch (Exception e) {}
                }
            });
        }

        @JavascriptInterface
        public void onHandRelease(float normalizedX, float normalizedY) {
            android.os.Handler handler = new android.os.Handler(getMainLooper());
            handler.post(() -> {
                if (robotWebView != null) {
                    robotWebView.evaluateJavascript(
                        "if(window.ArgosJS){ArgosJS.setExpression('HAPPY');}", null);
                }
            });
        }

        @JavascriptInterface
        public void onHandTrackingStatus(String status) {
            android.os.Handler handler = new android.os.Handler(getMainLooper());
            handler.post(() -> {
                if (status.equals("error")) {
                    addMessage("📷 Hand tracking error — check camera permission", Color.rgb(255, 150, 100));
                } else if (status.equals("active")) {
                    addMessage("✋ Hand tracking on — pinch to drag Argos!", Color.rgb(0, 255, 136));
                } else if (status.equals("stopped")) {
                    addMessage("✋ Hand tracking off", Color.rgb(180, 180, 180));
                }
            });
        }
    }
}
