package app.simplestcut.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.opengl.GLES20
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.TextOverlay
import android.text.SpannableString
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import app.simplestcut.effects.CensorFx
import app.simplestcut.effects.CensorMode
import app.simplestcut.effects.SectionTiming
import app.simplestcut.effects.TextFx
import app.simplestcut.effects.TextRender
import app.simplestcut.effects.fadeAlpha
import kotlin.math.min

private const val TAG = "OverlayFx"

/** So viele Zensur-Bereiche zeichnet ein Durchgang; mehr werden auf mehrere Effekte verteilt. */
const val CENSORS_PER_PASS = 8

/**
 * Zeit eines Bildes relativ zum ersten Bild des Abschnitts. Media3 verschiebt die Zeitstempel je nach Lage in der
 * Folge; darum zählt der erste gesehene Zeitstempel als 0 (jeder Abschnitt hat eigene Effekt-Objekte).
 */
private class RelativeClock {
    private var first = Long.MIN_VALUE
    fun rel(pts: Long): Long {
        if (first == Long.MIN_VALUE) first = pts
        return pts - first
    }
}

/**
 * Zensur: schwarz, verpixelt oder weichgezeichnet, je Bereich Rechteck oder Ellipse. Die Bereiche liegen in
 * Bruchteilen des Bildes, das an dieser Stelle der Effektkette ankommt (= Ergebnisbild).
 */
@OptIn(UnstableApi::class)
class CensorEffect(private val censors: List<CensorFx>, private val timing: SectionTiming?) : GlEffect {
    init {
        require(censors.size <= CENSORS_PER_PASS)
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = CensorProgram(censors, timing, useHdr)
}

@OptIn(UnstableApi::class)
private class CensorProgram(
    private val censors: List<CensorFx>,
    private val timing: SectionTiming?,
    private val useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, 1) {
    private val program = GlProgram(VERTEX, FRAGMENT)
    private val hasBlur = censors.any { it.mode == CensorMode.BLUR }

    /** Zwischenbild fürs Weichzeichnen (erst waagrecht hinein, dann senkrecht ins Ergebnis). */
    private var tempTex = -1
    private var tempFbo = -1
    private val clock = RelativeClock()
    private var width = 0
    private var height = 0
    private var logged = false

    init {
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val t = clock.rel(presentationTimeUs)
        if (!logged) {
            logged = true
            Log.i(TAG, "Zensur: erstes Bild pts=$presentationTimeUs, ${censors.size} Bereiche, ${width}x$height")
        }
        val n = censors.size
        val rects = FloatArray(4 * CENSORS_PER_PASS)
        val params = FloatArray(4 * CENSORS_PER_PASS)
        val short = min(width, height).toFloat()
        for ((i, c) in censors.withIndex()) {
            val r = c.rectAt(t) { timing?.keyTimeUs?.invoke(it) ?: 0L }
            // Pixel, y nach oben (GL)
            rects[4 * i] = r.left * width
            rects[4 * i + 1] = (1 - r.bottom) * height
            rects[4 * i + 2] = r.right * width
            rects[4 * i + 3] = (1 - r.top) * height
            params[4 * i] = c.mode.ordinal.toFloat()
            params[4 * i + 1] = if (c.oval) 1f else 0f
            params[4 * i + 2] = when (c.mode) {
                CensorMode.PIXELATE -> (c.cellSize * short).coerceAtLeast(2f)
                CensorMode.BLUR -> (c.blurSigma * short).coerceAtLeast(1f)
                CensorMode.BLACK -> 0f
            }
        }
        try {
            if (hasBlur) {
                // Erst waagrecht weichzeichnen ins Zwischenbild, dann zurück auf das Ziel, das Media3 gesetzt hat.
                val fbo = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, it, 0) }[0]
                val viewport = IntArray(4).also { GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, it, 0) }
                if (tempTex < 0) {
                    tempTex = GlUtil.createTexture(width, height, useHdr)
                    tempFbo = GlUtil.createFboForTexture(tempTex)
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, tempFbo)
                GLES20.glViewport(0, 0, width, height)
                draw(inputTexId, inputTexId, 1, n, rects, params)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
                GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
                draw(inputTexId, tempTex, 0, n, rects, params)
            } else {
                draw(inputTexId, inputTexId, 0, n, rects, params)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    /** [pass] 1 = waagrecht ins Zwischenbild, 0 = Ergebnis ([blurTex] = waagrecht weichgezeichnetes Bild). */
    private fun draw(inputTexId: Int, blurTex: Int, pass: Int, n: Int, rects: FloatArray, params: FloatArray) {
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        program.setSamplerTexIdUniform("uBlurSampler", blurTex, 1)
        program.bindAttributesAndUniforms()
        // Felder direkt setzen (GlProgram kennt nur einzelne Werte).
        GLES20.glUniform2f(program.getUniformLocation("uSize"), width.toFloat(), height.toFloat())
        GLES20.glUniform1i(program.getUniformLocation("uCount"), n)
        GLES20.glUniform1i(program.getUniformLocation("uPass"), pass)
        GLES20.glUniform4fv(program.getUniformLocation("uRects"), CENSORS_PER_PASS, rects, 0)
        GLES20.glUniform4fv(program.getUniformLocation("uParams"), CENSORS_PER_PASS, params, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    override fun release() {
        super.release()
        try {
            if (tempFbo >= 0) GlUtil.deleteFbo(tempFbo)
            if (tempTex >= 0) GlUtil.deleteTexture(tempTex)
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}

private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

/*
 * Je Bereich: uRects = (x0, y0, x1, y1) in Pixeln (y nach oben), uParams = (Art, oval, Grösse, -).
 * Art 0 = schwarz, 1 = verpixeln (Zellen ab der Ecke des Bereichs, Mittel aus 4×4 Proben), 2 = weichzeichnen
 * (Gauss in zwei Durchgängen: uPass 1 waagrecht ins Zwischenbild, im Streifen über/unter dem Bereich, den der
 * senkrechte Durchgang braucht; uPass 0 senkrecht aus dem Zwischenbild, nur im Bereich). Proben alle Sigma/6 über
 * ±3 Sigma, die lineare Filterung glättet dazwischen.
 */
private const val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform sampler2D uBlurSampler;
uniform vec2 uSize;
uniform int uCount;
uniform int uPass;
uniform vec4 uRects[8];
uniform vec4 uParams[8];
varying vec2 vTexSamplingCoord;

vec4 tex(vec2 p) {
  return texture2D(uTexSampler, clamp(p, vec2(0.5), uSize - vec2(0.5)) / uSize);
}

vec4 blurTex(vec2 p) {
  return texture2D(uBlurSampler, clamp(p, vec2(0.5), uSize - vec2(0.5)) / uSize);
}

vec4 gauss(vec2 p, vec2 dir, float sigma, bool second) {
  vec4 sum = vec4(0.0);
  float total = 0.0;
  for (int k = -18; k <= 18; k++) {
    float o = float(k) * sigma / 6.0;
    float w = exp(-o * o / (2.0 * sigma * sigma));
    sum += (second ? blurTex(p + dir * o) : tex(p + dir * o)) * w;
    total += w;
  }
  return sum / total;
}

void main() {
  vec2 p = vTexSamplingCoord * uSize;
  vec4 color = texture2D(uTexSampler, vTexSamplingCoord);
  for (int i = 0; i < 8; i++) {
    if (i >= uCount) break;
    vec4 r = uRects[i];
    vec4 q = uParams[i];
    if (uPass == 1) {
      // Waagrecht: nur weichgezeichnete Bereiche, samt Streifen von 3 Sigma darüber und darunter
      if (q.x > 1.5 && p.x >= r.x && p.x <= r.z && p.y >= r.y - 3.0 * q.z && p.y <= r.w + 3.0 * q.z) {
        color = gauss(p, vec2(1.0, 0.0), q.z, false);
      }
      continue;
    }
    if (p.x < r.x || p.x > r.z || p.y < r.y || p.y > r.w) continue;
    if (q.y > 0.5) {
      vec2 c = (r.xy + r.zw) * 0.5;
      vec2 h = max((r.zw - r.xy) * 0.5, vec2(1.0));
      vec2 d = (p - c) / h;
      if (dot(d, d) > 1.0) continue;
    }
    if (q.x < 0.5) {
      color = vec4(0.0, 0.0, 0.0, 1.0);
    } else if (q.x < 1.5) {
      float s = q.z;
      vec2 o = r.xy + floor((p - r.xy) / s) * s;
      vec4 sum = vec4(0.0);
      for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
          sum += tex(o + (vec2(float(x), float(y)) + 0.5) * s * 0.25);
        }
      }
      color = sum / 16.0;
    } else {
      color = gauss(p, vec2(0.0, 1.0), q.z, true);
    }
  }
  gl_FragColor = color;
}
"""

/**
 * Text als ein Bild über dem ganzen Ergebnisbild: Texte des ganzen Videos ([wholeTexts]) und des Abschnitts
 * ([sectionTexts]). Neu gezeichnet wird nur, wenn sich die Deckkraft (Ein-/Ausblenden) ändert.
 */
@OptIn(UnstableApi::class)
class TextBitmapOverlay(
    private val wholeTexts: List<TextFx>,
    private val sectionTexts: List<TextFx>,
    private val timing: SectionTiming?,
) : TextOverlay() {
    // TextOverlay statt BitmapOverlay: nur so nimmt Media3 das Bild bei HDR an (als normales Bild, das es
    // aufhellt); ein BitmapOverlay müsste dort ein Ultra-HDR-Bild mit Gainmap sein.
    override fun getText(presentationTimeUs: Long) = SpannableString("")

    private var size: Size? = null
    private var bitmap: Bitmap? = null
    private var lastAlphas: List<Float>? = null
    private val clock = RelativeClock()

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        size = videoSize
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val s = size ?: throw VideoFrameProcessingException("Overlay not configured")
        val t = clock.rel(presentationTimeUs)
        val alphas = wholeTexts.map { if (it.fade && timing != null) fadeAlpha(t, timing.videoStartUs, timing.videoEndUs) else 1f } +
            sectionTexts.map { if (it.fade && timing != null) fadeAlpha(t, timing.runStartUs, timing.runEndUs) else 1f }
        // Auf Hundertstel gerundet: nicht bei jedem Bild neu zeichnen, wenn sich kaum etwas ändert.
        val rounded = alphas.map { Math.round(it * 100) / 100f }
        val b = bitmap?.takeIf { it.width == s.width && it.height == s.height }
            ?: Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888).also { bitmap = it; lastAlphas = null }
        if (rounded != lastAlphas) {
            lastAlphas = rounded
            b.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(b)
            (wholeTexts + sectionTexts).forEachIndexed { i, text ->
                TextRender.draw(canvas, text, s.width.toFloat(), s.height.toFloat(), rounded[i])
            }
        }
        return b
    }

    override fun release() {
        super.release()
        bitmap?.recycle()
        bitmap = null
    }
}
