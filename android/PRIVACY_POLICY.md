# Argos AI Companion — Privacy Policy

**Last updated: August 12, 2026**

## Overview

Argos is an AI companion robot app that provides intelligent assistance on your Android device. This privacy policy explains what data we collect, how we use it, and the choices you have.

## Data Collected Through AccessibilityService API

Argos uses the Android AccessibilityService API to provide its core functionality. Specifically:

- **Screen Text and UI Elements**: We read text and UI elements visible on your screen to understand what you are looking at and provide relevant assistance.
- **Active App Information**: We identify which app you are currently using to provide context-aware responses.
- **Notifications**: We read active notifications so Argos can inform you and reply on your behalf when instructed.
- **Screenshots for OCR**: We capture screenshots and perform text recognition (OCR) to understand screen content when accessibility nodes are insufficient.
- **Gesture Performance**: We perform clicks, typing, scrolling, and swipe gestures on your behalf when you instruct Argos to interact with your device.

**How this data is used:**
- Screen content is sent to our AI server (Amazon Bedrock) to generate helpful responses and actions.
- Screen content is processed in real-time and is **not permanently stored** on our servers.
- Sensitive data (passwords, credit card numbers, authentication tokens) is automatically filtered out before being sent to the AI.
- No screen content are shared with third parties or used for advertising.

**Your control:**
- You can disable the accessibility service at any time in Settings > Accessibility.
- You can enable Privacy Mode within Argos to prevent screen reading.
- You can block specific apps from being read by Argos using the per-app blocklist.

## Data Collected Through Other Permissions

### Microphone (RECORD_AUDIO)
- Used for voice input when you speak to Argos.
- Audio is sent to our self-hosted Whisper transcription server and then discarded.
- Audio is not permanently stored.

### Contacts (READ_CONTACTS)
- Used to search for contact information when you ask Argos to call or text someone.
- Contact data is only accessed when you explicitly request it.
- Contact data is not stored or shared.

### Calendar (READ_CALENDAR)
- Used to read upcoming calendar events when you ask about your schedule.
- Calendar data is only accessed when you explicitly request it.
- Calendar data is not stored or shared.

### Location (ACCESS_FINE_LOCATION)
- Used to get your current GPS coordinates when you ask about your location or request navigation.
- Location is only accessed when you explicitly request it.
- Location data is not stored or shared.

### Notifications (POST_NOTIFICATIONS)
- Used to show Argos reminders and scheduled task notifications.
- Argos does not send promotional notifications.

### Alarms (SCHEDULE_EXACT_ALARM, USE_EXACT_ALARM)
- Used to set reminders and scheduled tasks at specific times.

### Overlay (SYSTEM_ALERT_WINDOW)
- Used to display the Argos robot companion as a floating overlay on top of other apps.
- This is the core visual interface of the app.

## Account Data

- **Device ID**: We use ANDROID_ID to identify your device for authentication.
- **Chat History**: Conversations are stored to provide persistent memory. You can clear this at any time.

## Data We Do NOT Collect

- We do **not** collect SMS messages (we use intents that let you review and send manually).
- We do **not** collect call logs.
- We do **not** use your data for advertising.
- We do **not** sell or share your data with third parties.
- We do **not** track your browsing history.
- We do **not** access your camera (flashlight uses CameraManager torch mode only).

## Data Storage and Security

- All server communication uses HTTPS/TLS encryption.
- Passwords are hashed with bcrypt.
- Screen content is processed in real-time and not persisted.
- Chat history is stored on our server and can be deleted by you at any time.
- We use AWS infrastructure with industry-standard security practices.

## Third-Party Services

- **Amazon Bedrock (AWS)**: Used for AI chat processing. Your messages are sent to AWS for inference. AWS does not use your data for model training.
- **Self-hosted Whisper Server**: Used for voice transcription. Audio is processed and discarded.

## Children's Privacy

Argos is not directed at children under 13. We do not knowingly collect data from children. If you believe a child has provided us with personal information, please contact us to have it deleted.

## Your Rights

- **Access**: You can view your chat history and credit balance within the app.
- **Deletion**: You can clear your chat history at any time. Contact us to delete your account.
- **Opt-out**: You can disable any permission at any time in your device settings.
- **Consent**: You can revoke accessibility service access at any time.

## Changes to This Policy

We may update this privacy policy from time to time. We will notify you of significant changes by updating the date at the top of this policy.

## Contact

For privacy questions or concerns, contact: support@your-domain.example.com

## Compliance Notes

This app complies with Google Play Store policies:
- AccessibilityService API is used for user-initiated automation, not autonomous actions.
- All actions are triggered by explicit user commands.
- SMS and calls use intent-based approaches (user confirms before sending).
- No restricted permissions (SEND_SMS, READ_SMS, READ_CALL_LOG) are used.
- `isAccessibilityTool` is not set to true (app is not a disability tool).
- Prominent in-app disclosure and consent are provided before enabling accessibility.
