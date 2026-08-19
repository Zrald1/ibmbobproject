#pragma once
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>
#include <string>

class EglRenderer {
public:
    EglRenderer();
    ~EglRenderer();

    bool init(ANativeWindow* window);
    void resize(int width, int height);
    void render();
    void destroy();

    int getWidth() const { return m_width; }
    int getHeight() const { return m_height; }
    bool isValid() const { return m_eglSurface != EGL_NO_SURFACE; }

    // Drawing primitives (used by RobotGles)
    void drawTriangle(float x1, float y1, float x2, float y2, float x3, float y3,
                      float r, float g, float b, float a = 1.0f);
    void drawCircle(float cx, float cy, float radius, float r, float g, float b, float a = 1.0f, int segments = 32);
    void drawRect(float x, float y, float w, float h, float r, float g, float b, float a = 1.0f);
    void drawRoundRect(float x, float y, float w, float h, float radius, float r, float g, float b, float a = 1.0f);

private:
    EGLDisplay m_eglDisplay = EGL_NO_DISPLAY;
    EGLContext m_eglContext = EGL_NO_CONTEXT;
    EGLSurface m_eglSurface = EGL_NO_SURFACE;
    EGLConfig  m_eglConfig = nullptr;
    int m_width = 0;
    int m_height = 0;

    GLuint m_shaderProgram = 0;
    GLuint m_vbo = 0;

    bool initShaders();
};
