package com.lyricstage.background

/**
 * AGSL "liquid glass": pools of the artwork's accent colours drift behind a slowly
 * undulating glass slab that refracts them. Sung words send ripples through the glass and
 * each new line sweeps a soft band of light across it. Tone mapped so the brightest spots
 * stay well below white text.
 *
 * Coordinates: fragCoord has a top-left origin; p is centred, y down, aspect corrected.
 */
internal const val LIQUID_GLASS_AGSL = """
uniform float2 uRes;
uniform float uTime;
uniform half3 uC0;
uniform half3 uC1;
uniform half3 uC2;
uniform half3 uBase;
uniform float4 uRip[8];
uniform float uLineT;

float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),
               mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.55;
    for (int i = 0; i < 3; i++) {
        v += a * noise(p);
        p = p * 1.9 + float2(1.7, 9.2);
        a *= 0.45;
    }
    return v / 0.88;
}

// Domain-warped noise picks where each accent glows. Colours are added, not blended,
// so neighbouring hues glow side by side instead of averaging into brown.
half3 lightField(float2 p, float t) {
    float2 s = p * 0.62;
    float2 q = float2(fbm(s + float2(0.0, t * 0.035)), fbm(s + float2(5.2, -t * 0.03)));
    float2 r = float2(fbm(s + 1.6 * q + float2(1.7, 9.2) + t * 0.022),
                      fbm(s + 1.6 * q + float2(8.3, 2.8) - t * 0.026));
    float f = fbm(s * 0.9 + 1.4 * r);
    float w0 = smoothstep(0.42, 0.82, f);
    float w1 = smoothstep(0.5, 0.88, r.x) * (1.0 - w0 * 0.6);
    float w2 = smoothstep(0.55, 0.92, q.y) * (1.0 - max(w0, w1) * 0.6);
    half3 col = uBase * 0.7 + uC0 * w0 * 0.95 + uC1 * w1 * 0.8 + uC2 * w2 * 0.65;
    half lum = dot(col, half3(0.299, 0.587, 0.114));
    return max(mix(half3(lum), col, 1.45), half3(0.0));
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uRes;
    float aspect = uRes.x / uRes.y;
    float2 p = float2((uv.x - 0.5) * aspect, uv.y - 0.5);
    float t = uTime;

    // the slab is never flat: slow undulation
    float2 slope = float2(
        sin(p.y * 2.3 + t * 0.21) + 0.6 * sin(p.x * 1.7 - p.y * 1.3 + t * 0.17),
        cos(p.x * 2.1 - t * 0.19) + 0.6 * cos(p.y * 1.9 + p.x * 0.8 - t * 0.23)) * 0.012;

    // ripples: a damped ring travelling outwards from each sung word
    float ring = 0.0;
    for (int i = 0; i < 8; i++) {
        float4 rp = uRip[i];
        float age = t - rp.z;
        if (rp.w > 0.0 && age >= 0.0 && age < 3.5) {
            float2 c = float2((rp.x - 0.5) * aspect, rp.y - 0.5);
            float2 d = p - c;
            float dist = length(d);
            float x = dist - age * 0.32;
            float env = exp(-x * x * 90.0) * exp(-age * 1.25) * rp.w;
            slope += (d / max(dist, 0.001)) * sin(x * 55.0) * env * 0.035;
            ring += env;
        }
    }

    // a band of light across the glass on each new line
    float lineAge = t - uLineT;
    float sweep = 0.0;
    if (lineAge > 0.0 && lineAge < 2.2) {
        float s = (p.x * 0.7 - p.y * 0.45) - (lineAge * 1.5 - 1.2);
        sweep = exp(-s * s * 16.0) * (1.0 - lineAge / 2.2);
        slope += float2(0.7, -0.45) * sweep * 0.02;
    }

    // refraction: the light is seen through the bent glass
    half3 col = lightField(p + slope * 6.0, t);
    col += half3(slope.x, 0.3 * (slope.y - slope.x), -slope.y) * 1.6;

    float3 n = normalize(float3(-slope * 14.0, 1.0));
    float3 L = normalize(float3(-0.45, -0.55, 0.7));
    float spec = pow(max(dot(reflect(-L, n), float3(0.0, 0.0, 1.0)), 0.0), 40.0);
    col += half(spec * 0.2 + ring * 0.05 + sweep * 0.1);

    // vignette, then a filmic roll-off that keeps highlights under the white type
    col *= half(mix(0.4, 1.0, smoothstep(1.2, 0.3, length(p * float2(0.85, 1.1)))));
    col = col / (1.0 + col * 0.7);
    col = pow(col, half3(1.18)) * 0.95;
    col += half((hash(fragCoord + fract(t) * 100.0) - 0.5) * 0.018);
    return half4(col, 1.0);
}
"""
