// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// Adapted for Android Views from Miuix Lens.kt (changes: native RuntimeShader uniforms).
// Upstream: compose-miuix-ui/miuix, commit 0657575a0259f89b3863166ec2cef4223ea01156.
// Miuix's original is adapted from Kyant0/AndroidLiquidGlass (Apache-2.0).

package io.github.lujinxin.nextep.config

import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RuntimeShader

internal object LiquidGlassLens {
    fun newShader(dispersion: Boolean) = RuntimeShader(
        if (dispersion) ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER else ROUNDED_RECT_REFRACTION_SHADER,
    )

    fun effect(shader: RuntimeShader, bounds: RectF, margin: Float,
        height: Float, amount: Float, selection: Boolean): RenderEffect {
        val radius = minOf(bounds.width(), bounds.height()) / 2f
        shader.setFloatUniform("size", bounds.width(), bounds.height())
        shader.setFloatUniform("offset", -margin - bounds.left, -margin - bounds.top)
        shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)
        shader.setFloatUniform("refractionHeight", height.coerceAtLeast(0.001f))
        shader.setFloatUniform("refractionAmount", -amount)
        shader.setFloatUniform("depthEffect", if (selection) 1f else 0f)
        if (selection) shader.setFloatUniform("chromaticAberration", 0.5f)
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }

    private const val ROUNDED_RECT_SDF = """
    float radiusAt(float2 coord, float4 radii) {
        if (coord.x >= 0.0) {
            if (coord.y <= 0.0) return radii.y;
            else return radii.z;
        } else {
            if (coord.y <= 0.0) return radii.x;
            else return radii.w;
        }
    }

    float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
        float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
        float outside = length(max(cornerCoord, 0.0)) - radius;
        float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
        return outside + inside;
    }

    float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
        float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
        if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
            return sign(coord) * normalize(max(cornerCoord, 0.0));
        } else {
            float gradX = step(cornerCoord.y, cornerCoord.x);
            return sign(coord) * float2(gradX, 1.0 - gradX);
        }
    }
    """

    private const val ROUNDED_RECT_REFRACTION_SHADER = """
    uniform shader content;

    uniform float2 size;
    uniform float2 offset;
    uniform float4 cornerRadii;
    uniform float refractionHeight;
    uniform float refractionAmount;
    uniform float depthEffect;

    $ROUNDED_RECT_SDF

    float circleMap(float x) {
        return 1.0 - sqrt(1.0 - x * x);
    }

    half4 main(float2 coord) {
        float2 halfSize = size * 0.5;
        float2 centeredCoord = (coord + offset) - halfSize;
        float radius = radiusAt(centeredCoord, cornerRadii);

        float sd = sdRoundedRect(centeredCoord, halfSize, radius);
        if (-sd >= refractionHeight) {
            return content.eval(coord);
        }
        sd = min(sd, 0.0);

        float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
        float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
        float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

        float2 refractedCoord = coord + d * grad;
        return content.eval(refractedCoord);
    }
    """

    private const val ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER = """
    uniform shader content;

    uniform float2 size;
    uniform float2 offset;
    uniform float4 cornerRadii;
    uniform float refractionHeight;
    uniform float refractionAmount;
    uniform float depthEffect;
    uniform float chromaticAberration;

    $ROUNDED_RECT_SDF

    float circleMap(float x) {
        return 1.0 - sqrt(1.0 - x * x);
    }

    half4 main(float2 coord) {
        float2 halfSize = size * 0.5;
        float2 centeredCoord = (coord + offset) - halfSize;
        float radius = radiusAt(centeredCoord, cornerRadii);

        float sd = sdRoundedRect(centeredCoord, halfSize, radius);
        if (-sd >= refractionHeight) {
            return content.eval(coord);
        }
        sd = min(sd, 0.0);

        float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
        float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
        float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

        float2 refractedCoord = coord + d * grad;
        float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
        float2 dispersedCoord = d * grad * dispersionIntensity;

        half4 color = half4(0.0);

        half4 red = content.eval(refractedCoord + dispersedCoord);
        color.r += red.r / 3.5;
        color.a += red.a / 7.0;

        half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
        color.r += orange.r / 3.5;
        color.g += orange.g / 7.0;
        color.a += orange.a / 7.0;

        half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
        color.r += yellow.r / 3.5;
        color.g += yellow.g / 3.5;
        color.a += yellow.a / 7.0;

        half4 green = content.eval(refractedCoord);
        color.g += green.g / 3.5;
        color.a += green.a / 7.0;

        half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
        color.g += cyan.g / 3.5;
        color.b += cyan.b / 3.0;
        color.a += cyan.a / 7.0;

        half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
        color.b += blue.b / 3.0;
        color.a += blue.a / 7.0;

        half4 purple = content.eval(refractedCoord - dispersedCoord);
        color.r += purple.r / 7.0;
        color.b += purple.b / 3.0;
        color.a += purple.a / 7.0;

        return color;
    }
    """
}
