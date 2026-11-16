/*
 * Spektrafilm desktop worker — resident NDJSON engine host.
 * GPLv3. Film modeling powered by spektrafilm.
 *
 * Protocol (one JSON object per line, one response per request):
 *   {"op":"open","path":"in.jpg","preview":"source.png","probe":[x,y]}
 *   {"op":"preview","recipe":{...},"out":"preview.png"}
 *   {"op":"export","recipe":{...},"out":"out.jpg","quality":92}
 *   {"op":"reset"}
 *   {"op":"close"}
 *
 * All request paths are relative to the session working directory handed to
 * argv[2]. Absolute paths, "..", and paths whose canonical target escapes that
 * directory are rejected. Outputs are written to a sibling temp file and
 * renamed into place, so a failed render never leaves a plausible output file.
 */
#include "worker.h"

#include <chrono>
#include <cctype>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <system_error>
#include <vector>

#include <sys/stat.h>
#include <unistd.h>

#include "image_io.h"
#include "params_json.h"
#include "spektra.h"

namespace spkdesk {
namespace {

namespace fs = std::filesystem;
using spk::json::Value;
using Clock = std::chrono::steady_clock;

constexpr size_t kMaxRequestBytes = 1u << 20;  /* json_min's own input ceiling */
constexpr int kPreviewMaxSize = 1024;

constexpr float kSrgbToProPhoto[9] = {
    0.5290825f, 0.3303437f, 0.1405738f,
    0.0982640f, 0.8734031f, 0.0283329f,
    0.0167029f, 0.1176946f, 0.8656026f,
};

float srgb_to_linear(float c) {
    if (c <= 0.04045f) return c / 12.92f;
    return static_cast<float>(std::pow((c + 0.055) / 1.055, 2.4));
}

void rgba8_to_linear_prophoto(const Rgba8Image& image,
                              std::vector<float>* out) {
    float lut[256];
    for (int i = 0; i < 256; ++i) lut[i] = srgb_to_linear(i / 255.0f);
    const size_t count = static_cast<size_t>(image.width) * image.height;
    out->resize(count * 3);
    for (size_t i = 0; i < count; ++i) {
        const uint8_t* px = &image.pixels[i * 4];
        const float r = lut[px[0]];
        const float g = lut[px[1]];
        const float b = lut[px[2]];
        (*out)[i * 3 + 0] =
            kSrgbToProPhoto[0] * r + kSrgbToProPhoto[1] * g + kSrgbToProPhoto[2] * b;
        (*out)[i * 3 + 1] =
            kSrgbToProPhoto[3] * r + kSrgbToProPhoto[4] * g + kSrgbToProPhoto[5] * b;
        (*out)[i * 3 + 2] =
            kSrgbToProPhoto[6] * r + kSrgbToProPhoto[7] * g + kSrgbToProPhoto[8] * b;
    }
}

std::string json_quote(const std::string& text) {
    std::string out;
    out.reserve(text.size() + 8);
    out.push_back('"');
    for (unsigned char c : text) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char escaped[7];
                    std::snprintf(escaped, sizeof(escaped), "\\u%04x", c);
                    out += escaped;
                } else {
                    out.push_back(static_cast<char>(c));
                }
        }
    }
    out.push_back('"');
    return out;
}

std::string error_json(const std::string& message) {
    return "{\"ok\":false,\"error\":" + json_quote(message) + "}";
}

std::string number_json(double value) {
    std::ostringstream out;
    out << std::setprecision(9) << value;
    return out.str();
}

std::string status_message(spk_status status) {
    std::string message = spk_status_str(status);
    const char* detail = spk_last_error_message();
    if (detail && *detail) {
        message += ": ";
        message += detail;
    }
    return message;
}

const Value& require_member(const Value& object, const std::string& key,
                            const std::string& where) {
    if (!object.is_object())
        throw std::runtime_error(where + ": expected an object");
    auto it = object.object.find(key);
    if (it == object.object.end())
        throw std::runtime_error(where + ": missing key '" + key + "'");
    return *it->second;
}

std::string require_string(const Value& value, const std::string& where) {
    if (!value.is_string())
        throw std::runtime_error(where + ": expected a string");
    return value.str;
}


double require_number(const Value& value, const std::string& where) {
    if (!value.is_number() || value.is_null_number)
        throw std::runtime_error(where + ": expected a finite number");
    const double number = value.as_number();
    if (!std::isfinite(number))
        throw std::runtime_error(where + ": number is not finite");
    return number;
}

void reject_unknown_keys(const Value& object,
                         std::initializer_list<const char*> allowed,
                         const std::string& where) {
    for (const auto& entry : object.object) {
        if (entry.first == "op") continue;
        bool known = false;
        for (const char* key : allowed)
            if (entry.first == key) {
                known = true;
                break;
            }
        if (!known)
            throw std::runtime_error(where + ": unknown key '" + entry.first + "'");
    }
}

std::string lower_ascii(std::string text) {
    for (char& c : text)
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return text;
}

bool path_is_within(const fs::path& root, const fs::path& path) {
    std::error_code ec;
    const fs::path relative = fs::relative(path, root, ec);
    if (ec) return false;
    for (const auto& component : relative)
        if (component == "..") return false;
    return true;
}

/* Reject absolute inputs, NUL bytes, and any literal ".." component before the
 * filesystem ever sees the path. */
fs::path safe_relative_path(const std::string& user,
                            const std::string& where) {
    if (user.empty())
        throw std::runtime_error(where + ": path must not be empty");
    if (user.find('\0') != std::string::npos)
        throw std::runtime_error(where + ": path contains a NUL byte");
    const fs::path path(user);
    if (path.is_absolute() || path.has_root_name() || path.has_root_directory())
        throw std::runtime_error(where + ": absolute paths are not allowed");
    for (const auto& component : path) {
        if (component == "..")
            throw std::runtime_error(where + ": '..' components are not allowed");
    }
    return path;
}

std::string make_temp_sibling(const std::string& final_path,
                              std::string* error) {
    const fs::path final(final_path);
    const fs::path parent = final.parent_path();
    std::string pattern = (parent / ".spk-tmp-XXXXXX").string();
    std::vector<char> writable(pattern.begin(), pattern.end());
    writable.push_back('\0');
    const int fd = ::mkstemp(writable.data());
    if (fd < 0) {
        if (error) *error = "cannot create temporary output: " +
                            std::string(std::strerror(errno));
        return {};
    }
    ::close(fd);
    return std::string(writable.data());
}

/* Write to a sibling temp file, then rename. The destination is only reached
 * after the writer reports success; every failure removes the temp file. */
template <typename WriteFn>
bool publish_atomic(const std::string& final_path, WriteFn write,
                    std::string* error) {
    const std::string temp = make_temp_sibling(final_path, error);
    if (temp.empty()) return false;
    bool wrote = false;
    try {
        wrote = write(temp);
    } catch (...) {
        ::unlink(temp.c_str());
        throw;
    }
    if (!wrote) {
        ::unlink(temp.c_str());
        return false;
    }
    ::chmod(temp.c_str(), 0644);
    if (::rename(temp.c_str(), final_path.c_str()) != 0) {
        const std::string message =
            "cannot publish " + final_path + ": " + std::strerror(errno);
        ::unlink(temp.c_str());
        if (error) *error = message;
        return false;
    }
    return true;
}

ImageFormat format_from_extension(const fs::path& path, bool preview,
                                  std::string* error) {
    const std::string extension = lower_ascii(path.extension().string());
    if (preview) {
        if (extension != ".png") {
            if (error) *error = "preview output must be a .png path";
            return ImageFormat::Png;
        }
        return ImageFormat::Png;
    }
    if (extension == ".png") return ImageFormat::Png;
    if (extension == ".jpg" || extension == ".jpeg") return ImageFormat::Jpeg;
    if (error)
        *error = "export output must end in .png, .jpg, or .jpeg (got '" +
                 extension + "')";
    return ImageFormat::Png;
}

}  // namespace

struct Worker::Impl {
    std::string asset_dir;
    std::string session_dir;
    std::string fatal_error;
    PresetLibrary library;
    std::set<std::string> profile_ids;
    spk_engine* engine = nullptr;
    std::vector<float> linear;
    spk_image image{};
    std::string image_path;
    bool closed = false;

    Impl(std::string asset, std::string session)
        : asset_dir(std::move(asset)), session_dir(std::move(session)) {
        try {
            std::error_code ec;
            asset_dir = fs::canonical(asset_dir, ec).string();
            if (ec || !fs::is_directory(asset_dir))
                throw std::runtime_error("asset directory does not exist: " + asset_dir);
            session_dir = fs::canonical(session_dir, ec).string();
            if (ec || !fs::is_directory(session_dir))
                throw std::runtime_error("session directory does not exist: " +
                                         session_dir);
        } catch (const std::exception& e) {
            fatal_error = e.what();
            return;
        }

        std::string error;
        if (!library.load(asset_dir, &error)) {
            fatal_error = error;
            return;
        }
        const spk_status status = spk_engine_create(asset_dir.c_str(), &engine);
        if (status != SPK_OK || !engine) {
            fatal_error = "cannot create engine: " + status_message(status);
            engine = nullptr;
            return;
        }

        size_t needed = 0;
        spk_status list_status =
            spk_engine_list_profiles(engine, nullptr, 0, &needed);
        if (list_status != SPK_OK || needed == 0) {
            fatal_error = "cannot list profiles: " + status_message(list_status);
            return;
        }
        std::vector<char> buffer(needed);
        list_status =
            spk_engine_list_profiles(engine, buffer.data(), buffer.size(), &needed);
        if (list_status != SPK_OK) {
            fatal_error = "cannot read profile catalog: " +
                          status_message(list_status);
            return;
        }
        std::string current;
        for (size_t i = 0; i < buffer.size() && buffer[i] != '\0'; ++i) {
            if (buffer[i] == '\n') {
                if (!current.empty()) profile_ids.insert(current);
                current.clear();
            } else {
                current.push_back(buffer[i]);
            }
        }
        if (!current.empty()) profile_ids.insert(current);
        if (profile_ids.empty()) fatal_error = "empty profile catalog";
    }

    ~Impl() {
        release_image();
        if (engine) {
            spk_engine_destroy(engine);
            engine = nullptr;
        }
    }

    void release_image() {
        /* image.data points into the std::vector below (the engine only reads
         * it); it must not go through spk_image_free, which expects an
         * engine-malloc'd output buffer. */
        image = {};
        linear.clear();
        image_path.clear();
    }

    bool resolve_input(const std::string& user, std::string* out,
                       std::string* error) const {
        try {
            const fs::path relative = safe_relative_path(user, "input path");
            const fs::path candidate = fs::path(session_dir) / relative;
            std::error_code ec;
            const fs::path canonical = fs::canonical(candidate, ec);
            if (ec)
                throw std::runtime_error("input path does not exist: " + user);
            if (!path_is_within(session_dir, canonical))
                throw std::runtime_error("input path escapes the session directory");
            if (!fs::is_regular_file(canonical))
                throw std::runtime_error("input path is not a regular file");
            *out = canonical.string();
            return true;
        } catch (const std::exception& e) {
            if (error) *error = e.what();
            return false;
        }
    }

    bool resolve_output(const std::string& user, const std::string& guard_input,
                        std::string* out, std::string* error) const {
        try {
            const fs::path relative = safe_relative_path(user, "output path");
            const fs::path candidate = fs::path(session_dir) / relative;
            fs::path parent = candidate.parent_path();
            if (parent.empty()) parent = session_dir;
            std::error_code ec;
            const fs::path canonical_parent = fs::canonical(parent, ec);
            if (ec)
                throw std::runtime_error("output directory does not exist: " +
                                         parent.string());
            if (!path_is_within(session_dir, canonical_parent))
                throw std::runtime_error("output path escapes the session directory");
            if (!fs::is_directory(canonical_parent))
                throw std::runtime_error("output parent is not a directory");
            const fs::path filename = candidate.filename();
            if (filename.empty() || filename == "." || filename == "..")
                throw std::runtime_error("output path has no file name");
            const fs::path final = canonical_parent / filename;
            if (!path_is_within(session_dir, final))
                throw std::runtime_error("output path escapes the session directory");

            const fs::file_status status = fs::symlink_status(final, ec);
            if (!ec && fs::is_symlink(status))
                throw std::runtime_error("output path is a symlink");
            if (!ec && fs::exists(status) && !fs::is_regular_file(status))
                throw std::runtime_error("output path is not a regular file");
            if (!guard_input.empty()) {
                if (final == fs::path(guard_input))
                    throw std::runtime_error("output would overwrite the open input");
                if (fs::exists(final)) {
                    std::error_code equivalent_error;
                    if (fs::equivalent(final, guard_input, equivalent_error) &&
                        !equivalent_error)
                        throw std::runtime_error(
                            "output would overwrite the open input");
                }
            }
            *out = final.string();
            return true;
        } catch (const std::exception& e) {
            if (error) *error = e.what();
            return false;
        }
    }

    bool profile_available(const std::string& id) const {
        return !id.empty() && profile_ids.count(id) != 0;
    }

    std::string handle_line(const std::string& line) {
        try {
            const spk::json::ValuePtr request = spk::json::parse(line);
            return handle_request(*request);
        } catch (const std::exception& e) {
            return error_json(e.what());
        }
    }

    std::string handle_request(const Value& request) {
        if (!request.is_object())
            throw std::runtime_error("request must be a JSON object");
        const std::string op =
            require_string(require_member(request, "op", "request"), "request.op");
        if (op == "open") return op_open(request);
        if (op == "preview") return op_render(request, true);
        if (op == "export") return op_render(request, false);
        if (op == "reset") return op_reset(request);
        if (op == "close") return op_close(request);
        throw std::runtime_error("unknown op '" + op + "'");
    }

    std::string op_open(const Value& request) {
        reject_unknown_keys(request, {"path", "preview", "out", "probe"}, "open");
        const std::string user_path =
            require_string(require_member(request, "path", "open"), "open.path");
        const bool has_preview_key = request.has("preview");
        const bool has_out_key = request.has("out");
        if (has_preview_key && has_out_key)
            throw std::runtime_error("open: provide preview or out, not both");
        if (!has_preview_key && !has_out_key)
            throw std::runtime_error("open: missing key 'preview'");
        const std::string preview_user = require_string(
            has_preview_key ? require_member(request, "preview", "open")
                            : require_member(request, "out", "open"),
            "open.preview");

        bool has_probe = false;
        int probe_x = 0;
        int probe_y = 0;
        auto probe_it = request.object.find("probe");
        if (probe_it != request.object.end()) {
            const Value& probe = *probe_it->second;
            if (!probe.is_array() || probe.array.size() != 2)
                throw std::runtime_error("open.probe: expected [x,y]");
            const double x = require_number(probe[0], "open.probe[0]");
            const double y = require_number(probe[1], "open.probe[1]");
            if (std::floor(x) != x || std::floor(y) != y)
                throw std::runtime_error("open.probe: coordinates must be integers");
            if (x < -2147483648.0 || x > 2147483647.0 ||
                y < -2147483648.0 || y > 2147483647.0)
                throw std::runtime_error("open.probe: coordinate out of range");
            probe_x = static_cast<int>(x);
            probe_y = static_cast<int>(y);
            has_probe = true;
        }

        std::string input_path;
        std::string preview_path;
        std::string error;
        if (!resolve_input(user_path, &input_path, &error))
            throw std::runtime_error(error);
        if (!resolve_output(preview_user, input_path, &preview_path, &error))
            throw std::runtime_error(error);
        if (lower_ascii(fs::path(preview_path).extension().string()) != ".png")
            throw std::runtime_error("open.preview must be a .png path");

        Rgba8Image rgba;
        int orientation = 1;
        if (!decode_srgb(input_path, 0, &rgba, &orientation, &error))
            throw std::runtime_error(error);
        if (has_probe &&
            (probe_x < 0 || probe_y < 0 || probe_x >= rgba.width ||
             probe_y >= rgba.height))
            throw std::runtime_error("open.probe: coordinate outside the image");

        std::vector<float> decoded_linear;
        rgba8_to_linear_prophoto(rgba, &decoded_linear);

        int preview_w = 0;
        int preview_h = 0;
        const bool wrote = publish_atomic(
            preview_path,
            [&](const std::string& temp) {
                return write_rgba8(temp, rgba, ImageFormat::Png, kPreviewMaxSize,
                                   92, &preview_w, &preview_h, &error);
            },
            &error);
        if (!wrote)
            throw std::runtime_error(error.empty() ? "preview write failed" : error);

        release_image();
        linear.swap(decoded_linear);
        image.data = linear.data();
        image.width = rgba.width;
        image.height = rgba.height;
        image.color_space = SPK_CS_PROPHOTO;
        image_path = input_path;

        std::ostringstream out;
        out << "{\"ok\":true,\"width\":" << rgba.width
            << ",\"height\":" << rgba.height
            << ",\"preview\":" << json_quote(preview_user)
            << ",\"previewWidth\":" << preview_w
            << ",\"previewHeight\":" << preview_h
            << ",\"orientation\":" << orientation;
        if (has_probe) {
            const size_t index =
                (static_cast<size_t>(probe_y) * rgba.width + probe_x) * 3;
            out << ",\"probe\":[" << number_json(linear[index]) << ","
                << number_json(linear[index + 1]) << ","
                << number_json(linear[index + 2]) << "]";
        }
        out << "}";
        return out.str();
    }

    std::string op_render(const Value& request, bool preview) {
        const std::string op = preview ? "preview" : "export";
        if (preview) {
            reject_unknown_keys(request, {"recipe", "out", "output"}, op);
        } else {
            reject_unknown_keys(request, {"recipe", "out", "output", "quality"}, op);
        }
        if (!image.data)
            throw std::runtime_error("no open image; send op=open first");

        const Value& recipe_value =
            require_member(request, "recipe", op);
        Recipe recipe;
        std::string error;
        if (!parse_recipe(recipe_value, &recipe, &error))
            throw std::runtime_error(error);

        RecipeParams resolved;
        if (!library.build(recipe, &resolved, &error))
            throw std::runtime_error(error);
        if (!profile_available(resolved.film_profile))
            throw std::runtime_error("unknown film profile '" +
                                     resolved.film_profile + "'");
        if (!resolved.print_profile.empty() &&
            !profile_available(resolved.print_profile))
            throw std::runtime_error("unknown print profile '" +
                                     resolved.print_profile + "'");

        const bool has_out_key = request.has("out");
        const bool has_output_key = request.has("output");
        if (has_out_key && has_output_key)
            throw std::runtime_error(op + ": provide out or output, not both");
        if (!has_out_key && !has_output_key)
            throw std::runtime_error(op + ": missing key 'out'");
        const std::string out_user = require_string(
            has_out_key ? require_member(request, "out", op)
                        : require_member(request, "output", op),
            op + ".out");
        int quality = 92;
        auto quality_it = request.object.find("quality");
        if (quality_it != request.object.end()) {
            const double value = require_number(*quality_it->second, op + ".quality");
            if (std::floor(value) != value || value < 1 || value > 100)
                throw std::runtime_error(op + ".quality: expected integer in [1,100]");
            quality = static_cast<int>(value);
        }

        std::string output_path;
        error.clear();
        if (!resolve_output(out_user, image_path, &output_path, &error))
            throw std::runtime_error(error);
        error.clear();
        const ImageFormat format =
            format_from_extension(output_path, preview, &error);
        if (!error.empty()) throw std::runtime_error(error);

        /* GPU paths are explicitly disabled for this headless CPU worker. */
        resolved.params.preview_max_size = kPreviewMaxSize;
        resolved.params.gpu_preview = 0;
        resolved.params.gpu_export = 0;
        resolved.params.allow_gpu_scan = 0;
        /* open() already applied the inverse sRGB CCTF: the engine receives
         * linear ProPhoto, so its input-decode latch must stay off or the
         * metering path would apply gamma a second time. */
        resolved.params.input_cctf_decoding = 0;

        const Clock::time_point started = Clock::now();
        spk_image result{};
        const spk_status status =
            preview ? spk_simulate_preview(engine, &image, &resolved.params, &result)
                    : spk_simulate(engine, &image, &resolved.params, &result);
        if (status != SPK_OK) {
            spk_image_free(&result);
            throw std::runtime_error("render failed: " + status_message(status));
        }
        const int result_width = result.width;
        const int result_height = result.height;

        const bool wrote = publish_atomic(
            output_path,
            [&](const std::string& temp) {
                return write_engine_rgb(
                    temp, result.data, result.width, result.height,
                    resolved.params.output_color_space,
                    resolved.params.output_cctf_encoding != 0, asset_dir, format,
                    quality, &error);
            },
            &error);
        spk_image_free(&result);
        if (!wrote)
            throw std::runtime_error(error.empty() ? "output write failed" : error);

        const long long elapsed_ms =
            std::chrono::duration_cast<std::chrono::milliseconds>(
                Clock::now() - started)
                .count();
        std::error_code size_error;
        const auto bytes = fs::file_size(output_path, size_error);
        std::ostringstream out;
        out << "{\"ok\":true,\"path\":" << json_quote(out_user)
            << ",\"format\":" << json_quote(format == ImageFormat::Png ? "png" : "jpeg")
            << ",\"width\":" << result_width
            << ",\"height\":" << result_height
            << ",\"bytes\":" << (size_error ? 0 : static_cast<long long>(bytes))
            << ",\"ms\":" << elapsed_ms << "}";
        return out.str();
    }

    std::string op_reset(const Value& request) {
        reject_unknown_keys(request, {}, "reset");
        release_image();
        return "{\"ok\":true}";
    }

    std::string op_close(const Value& request) {
        reject_unknown_keys(request, {}, "close");
        release_image();
        if (engine) {
            spk_engine_destroy(engine);
            engine = nullptr;
        }
        closed = true;
        return "{\"ok\":true}";
    }

    int serve() {
        if (!fatal_error.empty()) {
            std::cerr << "spektra_worker: " << fatal_error << '\n';
            return 2;
        }
        std::string line;
        while (std::getline(std::cin, line)) {
            if (line.size() > kMaxRequestBytes) {
                std::cout << error_json("request exceeds 1 MiB") << '\n';
                std::cout.flush();
                continue;
            }
            if (line.find_first_not_of(" \t\r\n") == std::string::npos) continue;
            std::cout << handle_line(line) << '\n';
            std::cout.flush();
            if (!std::cout) return 1;
            if (closed) break;
        }
        return 0;
    }
};

Worker::Worker(std::string asset_dir, std::string session_dir)
    : impl_(std::make_unique<Impl>(std::move(asset_dir),
                                   std::move(session_dir))) {}

Worker::~Worker() = default;

int Worker::serve() { return impl_->serve(); }

}  // namespace spkdesk
