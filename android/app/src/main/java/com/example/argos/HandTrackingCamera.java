package com.example.argos;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HandTrackingCamera — uses the front camera + MediaPipe Hand Landmark Detection
 * to let the user grab and drag the 3D Argos robot with their hand.
 *
 * Key design decisions:
 * - Implements LifecycleOwner so CameraX can bind to it (Services don't have a Lifecycle)
 * - Uses CameraX 1.4+ ImageProxy.toBitmap() for fast frame conversion
 * - Camera overlay is placed BEHIND the robot (lower z-order)
 * - Pinch gesture (thumb + index) to grab, release to drop
 */
public class HandTrackingCamera implements LifecycleOwner {

    private static final String TAG = "ArgosHandTracking";
    private static final float PINCH_GRAB_THRESHOLD = 0.07f;
    private static final float PINCH_RELEASE_THRESHOLD = 0.12f;
    private static final String MODEL_FILE = "hand_landmarker.task";

    public interface HandTrackingCallback {
        void onHandGrab(float normalizedX, float normalizedY);
        void onHandDrag(float normalizedX, float normalizedY);
        void onHandRelease(float normalizedX, float normalizedY);
        void onHandTrackingError(String message);
        void onCameraReady();
    }

    private final Context context;
    private final WindowManager windowManager;
    private final HandTrackingCallback callback;
    private final int layoutType;

    // Custom lifecycle for CameraX (Services don't have one)
    private final LifecycleRegistry lifecycleRegistry;

    // Camera UI
    private View cameraOverlay;
    private WindowManager.LayoutParams cameraParams;
    private PreviewView previewView;
    private TextView statusText;

    // Camera + ML
    private ProcessCameraProvider cameraProvider;
    private HandLandmarker handLandmarker;
    private ExecutorService cameraExecutor;
    private boolean isActive = false;
    private boolean isGrabbing = false;
    private boolean cameraStarted = false;

    // For smoothing hand position
    private float smoothedX = 0.5f;
    private float smoothedY = 0.5f;
    private static final float SMOOTHING_FACTOR = 0.4f;

    public HandTrackingCamera(Context context, WindowManager windowManager,
                              int layoutType, HandTrackingCallback callback) {
        this.context = context;
        this.windowManager = windowManager;
        this.layoutType = layoutType;
        this.callback = callback;
        this.cameraExecutor = Executors.newSingleThreadExecutor();
        this.lifecycleRegistry = new LifecycleRegistry(this);
        this.lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
    }

    // ── LifecycleOwner implementation (needed by CameraX) ──
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    public static boolean hasCamera(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    public static boolean hasPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void start() {
        if (isActive) return;
        if (!hasPermission(context)) {
            callback.onHandTrackingError("Camera permission not granted");
            return;
        }

        Log.i(TAG, "Starting hand tracking camera...");
        createCameraOverlay();
        initHandLandmarker();

        // Mark as active BEFORE starting camera so analyzeImage doesn't skip frames
        isActive = true;

        // Start lifecycle for CameraX
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);

        startCamera();
    }

    public void stop() {
        isActive = false;
        isGrabbing = false;
        cameraStarted = false;

        // Stop lifecycle
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP);

        if (cameraProvider != null) {
            try { cameraProvider.unbindAll(); } catch (Exception e) {}
            cameraProvider = null;
        }

        if (handLandmarker != null) {
            try { handLandmarker.close(); } catch (Exception e) {}
            handLandmarker = null;
        }

        if (cameraOverlay != null) {
            try {
                if (cameraOverlay.getWindowToken() != null) {
                    windowManager.removeView(cameraOverlay);
                }
            } catch (Exception e) {}
            cameraOverlay = null;
        }
    }

    public boolean isActive() {
        return isActive;
    }

    // Create the semi-transparent camera overlay window
    // IMPORTANT: This overlay is placed at a LOWER z-order than the robot
    // so the robot appears ON TOP of the camera feed
    private void createCameraOverlay() {
        FrameLayout container = new FrameLayout(context);
        container.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Camera preview — fills the screen, semi-transparent
        previewView = new PreviewView(context);
        previewView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        previewView.setAlpha(0.5f); // Semi-transparent so robot is visible on top
        container.addView(previewView);

        // Status text at top
        statusText = new TextView(context);
        statusText.setText("✋ Show your hand — pinch to grab Argos");
        statusText.setTextColor(Color.rgb(0, 255, 136));
        statusText.setTextSize(16f);
        statusText.setPadding(24, 60, 24, 16);
        statusText.setShadowLayer(6, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        statusParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        container.addView(statusText, statusParams);

        // Close button at bottom-right
        TextView closeBtn = new TextView(context);
        closeBtn.setText("✕ Close Camera");
        closeBtn.setTextColor(Color.rgb(255, 100, 100));
        closeBtn.setTextSize(16f);
        closeBtn.setPadding(24, 16, 24, 16);
        closeBtn.setShadowLayer(6, 0, 0, Color.BLACK);
        closeBtn.setOnClickListener(v -> stop());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        closeParams.gravity = Gravity.BOTTOM | Gravity.END;
        closeParams.setMargins(0, 0, 24, 100);
        container.addView(closeBtn, closeParams);

        cameraOverlay = container;

        // Use TYPE_APPLICATION_OVERLAY (same as robot) but add FLAG_NOT_TOUCH_MODAL
        // so the camera doesn't block touches to the robot.
        // The camera overlay will be BELOW the robot because we add it first
        // and the robot is added on top.
        cameraParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        cameraParams.gravity = Gravity.TOP | Gravity.START;
        cameraParams.x = 0;
        cameraParams.y = 0;

        try {
            windowManager.addView(cameraOverlay, cameraParams);
            Log.i(TAG, "Camera overlay added to window manager");
        } catch (Exception e) {
            Log.e(TAG, "Failed to add camera overlay: " + e.getMessage());
            callback.onHandTrackingError("Failed to show camera: " + e.getMessage());
        }
    }

    // Initialize MediaPipe Hand Landmarker
    private void initHandLandmarker() {
        try {
            String modelPath = copyModelFromAssets();
            if (modelPath == null) {
                callback.onHandTrackingError("Hand tracking model not found in assets");
                return;
            }

            Log.i(TAG, "Loading hand landmarker model from: " + modelPath);
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath(modelPath)
                    .build();

            HandLandmarker.HandLandmarkerOptions options =
                    HandLandmarker.HandLandmarkerOptions.builder()
                            .setBaseOptions(baseOptions)
                            .setRunningMode(RunningMode.LIVE_STREAM)
                            .setNumHands(1)
                            .setMinHandDetectionConfidence(0.4f)
                            .setMinHandPresenceConfidence(0.4f)
                            .setMinTrackingConfidence(0.4f)
                            .setResultListener(this::onHandLandmarkerResult)
                            .setErrorListener((error) -> {
                                Log.e(TAG, "HandLandmarker error: " + error.getMessage());
                            })
                            .build();

            handLandmarker = HandLandmarker.createFromOptions(context, options);
            Log.i(TAG, "Hand landmarker initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to init hand landmarker: " + e.getMessage());
            callback.onHandTrackingError("Failed to init hand tracking: " + e.getMessage());
        }
    }

    private String copyModelFromAssets() {
        try {
            java.io.InputStream is = context.getAssets().open(MODEL_FILE);
            java.io.File outFile = new java.io.File(context.getFilesDir(), MODEL_FILE);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
            fos.close();
            is.close();
            Log.i(TAG, "Model copied: " + outFile.length() + " bytes");
            return outFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Model file not found in assets: " + MODEL_FILE + " — " + e.getMessage());
            return null;
        }
    }

    // Start the camera with CameraX
    // Uses THIS class as the LifecycleOwner (not the Service context)
    private void startCamera() {
        try {
            Log.i(TAG, "Requesting ProcessCameraProvider...");
            com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider> future =
                    ProcessCameraProvider.getInstance(context);

            future.addListener(() -> {
                try {
                    cameraProvider = future.get();
                    Log.i(TAG, "ProcessCameraProvider obtained");

                    // Preview use case
                    Preview preview = new Preview.Builder().build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());

                    // Image analysis for hand detection
                    ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build();
                    imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                    // Select front camera
                    CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                    cameraProvider.unbindAll();
                    // Bind to THIS (HandTrackingCamera) which implements LifecycleOwner
                    cameraProvider.bindToLifecycle(
                            this,
                            cameraSelector,
                            preview,
                            imageAnalysis
                    );

                    cameraStarted = true;
                    Log.i(TAG, "Camera started — front camera bound to lifecycle");
                    if (callback != null) callback.onCameraReady();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start camera: " + e.getMessage(), e);
                    callback.onHandTrackingError("Failed to start camera: " + e.getMessage());
                }
            }, ContextCompat.getMainExecutor(context));
        } catch (Exception e) {
            Log.e(TAG, "Camera init failed: " + e.getMessage(), e);
            callback.onHandTrackingError("Camera init failed: " + e.getMessage());
        }
    }

    // Analyze each camera frame for hand landmarks
    private void analyzeImage(ImageProxy imageProxy) {
        if (handLandmarker == null || !isActive) {
            imageProxy.close();
            return;
        }

        try {
            // Fast conversion: CameraX 1.4+ with RGBA_8888 output format
            // gives us a bitmap directly via toBitmap()
            android.graphics.Bitmap bitmap = imageProxyToBitmap(imageProxy);
            if (bitmap == null) {
                imageProxy.close();
                return;
            }

            MPImage mpImage = new BitmapImageBuilder(bitmap).build();
            long timestampMs = System.currentTimeMillis();

            try {
                handLandmarker.detectAsync(mpImage, timestampMs);
            } catch (IllegalStateException e) {
                // Previous detection still running — skip this frame
            } catch (Exception e) {
                Log.w(TAG, "detectAsync error: " + e.getMessage());
            }

        } catch (Exception e) {
            Log.w(TAG, "Image analysis error: " + e.getMessage());
        } finally {
            imageProxy.close();
        }
    }

    // Convert ImageProxy to Bitmap — fast path using CameraX 1.4+ API
    private android.graphics.Bitmap imageProxyToBitmap(ImageProxy image) {
        try {
            // CameraX 1.4+ with OUTPUT_IMAGE_FORMAT_RGBA_8888 gives us
            // a direct bitmap conversion — much faster than manual YUV conversion
            android.graphics.Bitmap bitmap;

            // Try the toBitmap() method first (CameraX 1.4+)
            try {
                bitmap = image.toBitmap();
            } catch (Exception e) {
                // Fallback: manual RGBA conversion from planes
                int width = image.getWidth();
                int height = image.getHeight();
                ImageProxy.PlaneProxy[] planes = image.getPlanes();
                java.nio.ByteBuffer buffer = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * width;

                bitmap = android.graphics.Bitmap.createBitmap(
                        width + rowPadding / pixelStride, height,
                        android.graphics.Bitmap.Config.ARGB_8888);
                buffer.rewind();
                bitmap.copyPixelsFromBuffer(buffer);

                // Crop to actual width (remove padding)
                if (rowPadding > 0) {
                    bitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height);
                }
            }

            // Rotate based on camera rotation degrees
            int rotation = image.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                matrix.postRotate(rotation);
                // Mirror horizontally for front camera (selfie view)
                matrix.postScale(-1, 1, bitmap.getWidth() / 2f, bitmap.getHeight() / 2f);
                bitmap = android.graphics.Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }

            return bitmap;
        } catch (Exception e) {
            Log.w(TAG, "Bitmap conversion failed: " + e.getMessage());
            return null;
        }
    }

    // Hand landmarker result callback (called on main thread)
    private void onHandLandmarkerResult(HandLandmarkerResult result, MPImage input) {
        if (result == null || result.landmarks().isEmpty()) {
            if (isGrabbing) {
                isGrabbing = false;
                if (callback != null) callback.onHandRelease(smoothedX, smoothedY);
                updateStatusText("✋ Show your hand — pinch to grab Argos");
            }
            return;
        }

        // Get landmarks for the first hand
        java.util.List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> landmarks =
                result.landmarks().get(0);

        if (landmarks.size() < 9) return;

        // Landmark indices:
        // 4 = thumb tip, 8 = index finger tip
        com.google.mediapipe.tasks.components.containers.NormalizedLandmark thumbTip = landmarks.get(4);
        com.google.mediapipe.tasks.components.containers.NormalizedLandmark indexTip = landmarks.get(8);

        // Calculate pinch distance (normalized 0-1)
        float dx = thumbTip.x() - indexTip.x();
        float dy = thumbTip.y() - indexTip.y();
        float pinchDist = (float) Math.sqrt(dx * dx + dy * dy);

        // Hand position = midpoint between thumb tip and index tip
        // MediaPipe gives normalized coords where (0,0) = top-left, (1,1) = bottom-right
        // For front camera, the image is already mirrored in our bitmap conversion,
        // so we DON'T need to flip X again here
        float rawX = (thumbTip.x() + indexTip.x()) / 2.0f;
        float rawY = (thumbTip.y() + indexTip.y()) / 2.0f;

        // Clamp to valid range
        rawX = Math.max(0f, Math.min(1f, rawX));
        rawY = Math.max(0f, Math.min(1f, rawY));

        // Smooth the position to reduce jitter
        smoothedX = smoothedX + (rawX - smoothedX) * SMOOTHING_FACTOR;
        smoothedY = smoothedY + (rawY - smoothedY) * SMOOTHING_FACTOR;

        // Gesture detection with hysteresis
        if (!isGrabbing && pinchDist < PINCH_GRAB_THRESHOLD) {
            isGrabbing = true;
            if (callback != null) callback.onHandGrab(smoothedX, smoothedY);
            updateStatusText("🤏 Grabbed! Move your hand to drag Argos");
        } else if (isGrabbing && pinchDist > PINCH_RELEASE_THRESHOLD) {
            isGrabbing = false;
            if (callback != null) callback.onHandRelease(smoothedX, smoothedY);
            updateStatusText("✋ Released — pinch to grab again");
        } else if (isGrabbing) {
            if (callback != null) callback.onHandDrag(smoothedX, smoothedY);
        } else {
            updateStatusText("✋ Pinch to grab Argos");
        }
    }

    private void updateStatusText(final String text) {
        if (statusText != null) {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(() -> {
                if (statusText != null) statusText.setText(text);
            });
        }
    }

    public void destroy() {
        stop();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
            cameraExecutor = null;
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
    }
}
