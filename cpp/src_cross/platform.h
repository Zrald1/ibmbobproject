#pragma once
#include <string>
#include <functional>

// Platform abstraction layer for Argos cross-platform support.
// Each platform (Windows, Android, iOS, macOS, Linux) implements these.

namespace argos {

// HTTP request (POST with JSON body). Returns response body or empty on error.
std::string httpPost(const std::string& url, const std::string& headers, const std::string& body);

// HTTP streaming request. Calls callback with each chunk. Returns full response.
std::string httpPostStream(const std::string& url, const std::string& headers,
                           const std::string& body,
                           std::function<bool(const std::string& chunk)> callback);

// Get app data directory for persistent storage
std::string getAppDataDir();

// Set app data directory (called by platform init)
void setAppDataDir(const char* dir);

// Log a message (platform-specific: OutputDebugString, __android_log_print, etc.)
void log(const char* message);

// Set JNI environment for HTTP requests (Android only — uses Java HttpURLConnection for HTTPS)
void setJniForHttp(void* jvm, void* service);

// ── Browser / Screen interaction (platform-specific) ──
// On Android these use Accessibility Service via JNI.
// On other platforms they may be stubs.

// Open a URL in the device's default browser
std::string openUrl(const std::string& url);

// Get all text content currently visible on screen (any app, including browser)
std::string getScreenText();

// Get the package name of the currently focused app
std::string getActiveApp();

// Click on the first element containing the given text
std::string clickText(const std::string& text);

// Type text into the currently focused input field
std::string typeText(const std::string& text);

// Scroll the current screen: direction 0=up, 1=down
std::string scrollScreen(int direction);

// ── UI Inspection & Automation (Android Accessibility Service) ──

// Get the full UI element tree as JSON (from Accessibility Service)
// maxDepth limits traversal depth (-1 = unlimited)
std::string getUITree(int maxDepth);

// Perform an accessibility action on a node by ID
// action: "click", "long_click", "focus", "set_text", "scroll_forward", "scroll_backward", "select", "expand", "collapse"
// extra: optional text for set_text action
std::string performUIAction(int elementId, const std::string& action, const std::string& extra);

// Take a screenshot and return base64-encoded JPEG (or save to file)
std::string takeScreenshot(const std::string& savePath);

// Get active notifications as JSON
std::string getNotificationsList();

// Reply to a notification by index
std::string replyToNotificationByIdx(int index, const std::string& message);

// ── Gesture-based UI automation (dispatchGesture) ──

// Tap (click) at a screen coordinate
std::string clickAtPoint(int x, int y);

// Long press at a screen coordinate
std::string longPressAtPoint(int x, int y);

// Swipe from one point to another
std::string swipeGesture(int x1, int y1, int x2, int y2, int durationMs);

// Swipe up (scroll down content)
std::string swipeUp();

// Swipe down (scroll up content)
std::string swipeDown();

// Swipe left (scroll right content)
std::string swipeLeft();

// Swipe right (scroll left content)
std::string swipeRight();

// Smart click at point — tries accessibility action first, falls back to gesture
std::string smartClick(int x, int y);

// Smart long press at point — tries accessibility action first, falls back to gesture
std::string smartLongPress(int x, int y);

// Get all clickable/interactive elements with their bounds and center points
std::string getClickableElements();

// Get screen size as JSON {"width":W,"height":H}
std::string getScreenSize();

// ── Voice / Audio (native TTS — STT handled by backend) ──

// Record audio from microphone (Android: AudioRecord via JNI, Windows: WaveIn)
// Returns raw 16-bit PCM data as a string (bytes)
std::string recordAudioJava(int durationSeconds);

// Text-to-Speech (Android: TextToSpeech, Windows: SAPI)
std::string ttsSpeakJava(const std::string& text);
std::string ttsStopJava();
std::string ttsIsSpeakingJava();

// Get current time in milliseconds
int64_t getTimeMs();

// ── System tools (platform-specific) ──

// Open a file, folder, or URL
std::string openFile(const std::string& path);

// Write text to a file (args: "filepath|content")
std::string writeFile(const std::string& args);

// Run/launch an app by package name (Android) or command (Windows)
std::string runApp(const std::string& packageName);

// Copy text to clipboard
std::string clipboardCopy(const std::string& text);

// Set system media volume (0-100)
std::string setVolume(int level);

// Show a system notification
std::string showNotification(const std::string& message);

// ── Robot Control (3D floating robot) ──

// Set robot expression: neutral, happy, thinking, talking, sleeping, surprised
void robotSetExpression(const std::string& expression);

// Move robot to screen position (x, y)
void robotMoveTo(float x, float y);

// Set robot animation state: idle, walking, greeting, thinking, talking, spinning, sleeping
void robotSetState(const std::string& state);

// Get robot position as JSON {"x":N,"y":N,"size":N,"screenW":N,"screenH":N}
std::string robotGetPosition();

// Set robot zoom scale manually (1.0=normal, 0.5=far/small, 1.5=close/big)
void robotSetZoom(float scale);

// Reset robot zoom to auto depth scaling
void robotResetZoom();

// Blink/teleport robot to screen position (x, y) with fade effect
void robotBlinkTo(float x, float y);

// Observe screen: real screenshot + OCR + UI elements in one call
std::string observeScreen();

// ── Phone Automation Tools (Play Store Compliant) ──

// Dial a phone number (opens dialer, user presses call)
std::string dialPhoneNumber(const std::string& number);

// Send SMS via intent (opens SMS app, user presses send)
std::string sendSmsViaIntent(const std::string& number, const std::string& message);

// Search contacts by name or number
std::string searchContacts(const std::string& query);

// Create calendar event via intent
std::string createCalendarEvent(const std::string& title, const std::string& description, long startMillis, long endMillis);

// Read upcoming calendar events
std::string readCalendarEvents(int daysAhead);

// Set a timer via AlarmClock intent
std::string setTimer(int seconds, const std::string& label);

// Set an alarm via AlarmClock intent
std::string setAlarm(int hour, int minute, const std::string& label);

// Get battery level and charging status
std::string getBatteryStatus();

// Toggle flashlight
std::string toggleFlashlight(bool on);

// Open maps for a location search
std::string openMaps(const std::string& query);

// Start navigation to a destination
std::string startNavigation(const std::string& destination);

// Share text content via system share sheet
std::string shareContent(const std::string& text);

// Play music (opens music app with search query)
std::string playMusic(const std::string& query);

// Open a specific settings page
std::string openSettings(const std::string& settingType);

// Read clipboard content
std::string readClipboard();

// Get current GPS location
std::string getLocation();

// Go back (global action)
std::string goBack();

// Go home (global action)
std::string goHome();

// Open recents (global action)
std::string openRecents();

} // namespace argos
