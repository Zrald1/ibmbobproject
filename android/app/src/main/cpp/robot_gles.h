#pragma once
#include "egl_renderer.h"
#include <string>

enum class AndroidRobotState {
    Idle,
    Walking,
    Greeting,
    Working,
    Sleeping,
    Spinning
};

class RobotGles {
public:
    RobotGles();
    ~RobotGles();

    void init(EglRenderer* renderer, float screenWidth, float screenHeight);
    void update(float dt);
    void render();

    void setThinking(bool thinking) { m_thinking = thinking; }
    void setTalking(bool talking) { m_talking = talking; }
    void onTouch(float x, float y, int action);
    bool consumeHeadTap();
    bool consumeBodyTap();
    bool isDragging() const { return m_dragging; }
    void setScreenPosition(float x, float y);
    void setScreenDims(float w, float h);

    float getScreenX() const { return m_cx; }
    float getScreenY() const { return m_cy; }
    float getRobotSize() const { return m_robotSize; }

private:
    EglRenderer* m_renderer = nullptr;
    float m_animTime = 0.0f;
    float m_bob = 0.0f;
    bool m_thinking = false;
    bool m_talking = false;
    AndroidRobotState m_state = AndroidRobotState::Idle;
    float m_stateTimer = 0.0f;
    float m_nextBehaviorTime = 3.0f;
    float m_eyeBlinkTimer = 3.0f;
    bool m_blinking = false;
    float m_blinkDuration = 0.0f;

    // Robot position — walks across screen
    float m_cx = 0.0f;
    float m_cy = 0.0f;
    float m_robotSize = 100.0f;
    float m_targetX = 0.0f;
    float m_walkSpeed = 150.0f;
    bool m_facingRight = true;

    // Spin state
    float m_spinTimer = 0.0f;
    float m_spinDuration = 1.0f;
    bool m_spinWalkLeft = false;

    // Touch state
    float m_touchStartX = 0;
    float m_touchStartY = 0;
    bool m_touchDown = false;
    bool m_headTapped = false;
    bool m_bodyTapped = false;
    bool m_dragging = false;

    // Screen dimensions for walking
    float m_screenWidth = 1080.0f;
    float m_screenHeight = 1920.0f;

    void setState(AndroidRobotState state, float duration);
    void pickNextBehavior();
    void walkToEdge(bool left);
    void startSpinThenWalk(bool left);

    void drawEggBody(float cx, float cy, float bob);
    void drawHead(float cx, float headY, float bob);
    void drawEye(float ex, float ey, float w, float h, bool thinking);
    void drawHoverGlow(float cx, float baseY);
    void drawArm(float cx, float cy, float bob, bool left, float waveAngle);
    void drawGroundShadow(float cx, float baseY, float w);
    void drawSpartanCrest(float cx, float headY);
    void drawThinkingDots(float cx, float cy);
    void drawSpinningArms(float cx, float cy, float bob, float spinAngle);
    float computeWalkLean();
};
