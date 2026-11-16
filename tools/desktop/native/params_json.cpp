/*
 * Spektrafilm desktop worker — recipe/preset mapping implementation.
 * GPLv3. Film modeling powered by spektrafilm.
 *
 * The authored preset schema is the nested camelCase tree documented by
 * app/src/main/java/com/spectrafilm/app/BuiltInPresets.kt. Every key that file
 * honours is mapped here; unknown keys are a hard error so a preset edit can
 * never silently lose a control. Numeric leaves are blended from
 * spk_default_params by `strength`; strings/booleans/enums are applied once
 * strength > 0 (a boolean cannot be half-true).
 */
#include "params_json.h"

#include <algorithm>
#include <cfloat>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace spkdesk {
namespace {

using spk::json::Value;

[[noreturn]] void fail(const std::string& path, const std::string& message) {
    throw std::runtime_error(path + ": " + message);
}

const Value& object_member(const Value& object, const std::string& key,
                           const std::string& path) {
    if (!object.is_object()) fail(path, "expected an object");
    auto it = object.object.find(key);
    if (it == object.object.end()) fail(path, "missing key '" + key + "'");
    return *it->second;
}

const Value* optional_member(const Value& object, const std::string& key) {
    if (!object.is_object()) return nullptr;
    auto it = object.object.find(key);
    return it == object.object.end() ? nullptr : it->second.get();
}

void expect_object(const Value& value, const std::string& path) {
    if (!value.is_object()) fail(path, "expected an object");
}

void expect_array(const Value& value, size_t count, const std::string& path) {
    if (!value.is_array()) fail(path, "expected an array");
    if (value.array.size() != count)
        fail(path, "expected " + std::to_string(count) + " elements, got " +
                       std::to_string(value.array.size()));
}

double read_number(const Value& value, const std::string& path) {
    if (!value.is_number() || value.is_null_number)
        fail(path, "expected a finite number");
    const double d = value.as_number();
    if (!std::isfinite(d)) fail(path, "number is not finite");
    if (std::fabs(d) > static_cast<double>(FLT_MAX))
        fail(path, "number is outside the float range");
    return d;
}

float read_float(const Value& value, const std::string& path) {
    return static_cast<float>(read_number(value, path));
}

int32_t read_int(const Value& value, int32_t lo, int32_t hi,
                 const std::string& path) {
    const double d = read_number(value, path);
    if (std::floor(d) != d) fail(path, "expected an integer");
    if (d < lo || d > hi)
        fail(path, "integer out of range [" + std::to_string(lo) + ", " +
                       std::to_string(hi) + "]");
    return static_cast<int32_t>(d);
}

bool read_bool(const Value& value, const std::string& path) {
    if (value.type != spk::json::Type::Bool) fail(path, "expected a boolean");
    return value.boolean;
}

std::string read_string(const Value& value, const std::string& path) {
    if (!value.is_string()) fail(path, "expected a string");
    return value.str;
}


/* Blend one numeric leaf. Even at strength 0 the value is validated above, so
 * a malformed authored preset is rejected regardless of the slider position. */
void blend_float(float* dst, float base, const Value& value, float strength,
                 const std::string& path) {
    const float target = read_float(value, path);
    if (strength >= 1.0f) *dst = target;
    else if (strength <= 0.0f) *dst = base;
    else *dst = base + (target - base) * strength;
    if (!std::isfinite(*dst)) fail(path, "blended value is not finite");
}

void blend_float_array(float* dst, const float* base, const Value& value,
                       size_t count, float strength, const std::string& path) {
    expect_array(value, count, path);
    for (size_t i = 0; i < count; ++i) {
        const std::string element = path + "[" + std::to_string(i) + "]";
        const float target = read_float(value[i], element);
        if (strength >= 1.0f) dst[i] = target;
        else if (strength <= 0.0f) dst[i] = base[i];
        else dst[i] = base[i] + (target - base[i]) * strength;
        if (!std::isfinite(dst[i])) fail(element, "blended value is not finite");
    }
}

void snap_bool(int32_t* dst, int32_t base, const Value& value, float strength,
               const std::string& path) {
    const bool target = read_bool(value, path);
    *dst = strength > 0.0f ? (target ? 1 : 0) : base;
}

void snap_int(int32_t* dst, int32_t base, const Value& value, int32_t lo,
              int32_t hi, float strength, const std::string& path) {
    const int32_t target = read_int(value, lo, hi, path);
    *dst = strength > 0.0f ? target : base;
}

void apply_string(std::string* dst, const Value& value, float strength,
                  const std::string& path) {
    const std::string target = read_string(value, path);
    if (strength > 0.0f) *dst = target;
}

spk_color_space output_color_space(const Value& value, const std::string& path) {
    const std::string name = read_string(value, path);
    if (name == "SRGB") return SPK_CS_SRGB;
    if (name == "ADOBE_RGB") return SPK_CS_ADOBE_RGB;
    if (name == "PROPHOTO") return SPK_CS_PROPHOTO;
    if (name == "REC2020") return SPK_CS_REC2020;
    if (name == "ACES2065_1") return SPK_CS_ACES2065_1;
    if (name == "LINEAR_SRGB") return SPK_CS_LINEAR_SRGB;
    fail(path, "unknown outputColorSpace '" + name + "'");
}

spk_rgb2raw rgb_to_raw_method(const Value& value, const std::string& path) {
    const std::string name = read_string(value, path);
    if (name == "HANATOS2025") return SPK_RGB2RAW_HANATOS2025;
    if (name == "MALLETT2019") return SPK_RGB2RAW_MALLETT2019;
    fail(path, "unknown rgbToRawMethod '" + name + "'");
}

int32_t diffusion_family(const Value& value, const std::string& path) {
    const std::string name = read_string(value, path);
    if (name == "glimmerglass") return SPK_DIFFUSION_GLIMMERGLASS;
    if (name == "black_pro_mist") return SPK_DIFFUSION_BLACK_PRO_MIST;
    if (name == "pro_mist") return SPK_DIFFUSION_PRO_MIST;
    if (name == "cinebloom") return SPK_DIFFUSION_CINEBLOOM;
    fail(path, "unknown diffusion filterFamily '" + name + "'");
}

void validate_ae_method(const std::string& method, const std::string& path) {
    static const char* const kKnown[] = {
        "center_weighted", "average", "median", "partial",
        "matrix", "multi_zone", "highlight_weighted",
    };
    for (const char* known : kKnown)
        if (method == known) return;
    fail(path, "unknown autoExposureMethod '" + method + "'");
}

struct MappingContext {
    const spk_params& base;
    float strength;
    RecipeParams* out;
    std::string preset_id;

    std::string path(const std::string& leaf) const {
        return "preset '" + preset_id + "' " + leaf;
    }
};

void apply_glare(MappingContext& ctx, const Value& object,
                 const std::string& leaf) {
    const std::string base_path = ctx.path(leaf);
    expect_object(object, base_path);
    spk_params& p = ctx.out->params;
    /* BuiltInPresets/ParamsState expose ONE glare state and write it to both
     * filmRender.glare and printRender.glare on the way to the engine, so a
     * preset that authors either object (or the top-level one) must update
     * both routes. Keep the two copies synchronised; the last authored field
     * wins, exactly like applyParams followed by ParamsState.toParams. */
    int32_t* film_active = &p.glare_active;
    int32_t* print_active = &p.print_glare_active;
    float* film_percent = &p.glare_percent;
    float* print_percent = &p.print_glare_percent;
    float* film_roughness = &p.glare_roughness;
    float* print_roughness = &p.print_glare_roughness;
    float* film_blur = &p.glare_blur;
    float* print_blur = &p.print_glare_blur;

    for (const auto& entry : object.object) {
        const std::string& key = entry.first;
        const Value& value = *entry.second;
        const std::string field = base_path + "." + key;
        if (key == "active") {
            const bool target = read_bool(value, field);
            const int32_t blended = ctx.strength > 0.0f
                                        ? (target ? 1 : 0)
                                        : *film_active;
            *film_active = blended;
            *print_active = blended;
        } else if (key == "percent") {
            const float target = read_float(value, field);
            float blended = *film_percent;
            if (ctx.strength >= 1.0f) blended = target;
            else if (ctx.strength > 0.0f)
                blended = blended + (target - blended) * ctx.strength;
            if (!std::isfinite(blended)) fail(field, "blended value is not finite");
            *film_percent = blended;
            *print_percent = blended;
        } else if (key == "roughness") {
            const float target = read_float(value, field);
            float blended = *film_roughness;
            if (ctx.strength >= 1.0f) blended = target;
            else if (ctx.strength > 0.0f)
                blended = blended + (target - blended) * ctx.strength;
            if (!std::isfinite(blended)) fail(field, "blended value is not finite");
            *film_roughness = blended;
            *print_roughness = blended;
        } else if (key == "blur") {
            const float target = read_float(value, field);
            float blended = *film_blur;
            if (ctx.strength >= 1.0f) blended = target;
            else if (ctx.strength > 0.0f)
                blended = blended + (target - blended) * ctx.strength;
            if (!std::isfinite(blended)) fail(field, "blended value is not finite");
            *film_blur = blended;
            *print_blur = blended;
        } else {
            fail(base_path, "unknown key '" + key + "'");
        }
    }
}

void apply_diffusion(MappingContext& ctx, const Value& object,
                     const std::string& leaf, bool camera_route) {
    const std::string base_path = ctx.path(leaf);
    expect_object(object, base_path);
    spk_params& p = ctx.out->params;
    const spk_params& b = ctx.base;
    int32_t* active = camera_route ? &p.camera_diffusion_active
                                   : &p.enlarger_diffusion_active;
    int32_t* family = camera_route ? &p.camera_diffusion_family
                                   : &p.enlarger_diffusion_family;
    float* strength = camera_route ? &p.camera_diffusion_strength
                                   : &p.enlarger_diffusion_strength;
    float* spatial = camera_route ? &p.camera_diffusion_spatial_scale
                                  : &p.enlarger_diffusion_spatial_scale;
    float* warmth = camera_route ? &p.camera_diffusion_halo_warmth
                                 : &p.enlarger_diffusion_halo_warmth;
    float* core_i = camera_route ? &p.camera_diffusion_core_intensity
                                 : &p.enlarger_diffusion_core_intensity;
    float* core_s = camera_route ? &p.camera_diffusion_core_size
                                 : &p.enlarger_diffusion_core_size;
    float* halo_i = camera_route ? &p.camera_diffusion_halo_intensity
                                 : &p.enlarger_diffusion_halo_intensity;
    float* halo_s = camera_route ? &p.camera_diffusion_halo_size
                                 : &p.enlarger_diffusion_halo_size;
    float* bloom_i = camera_route ? &p.camera_diffusion_bloom_intensity
                                  : &p.enlarger_diffusion_bloom_intensity;
    float* bloom_s = camera_route ? &p.camera_diffusion_bloom_size
                                  : &p.enlarger_diffusion_bloom_size;
    const int32_t base_active = camera_route ? b.camera_diffusion_active
                                             : b.enlarger_diffusion_active;
    const int32_t base_family = camera_route ? b.camera_diffusion_family
                                             : b.enlarger_diffusion_family;
    const float base_strength = camera_route ? b.camera_diffusion_strength
                                             : b.enlarger_diffusion_strength;
    const float base_spatial = camera_route ? b.camera_diffusion_spatial_scale
                                            : b.enlarger_diffusion_spatial_scale;
    const float base_warmth = camera_route ? b.camera_diffusion_halo_warmth
                                           : b.enlarger_diffusion_halo_warmth;
    const float base_core_i = camera_route ? b.camera_diffusion_core_intensity
                                           : b.enlarger_diffusion_core_intensity;
    const float base_core_s = camera_route ? b.camera_diffusion_core_size
                                           : b.enlarger_diffusion_core_size;
    const float base_halo_i = camera_route ? b.camera_diffusion_halo_intensity
                                           : b.enlarger_diffusion_halo_intensity;
    const float base_halo_s = camera_route ? b.camera_diffusion_halo_size
                                           : b.enlarger_diffusion_halo_size;
    const float base_bloom_i = camera_route ? b.camera_diffusion_bloom_intensity
                                            : b.enlarger_diffusion_bloom_intensity;
    const float base_bloom_s = camera_route ? b.camera_diffusion_bloom_size
                                            : b.enlarger_diffusion_bloom_size;

    bool saw_family = false;
    for (const auto& entry : object.object) {
        const std::string& key = entry.first;
        const Value& value = *entry.second;
        const std::string field = base_path + "." + key;
        if (key == "active") {
            snap_bool(active, base_active, value, ctx.strength, field);
        } else if (key == "filterFamily" || key == "family") {
            const int32_t target = diffusion_family(value, field);
            if (saw_family) fail(base_path, "duplicate filterFamily/family");
            saw_family = true;
            *family = ctx.strength > 0.0f ? target : base_family;
        } else if (key == "strength") {
            blend_float(strength, base_strength, value, ctx.strength, field);
        } else if (key == "spatialScale") {
            blend_float(spatial, base_spatial, value, ctx.strength, field);
        } else if (key == "haloWarmth") {
            blend_float(warmth, base_warmth, value, ctx.strength, field);
        } else if (key == "coreIntensity") {
            blend_float(core_i, base_core_i, value, ctx.strength, field);
        } else if (key == "coreSize") {
            blend_float(core_s, base_core_s, value, ctx.strength, field);
        } else if (key == "haloIntensity") {
            blend_float(halo_i, base_halo_i, value, ctx.strength, field);
        } else if (key == "haloSize") {
            blend_float(halo_s, base_halo_s, value, ctx.strength, field);
        } else if (key == "bloomIntensity") {
            blend_float(bloom_i, base_bloom_i, value, ctx.strength, field);
        } else if (key == "bloomSize") {
            blend_float(bloom_s, base_bloom_s, value, ctx.strength, field);
        } else {
            fail(base_path, "unknown key '" + key + "'");
        }
    }
}

void apply_grain(MappingContext& ctx, const Value& object,
                 const std::string& leaf) {
    const std::string base_path = ctx.path(leaf);
    expect_object(object, base_path);
    spk_params& p = ctx.out->params;
    const spk_params& b = ctx.base;
    for (const auto& entry : object.object) {
        const std::string& key = entry.first;
        const Value& value = *entry.second;
        const std::string field = base_path + "." + key;
        if (key == "active") snap_bool(&p.grain_active, b.grain_active, value, ctx.strength, field);
        else if (key == "sublayersActive") snap_bool(&p.grain_sublayers_active, b.grain_sublayers_active, value, ctx.strength, field);
        else if (key == "agxParticleAreaUm2") blend_float(&p.grain_particle_area_um2, b.grain_particle_area_um2, value, ctx.strength, field);
        else if (key == "agxParticleScale") blend_float_array(p.grain_particle_scale, b.grain_particle_scale, value, 3, ctx.strength, field);
        else if (key == "agxParticleScaleLayers") blend_float_array(p.grain_particle_scale_layers, b.grain_particle_scale_layers, value, 3, ctx.strength, field);
        else if (key == "densityMin") blend_float_array(p.grain_density_min, b.grain_density_min, value, 3, ctx.strength, field);
        else if (key == "uniformity") blend_float_array(p.grain_uniformity, b.grain_uniformity, value, 3, ctx.strength, field);
        else if (key == "blur") blend_float(&p.grain_blur, b.grain_blur, value, ctx.strength, field);
        else if (key == "blurDyeCloudsUm") blend_float(&p.grain_blur_dye_clouds_um, b.grain_blur_dye_clouds_um, value, ctx.strength, field);
        else if (key == "microStructure") blend_float_array(p.grain_micro_structure, b.grain_micro_structure, value, 2, ctx.strength, field);
        else if (key == "nSubLayers") snap_int(&p.grain_n_sub_layers, b.grain_n_sub_layers, value, 1, 16, ctx.strength, field);
        else fail(base_path, "unknown key '" + key + "'");
    }
}

void apply_halation(MappingContext& ctx, const Value& object,
                    const std::string& leaf) {
    const std::string base_path = ctx.path(leaf);
    expect_object(object, base_path);
    spk_params& p = ctx.out->params;
    const spk_params& b = ctx.base;
    for (const auto& entry : object.object) {
        const std::string& key = entry.first;
        const Value& value = *entry.second;
        const std::string field = base_path + "." + key;
        if (key == "active") snap_bool(&p.halation_active, b.halation_active, value, ctx.strength, field);
        else if (key == "scatterAmount") blend_float(&p.halation_scatter_amount, b.halation_scatter_amount, value, ctx.strength, field);
        else if (key == "scatterSpatialScale") blend_float(&p.halation_scatter_spatial_scale, b.halation_scatter_spatial_scale, value, ctx.strength, field);
        else if (key == "halationAmount") blend_float(&p.halation_halation_amount, b.halation_halation_amount, value, ctx.strength, field);
        else if (key == "halationSpatialScale") blend_float(&p.halation_halation_spatial_scale, b.halation_halation_spatial_scale, value, ctx.strength, field);
        else if (key == "scatterCoreUm") blend_float_array(p.halation_scatter_core_um, b.halation_scatter_core_um, value, 3, ctx.strength, field);
        else if (key == "scatterTailUm") blend_float_array(p.halation_scatter_tail_um, b.halation_scatter_tail_um, value, 3, ctx.strength, field);
        else if (key == "scatterTailWeight") blend_float_array(p.halation_scatter_tail_weight, b.halation_scatter_tail_weight, value, 3, ctx.strength, field);
        else if (key == "boostEv") blend_float(&p.halation_boost_ev, b.halation_boost_ev, value, ctx.strength, field);
        else if (key == "boostRange") blend_float(&p.halation_boost_range, b.halation_boost_range, value, ctx.strength, field);
        else if (key == "protectEv") blend_float(&p.halation_protect_ev, b.halation_protect_ev, value, ctx.strength, field);
        else if (key == "halationStrength") blend_float_array(p.halation_strength, b.halation_strength, value, 3, ctx.strength, field);
        else if (key == "halationFirstSigmaUm") blend_float_array(p.halation_first_sigma_um, b.halation_first_sigma_um, value, 3, ctx.strength, field);
        else if (key == "halationNBounces") snap_int(&p.halation_n_bounces, b.halation_n_bounces, value, 1, 16, ctx.strength, field);
        else if (key == "halationBounceDecay") blend_float(&p.halation_bounce_decay, b.halation_bounce_decay, value, ctx.strength, field);
        else if (key == "halationRenormalize") snap_bool(&p.halation_renormalize, b.halation_renormalize, value, ctx.strength, field);
        else fail(base_path, "unknown key '" + key + "'");
    }
}

void apply_dir_couplers(MappingContext& ctx, const Value& object,
                        const std::string& leaf) {
    const std::string base_path = ctx.path(leaf);
    expect_object(object, base_path);
    spk_params& p = ctx.out->params;
    const spk_params& b = ctx.base;
    for (const auto& entry : object.object) {
        const std::string& key = entry.first;
        const Value& value = *entry.second;
        const std::string field = base_path + "." + key;
        if (key == "active") snap_bool(&p.dir_couplers_active, b.dir_couplers_active, value, ctx.strength, field);
        else if (key == "amount") blend_float(&p.dir_amount, b.dir_amount, value, ctx.strength, field);
        else if (key == "inhibitionSamelayer") blend_float(&p.dir_inhibition_samelayer, b.dir_inhibition_samelayer, value, ctx.strength, field);
        else if (key == "inhibitionInterlayer") blend_float(&p.dir_inhibition_interlayer, b.dir_inhibition_interlayer, value, ctx.strength, field);
        else if (key == "gammaSamelayerRgb") blend_float_array(p.dir_gamma_samelayer_rgb, b.dir_gamma_samelayer_rgb, value, 3, ctx.strength, field);
        else if (key == "gammaInterlayerRToGb") blend_float_array(p.dir_gamma_interlayer_r_to_gb, b.dir_gamma_interlayer_r_to_gb, value, 2, ctx.strength, field);
        else if (key == "gammaInterlayerGToRb") blend_float_array(p.dir_gamma_interlayer_g_to_rb, b.dir_gamma_interlayer_g_to_rb, value, 2, ctx.strength, field);
        else if (key == "gammaInterlayerBToRg") blend_float_array(p.dir_gamma_interlayer_b_to_rg, b.dir_gamma_interlayer_b_to_rg, value, 2, ctx.strength, field);
        else if (key == "diffusionSizeUm") blend_float(&p.dir_diffusion_size_um, b.dir_diffusion_size_um, value, ctx.strength, field);
        else if (key == "diffusionTailUm") blend_float(&p.dir_diffusion_tail_um, b.dir_diffusion_tail_um, value, ctx.strength, field);
        else if (key == "diffusionTailWeight") blend_float(&p.dir_diffusion_tail_weight, b.dir_diffusion_tail_weight, value, ctx.strength, field);
        else fail(base_path, "unknown key '" + key + "'");
    }
}

struct CurvePoint {
    float x = 0.0f;
    float y = 0.0f;
};

std::vector<CurvePoint> read_points(const Value& value,
                                    const std::string& path) {
    if (!value.is_array()) fail(path, "expected an array of [x,y] points");
    if (value.array.size() > SPK_TONE_MAX_PTS)
        fail(path, "more than " + std::to_string(SPK_TONE_MAX_PTS) + " points");
    std::vector<CurvePoint> points;
    points.reserve(value.array.size());
    float previous_x = -1.0f;
    for (size_t i = 0; i < value.array.size(); ++i) {
        const std::string point_path = path + "[" + std::to_string(i) + "]";
        const Value& point = value[i];
        expect_array(point, 2, point_path);
        const float x = read_float(point[0], point_path + "[0]");
        const float y = read_float(point[1], point_path + "[1]");
        if (x < 0.0f || x > 1.0f || y < 0.0f || y > 1.0f)
            fail(point_path, "point coordinates must be in [0,1]");
        if (i > 0 && !(x > previous_x))
            fail(point_path, "x coordinates must be strictly increasing");
        previous_x = x;
        points.push_back({x, y});
    }
    return points;
}

void apply_curve(MappingContext& ctx, const Value& value, int32_t* count,
                 float* xs, float* ys, int32_t base_count, const float* base_xs,
                 const float* base_ys, const std::string& path) {
    const std::vector<CurvePoint> target = read_points(value, path);
    if (ctx.strength <= 0.0f) {
        *count = base_count;
        for (int32_t i = 0; i < base_count; ++i) {
            xs[i] = base_xs[i];
            ys[i] = base_ys[i];
        }
        return;
    }
    *count = static_cast<int32_t>(target.size());
    for (size_t i = 0; i < target.size(); ++i) {
        xs[i] = target[i].x;
        /* The default curve is the identity, so a partial strength blends the
         * authored y toward identity(x) rather than toward a nonexistent point. */
        ys[i] = (ctx.strength >= 1.0f)
                    ? target[i].y
                    : target[i].x + (target[i].y - target[i].x) * ctx.strength;
        if (!std::isfinite(ys[i])) fail(path, "blended curve value is not finite");
    }
}

void apply_tone_curve(MappingContext& ctx, const Value& object,
                      const std::string& leaf) {
    const std::string base_path = ctx.path(leaf);
    expect_object(object, base_path);
    spk_params& p = ctx.out->params;
    const spk_params& b = ctx.base;
    for (const auto& entry : object.object) {
        const std::string& key = entry.first;
        const Value& value = *entry.second;
        const std::string field = base_path + "." + key;
        if (key == "active") {
            snap_bool(&p.tone_curve_active, b.tone_curve_active, value,
                      ctx.strength, field);
        } else if (key == "master") {
            apply_curve(ctx, value, &p.tone_curve_master_n,
                        p.tone_curve_master_x, p.tone_curve_master_y,
                        b.tone_curve_master_n, b.tone_curve_master_x,
                        b.tone_curve_master_y, field);
        } else if (key == "red") {
            apply_curve(ctx, value, &p.tone_curve_rgb_n[0],
                        p.tone_curve_rgb_x[0], p.tone_curve_rgb_y[0],
                        b.tone_curve_rgb_n[0], b.tone_curve_rgb_x[0],
                        b.tone_curve_rgb_y[0], field);
        } else if (key == "green") {
            apply_curve(ctx, value, &p.tone_curve_rgb_n[1],
                        p.tone_curve_rgb_x[1], p.tone_curve_rgb_y[1],
                        b.tone_curve_rgb_n[1], b.tone_curve_rgb_x[1],
                        b.tone_curve_rgb_y[1], field);
        } else if (key == "blue") {
            apply_curve(ctx, value, &p.tone_curve_rgb_n[2],
                        p.tone_curve_rgb_x[2], p.tone_curve_rgb_y[2],
                        b.tone_curve_rgb_n[2], b.tone_curve_rgb_x[2],
                        b.tone_curve_rgb_y[2], field);
        } else {
            fail(base_path, "unknown key '" + key + "'");
        }
    }
}

void apply_preset_object(const Value& params, const std::string& preset_id,
                         float strength, RecipeParams* out) {
    expect_object(params, "preset '" + preset_id + "' params");
    /* Snapshot the defaults before any leaf is touched: every blend reads its
     * base from here, not from the half-mutated destination struct. */
    const spk_params base = out->params;
    MappingContext ctx{base, strength, out, preset_id};
    spk_params& p = out->params;
    const spk_params& b = ctx.base;

    for (const auto& entry : params.object) {
        const std::string& key = entry.first;
        const Value& value = *entry.second;
        const std::string path = ctx.path(key);
        if (key == "filmProfile") {
            apply_string(&out->film_profile, value, strength, path);
        } else if (key == "printProfile") {
            apply_string(&out->print_profile, value, strength, path);
        } else if (key == "io") {
            expect_object(value, path);
            for (const auto& io_entry : value.object) {
                const std::string& io_key = io_entry.first;
                const Value& io_value = *io_entry.second;
                const std::string field = path + "." + io_key;
                if (io_key == "inputColorSpace") apply_string(&out->input_color_space, io_value, strength, field);
                else if (io_key == "inputCctfDecoding") snap_bool(&p.input_cctf_decoding, b.input_cctf_decoding, io_value, strength, field);
                else if (io_key == "outputCctfEncoding") snap_bool(&p.output_cctf_encoding, b.output_cctf_encoding, io_value, strength, field);
                else if (io_key == "scanFilm") snap_bool(&p.scan_film, b.scan_film, io_value, strength, field);
                else if (io_key == "crop") snap_bool(&p.crop, b.crop, io_value, strength, field);
                else if (io_key == "upscaleFactor") blend_float(&p.upscale_factor, b.upscale_factor, io_value, strength, field);
                else if (io_key == "cropCenter") blend_float_array(p.crop_center, b.crop_center, io_value, 2, strength, field);
                else if (io_key == "cropSize") blend_float_array(p.crop_size, b.crop_size, io_value, 2, strength, field);
                else if (io_key == "outputColorSpace") {
                    const spk_color_space target = output_color_space(io_value, field);
                    p.output_color_space = strength > 0.0f ? target : b.output_color_space;
                } else fail(path, "unknown key '" + io_key + "'");
            }
        } else if (key == "settings") {
            expect_object(value, path);
            for (const auto& st_entry : value.object) {
                const std::string& st_key = st_entry.first;
                const Value& st_value = *st_entry.second;
                const std::string field = path + "." + st_key;
                if (st_key == "rgbToRawMethod") {
                    const spk_rgb2raw target = rgb_to_raw_method(st_value, field);
                    p.rgb_to_raw_method = strength > 0.0f ? target : b.rgb_to_raw_method;
                } else if (st_key == "applyHanatos2025AdaptationWindow") snap_bool(&p.apply_hanatos_window, b.apply_hanatos_window, st_value, strength, field);
                else if (st_key == "applyHanatos2025AdaptationSurface") snap_bool(&p.apply_hanatos_surface, b.apply_hanatos_surface, st_value, strength, field);
                else if (st_key == "spectralGaussianBlur") blend_float(&p.spectral_gaussian_blur, b.spectral_gaussian_blur, st_value, strength, field);
                else fail(path, "unknown key '" + st_key + "'");
            }
        } else if (key == "camera") {
            expect_object(value, path);
            for (const auto& c_entry : value.object) {
                const std::string& c_key = c_entry.first;
                const Value& c_value = *c_entry.second;
                const std::string field = path + "." + c_key;
                if (c_key == "exposureCompensationEv") blend_float(&p.exposure_compensation_ev, b.exposure_compensation_ev, c_value, strength, field);
                else if (c_key == "autoExposure") snap_bool(&p.auto_exposure, b.auto_exposure, c_value, strength, field);
                else if (c_key == "autoExposureMethod") {
                    const std::string target = read_string(c_value, field);
                    validate_ae_method(target, field);
                    if (strength > 0.0f) out->auto_exposure_method = target;
                } else if (c_key == "filmFormatMm") blend_float(&p.film_format_mm, b.film_format_mm, c_value, strength, field);
                else if (c_key == "lensBlurUm") blend_float(&p.lens_blur_um, b.lens_blur_um, c_value, strength, field);
                else if (c_key == "filterUv") blend_float_array(p.camera_filter_uv, b.camera_filter_uv, c_value, 3, strength, field);
                else if (c_key == "filterIr") blend_float_array(p.camera_filter_ir, b.camera_filter_ir, c_value, 3, strength, field);
                else if (c_key == "diffusionFilter") apply_diffusion(ctx, c_value, field, true);
                else fail(path, "unknown key '" + c_key + "'");
            }
        } else if (key == "enlarger") {
            expect_object(value, path);
            for (const auto& e_entry : value.object) {
                const std::string& e_key = e_entry.first;
                const Value& e_value = *e_entry.second;
                const std::string field = path + "." + e_key;
                if (e_key == "illuminant") apply_string(&out->enlarger_illuminant, e_value, strength, field);
                else if (e_key == "printExposure") blend_float(&p.print_exposure, b.print_exposure, e_value, strength, field);
                else if (e_key == "printExposureCompensation") snap_bool(&p.print_exposure_compensation, b.print_exposure_compensation, e_value, strength, field);
                else if (e_key == "yFilterShift") blend_float(&p.y_filter_shift, b.y_filter_shift, e_value, strength, field);
                else if (e_key == "mFilterShift") blend_float(&p.m_filter_shift, b.m_filter_shift, e_value, strength, field);
                else if (e_key == "lensBlur") blend_float(&p.enlarger_lens_blur, b.enlarger_lens_blur, e_value, strength, field);
                else if (e_key == "preflashExposure") blend_float(&p.preflash_exposure, b.preflash_exposure, e_value, strength, field);
                else if (e_key == "preflashYFilterShift") blend_float(&p.preflash_y_filter_shift, b.preflash_y_filter_shift, e_value, strength, field);
                else if (e_key == "preflashMFilterShift") blend_float(&p.preflash_m_filter_shift, b.preflash_m_filter_shift, e_value, strength, field);
                else if (e_key == "diffusionFilter") apply_diffusion(ctx, e_value, field, false);
                else fail(path, "unknown key '" + e_key + "'");
            }
        } else if (key == "scanner") {
            expect_object(value, path);
            for (const auto& s_entry : value.object) {
                const std::string& s_key = s_entry.first;
                const Value& s_value = *s_entry.second;
                const std::string field = path + "." + s_key;
                if (s_key == "lensBlur") blend_float(&p.scanner_lens_blur, b.scanner_lens_blur, s_value, strength, field);
                else if (s_key == "whiteCorrection") snap_bool(&p.scanner_white_correction, b.scanner_white_correction, s_value, strength, field);
                else if (s_key == "whiteLevel") blend_float(&p.scanner_white_level, b.scanner_white_level, s_value, strength, field);
                else if (s_key == "blackCorrection") snap_bool(&p.scanner_black_correction, b.scanner_black_correction, s_value, strength, field);
                else if (s_key == "blackLevel") blend_float(&p.scanner_black_level, b.scanner_black_level, s_value, strength, field);
                else if (s_key == "unsharpMask") blend_float_array(p.scanner_unsharp, b.scanner_unsharp, s_value, 2, strength, field);
                else fail(path, "unknown key '" + s_key + "'");
            }
        } else if (key == "filmRender") {
            expect_object(value, path);
            for (const auto& fr_entry : value.object) {
                const std::string& fr_key = fr_entry.first;
                const Value& fr_value = *fr_entry.second;
                const std::string field = path + "." + fr_key;
                if (fr_key == "densityCurveGamma") blend_float(&p.density_curve_gamma, b.density_curve_gamma, fr_value, strength, field);
                else if (fr_key == "grain") apply_grain(ctx, fr_value, field);
                else if (fr_key == "halation") apply_halation(ctx, fr_value, field);
                else if (fr_key == "dirCouplers") apply_dir_couplers(ctx, fr_value, field);
                else if (fr_key == "glare") apply_glare(ctx, fr_value, field);
                else fail(path, "unknown key '" + fr_key + "'");
            }
        } else if (key == "printRender") {
            expect_object(value, path);
            for (const auto& pr_entry : value.object) {
                const std::string& pr_key = pr_entry.first;
                const Value& pr_value = *pr_entry.second;
                const std::string field = path + "." + pr_key;
                if (pr_key == "densityCurveGamma") blend_float(&p.print_density_curve_gamma, b.print_density_curve_gamma, pr_value, strength, field);
                else if (pr_key == "glare") apply_glare(ctx, pr_value, field);
                else fail(path, "unknown key '" + pr_key + "'");
            }
        } else if (key == "glare") {
            apply_glare(ctx, value, path);
        } else if (key == "toneCurve") {
            apply_tone_curve(ctx, value, path);
        } else {
            fail("preset '" + preset_id + "' params", "unknown key '" + key + "'");
        }
    }
}

std::string join_path(const std::string& dir, const std::string& leaf) {
    if (dir.empty()) return leaf;
    if (dir.back() == '/') return dir + leaf;
    return dir + "/" + leaf;
}

std::string read_file(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) throw std::runtime_error("cannot open " + path);
    std::ostringstream buffer;
    buffer << in.rdbuf();
    if (!in.good() && !in.eof()) throw std::runtime_error("cannot read " + path);
    return buffer.str();
}

}  // namespace

RecipeParams::RecipeParams() {
    spk_default_params(&params);
    enlarger_illuminant = params.enlarger_illuminant ? params.enlarger_illuminant : "TH-KG3";
    input_color_space = params.input_color_space ? params.input_color_space : "ProPhoto RGB";
    bind();
}

void RecipeParams::bind() {
    params.film_profile = film_profile.empty() ? nullptr : film_profile.c_str();
    params.print_profile = print_profile.empty() ? nullptr : print_profile.c_str();
    params.auto_exposure_method =
        auto_exposure_method.empty() ? nullptr : auto_exposure_method.c_str();
    params.enlarger_illuminant =
        enlarger_illuminant.empty() ? nullptr : enlarger_illuminant.c_str();
    params.input_color_space =
        input_color_space.empty() ? nullptr : input_color_space.c_str();
}

bool parse_recipe(const Value& value, Recipe* out, std::string* error) {
    try {
        expect_object(value, "recipe");
        bool saw_preset = false;
        for (const auto& entry : value.object) {
            const std::string& key = entry.first;
            const Value& field = *entry.second;
            const std::string path = "recipe." + key;
            if (key == "presetId") {
                out->preset_id = read_string(field, path);
                if (out->preset_id.empty()) fail(path, "presetId must not be empty");
                saw_preset = true;
            } else if (key == "strength") {
                out->strength = read_float(field, path);
                if (out->strength < 0.0f || out->strength > 1.0f)
                    fail(path, "strength must be in [0,1]");
            } else if (key == "exposureEv") {
                out->exposure_ev = read_float(field, path);
                if (out->exposure_ev < -12.0f || out->exposure_ev > 12.0f)
                    fail(path, "exposureEv must be in [-12,12]");
                out->has_exposure_ev = true;
            } else if (key == "grainScale") {
                out->grain_scale = read_float(field, path);
                if (out->grain_scale < 0.0f || out->grain_scale > 4.0f)
                    fail(path, "grainScale must be in [0,4]");
            } else if (key == "halationScale") {
                out->halation_scale = read_float(field, path);
                if (out->halation_scale < 0.0f || out->halation_scale > 4.0f)
                    fail(path, "halationScale must be in [0,4]");
            } else if (key == "filmProfile") {
                out->film_profile = read_string(field, path);
                if (out->film_profile.empty())
                    fail(path, "filmProfile must not be empty");
                out->has_film_profile = true;
            } else if (key == "printProfile") {
                out->print_profile = read_string(field, path);
                if (out->print_profile.empty())
                    fail(path, "printProfile must not be empty");
                out->has_print_profile = true;
            } else {
                fail("recipe", "unknown key '" + key + "'");
            }
        }
        if (!saw_preset) fail("recipe", "missing required key 'presetId'");
        return true;
    } catch (const std::exception& e) {
        if (error) *error = e.what();
        return false;
    }
}

bool PresetLibrary::load(const std::string& asset_dir, std::string* error) {
    try {
        const std::string path = join_path(asset_dir, "presets.json");
        const spk::json::ValuePtr root = spk::json::parse(read_file(path));
        if (!root->is_object()) throw std::runtime_error(path + ": root is not an object");
        const Value& presets = object_member(*root, "presets", path);
        if (!presets.is_array())
            throw std::runtime_error(path + ": 'presets' is not an array");
        presets_.clear();
        ids_.clear();
        for (size_t i = 0; i < presets.array.size(); ++i) {
            const Value& entry = presets[i];
            const std::string entry_path = path + ".presets[" + std::to_string(i) + "]";
            expect_object(entry, entry_path);
            const Value& id_value = object_member(entry, "id", entry_path);
            const std::string id = read_string(id_value, entry_path + ".id");
            if (id.empty()) fail(entry_path + ".id", "preset id must not be empty");
            if (presets_.count(id)) fail(entry_path + ".id", "duplicate preset id '" + id + "'");
            const Value* params = optional_member(entry, "params");
            if (!params) fail(entry_path, "missing key 'params'");
            expect_object(*params, entry_path + ".params");
            presets_.emplace(id, entry.object.at("params"));
            ids_.push_back(id);
        }
        if (presets_.empty())
            throw std::runtime_error(path + ": no presets");
        return true;
    } catch (const std::exception& e) {
        if (error) *error = "presets.json: " + std::string(e.what());
        return false;
    }
}

bool PresetLibrary::has(const std::string& id) const {
    return presets_.find(id) != presets_.end();
}

bool PresetLibrary::build(const Recipe& recipe, RecipeParams* out,
                          std::string* error) const {
    try {
        RecipeParams result;
        if (recipe.preset_id != "__default__") {
            auto it = presets_.find(recipe.preset_id);
            if (it == presets_.end())
                throw std::runtime_error("unknown presetId '" + recipe.preset_id + "'");
            apply_preset_object(*it->second, recipe.preset_id, recipe.strength,
                                &result);
        }

        if (recipe.has_film_profile) result.film_profile = recipe.film_profile;
        if (recipe.has_print_profile) result.print_profile = recipe.print_profile;

        if (result.film_profile.empty())
            throw std::runtime_error("recipe resolves no filmProfile");
        if (result.params.scan_film == 0 && result.print_profile.empty())
            throw std::runtime_error("recipe resolves no printProfile (print route)");
        if (result.enlarger_illuminant != "TH-KG3")
            throw std::runtime_error("unsupported enlarger illuminant '" +
                                     result.enlarger_illuminant + "'");
        if (result.input_color_space != "ProPhoto RGB")
            throw std::runtime_error("unsupported inputColorSpace '" +
                                     result.input_color_space + "'");

        if (recipe.has_exposure_ev)
            result.params.exposure_compensation_ev = recipe.exposure_ev;

        /* Real engine meaning, not a UI opacity:
         *   grainScale multiplies grain.agx_particle_area_um2 (the physical
         *   silver-halide particle area). Larger particles => coarser grain;
         *   zero is explicit "no grain".
         *   halationScale multiplies both halation amount leaves (in-emulsion
         *   scatter + back-of-base halation), leaving their spatial sigmas and
         *   the profile-baked halationStrength untouched. */
        if (recipe.grain_scale == 0.0f) {
            result.params.grain_active = 0;
        } else if (recipe.grain_scale != 1.0f) {
            result.params.grain_particle_area_um2 *= recipe.grain_scale;
        }
        if (recipe.halation_scale != 1.0f) {
            result.params.halation_scatter_amount *= recipe.halation_scale;
            result.params.halation_halation_amount *= recipe.halation_scale;
        }

        if (!std::isfinite(result.params.grain_particle_area_um2) ||
            result.params.grain_particle_area_um2 < 0.0f)
            throw std::runtime_error("grainScale produces an invalid particle area");
        if (!std::isfinite(result.params.halation_scatter_amount) ||
            !std::isfinite(result.params.halation_halation_amount))
            throw std::runtime_error("halationScale produces a non-finite amount");

        result.bind();
        *out = std::move(result);
        /* Moving std::string can move (or for SSO copy) the character storage,
         * so the const char* pointers inside the moved spk_params are stale.
         * Rebind them against the destination's own strings. */
        out->bind();
        return true;
    } catch (const std::exception& e) {
        if (error) *error = e.what();
        return false;
    }
}

}  // namespace spkdesk
