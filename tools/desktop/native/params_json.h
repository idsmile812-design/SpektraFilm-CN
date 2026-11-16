/*
 * Spektrafilm desktop worker — recipe and built-in preset parameter mapping.
 * GPLv3. Film modeling powered by spektrafilm.
 *
 * This is host-side glue only: it owns no engine code. It reads the SAME
 * assets/spektra/presets.json used by the Android editor and maps its authored
 * sparse nested schema onto spk_params, exactly as BuiltInPresets.kt does.
 */
#ifndef SPK_DESKTOP_PARAMS_JSON_H
#define SPK_DESKTOP_PARAMS_JSON_H

#include <map>
#include <string>
#include <vector>

#include "profiles/json_min.h"
#include "spektra.h"

namespace spkdesk {

/* A validated worker recipe. All fields are optional except preset_id; the
 * worker distinguishes "absent" from "present with the neutral value". */
struct Recipe {
    std::string preset_id;          /* "__default__" means no preset overlay */
    float strength = 1.0f;          /* [0,1]: blend from spk_default_params */
    bool has_exposure_ev = false;
    float exposure_ev = 0.0f;       /* explicit camera EV override */
    float grain_scale = 1.0f;       /* [0,4]: multiplier on grain particle area */
    float halation_scale = 1.0f;    /* [0,4]: multiplier on scatter/halation amount */
    bool has_film_profile = false;
    bool has_print_profile = false;
    std::string film_profile;
    std::string print_profile;
};

/* spk_params plus the string storage its const char* fields point into. Keep
 * this object alive for the whole simulate() call. */
struct RecipeParams {
    spk_params params{};
    std::string film_profile;
    std::string print_profile;
    std::string auto_exposure_method;
    std::string enlarger_illuminant;
    std::string input_color_space;

    RecipeParams();

    /* Point params.*_profile / method / illuminant at the owned std::strings.
     * Called after every mapping/override pass. */
    void bind();
};

/* Parse the "recipe" object of a preview/export request. Unknown keys, wrong
 * JSON types, and out-of-range values are errors (never silently ignored). */
bool parse_recipe(const spk::json::Value& value, Recipe* out, std::string* error);

/* Loaded presets.json, keyed by preset id, plus the guarded mapping code. */
class PresetLibrary {
public:
    bool load(const std::string& asset_dir, std::string* error);

    bool has(const std::string& id) const;
    size_t size() const { return ids_.size(); }
    const std::vector<std::string>& ids() const { return ids_; }

    /* Build the final params for a recipe: defaults -> preset overlay (blended
     * by strength) -> explicit recipe overrides (profiles / EV / grain /
     * halation). Fails on an unknown preset or profile, an invalid authored
     * field, or a non-finite result. */
    bool build(const Recipe& recipe, RecipeParams* out, std::string* error) const;

private:
    std::map<std::string, spk::json::ValuePtr> presets_;
    std::vector<std::string> ids_;
};

}  // namespace spkdesk

#endif  // SPK_DESKTOP_PARAMS_JSON_H
