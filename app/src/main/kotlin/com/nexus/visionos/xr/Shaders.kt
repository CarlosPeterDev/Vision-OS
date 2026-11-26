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
    /** Four original procedural scenes: dusk, night sky, mountains and ocean. */
    const val SKY = ES + PRECISION + """
        uniform vec2 uTan; uniform vec4 uHead; uniform int uScene; in vec2 vUv; out vec4 color;
        float hash(vec3 p) { return fract(sin(dot(p, vec3(12.99, 78.23, 37.72))) * 4375.85); }
        void main() {
            vec3 d = normalize(vec3((vUv * 2.0 - 1.0) * uTan, -1.0));
            d = d + 2.0 * cross(uHead.xyz, cross(uHead.xyz, d) + uHead.w * d);
            float up = clamp(d.y * 1.6, 0.0, 1.0);
            float horizon = pow(1.0 - abs(d.y), 10.0);
            float azimuth = atan(d.x, -d.z);
            vec3 sky;
            if (uScene == 1) {
                sky = mix(vec3(0.035, 0.05, 0.11), vec3(0.004, 0.006, 0.02), up);
                vec3 cell = floor(d * 140.0);
                float star = step(0.9965, hash(cell)) * smoothstep(0.5, 0.15, length(fract(d * 140.0) - 0.5));
                sky += vec3(star) * (0.4 + 0.6 * hash(cell + 3.0));
                float band = exp(-pow(dot(d, normalize(vec3(0.5, 0.55, 0.65))) * 4.0, 2.0));
                sky += vec3(0.05, 0.045, 0.085) * band;
            } else if (uScene == 2) {
                sky = mix(vec3(0.95, 0.55, 0.34), vec3(0.16, 0.24, 0.47), up);
                float far = 0.13 + 0.05 * sin(azimuth * 3.0) + 0.025 * sin(azimuth * 11.0 + 1.0) + 0.01 * sin(azimuth * 29.0);
                float near = 0.06 + 0.04 * sin(azimuth * 5.0 + 2.0) + 0.016 * sin(azimuth * 17.0);
                sky = mix(sky, vec3(0.21, 0.17, 0.30), step(d.y, far));
                sky = mix(sky, vec3(0.09, 0.08, 0.15), step(d.y, near));
                if (d.y < -0.02) sky = mix(vec3(0.09, 0.08, 0.15), vec3(0.03, 0.04, 0.05), clamp(-d.y * 3.0, 0.0, 1.0));
            } else if (uScene == 3) {
                sky = mix(vec3(0.62, 0.80, 0.92), vec3(0.16, 0.40, 0.72), up);
                vec3 sun = normalize(vec3(0.35, 0.28, -1.0));
                sky += vec3(1.0, 0.85, 0.6) * (pow(max(dot(d, sun), 0.0), 600.0) + 0.18 * pow(max(dot(d, sun), 0.0), 12.0));
                if (d.y < 0.0) {
                    vec2 p = d.xz / max(-d.y, 0.02);
                    float wave = sin(p.x * 3.1 + sin(p.y * 1.7)) * sin(p.y * 2.3 + sin(p.x * 1.1));
                    sky = mix(vec3(0.38, 0.62, 0.74), vec3(0.02, 0.17, 0.28), clamp(-d.y * 4.0, 0.0, 1.0));
                    sky += vec3(0.07) * wave * exp(-length(p) * 0.12);
                }
            } else {
                sky = mix(vec3(0.16, 0.20, 0.32), vec3(0.015, 0.02, 0.06), up);
                sky += vec3(0.22, 0.12, 0.10) * horizon;
                if (d.y < -0.02) {
                    vec2 p = d.xz / -d.y * 1.4;
                    vec2 g = abs(fract(p) - 0.5);
                    float line = smoothstep(0.46, 0.5, max(g.x, g.y)) * exp(-length(p) * 0.18);
                    sky = mix(vec3(0.03, 0.04, 0.07), sky, exp(d.y * 6.0)) + vec3(0.14, 0.25, 0.38) * line;
                }
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
    /**
     * Liquid glass: a frosted sample bent strongly near the edge like a thick lens, slight colour
     * dispersion, a rim lit from the top left, an inner glow and a soft diagonal reflection.
     */
    const val GLASS = ES + PRECISION + ROUNDED + """
        uniform sampler2D uBlur; uniform vec2 uViewport; uniform vec2 uSize; uniform float uRadius;
        uniform int uQuality; uniform float uFocus; uniform float uAlpha;
        in vec2 vUv; out vec4 color;
        void main() {
            float d = rounded(vUv, uSize, uRadius);
            float alpha = 1.0 - smoothstep(-0.0025, 0.001, d);
            if (alpha <= 0.0) discard;
            float depth = min(uRadius, 0.05);
            float edge = smoothstep(-depth, 0.0, d);
            // Outward direction of the rounded outline, used to light the rim.
            vec2 p = (vUv - 0.5) * uSize;
            vec2 q = max(abs(p) - uSize * 0.5 + uRadius, 0.0);
            vec2 normal = normalize(sign(p) * q + vec2(0.00001));
            float lit = max(dot(normal, vec2(-0.45, 0.89)), 0.0) + 0.55 * max(dot(normal, vec2(0.45, -0.89)), 0.0);
            vec3 base = vec3(0.16, 0.18, 0.23);
            if (uQuality > 0) {
                vec2 screen = gl_FragCoord.xy / uViewport;
                vec2 bend = -normal * edge * edge * 0.045;
                vec3 behind = texture(uBlur, screen + bend).rgb;
                if (uQuality > 1) {
                    behind.r = texture(uBlur, screen + bend * 1.3).r;
                    behind.b = texture(uBlur, screen + bend * 0.7).b;
                }
                // Brighter surroundings get more tint, so white text stays readable.
                float light = dot(behind, vec3(0.299, 0.587, 0.114));
                vec3 vivid = mix(vec3(light), behind, 1.35);
                base = mix(vivid, vec3(0.08, 0.10, 0.14), 0.34 + 0.34 * light) + 0.035;
            }
            float line = smoothstep(-0.006, -0.0015, d);
            base += line * (0.16 + 0.62 * lit) + pow(edge, 3.0) * 0.10 * (0.4 + lit);
            if (uQuality > 0) base += 0.075 * smoothstep(0.30, 0.0, abs(vUv.x * 0.55 + (1.0 - vUv.y) - 0.30));
            base += uFocus * (0.07 + line * 0.25);
            float a = alpha * (uQuality > 0 ? 0.94 : 0.84) * uAlpha;
            color = vec4(base * a, a);
        }
    """
    /** Soft drop shadow that separates a window from what is behind it. */
    const val SHADOW = ES + PRECISION + ROUNDED + """
        uniform vec2 uSize; uniform float uMargin; uniform float uAlpha; in vec2 vUv; out vec4 color;
        void main() {
            float d = rounded(vUv, uSize, uMargin + 0.06) + uMargin;
            float a = (1.0 - smoothstep(-0.02, uMargin, d)) * smoothstep(-0.03, 0.0, d + 0.02) * 0.32 * uAlpha;
            color = vec4(0.0, 0.0, 0.0, a);
        }
    """
    const val CONTENT = OES + PRECISION + ROUNDED + """
        uniform samplerExternalOES uTexture; uniform mat4 uTransform; uniform vec2 uSize; uniform float uAlpha;
        in vec2 vUv; out vec4 color;
        void main() {
            float alpha = 1.0 - smoothstep(-0.003, 0.001, rounded(vUv, uSize, 0.045));
            color = texture(uTexture, (uTransform * vec4(vUv, 0.0, 1.0)).xy) * alpha * uAlpha;
        }
    """
    const val TEXTURE = ES + PRECISION + """
        uniform sampler2D uTexture; uniform float uAlpha; in vec2 vUv; out vec4 color;
        void main() { color = texture(uTexture, vec2(vUv.x, 1.0 - vUv.y)) * uAlpha; }
    """
    const val FLAT = ES + PRECISION + """
        uniform vec4 uColor; out vec4 color;
        void main() { color = vec4(uColor.rgb * uColor.a, uColor.a); }
    """
    /** The pointer: a ring that fills while pinched, with an arc showing a hold towards a long press. */
    const val RING = ES + PRECISION + """
        uniform float uFill; uniform float uProgress; in vec2 vUv; out vec4 color;
        void main() {
            vec2 p = (vUv - 0.5) * 2.0;
            float r = length(p);
            float ring = smoothstep(0.50, 0.60, r) * (1.0 - smoothstep(0.70, 0.80, r));
            float a = max(ring, uFill * (1.0 - smoothstep(0.62, 0.74, r)));
            float halo = (1.0 - smoothstep(0.7, 0.82, r)) * 0.18;
            float turn = fract(atan(p.x, p.y) / 6.28318 + 1.0);
            float arc = smoothstep(0.84, 0.87, r) * (1.0 - smoothstep(0.96, 0.99, r)) * step(turn, uProgress) * step(0.02, uProgress);
            float total = max(max(a, halo), arc);
            color = vec4(vec3(a) + arc * vec3(0.35, 0.75, 1.0), total);
        }
    """
    /** Barrel pre-distortion with adjustable lens centre and zoom, plus an alignment grid. */
    const val LENS = ES + PRECISION + """
        uniform sampler2D uTexture; uniform float uK; uniform float uAspect; uniform vec2 uCentre;
        uniform float uZoom; uniform int uGrid; in vec2 vUv; out vec4 color;
        void main() {
            vec2 p = (vUv - 0.5 - uCentre) * vec2(uAspect, 1.0) * 2.0;
            vec2 uv = p * (1.0 + uK * dot(p, p)) / (vec2(uAspect, 1.0) * 2.0 * uZoom) + 0.5;
            bool outside = uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0;
            color = outside ? vec4(0.0, 0.0, 0.0, 1.0) : texture(uTexture, uv);
            if (uGrid == 1 && !outside) {
                vec2 g = abs(fract((uv - 0.5) * vec2(uAspect, 1.0) * 8.0) - 0.5);
                float lines = step(0.47, max(g.x, g.y));
                float cross = step(length((uv - 0.5) * vec2(uAspect, 1.0)), 0.015);
                color.rgb = mix(color.rgb * 0.35, vec3(0.3, 1.0, 0.6), max(lines, cross));
            }
        }
    """
}
