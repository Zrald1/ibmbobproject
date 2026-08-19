#include "egl_renderer.h"
#include <android/log.h>
#include <cmath>
#include <vector>

#define TAG "Argos"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static const char* kVertShader = R"(
    #version 300 es
    precision highp float;
    uniform vec2 uResolution;
    in vec2 aPos;
    in vec4 aColor;
    out vec4 vColor;
    void main() {
        float x = (aPos.x / uResolution.x) * 2.0 - 1.0;
        float y = 1.0 - (aPos.y / uResolution.y) * 2.0;
        gl_Position = vec4(x, y, 0.0, 1.0);
        vColor = aColor;
    }
)";

static const char* kFragShader = R"(
    #version 300 es
    precision highp float;
    in vec4 vColor;
    out vec4 fragColor;
    void main() {
        fragColor = vColor;
    }
)";

static GLuint compileShader(GLenum type, const char* src) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &src, nullptr);
    glCompileShader(shader);
    GLint status;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (!status) {
        char log[512];
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("Shader compile error: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

EglRenderer::EglRenderer() {}

EglRenderer::~EglRenderer() {
    destroy();
}

bool EglRenderer::init(ANativeWindow* window) {
    m_eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (m_eglDisplay == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return false;
    }

    EGLint major, minor;
    if (!eglInitialize(m_eglDisplay, &major, &minor)) {
        LOGE("eglInitialize failed");
        return false;
    }
    LOGI("EGL initialized: version %d.%d", major, minor);

    const EGLint configAttribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_NONE
    };
    EGLint numConfigs;
    if (!eglChooseConfig(m_eglDisplay, configAttribs, &m_eglConfig, 1, &numConfigs) || numConfigs < 1) {
        LOGE("eglChooseConfig failed");
        return false;
    }

    eglGetConfigAttrib(m_eglDisplay, m_eglConfig, EGL_NATIVE_VISUAL_ID, (EGLint*)&numConfigs);
    ANativeWindow_setBuffersGeometry(window, 0, 0, numConfigs);

    m_eglSurface = eglCreateWindowSurface(m_eglDisplay, m_eglConfig, window, nullptr);
    if (m_eglSurface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed");
        return false;
    }

    const EGLint ctxAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    m_eglContext = eglCreateContext(m_eglDisplay, m_eglConfig, EGL_NO_CONTEXT, ctxAttribs);
    if (m_eglContext == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed");
        return false;
    }

    if (!eglMakeCurrent(m_eglDisplay, m_eglSurface, m_eglSurface, m_eglContext)) {
        LOGE("eglMakeCurrent failed");
        return false;
    }

    eglQuerySurface(m_eglDisplay, m_eglSurface, EGL_WIDTH, &m_width);
    eglQuerySurface(m_eglDisplay, m_eglSurface, EGL_HEIGHT, &m_height);

    if (!initShaders()) return false;

    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    LOGI("EGL renderer initialized: %dx%d", m_width, m_height);
    return true;
}

bool EglRenderer::initShaders() {
    GLuint vert = compileShader(GL_VERTEX_SHADER, kVertShader);
    GLuint frag = compileShader(GL_FRAGMENT_SHADER, kFragShader);
    if (!vert || !frag) return false;

    m_shaderProgram = glCreateProgram();
    glAttachShader(m_shaderProgram, vert);
    glAttachShader(m_shaderProgram, frag);
    glLinkProgram(m_shaderProgram);
    glDeleteShader(vert);
    glDeleteShader(frag);

    GLint status;
    glGetProgramiv(m_shaderProgram, GL_LINK_STATUS, &status);
    if (!status) {
        char log[512];
        glGetProgramInfoLog(m_shaderProgram, sizeof(log), nullptr, log);
        LOGE("Program link error: %s", log);
        return false;
    }

    glGenBuffers(1, &m_vbo);
    return true;
}

void EglRenderer::resize(int width, int height) {
    m_width = width;
    m_height = height;
    glViewport(0, 0, width, height);
}

void EglRenderer::destroy() {
    if (m_vbo) { glDeleteBuffers(1, &m_vbo); m_vbo = 0; }
    if (m_shaderProgram) { glDeleteProgram(m_shaderProgram); m_shaderProgram = 0; }
    if (m_eglDisplay != EGL_NO_DISPLAY) {
        eglMakeCurrent(m_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (m_eglContext != EGL_NO_CONTEXT) eglDestroyContext(m_eglDisplay, m_eglContext);
        if (m_eglSurface != EGL_NO_SURFACE) eglDestroySurface(m_eglDisplay, m_eglSurface);
        eglTerminate(m_eglDisplay);
    }
    m_eglDisplay = EGL_NO_DISPLAY;
    m_eglContext = EGL_NO_CONTEXT;
    m_eglSurface = EGL_NO_SURFACE;
}

// Draw a filled triangle
void EglRenderer::drawTriangle(float x1, float y1, float x2, float y2, float x3, float y3,
                                float r, float g, float b, float a) {
    float vertices[] = {
        x1, y1, r, g, b, a,
        x2, y2, r, g, b, a,
        x3, y3, r, g, b, a,
    };
    glUseProgram(m_shaderProgram);
    glUniform2f(glGetUniformLocation(m_shaderProgram, "uResolution"), (float)m_width, (float)m_height);
    glBindBuffer(GL_ARRAY_BUFFER, m_vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_DYNAMIC_DRAW);
    GLint posLoc = glGetAttribLocation(m_shaderProgram, "aPos");
    GLint colorLoc = glGetAttribLocation(m_shaderProgram, "aColor");
    glEnableVertexAttribArray(posLoc);
    glVertexAttribPointer(posLoc, 2, GL_FLOAT, GL_FALSE, 6 * sizeof(float), 0);
    glEnableVertexAttribArray(colorLoc);
    glVertexAttribPointer(colorLoc, 4, GL_FLOAT, GL_FALSE, 6 * sizeof(float), (void*)(2 * sizeof(float)));
    glDrawArrays(GL_TRIANGLES, 0, 3);
}

// Draw a filled circle using triangle fan
void EglRenderer::drawCircle(float cx, float cy, float radius, float r, float g, float b, float a, int segments) {
    std::vector<float> vertices;
    vertices.push_back(cx); vertices.push_back(cy); vertices.push_back(r); vertices.push_back(g); vertices.push_back(b); vertices.push_back(a);
    for (int i = 0; i <= segments; i++) {
        float angle = (float)i / segments * 2.0f * 3.14159265f;
        vertices.push_back(cx + cosf(angle) * radius);
        vertices.push_back(cy + sinf(angle) * radius);
        vertices.push_back(r); vertices.push_back(g); vertices.push_back(b); vertices.push_back(a);
    }
    glUseProgram(m_shaderProgram);
    glUniform2f(glGetUniformLocation(m_shaderProgram, "uResolution"), (float)m_width, (float)m_height);
    glBindBuffer(GL_ARRAY_BUFFER, m_vbo);
    glBufferData(GL_ARRAY_BUFFER, vertices.size() * sizeof(float), vertices.data(), GL_DYNAMIC_DRAW);
    GLint posLoc = glGetAttribLocation(m_shaderProgram, "aPos");
    GLint colorLoc = glGetAttribLocation(m_shaderProgram, "aColor");
    glEnableVertexAttribArray(posLoc);
    glVertexAttribPointer(posLoc, 2, GL_FLOAT, GL_FALSE, 6 * sizeof(float), 0);
    glEnableVertexAttribArray(colorLoc);
    glVertexAttribPointer(colorLoc, 4, GL_FLOAT, GL_FALSE, 6 * sizeof(float), (void*)(2 * sizeof(float)));
    glDrawArrays(GL_TRIANGLE_FAN, 0, segments + 2);
}

// Draw a filled rectangle (2 triangles)
void EglRenderer::drawRect(float x, float y, float w, float h, float r, float g, float b, float a) {
    float vertices[] = {
        x,     y,     r, g, b, a,
        x + w, y,     r, g, b, a,
        x,     y + h, r, g, b, a,
        x + w, y,     r, g, b, a,
        x + w, y + h, r, g, b, a,
        x,     y + h, r, g, b, a,
    };
    glUseProgram(m_shaderProgram);
    glUniform2f(glGetUniformLocation(m_shaderProgram, "uResolution"), (float)m_width, (float)m_height);
    glBindBuffer(GL_ARRAY_BUFFER, m_vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_DYNAMIC_DRAW);
    GLint posLoc = glGetAttribLocation(m_shaderProgram, "aPos");
    GLint colorLoc = glGetAttribLocation(m_shaderProgram, "aColor");
    glEnableVertexAttribArray(posLoc);
    glVertexAttribPointer(posLoc, 2, GL_FLOAT, GL_FALSE, 6 * sizeof(float), 0);
    glEnableVertexAttribArray(colorLoc);
    glVertexAttribPointer(colorLoc, 4, GL_FLOAT, GL_FALSE, 6 * sizeof(float), (void*)(2 * sizeof(float)));
    glDrawArrays(GL_TRIANGLES, 0, 6);
}

// Draw a rounded rectangle (approximate with rect + corner circles)
void EglRenderer::drawRoundRect(float x, float y, float w, float h, float radius,
                                 float r, float g, float b, float a) {
    // Main body (without corners)
    drawRect(x + radius, y, w - 2 * radius, h, r, g, b, a);
    drawRect(x, y + radius, w, h - 2 * radius, r, g, b, a);
    // Four corner circles
    drawCircle(x + radius, y + radius, radius, r, g, b, a, 16);
    drawCircle(x + w - radius, y + radius, radius, r, g, b, a, 16);
    drawCircle(x + radius, y + h - radius, radius, r, g, b, a, 16);
    drawCircle(x + w - radius, y + h - radius, radius, r, g, b, a, 16);
}

void EglRenderer::render() {
    if (m_eglSurface == EGL_NO_SURFACE) return;
    // Only swap — clearing and drawing are handled by the render loop
    // and RobotGles before this call
    eglSwapBuffers(m_eglDisplay, m_eglSurface);
}
