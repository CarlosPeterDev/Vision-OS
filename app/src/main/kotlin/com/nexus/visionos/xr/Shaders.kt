package com.nexus.visionos.xr

object Shaders {
    private const val ES = "#version 300 es\n"
    private const val OES = "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\n"
    private const val PRECISION = "precision mediump float;\n"
    private const val ROUNDED = """
        float rounded(vec2 uv, vec2 size, float radius) {
            vec2 q = abs((uv - 0.5) * size) - size * 0.5 + radius;
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
        }
    """

    const val SCREEN_VERTEX = ES + """
        in vec2 aPosition; out vec2 vUv;
        void main() { vUv = aPosition * 0.5 + 0.5; gl_Position = vec4(aPosition, 0.0, 1.0); }
    """
    const val WORLD_VERTEX = ES + """
        uniform mat4 uViewProjection; in vec3 aPosition; in vec2 aUv; out vec2 vUv;
        void main() { vUv = aUv; gl_Position = uViewProjection * vec4(aPosition, 1.0); }
    """
    const val FLAT_VERTEX = ES + """
        in vec2 aPosition;
        void main() { gl_Position = vec4(aPosition, 0.0, 1.0); }
    """
    const val CAMERA = OES + PRECISION + """
        uniform samplerExternalOES uTexture; uniform mat4 uTransform; uniform vec2 uCrop; uniform int uTurns;
        in vec2 vUv; out vec4 color;
        void main() {
            vec2 s = (vUv - 0.5) * uCrop + 0.5;
            // The crop is in screen space; turn it back into the camera's natural orientation.
            vec2 n = uTurns == 1 ? vec2(s.y, 1.0 - s.x) : uTurns == 2 ? 1.0 - s
                   : uTurns == 3 ? vec2(1.0 - s.y, s.x) : s;
            color = vec4(texture(uTexture, (uTransform * vec4(n, 0.0, 1.0)).xy).rgb, 1.0);
        }
    """
    const val SKY = ES + PRECISION + """
        uniform vec2 uTan; uniform vec4 uHead; in vec2 vUv; out vec4 color;
        void main() {
            vec3 d = normalize(vec3((vUv * 2.0 - 1.0) * uTan, -1.0));
            d = d + 2.0 * cross(uHead.xyz, cross(uHead.xyz, d) + uHead.w * d);
            vec3 sky = mix(vec3(0.16, 0.20, 0.32), vec3(0.015, 0.02, 0.06), clamp(d.y * 1.6, 0.0, 1.0));
            sky += vec3(0.22, 0.12, 0.10) * pow(1.0 - abs(d.y), 10.0);
            if (d.y < -0.02) {
                vec2 p = d.xz / -d.y * 1.4;
                vec2 g = abs(fract(p) - 0.5);
                float line = smoothstep(0.46, 0.5, max(g.x, g.y)) * exp(-length(p) * 0.18);
                sky = mix(vec3(0.03, 0.04, 0.07), sky, exp(d.y * 6.0)) + vec3(0.14, 0.25, 0.38) * line;
            }
            color = vec4(sky, 1.0);
        }
    """
    const val BLUR = ES + PRECISION + """
        uniform sampler2D uTexture; uniform vec2 uStep; in vec2 vUv; out vec4 color;
        void main() {
            vec3 sum = texture(uTexture, vUv).rgb * 0.227;
            sum += (texture(uTexture, vUv + uStep * 1.385).rgb + texture(uTexture, vUv - uStep * 1.385).rgb) * 0.316;
            sum += (texture(uTexture, vUv + uStep * 3.231).rgb + texture(uTexture, vUv - uStep * 3.231).rgb) * 0.070;
            color = vec4(sum, 1.0);
        }
    """
    /** Frosted sample with edge refraction, slight dispersion, rim light and a soft reflection. */
    const val GLASS = ES + PRECISION + ROUNDED + """
        uniform sampler2D uBlur; uniform vec2 uViewport; uniform vec2 uSize; uniform int uQuality; uniform float uFocus;
        in vec2 vUv; out vec4 color;
        void main() {
            float d = rounded(vUv, uSize, 0.045);
            float alpha = 1.0 - smoothstep(-0.003, 0.001, d);
            if (alpha <= 0.0) discard;
            float edge = smoothstep(-0.04, 0.0, d);
            vec3 base = vec3(0.12, 0.14, 0.18);
            if (uQuality > 0) {
                vec2 screen = gl_FragCoord.xy / uViewport;
                vec2 bend = (vUv - 0.5) * edge * edge * -0.07;
                vec3 behind = texture(uBlur, screen + bend).rgb;
                if (uQuality > 1) {
                    behind.r = texture(uBlur, screen + bend * 1.18).r;
                    behind.b = texture(uBlur, screen + bend * 0.82).b;
                }
                // Brighter surroundings get more tint, so white text stays readable.
                float light = dot(behind, vec3(0.299, 0.587, 0.114));
                base = mix(behind, vec3(0.07, 0.09, 0.13), 0.42 + 0.3 * light);
            }
            float rim = pow(edge, 3.0) * (0.35 + 0.35 * vUv.y + 0.2 * uFocus);
            float sheen = uQuality > 0 ? 0.07 * smoothstep(0.25, 0.0, abs(vUv.x * 0.6 + (1.0 - vUv.y) - 0.35)) : 0.0;
            float a = alpha * (uQuality > 0 ? 0.96 : 0.82);
            color = vec4((base + rim + sheen) * a, a);
        }
    """
    const val CONTENT = OES + PRECISION + ROUNDED + """
        uniform samplerExternalOES uTexture; uniform mat4 uTransform; uniform vec2 uSize;
        in vec2 vUv; out vec4 color;
        void main() {
            float alpha = 1.0 - smoothstep(-0.003, 0.001, rounded(vUv, uSize, 0.032));
            color = texture(uTexture, (uTransform * vec4(vUv, 0.0, 1.0)).xy) * alpha;
        }
    """
    const val TEXTURE = ES + PRECISION + """
        uniform sampler2D uTexture; in vec2 vUv; out vec4 color;
        void main() { color = texture(uTexture, vec2(vUv.x, 1.0 - vUv.y)); }
    """
    const val FLAT = ES + PRECISION + """
        uniform vec4 uColor; out vec4 color;
        void main() { color = vec4(uColor.rgb * uColor.a, uColor.a); }
    """
    const val RING = ES + PRECISION + """
        uniform float uFill; in vec2 vUv; out vec4 color;
        void main() {
            float r = length(vUv - 0.5) * 2.0;
            float ring = smoothstep(0.55, 0.66, r) * (1.0 - smoothstep(0.86, 0.98, r));
            float a = max(ring, uFill * (1.0 - smoothstep(0.85, 0.98, r)));
            float halo = (1.0 - smoothstep(0.85, 1.0, r)) * 0.18;
            float total = max(a, halo);
            color = vec4(vec3(a), total);
        }
    """
    /** Barrel pre-distortion for simple phone viewers. One coefficient, tuned by eye. */
    const val LENS = ES + PRECISION + """
        uniform sampler2D uTexture; uniform float uK; uniform float uAspect; in vec2 vUv; out vec4 color;
        void main() {
            vec2 p = (vUv - 0.5) * vec2(uAspect, 1.0) * 2.0;
            vec2 uv = p * (1.0 + uK * dot(p, p)) / (vec2(uAspect, 1.0) * 2.0) + 0.5;
            bool outside = uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0;
            color = outside ? vec4(0.0, 0.0, 0.0, 1.0) : texture(uTexture, uv);
        }
    """
}
