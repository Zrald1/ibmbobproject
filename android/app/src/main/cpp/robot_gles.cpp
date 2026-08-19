#include "robot_gles.h"
#include <cmath>
#include <android/log.h>
#include <cstdlib>

#define TAG "Argos"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

RobotGles::RobotGles() {}
RobotGles::~RobotGles() {}

void RobotGles::init(EglRenderer* renderer, float screenWidth, float screenHeight) {
    m_renderer = renderer;
    m_screenWidth = screenWidth;
    m_screenHeight = screenHeight;
    if (renderer) {
        // Robot size based on screen width
        m_robotSize = screenWidth * 0.12f;
        // Start at center of screen
        m_cx = screenWidth / 2.0f;
        m_cy = screenHeight * 0.35f;
        m_targetX = m_cx;

        // Start walking immediately — pick a random direction
        walkToEdge(rand() % 2 == 0);
    }
}

void RobotGles::setScreenPosition(float x, float y) {
    m_cx = x;
    m_cy = y;
    m_targetX = x;
}

void RobotGles::setScreenDims(float w, float h) {
    m_screenWidth = w;
    m_screenHeight = h;
}

void RobotGles::setState(AndroidRobotState state, float duration) {
    m_state = state;
    m_stateTimer = 0.0f;
    m_nextBehaviorTime = duration;
}

void RobotGles::pickNextBehavior() {
    float roll = (float)rand() / RAND_MAX;
    if (roll < 0.25f) {
        setState(AndroidRobotState::Greeting, 1.5f);
    } else if (roll < 0.55f) {
        walkToEdge(rand() % 2 == 0);
    } else {
        setState(AndroidRobotState::Idle, 4.0f + (float)(rand() % 5));
    }
}

void RobotGles::walkToEdge(bool left) {
    float margin = m_robotSize * 1.5f;
    m_targetX = left ? margin : m_screenWidth - margin;
    m_facingRight = !left;
    setState(AndroidRobotState::Walking, 999.0f);
}

void RobotGles::startSpinThenWalk(bool left) {
    m_spinWalkLeft = left;
    m_spinTimer = 0.0f;
    float margin = m_robotSize * 1.5f;
    m_targetX = left ? margin : m_screenWidth - margin;
    m_facingRight = !left;
    setState(AndroidRobotState::Spinning, m_spinDuration);
}

float RobotGles::computeWalkLean() {
    if (m_state != AndroidRobotState::Walking) return 0.0f;
    float lean = sinf(m_animTime * 8.0f) * 5.0f;
    return lean * (m_facingRight ? 1.0f : -1.0f);
}

void RobotGles::update(float dt) {
    m_animTime += dt;
    m_bob = sinf(m_animTime * 2.0f) * 6.0f;
    m_stateTimer += dt;

    // Eye blink logic
    m_eyeBlinkTimer -= dt;
    if (m_eyeBlinkTimer <= 0 && !m_blinking) {
        m_blinking = true;
        m_blinkDuration = 0.15f;
        m_eyeBlinkTimer = 3.0f + (float)(rand() % 40) / 10.0f;
    }
    if (m_blinking) {
        m_blinkDuration -= dt;
        if (m_blinkDuration <= 0) m_blinking = false;
    }

    // State machine
    if (m_state == AndroidRobotState::Spinning) {
        m_spinTimer += dt;
        if (m_spinTimer >= m_spinDuration) {
            setState(AndroidRobotState::Walking, 999.0f);
        }
    }

    if (m_state == AndroidRobotState::Walking && !m_dragging) {
        float dx = m_targetX - m_cx;
        if (fabs(dx) < 3.0f) {
            m_cx = m_targetX;
            setState(AndroidRobotState::Idle, 3.0f + (float)(rand() % 5));
        } else {
            float step = m_walkSpeed * dt;
            if (fabs(dx) < step) step = fabs(dx);
            if (dx > 0) { m_cx += step; m_facingRight = true; }
            else { m_cx -= step; m_facingRight = false; }
        }
    }

    // Pick next behavior when idle timer expires
    if (m_state != AndroidRobotState::Walking && m_state != AndroidRobotState::Spinning &&
        m_stateTimer >= m_nextBehaviorTime) {
        if (m_state == AndroidRobotState::Greeting || m_state == AndroidRobotState::Working) {
            setState(AndroidRobotState::Idle, 3.0f + (float)(rand() % 5));
        } else {
            pickNextBehavior();
        }
    }
}

void RobotGles::onTouch(float x, float y, int action) {
    // x, y are window-local coords; robot is at window center
    float winW = m_renderer ? m_renderer->getWidth() : 400;
    float winH = m_renderer ? m_renderer->getHeight() : 500;
    float rcx = winW / 2.0f;
    float rcy = winH / 2.0f;

    if (action == 0) { // DOWN
        m_touchDown = true;
        m_touchStartX = x;
        m_touchStartY = y;
        m_dragging = false;
    } else if (action == 2) { // MOVE
        if (m_touchDown) {
            float dx = x - m_touchStartX;
            float dy = y - m_touchStartY;
            float dist = sqrtf(dx * dx + dy * dy);
            if (dist > 20.0f) {
                m_dragging = true;
            }
        }
    } else if (action == 1) { // UP
        m_touchDown = false;
        if (m_dragging) {
            m_dragging = false;
            // After drag, resume walking from current position
            setState(AndroidRobotState::Idle, 2.0f);
            return;
        }

        float dx = x - m_touchStartX;
        float dy = y - m_touchStartY;
        float dist = sqrtf(dx * dx + dy * dy);

        if (dist < 30.0f) { // Tap (not drag)
            float headY = rcy - m_robotSize * 0.55f;
            float headRadius = m_robotSize * 0.35f;

            // Tap on head = speech bubble
            float dxHead = x - rcx;
            float dyHead = y - headY;
            if (sqrtf(dxHead * dxHead + dyHead * dyHead) < headRadius) {
                m_headTapped = true;
                return;
            }

            // Tap on body = spin then walk
            float dxBody = x - rcx;
            float dyBody = y - rcy;
            if (sqrtf(dxBody * dxBody + dyBody * dyBody) < m_robotSize * 0.5f) {
                m_bodyTapped = true;
                startSpinThenWalk(x < rcx);
            }
        }
    }
}

bool RobotGles::consumeHeadTap() {
    bool t = m_headTapped;
    m_headTapped = false;
    return t;
}

bool RobotGles::consumeBodyTap() {
    bool t = m_bodyTapped;
    m_bodyTapped = false;
    return t;
}

void RobotGles::render() {
    if (!m_renderer) return;

    // Render at window center — the window follows the robot via Java
    float cx = m_renderer->getWidth() / 2.0f;
    float cy = m_renderer->getHeight() / 2.0f + m_bob;
    float s = m_robotSize;

    // Ground shadow
    drawGroundShadow(cx, cy + s * 0.9f, s * 0.7f);

    // Hover glow
    drawHoverGlow(cx, cy + s * 0.8f);

    // Egg body (golden armor)
    drawEggBody(cx, cy, m_bob);

    // Arms or spinning arms
    if (m_state == AndroidRobotState::Spinning) {
        float spinAngle = m_spinTimer * 6.28f * 2.0f;
        drawSpinningArms(cx, cy, m_bob, spinAngle);
    } else {
        float waveAngle = sinf(m_animTime * 3.0f) * 0.3f;
        if (m_state == AndroidRobotState::Greeting) waveAngle = sinf(m_animTime * 6.0f) * 0.6f;
        drawArm(cx, cy, m_bob, true, waveAngle);
        drawArm(cx, cy, m_bob, false, -waveAngle);
    }

    // Head with Spartan helmet
    float headY = cy - s * 0.55f;
    drawHead(cx, headY, m_bob);

    // Spartan crest on top
    drawSpartanCrest(cx, headY - s * 0.15f);

    // Thinking dots if thinking
    if (m_thinking) {
        drawThinkingDots(cx, headY - s * 0.5f);
    }
}

void RobotGles::drawEggBody(float cx, float cy, float bob) {
    if (!m_renderer) return;
    float s = m_robotSize;
    // Main body — golden egg shape (Spartan armor gold)
    m_renderer->drawCircle(cx, cy, s * 0.5f, 0.85f, 0.7f, 0.2f, 1.0f, 48);
    // Highlight (lighter gold, upper-left)
    m_renderer->drawCircle(cx - s * 0.15f, cy - s * 0.15f, s * 0.25f, 1.0f, 0.9f, 0.4f, 0.4f, 32);
    // Shadow (darker gold, lower-right)
    m_renderer->drawCircle(cx + s * 0.15f, cy + s * 0.2f, s * 0.3f, 0.6f, 0.5f, 0.15f, 0.3f, 32);
}

void RobotGles::drawHead(float cx, float headY, float bob) {
    if (!m_renderer) return;
    float s = m_robotSize;

    // Head — golden helmet
    m_renderer->drawCircle(cx, headY, s * 0.35f, 0.85f, 0.7f, 0.2f, 1.0f, 48);

    // Face screen (black, rounded)
    m_renderer->drawRoundRect(cx - s * 0.22f, headY - s * 0.15f,
                              s * 0.44f, s * 0.3f, s * 0.08f,
                              0.05f, 0.05f, 0.08f, 1.0f);

    // Eyes — neon green (Spartan serious gaze)
    float eyeY = headY - s * 0.02f;
    float eyeSpacing = s * 0.1f;
    float eyeW = s * 0.06f;
    float eyeH = m_blinking ? s * 0.01f : s * 0.04f;

    drawEye(cx - eyeSpacing, eyeY, eyeW, eyeH, m_thinking);
    drawEye(cx + eyeSpacing, eyeY, eyeW, eyeH, m_thinking);
}

void RobotGles::drawEye(float ex, float ey, float w, float h, bool thinking) {
    if (!m_renderer) return;

    // Green neon eye (Spartan green gaze) — blue when thinking
    float r = 0.1f, g = 0.9f, b = 0.2f;
    if (thinking) { r = 0.2f; g = 0.6f; b = 1.0f; }

    // Glow
    m_renderer->drawCircle(ex, ey, w * 1.8f, r * 0.3f, g * 0.3f, b * 0.3f, 0.4f, 24);
    // Eye — flat horizontal slit (serious gaze)
    m_renderer->drawRect(ex - w, ey - h * 0.5f, w * 2.0f, h, r, g, b, 1.0f);
    // Spark (white center)
    if (!m_blinking) {
        m_renderer->drawCircle(ex - w * 0.2f, ey - h * 0.2f, w * 0.3f, 1.0f, 1.0f, 1.0f, 0.8f, 16);
    }
}

void RobotGles::drawHoverGlow(float cx, float baseY) {
    if (!m_renderer) return;
    float s = m_robotSize;
    for (int i = 0; i < 3; i++) {
        float alpha = 0.15f - i * 0.04f;
        m_renderer->drawCircle(cx, baseY, s * (0.4f + i * 0.15f), 0.0f, 0.5f, 1.0f, alpha, 32);
    }
}

void RobotGles::drawArm(float cx, float cy, float bob, bool left, float waveAngle) {
    if (!m_renderer) return;
    float s = m_robotSize;
    float armX = left ? cx - s * 0.45f : cx + s * 0.45f;
    float armY = cy + sinf(m_animTime * 2.0f + (left ? 0 : 3.14f)) * 4.0f + waveAngle * 10.0f;
    m_renderer->drawCircle(armX, armY, s * 0.12f, 0.85f, 0.7f, 0.2f, 1.0f, 24);
}

void RobotGles::drawGroundShadow(float cx, float baseY, float w) {
    if (!m_renderer) return;
    m_renderer->drawCircle(cx, baseY, w * 0.5f, 0.0f, 0.0f, 0.0f, 0.2f, 32);
}

void RobotGles::drawSpartanCrest(float cx, float headY) {
    if (!m_renderer) return;
    float s = m_robotSize;
    m_renderer->drawRect(cx - s * 0.03f, headY - s * 0.2f, s * 0.06f, s * 0.2f,
                         0.85f, 0.7f, 0.2f, 1.0f);
    for (int i = 0; i < 5; i++) {
        float fx = cx - s * 0.08f + i * s * 0.04f;
        float fy = headY - s * 0.25f - sinf(m_animTime * 2.0f + i * 0.5f) * 2.0f;
        m_renderer->drawCircle(fx, fy, s * 0.04f, 0.8f, 0.15f, 0.15f, 0.9f, 12);
    }
}

void RobotGles::drawThinkingDots(float cx, float cy) {
    if (!m_renderer) return;
    float s = m_robotSize;
    int phase = (int)(m_animTime * 3.0f) % 3;
    for (int i = 0; i < 3; i++) {
        float alpha = (i == phase) ? 1.0f : 0.3f;
        float dx = cx - s * 0.1f + i * s * 0.1f;
        m_renderer->drawCircle(dx, cy, s * 0.04f, 0.2f, 0.6f, 1.0f, alpha, 16);
    }
}

void RobotGles::drawSpinningArms(float cx, float cy, float bob, float spinAngle) {
    if (!m_renderer) return;
    float s = m_robotSize;
    float orbitR = s * 0.42f;
    for (int i = 0; i < 2; i++) {
        float angle = spinAngle + i * 3.14159f;
        float ax = cx + cosf(angle) * orbitR;
        float ay = cy + sinf(angle) * orbitR * 0.6f;
        m_renderer->drawCircle(ax, ay, s * 0.1f, 0.85f, 0.7f, 0.2f, 1.0f, 24);
    }
}
