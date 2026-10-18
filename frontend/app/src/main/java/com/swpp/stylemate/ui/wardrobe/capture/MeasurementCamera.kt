// AI-generated with Codex, 2026-09-29, reviewed by Hyeon U Jeong
package com.swpp.stylemate.ui.wardrobe.capture

import com.swpp.stylemate.data.wardrobe.*
import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.google.ar.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

data class MeasurementSnapshot(val bitmap: Bitmap, val geometry: MeasurementGeometry,
    val timestamp: Long, val tiltDegrees: Float) {
    // The garment is assumed to lie on the same flat floor, including beyond the observed polygon.
    fun point(u: Float, v: Float): MeasurePoint? = geometry.point(u, v)
}

data class LandmarkPreview(val bitmap: Bitmap, val pose: Pose)

/** ARCore owns the camera. The framebuffer and metric plane are captured from the same frame. */
class MeasurementCamera(
    private val rotation: () -> Int,
    private val onStatus: (String, Boolean) -> Unit,
    private val onCapture: (MeasurementSnapshot) -> Unit,
    private val onPreview: (LandmarkPreview) -> Unit = { it.bitmap.recycle() },
) : GLSurfaceView.Renderer {
    @Volatile var session: Session? = null
    @Volatile private var latestPose: Pose? = null
    val captureRequested = AtomicBoolean(false)
    val previewRequested = AtomicBoolean(false)
    private val maximumCaptureTiltDegrees = 75f
    private val maximumPreviewTranslationMeters = .02
    private val minimumPreviewAxisCosine = .9986 // About three degrees, including roll.
    private var width = 1
    private var height = 1
    private var texture = 0
    private var program = 0
    @Volatile private var lastStatus = ""
    fun resetStatus() { lastStatus = ""; latestPose = null }
    fun matchesPreview(pose: Pose): Boolean {
        val current = latestPose ?: return false
        val translation = current.translation.zip(pose.translation).sumOf { (a, b) -> ((a - b) * (a - b)).toDouble() }
        fun dotAxis(axis: Int) = current.getTransformedAxis(axis, 1f).zip(pose.getTransformedAxis(axis, 1f)).sumOf { (a, b) -> (a * b).toDouble() }
        return translation < maximumPreviewTranslationMeters * maximumPreviewTranslationMeters &&
            dotAxis(2) > minimumPreviewAxisCosine && dotAxis(0) > minimumPreviewAxisCosine
    }
    private val vertices = buffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val uv = buffer(FloatArray(8))

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val vertex = shader(GLES20.GL_VERTEX_SHADER,
            "attribute vec2 position; attribute vec2 texCoord; varying vec2 uv; void main(){gl_Position=vec4(position,0.,1.);uv=texCoord;}")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES camera; varying vec2 uv; void main(){gl_FragColor=texture2D(camera,uv);}")
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { "Camera shader link failed" }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val active = session ?: return
        try {
            active.setDisplayGeometry(rotation(), width, height)
            active.setCameraTextureName(texture)
            val frame = active.update()
            if (frame.timestamp == 0L) return
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                vertices, Coordinates2d.TEXTURE_NORMALIZED, uv)
            drawBackground()
            latestPose = frame.camera.pose.takeIf { frame.camera.trackingState == TrackingState.TRACKING }
            val hit = if (frame.camera.trackingState == TrackingState.TRACKING) {
                frame.hitTest(width / 2f, height / 2f).firstOrNull {
                    val plane = it.trackable as? Plane
                    plane != null && plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                        plane.trackingState == TrackingState.TRACKING && plane.isPoseInPolygon(it.hitPose)
                }
            } else null
            val plane = hit?.trackable as? Plane
            val center = plane?.centerPose
            val normal = center?.getTransformedAxis(1, 1f)
            val pose = frame.camera.pose
            val forward = pose.getTransformedAxis(2, -1f)
            val tiltDegrees = if (normal != null) captureTiltDegrees(
                MeasurePoint(forward[0], forward[1], forward[2]), MeasurePoint(normal[0], normal[1], normal[2])) else null
            val canCapture = tiltDegrees != null && tiltDegrees < maximumCaptureTiltDegrees
            status(when {
                tiltDegrees == null -> "주변 바닥을 천천히 비추세요."
                !canCapture -> "옷을 위에서 비추세요."
                else -> ""
            }, canCapture)
            if (captureRequested.getAndSet(false)) {
                if (!canCapture || center == null || normal == null) {
                    status("바닥 추적이 끊겼습니다. 다시 인식한 뒤 촬영해 주세요.", false)
                    return
                }
                val view = FloatArray(16)
                val projection = FloatArray(16)
                val vp = FloatArray(16)
                val inverse = FloatArray(16)
                frame.camera.getViewMatrix(view, 0)
                frame.camera.getProjectionMatrix(projection, 0, 0.01f, 100f)
                Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
                check(Matrix.invertM(inverse, 0, vp, 0))
                val geometry = MeasurementGeometry(inverse, MeasurePoint(center.tx(), center.ty(), center.tz()),
                    MeasurePoint(normal[0], normal[1], normal[2]))
                check(geometry.point(0.5f, 0.5f) != null) { "옷을 더 위에서 내려다보며 촬영해 주세요." }
                onCapture(MeasurementSnapshot(readBitmap(), geometry, frame.timestamp, tiltDegrees))
            } else if (previewRequested.getAndSet(false)) {
                onPreview(LandmarkPreview(readBitmap(), frame.camera.pose))
            }
        } catch (_: com.google.ar.core.exceptions.CameraNotAvailableException) {
            status("카메라를 사용할 수 없습니다. 화면을 나갔다가 다시 열어 주세요.", false)
        } catch (error: Exception) {
            status(error.message ?: "AR 측정에 실패했습니다. 다시 촬영해 주세요.", false)
        }
    }

    private fun status(message: String, ready: Boolean) {
        val state = "$ready:$message"
        if (state != lastStatus) { lastStatus = state; onStatus(message, ready) }
    }

    private fun drawBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "camera"), 0)
        val position = GLES20.glGetAttribLocation(program, "position")
        val texCoord = GLES20.glGetAttribLocation(program, "texCoord")
        vertices.position(0); uv.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texCoord)
    }

    private fun readBitmap(): Bitmap {
        val rgba = ByteBuffer.allocateDirect(width * height * 4)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "사진을 읽지 못했습니다. 다시 촬영해 주세요." }
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val offset = (y * width + x) * 4
            pixels[(height - 1 - y) * width + x] = android.graphics.Color.rgb(
                rgba[offset].toInt() and 255, rgba[offset + 1].toInt() and 255, rgba[offset + 2].toInt() and 255)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun shader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] != 0) { "Camera shader compilation failed" }
        return id
    }

    private fun buffer(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
}
