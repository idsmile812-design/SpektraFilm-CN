/*
 * Spektrafilm desktop worker — end-to-end host test.
 * GPLv3. Film modeling powered by spektrafilm.
 *
 * This drives the real .build/spektra_worker over its NDJSON protocol. It does
 * not call the engine directly: every render assertion below is produced by the
 * worker process, and outputs are re-decoded with ImageIO (independent of the
 * worker's writer). Small JPEG/PNG fixtures are generated with ImageIO too, so
 * no Python image library is involved.
 */
#include <ImageIO/ImageIO.h>
#include <CoreFoundation/CoreFoundation.h>
#include <CoreGraphics/CoreGraphics.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include <fcntl.h>
#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>

#include "image_io.h"
#include "profiles/json_min.h"
#include "spektra.h"

namespace fs = std::filesystem;
using spk::json::Value;
using Clock = std::chrono::steady_clock;

namespace {

int g_failures = 0;
std::string g_root;
std::string g_build;
std::string g_assets;
std::string g_session;
std::string g_outside;

void fail(const std::string& message) {
    ++g_failures;
    std::cerr << "FAIL: " << message << "\n";
}

#define CHECK(condition, message)                                            \
    do {                                                                     \
        if (!(condition)) {                                                  \
            fail(std::string(message) + " (line " + std::to_string(__LINE__) + ")"); \
        }                                                                    \
    } while (0)

long elapsed_ms(Clock::time_point start) {
    return static_cast<long>(
        std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start)
            .count());
}

std::string join(const std::string& a, const std::string& b) {
    return (fs::path(a) / b).string();
}

std::vector<uint8_t> read_bytes(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return {};
    in.seekg(0, std::ios::end);
    const std::streamoff size = in.tellg();
    if (size <= 0) return {};
    in.seekg(0, std::ios::beg);
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    in.read(reinterpret_cast<char*>(bytes.data()), size);
    return bytes;
}

uint64_t content_hash(const std::string& path) {
    const std::vector<uint8_t> bytes = read_bytes(path);
    uint64_t hash = 1469598103934665603ull;
    for (uint8_t byte : bytes) {
        hash ^= byte;
        hash *= 1099511628211ull;
    }
    return hash;
}

spkdesk::Rgba8Image make_image(int width, int height) {
    spkdesk::Rgba8Image image;
    image.width = width;
    image.height = height;
    image.pixels.assign(static_cast<size_t>(width) * height * 4, 0);
    return image;
}

void set_pixel(spkdesk::Rgba8Image& image, int x, int y, uint8_t r, uint8_t g,
               uint8_t b) {
    uint8_t* px = &image.pixels[(static_cast<size_t>(y) * image.width + x) * 4];
    px[0] = r;
    px[1] = g;
    px[2] = b;
    px[3] = 255;
}

spkdesk::Rgba8Image gradient_image(int width, int height) {
    spkdesk::Rgba8Image image = make_image(width, height);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const uint8_t r = static_cast<uint8_t>(x * 255 / std::max(1, width - 1));
            const uint8_t g = static_cast<uint8_t>(y * 255 / std::max(1, height - 1));
            const uint8_t b = static_cast<uint8_t>((x + y) * 255 /
                                                   std::max(1, width + height - 2));
            set_pixel(image, x, y, r, g, b);
        }
    }
    return image;
}

bool write_png(const std::string& path, const spkdesk::Rgba8Image& image,
               std::string* error) {
    return spkdesk::write_rgba8(path, image, spkdesk::ImageFormat::Png, 0, 92,
                                nullptr, nullptr, error);
}

bool write_jpeg(const std::string& path, const spkdesk::Rgba8Image& image,
                int quality, std::string* error) {
    return spkdesk::write_rgba8(path, image, spkdesk::ImageFormat::Jpeg, 0,
                                quality, nullptr, nullptr, error);
}

/* Write a PNG carrying an EXIF orientation tag. ImageIO stores the pixels
 * unrotated and the tag describes the display transform, exactly like a camera
 * JPEG; the worker must apply it. */
bool write_png_with_orientation(const std::string& path,
                                const spkdesk::Rgba8Image& image,
                                int orientation, std::string* error) {
    CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    if (!space) {
        if (error) *error = "cannot create sRGB";
        return false;
    }
    CGDataProviderRef provider = CGDataProviderCreateWithData(
        nullptr, image.pixels.data(), image.pixels.size(), nullptr);
    CGImageRef cg_image = CGImageCreate(
        static_cast<size_t>(image.width), static_cast<size_t>(image.height), 8, 32,
        static_cast<size_t>(image.width) * 4, space,
        static_cast<CGBitmapInfo>(kCGImageAlphaNoneSkipLast |
                                  kCGBitmapByteOrder32Big),
        provider, nullptr, false, kCGRenderingIntentDefault);
    CGDataProviderRelease(provider);
    CGColorSpaceRelease(space);
    if (!cg_image) {
        if (error) *error = "cannot create CGImage";
        return false;
    }
    CFMutableDictionaryRef properties = CFDictionaryCreateMutable(
        kCFAllocatorDefault, 0, &kCFTypeDictionaryKeyCallBacks,
        &kCFTypeDictionaryValueCallBacks);
    CFNumberRef orientation_value =
        CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, &orientation);
    CFDictionarySetValue(properties, kCGImagePropertyOrientation, orientation_value);
    CFRelease(orientation_value);

    CFURLRef url = CFURLCreateFromFileSystemRepresentation(
        kCFAllocatorDefault, reinterpret_cast<const UInt8*>(path.c_str()),
        static_cast<CFIndex>(path.size()), false);
    CGImageDestinationRef destination =
        CGImageDestinationCreateWithURL(url, CFSTR("public.png"), 1, nullptr);
    CFRelease(url);
    bool ok = false;
    if (destination) {
        CGImageDestinationAddImage(destination, cg_image, properties);
        ok = CGImageDestinationFinalize(destination);
        CFRelease(destination);
    }
    CFRelease(properties);
    CGImageRelease(cg_image);
    if (!ok && error) *error = "cannot write orientation PNG";
    return ok;
}

bool write_gif(const std::string& path, const spkdesk::Rgba8Image& image,
               std::string* error) {
    CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    CGDataProviderRef provider = CGDataProviderCreateWithData(
        nullptr, image.pixels.data(), image.pixels.size(), nullptr);
    CGImageRef cg_image = CGImageCreate(
        static_cast<size_t>(image.width), static_cast<size_t>(image.height), 8, 32,
        static_cast<size_t>(image.width) * 4, space,
        static_cast<CGBitmapInfo>(kCGImageAlphaNoneSkipLast |
                                  kCGBitmapByteOrder32Big),
        provider, nullptr, false, kCGRenderingIntentDefault);
    CGDataProviderRelease(provider);
    CGColorSpaceRelease(space);
    if (!cg_image) return false;
    CFURLRef url = CFURLCreateFromFileSystemRepresentation(
        kCFAllocatorDefault, reinterpret_cast<const UInt8*>(path.c_str()),
        static_cast<CFIndex>(path.size()), false);
    CGImageDestinationRef destination =
        CGImageDestinationCreateWithURL(url, CFSTR("com.compuserve.gif"), 1, nullptr);
    CFRelease(url);
    bool ok = false;
    if (destination) {
        CGImageDestinationAddImage(destination, cg_image, nullptr);
        ok = CGImageDestinationFinalize(destination);
        CFRelease(destination);
    }
    CGImageRelease(cg_image);
    if (!ok && error) *error = "cannot write GIF";
    return ok;
}

struct WorkerProcess {
    pid_t pid = -1;
    int input_fd = -1;
    int output_fd = -1;
    std::string pending;

    ~WorkerProcess() { stop(); }

    bool start(const std::string& binary, const std::string& assets,
               const std::string& session, std::string* error) {
        int to_child[2] = {-1, -1};
        int from_child[2] = {-1, -1};
        if (::pipe(to_child) != 0 || ::pipe(from_child) != 0) {
            if (error) *error = "pipe() failed";
            return false;
        }
        pid = ::fork();
        if (pid < 0) {
            if (error) *error = "fork() failed";
            return false;
        }
        if (pid == 0) {
            ::dup2(to_child[0], STDIN_FILENO);
            ::dup2(from_child[1], STDOUT_FILENO);
            ::close(to_child[0]);
            ::close(to_child[1]);
            ::close(from_child[0]);
            ::close(from_child[1]);
            ::execl(binary.c_str(), binary.c_str(), assets.c_str(), session.c_str(),
                    static_cast<char*>(nullptr));
            std::fprintf(stderr, "cannot exec %s: %s\n", binary.c_str(),
                         std::strerror(errno));
            ::_exit(127);
        }
        ::close(to_child[0]);
        ::close(from_child[1]);
        input_fd = to_child[1];
        output_fd = from_child[0];
        return true;
    }

    bool write_all(const std::string& data) {
        size_t offset = 0;
        while (offset < data.size()) {
            const ssize_t written =
                ::write(input_fd, data.data() + offset, data.size() - offset);
            if (written < 0) {
                if (errno == EINTR) continue;
                return false;
            }
            offset += static_cast<size_t>(written);
        }
        return true;
    }

    bool read_line(std::string* line) {
        while (true) {
            const size_t newline = pending.find('\n');
            if (newline != std::string::npos) {
                *line = pending.substr(0, newline);
                pending.erase(0, newline + 1);
                return true;
            }
            char buffer[4096];
            const ssize_t read_count = ::read(output_fd, buffer, sizeof(buffer));
            if (read_count < 0) {
                if (errno == EINTR) continue;
                return false;
            }
            if (read_count == 0) return false;
            pending.append(buffer, static_cast<size_t>(read_count));
        }
    }

    std::string request(const std::string& line) {
        if (input_fd < 0 || !write_all(line + "\n")) return {};
        std::string response;
        if (!read_line(&response)) return {};
        return response;
    }

    void stop() {
        if (input_fd >= 0) {
            ::close(input_fd);
            input_fd = -1;
        }
        if (output_fd >= 0) {
            ::close(output_fd);
            output_fd = -1;
        }
        if (pid > 0) {
            int status = 0;
            ::waitpid(pid, &status, 0);
            pid = -1;
        }
    }
};

struct Response {
    bool ok = false;
    std::string error;
    spk::json::ValuePtr json;
};

Response ask(WorkerProcess& worker, const std::string& request) {
    Response response;
    const std::string line = worker.request(request);
    if (line.empty()) {
        response.error = "empty response (worker died?)";
        return response;
    }
    try {
        response.json = spk::json::parse(line);
        if (!response.json->is_object())
            throw std::runtime_error("response is not an object");
        const Value& ok = response.json->at("ok");
        if (ok.type != spk::json::Type::Bool)
            throw std::runtime_error("response.ok is not a boolean");
        response.ok = ok.boolean;
        if (!response.ok) {
            const Value& error = response.json->at("error");
            if (!error.is_string())
                throw std::runtime_error("error is not a string");
            response.error = error.str;
        }
    } catch (const std::exception& e) {
        response.ok = false;
        response.error = std::string("malformed worker response: ") + e.what();
    }
    return response;
}

spk::json::ValuePtr expect_ok(WorkerProcess& worker, const std::string& request,
                              const std::string& label, long* ms = nullptr) {
    const Clock::time_point start = Clock::now();
    Response response = ask(worker, request);
    if (ms) *ms = elapsed_ms(start);
    if (!response.ok) {
        fail(label + ": " + response.error);
        return nullptr;
    }
    return response.json;
}

bool expect_error(WorkerProcess& worker, const std::string& request,
                  const std::string& label) {
    Response response = ask(worker, request);
    if (response.ok) {
        fail(label + ": expected an error, got ok");
        return false;
    }
    if (response.error.empty()) {
        fail(label + ": error response had no message");
        return false;
    }
    return true;
}

int int_field(const spk::json::ValuePtr& json, const std::string& key) {
    const Value& value = json->at(key);
    if (value.type != spk::json::Type::Number)
        throw std::runtime_error("field " + key + " is not a number");
    return static_cast<int>(value.as_number());
}

std::string string_field(const spk::json::ValuePtr& json, const std::string& key) {
    const Value& value = json->at(key);
    if (!value.is_string())
        throw std::runtime_error("field " + key + " is not a string");
    return value.str;
}

const uint8_t* pixel_at(const spkdesk::Rgba8Image& image, int x, int y) {
    return &image.pixels[(static_cast<size_t>(y) * image.width + x) * 4];
}

double srgb_to_linear_double(double c) {
    if (c <= 0.04045) return c / 12.92;
    return std::pow((c + 0.055) / 1.055, 2.4);
}

struct Colour {
    double r;
    double g;
    double b;
};

/* Expected linear ProPhoto for an 8-bit sRGB triple, using the same matrix as
 * ImagePipeline.kt / image_io_mac.mm. */
Colour expected_prophoto(uint8_t r, uint8_t g, uint8_t b) {
    const double table[9] = {
        0.5290825, 0.3303437, 0.1405738,
        0.0982640, 0.8734031, 0.0283329,
        0.0167029, 0.1176946, 0.8656026,
    };
    const double sr = srgb_to_linear_double(r / 255.0);
    const double sg = srgb_to_linear_double(g / 255.0);
    const double sb = srgb_to_linear_double(b / 255.0);
    return {
        table[0] * sr + table[1] * sg + table[2] * sb,
        table[3] * sr + table[4] * sg + table[5] * sb,
        table[6] * sr + table[7] * sg + table[8] * sb,
    };
}

bool near(double actual, double expected, double tolerance) {
    return std::fabs(actual - expected) <= tolerance;
}

void check_probe(const spk::json::ValuePtr& json, const Colour& expected,
                 double tolerance, const std::string& label) {
    if (!json) return;
    const Value& probe = json->at("probe");
    if (!probe.is_array() || probe.array.size() != 3) {
        fail(label + ": probe is not a 3-element array");
        return;
    }
    const double actual[3] = {probe[0].as_number(), probe[1].as_number(),
                              probe[2].as_number()};
    if (!near(actual[0], expected.r, tolerance) ||
        !near(actual[1], expected.g, tolerance) ||
        !near(actual[2], expected.b, tolerance)) {
        std::ostringstream message;
        message << label << ": probe [" << actual[0] << ", " << actual[1] << ", "
                << actual[2] << "] expected [" << expected.r << ", " << expected.g
                << ", " << expected.b << "]";
        fail(message.str());
    }
}

bool has_variation(const std::string& path, const std::string& label) {
    spkdesk::Rgba8Image image;
    int orientation = 0;
    std::string error;
    if (!spkdesk::decode_srgb(path, 0, &image, &orientation, &error)) {
        fail(label + ": cannot decode for variation check: " + error);
        return false;
    }
    if (image.pixels.size() < 8) {
        fail(label + ": image is too small");
        return false;
    }
    const uint8_t* first = image.pixels.data();
    for (size_t i = 1; i < static_cast<size_t>(image.width) * image.height; ++i) {
        const uint8_t* px = &image.pixels[i * 4];
        if (px[0] != first[0] || px[1] != first[1] || px[2] != first[2])
            return true;
    }
    fail(label + ": rendered output is a uniform colour");
    return false;
}

bool verify_output(const std::string& path, int width, int height,
                   const std::string& type, const std::string& label,
                   std::string* profile = nullptr) {
    spkdesk::ImageInfo info;
    std::string error;
    if (!spkdesk::probe_image(path, &info, &error)) {
        fail(label + ": cannot probe output: " + error);
        return false;
    }
    if (info.width != width || info.height != height) {
        fail(label + ": dimensions " + std::to_string(info.width) + "x" +
             std::to_string(info.height) + " expected " + std::to_string(width) +
             "x" + std::to_string(height));
        return false;
    }
    if (info.type != type) {
        fail(label + ": type '" + info.type + "' expected '" + type + "'");
        return false;
    }
    if (profile) *profile = info.profile_name;
    return true;
}

std::string recipe(const std::string& preset, double strength,
                   const std::string& extra = "") {
    std::ostringstream out;
    out << "{\"presetId\":\"" << preset << "\",\"strength\":" << strength;
    if (!extra.empty()) out << "," << extra;
    out << "}";
    return out.str();
}

void test_open_orientation_and_linear(WorkerProcess& worker) {
    const std::string label = "open orientation + linear";
    spkdesk::Rgba8Image orientation_image = make_image(4, 2);
    set_pixel(orientation_image, 0, 0, 255, 0, 0);
    set_pixel(orientation_image, 1, 0, 0, 255, 0);
    set_pixel(orientation_image, 2, 0, 0, 0, 255);
    set_pixel(orientation_image, 3, 0, 255, 255, 0);
    set_pixel(orientation_image, 0, 1, 255, 255, 255);
    set_pixel(orientation_image, 1, 1, 128, 128, 128);
    set_pixel(orientation_image, 2, 1, 0, 0, 0);
    set_pixel(orientation_image, 3, 1, 255, 0, 255);
    std::string error;
    CHECK(write_png_with_orientation(join(g_session, "orientation.png"),
                                     orientation_image, 6, &error),
          "write orientation.png: " + error);

    const Clock::time_point test_start = Clock::now();
    auto response = expect_ok(
        worker,
        "{\"op\":\"open\",\"path\":\"orientation.png\",\"preview\":\"orientation_preview.png\",\"probe\":[1,0]}",
        label);
    if (!response) return;
    CHECK(int_field(response, "width") == 2, "orientation open width");
    CHECK(int_field(response, "height") == 4, "orientation open height");
    CHECK(int_field(response, "orientation") == 6, "orientation tag reported");
    CHECK(int_field(response, "previewWidth") == 2, "orientation preview width");
    CHECK(int_field(response, "previewHeight") == 4, "orientation preview height");
    check_probe(response, expected_prophoto(255, 0, 0), 1e-3,
                "orientation red probe");

    spkdesk::Rgba8Image preview;
    int preview_orientation = 0;
    CHECK(spkdesk::decode_srgb(join(g_session, "orientation_preview.png"), 0,
                               &preview, &preview_orientation, &error),
          "decode orientation preview: " + error);
    CHECK(preview.width == 2 && preview.height == 4,
          "orientation preview dimensions");
    struct Expected {
        int x, y;
        uint8_t r, g, b;
    };
    const Expected expected[] = {
        {0, 0, 255, 255, 255}, {1, 0, 255, 0, 0},
        {0, 1, 128, 128, 128}, {1, 1, 0, 255, 0},
        {0, 2, 0, 0, 0},       {1, 2, 0, 0, 255},
        {0, 3, 255, 0, 255},   {1, 3, 255, 255, 0},
    };
    for (const Expected& e : expected) {
        const uint8_t* px = pixel_at(preview, e.x, e.y);
        if (std::abs(px[0] - e.r) > 2 || std::abs(px[1] - e.g) > 2 ||
            std::abs(px[2] - e.b) > 2) {
            fail("orientation pixel (" + std::to_string(e.x) + "," +
                 std::to_string(e.y) + ") mismatch");
        }
    }
    std::cout << "PASS: " << label << " (" << elapsed_ms(test_start)
              << " ms)\n";

    /* A neutral 1x1 sRGB(188) pixel: inverse sRGB CCTF is ~0.5029, and the
     * sRGB->ProPhoto rows sum to 1 for a neutral. Double-gamma would land near
     * 0.73 instead, so this is a real CCTF assertion. */
    spkdesk::Rgba8Image neutral = make_image(1, 1);
    set_pixel(neutral, 0, 0, 188, 188, 188);
    CHECK(write_png(join(g_session, "neutral.png"), neutral, &error),
          "write neutral.png: " + error);
    const Clock::time_point neutral_start = Clock::now();
    auto neutral_response = expect_ok(
        worker,
        "{\"op\":\"open\",\"path\":\"neutral.png\",\"preview\":\"neutral_preview.png\",\"probe\":[0,0]}",
        "open linear conversion");
    if (!neutral_response) return;
    CHECK(int_field(neutral_response, "width") == 1, "neutral width");
    CHECK(int_field(neutral_response, "height") == 1, "neutral height");
    const Colour expected_gray = expected_prophoto(188, 188, 188);
    check_probe(neutral_response, expected_gray, 1e-3,
                "neutral linear ProPhoto probe");
    std::cout << "PASS: open linear conversion (" << elapsed_ms(neutral_start)
              << " ms)\n";

    /* JPEG input path: ImageIO decode, orientation tag 1, real dimensions. */
    CHECK(write_jpeg(join(g_session, "scene.jpg"), gradient_image(96, 72), 95,
                     &error),
          "write scene.jpg: " + error);
    const Clock::time_point jpeg_start = Clock::now();
    auto jpeg_open = expect_ok(
        worker,
        "{\"op\":\"open\",\"path\":\"scene.jpg\",\"preview\":\"scene_jpeg_preview.png\"}",
        "open JPEG");
    if (jpeg_open) {
        CHECK(int_field(jpeg_open, "width") == 96, "JPEG open width");
        CHECK(int_field(jpeg_open, "height") == 72, "JPEG open height");
        CHECK(verify_output(join(g_session, "scene_jpeg_preview.png"), 96, 72,
                            "public.png", "JPEG open preview"),
              "JPEG open preview decode");
    }
    std::cout << "PASS: open JPEG (" << elapsed_ms(jpeg_start) << " ms)\n";
}


void test_all_orientations(WorkerProcess& worker) {
    const std::string label = "EXIF orientations 1..8";
    const Clock::time_point test_start = Clock::now();
    const int width = 4;
    const int height = 2;
    const uint8_t palette[8][3] = {
        {255, 0, 0},   {0, 255, 0},   {0, 0, 255},   {255, 255, 0},
        {255, 255, 255}, {128, 128, 128}, {0, 0, 0}, {255, 0, 255},
    };
    spkdesk::Rgba8Image source = make_image(width, height);
    for (int y = 0; y < height; ++y)
        for (int x = 0; x < width; ++x)
            set_pixel(source, x, y, palette[y * width + x][0],
                      palette[y * width + x][1], palette[y * width + x][2]);
    std::string error;
    for (int orientation = 1; orientation <= 8; ++orientation) {
        const std::string input = "orientation_" + std::to_string(orientation) + ".png";
        const std::string preview = "orientation_" + std::to_string(orientation) +
                                    "_preview.png";
        CHECK(write_png_with_orientation(join(g_session, input), source, orientation,
                                         &error),
              "write " + input + ": " + error);
        auto response = expect_ok(
            worker,
            "{\"op\":\"open\",\"path\":\"" + input + "\",\"preview\":\"" + preview +
                "\"}",
            label + " " + std::to_string(orientation));
        if (!response) continue;
        const bool swapped = orientation >= 5;
        const int out_w = swapped ? height : width;
        const int out_h = swapped ? width : height;
        CHECK(int_field(response, "width") == out_w,
              "orientation " + std::to_string(orientation) + " width");
        CHECK(int_field(response, "height") == out_h,
              "orientation " + std::to_string(orientation) + " height");
        CHECK(int_field(response, "orientation") == orientation,
              "orientation " + std::to_string(orientation) + " tag");
        spkdesk::Rgba8Image decoded;
        int decoded_orientation = 0;
        CHECK(spkdesk::decode_srgb(join(g_session, preview), 0, &decoded,
                                   &decoded_orientation, &error),
              "decode " + preview + ": " + error);
        for (int y = 0; y < out_h; ++y) {
            for (int x = 0; x < out_w; ++x) {
                int u = 0;
                int v = 0;
                switch (orientation) {
                    case 1: u = x; v = y; break;
                    case 2: u = width - 1 - x; v = y; break;
                    case 3: u = width - 1 - x; v = height - 1 - y; break;
                    case 4: u = x; v = height - 1 - y; break;
                    case 5: u = y; v = x; break;
                    case 6: u = y; v = height - 1 - x; break;
                    case 7: u = width - 1 - y; v = height - 1 - x; break;
                    case 8: u = width - 1 - y; v = x; break;
                }
                const uint8_t* expected = palette[v * width + u];
                const uint8_t* actual = pixel_at(decoded, x, y);
                if (std::abs(actual[0] - expected[0]) > 2 ||
                    std::abs(actual[1] - expected[1]) > 2 ||
                    std::abs(actual[2] - expected[2]) > 2) {
                    fail(label + " " + std::to_string(orientation) + " pixel (" +
                         std::to_string(x) + "," + std::to_string(y) + ") mismatch");
                }
            }
        }
    }
    std::cout << "PASS: " << label << " (" << elapsed_ms(test_start) << " ms)\n";
}

void test_path_rejections(WorkerProcess& worker) {
    const std::string label = "path safety";
    const std::string scene = join(g_session, "scene.png");
    const Clock::time_point test_start = Clock::now();
    const uint64_t scene_hash = content_hash(scene);

    expect_error(worker,
                 "{\"op\":\"open\",\"path\":\"../outside/secret.png\",\"preview\":\"x.png\"}",
                 label + " parent traversal");
    expect_error(worker,
                 "{\"op\":\"open\",\"path\":\"" + join(g_outside, "secret.png") +
                     "\",\"preview\":\"x.png\"}",
                 label + " absolute input");
    expect_error(worker,
                 "{\"op\":\"open\",\"path\":\"escape.png\",\"preview\":\"x.png\"}",
                 label + " input symlink escape");
    expect_error(worker,
                 "{\"op\":\"open\",\"path\":\"outdir/secret.png\",\"preview\":\"x.png\"}",
                 label + " directory symlink escape");
    expect_error(worker,
                 "{\"op\":\"open\",\"path\":\"missing.png\",\"preview\":\"x.png\"}",
                 label + " missing input");
    expect_error(worker,
                 "{\"op\":\"open\",\"path\":\"scene.png\",\"preview\":\"scene.png\"}",
                 label + " open preview over input");
    expect_error(worker,
                 "{\"op\":\"open\",\"path\":\"bad.jpg\",\"preview\":\"x.png\"}",
                 label + " corrupt image");
    expect_error(worker,
                 "{\"op\":\"open\",\"path\":\"notpng.gif\",\"preview\":\"x.png\"}",
                 label + " unsupported GIF");

    auto opened = expect_ok(worker,
                            "{\"op\":\"open\",\"path\":\"scene.png\",\"preview\":\"scene_source.png\"}",
                            label + " valid open");
    if (!opened) return;

    expect_error(worker,
                 "{\"op\":\"export\",\"recipe\":" + recipe("neutral_adobe_like", 1.0) +
                     ",\"out\":\"scene.png\"}",
                 label + " export over input");
    CHECK(content_hash(scene) == scene_hash, "input mutated by rejected export");
    expect_error(worker,
                 "{\"op\":\"export\",\"recipe\":" + recipe("neutral_adobe_like", 1.0) +
                     ",\"out\":\"../outside/leak.png\"}",
                 label + " output traversal");
    expect_error(worker,
                 "{\"op\":\"export\",\"recipe\":" + recipe("neutral_adobe_like", 1.0) +
                     ",\"out\":\"" + join(g_outside, "leak.png") + "\"}",
                 label + " absolute output");
    expect_error(worker,
                 "{\"op\":\"export\",\"recipe\":" + recipe("neutral_adobe_like", 1.0) +
                     ",\"out\":\"evil_out.png\"}",
                 label + " output symlink escape");
    expect_error(worker,
                 "{\"op\":\"export\",\"recipe\":" + recipe("neutral_adobe_like", 1.0) +
                     ",\"out\":\"outdir/leak.png\"}",
                 label + " output directory symlink escape");

    /* The failed requests must not have killed the worker or created files. */
    CHECK(!fs::exists(join(g_outside, "leak.png")), "escaped output was created");
    CHECK(!fs::exists(join(g_session, "x.png")), "rejected open created a preview");
    auto alive = expect_ok(worker,
                           "{\"op\":\"preview\",\"recipe\":" +
                               recipe("neutral_adobe_like", 1.0) +
                               ",\"out\":\"after_errors.png\"}",
                           label + " worker alive");
    CHECK(alive != nullptr, "worker not alive after rejected paths");
    std::cout << "PASS: " << label << " (" << elapsed_ms(test_start)
              << " ms)\n";
}

void test_recipe_rejections(WorkerProcess& worker) {
    const std::string label = "recipe validation";
    const Clock::time_point test_start = Clock::now();
    const std::vector<std::pair<std::string, std::string>> cases = {
        {"missing presetId",
         "{\"op\":\"preview\",\"recipe\":{\"strength\":1.0},\"out\":\"bad_recipe.png\"}"},
        {"unknown preset",
         "{\"op\":\"preview\",\"recipe\":{\"presetId\":\"nope\"},\"out\":\"bad_recipe.png\"}"},
        {"strength > 1",
         "{\"op\":\"preview\",\"recipe\":{\"presetId\":\"neutral_adobe_like\",\"strength\":1.2},\"out\":\"bad_recipe.png\"}"},
        {"strength < 0",
         "{\"op\":\"preview\",\"recipe\":{\"presetId\":\"neutral_adobe_like\",\"strength\":-0.1},\"out\":\"bad_recipe.png\"}"},
        {"exposureEv out of range",
         "{\"op\":\"preview\",\"recipe\":{\"presetId\":\"neutral_adobe_like\",\"exposureEv\":99},\"out\":\"bad_recipe.png\"}"},
        {"grainScale < 0",
         "{\"op\":\"preview\",\"recipe\":{\"presetId\":\"neutral_adobe_like\",\"grainScale\":-0.1},\"out\":\"bad_recipe.png\"}"},
        {"grainScale > 4",
         "{\"op\":\"preview\",\"recipe\":{\"presetId\":\"neutral_adobe_like\",\"grainScale\":4.1},\"out\":\"bad_recipe.png\"}"},
        {"halationScale > 4",
         "{\"op\":\"preview\",\"recipe\":{\"presetId\":\"neutral_adobe_like\",\"halationScale\":4.1},\"out\":\"bad_recipe.png\"}"},
        {"unknown recipe key",
         "{\"op\":\"preview\",\"recipe\":{\"presetId\":\"neutral_adobe_like\",\"bogus\":1},\"out\":\"bad_recipe.png\"}"},
        {"unknown film profile",
         "{\"op\":\"preview\",\"recipe\":{\"presetId\":\"neutral_adobe_like\",\"filmProfile\":\"nope\"},\"out\":\"bad_recipe.png\"}"},
        {"empty print profile",
         "{\"op\":\"preview\",\"recipe\":{\"presetId\":\"neutral_adobe_like\",\"printProfile\":\"\"},\"out\":\"bad_recipe.png\"}"},
        {"presetId is not a string",
         "{\"op\":\"preview\",\"recipe\":{\"presetId\":7},\"out\":\"bad_recipe.png\"}"},
        {"recipe is not an object",
         "{\"op\":\"preview\",\"recipe\":[],\"out\":\"bad_recipe.png\"}"},
        {"malformed JSON", "{\"op\":\"preview\""},
        {"unknown op", "{\"op\":\"launch_missiles\"}"},
    };
    for (const auto& entry : cases) {
        expect_error(worker, entry.second, label + " / " + entry.first);
    }
    CHECK(!fs::exists(join(g_session, "bad_recipe.png")),
          "failed recipe request left an output file");
    std::cout << "PASS: " << label << " (" << elapsed_ms(test_start)
              << " ms)\n";
}

void test_all_presets(WorkerProcess& worker) {
    const std::string label = "all built-in presets";
    const Clock::time_point test_start = Clock::now();

    auto default_response = expect_ok(
        worker,
        "{\"op\":\"preview\",\"recipe\":{\"presetId\":\"__default__\",\"strength\":1.0,"
        "\"filmProfile\":\"kodak_portra_400\",\"printProfile\":\"kodak_portra_endura\"},"
        "\"out\":\"default_preview.png\"}",
        label + " default params");
    if (default_response) {
        CHECK(int_field(default_response, "width") == 96, "default preview width");
        CHECK(int_field(default_response, "height") == 72, "default preview height");
        CHECK(verify_output(join(g_session, "default_preview.png"), 96, 72,
                            "public.png", label + " default output"),
              "default output decode");
        CHECK(has_variation(join(g_session, "default_preview.png"),
                            label + " default output"),
              "default render is not uniform");
    }

    std::string error;
    std::ifstream in(join(g_assets, "presets.json"), std::ios::binary);
    CHECK(in.good(), "presets.json is readable");
    if (!in.good()) return;
    std::ostringstream buffer;
    buffer << in.rdbuf();
    const spk::json::ValuePtr presets = spk::json::parse(buffer.str());
    const Value& list = presets->at("presets");
    CHECK(list.is_array(), "presets is an array");
    size_t count = 0;
    size_t failures_before = static_cast<size_t>(g_failures);
    for (size_t i = 0; i < list.array.size(); ++i) {
        const Value& preset = list[i];
        const std::string id = preset.at("id").as_string();
        const std::string out = "preset_" + id + ".png";
        const Clock::time_point one_start = Clock::now();
        auto response = expect_ok(
            worker,
            "{\"op\":\"preview\",\"recipe\":" + recipe(id, 1.0) + ",\"out\":\"" +
                out + "\"}",
            label + " '" + id + "'");
        if (response) {
            CHECK(int_field(response, "width") == 96, id + " preview width");
            CHECK(int_field(response, "height") == 72, id + " preview height");
            CHECK(verify_output(join(g_session, out), 96, 72, "public.png",
                                id + " output"),
                  id + " output decode");
        }
        ++count;
        std::cout << "  preset " << id << ": "
                  << (response ? "ok" : "FAIL") << " (" << elapsed_ms(one_start)
                  << " ms)\n";
    }
    CHECK(count == 27, "preset count is 27");
    if (static_cast<size_t>(g_failures) == failures_before) {
        std::cout << "PASS: " << label << " (" << count << " presets, "
                  << elapsed_ms(test_start) << " ms)\n";
    }

    auto partial = expect_ok(
        worker,
        "{\"op\":\"preview\",\"recipe\":" + recipe("portra400_endura_wedding", 0.5) +
            ",\"out\":\"preset_partial.png\"}",
        label + " partial strength");
    if (partial) {
        CHECK(verify_output(join(g_session, "preset_partial.png"), 96, 72,
                            "public.png", label + " partial output"),
              "partial output decode");
    }

    /* strength=0 must be exactly spk_default_params. Force the stochastic /
     * spatial effects off on both sides so the comparison is deterministic. */
    auto zero = expect_ok(
        worker,
        "{\"op\":\"preview\",\"recipe\":{\"presetId\":\"portra800_wide_gamut_glow\","
        "\"strength\":0.0,\"filmProfile\":\"kodak_portra_400\","
        "\"printProfile\":\"kodak_portra_endura\",\"grainScale\":0,"
        "\"halationScale\":0},\"out\":\"strength_zero.png\"}",
        label + " strength 0");
    auto defaults = expect_ok(
        worker,
        "{\"op\":\"preview\",\"recipe\":{\"presetId\":\"__default__\","
        "\"strength\":1.0,\"filmProfile\":\"kodak_portra_400\","
        "\"printProfile\":\"kodak_portra_endura\",\"grainScale\":0,"
        "\"halationScale\":0},\"out\":\"strength_defaults.png\"}",
        label + " default equivalence");
    if (zero && defaults) {
        CHECK(read_bytes(join(g_session, "strength_zero.png")) ==
                  read_bytes(join(g_session, "strength_defaults.png")),
              "strength=0 differs from spk_default_params");
    }
}

void test_full_size_export(WorkerProcess& worker) {
    const std::string label = "full-size export";
    const std::string source = join(g_session, "large_source.png");
    const Clock::time_point test_start = Clock::now();

    auto opened = expect_ok(
        worker,
        "{\"op\":\"open\",\"path\":\"large_source.png\",\"preview\":\"large_source_preview.png\"}",
        label + " open");
    if (!opened) return;
    CHECK(int_field(opened, "width") == 1100, "large open width");
    CHECK(int_field(opened, "height") == 700, "large open height");

    auto preview = expect_ok(
        worker,
        "{\"op\":\"preview\",\"recipe\":" + recipe("neutral_adobe_like", 1.0) +
            ",\"out\":\"large_preview.png\"}",
        label + " preview");
    if (preview) {
        CHECK(int_field(preview, "width") == 1024, "large preview width");
        CHECK(int_field(preview, "height") == 652, "large preview height");
        CHECK(verify_output(join(g_session, "large_preview.png"), 1024, 652,
                            "public.png", label + " preview output"),
              "large preview decode");
    }

    const uint64_t source_hash = content_hash(source);
    auto jpeg = expect_ok(
        worker,
        "{\"op\":\"export\",\"recipe\":" + recipe("neutral_adobe_like", 1.0) +
            ",\"out\":\"large_export.jpg\",\"quality\":90}",
        label + " JPEG");
    if (jpeg) {
        CHECK(int_field(jpeg, "width") == 1100, "large JPEG width");
        CHECK(int_field(jpeg, "height") == 700, "large JPEG height");
        CHECK(string_field(jpeg, "format") == "jpeg", "large JPEG format field");
        std::string profile;
        CHECK(verify_output(join(g_session, "large_export.jpg"), 1100, 700,
                            "public.jpeg", label + " JPEG output", &profile),
              "large JPEG decode");
        CHECK(profile.find("sRGB") != std::string::npos,
              "large JPEG has an sRGB profile (got '" + profile + "')");
        CHECK(has_variation(join(g_session, "large_export.jpg"),
                            label + " JPEG output"),
              "full-size JPEG render is not uniform");
    }
    auto png = expect_ok(
        worker,
        "{\"op\":\"export\",\"recipe\":" + recipe("neutral_adobe_like", 1.0) +
            ",\"out\":\"large_export.png\"}",
        label + " PNG");
    if (png) {
        CHECK(int_field(png, "width") == 1100, "large PNG width");
        CHECK(int_field(png, "height") == 700, "large PNG height");
        CHECK(string_field(png, "format") == "png", "large PNG format field");
        std::string profile;
        CHECK(verify_output(join(g_session, "large_export.png"), 1100, 700,
                            "public.png", label + " PNG output", &profile),
              "large PNG decode");
        CHECK(profile.find("sRGB") != std::string::npos,
              "large PNG has an sRGB profile (got '" + profile + "')");
        CHECK(has_variation(join(g_session, "large_export.png"),
                            label + " PNG output"),
              "full-size PNG render is not uniform");
    }
    CHECK(content_hash(source) == source_hash,
          "full-size export overwrote the open input");

    /* One preset authors io.outputColorSpace=ADOBE_RGB; its file must carry
     * the matching profile, proving the writer does not mislabel wide-gamut
     * pixels as sRGB. */
    auto reopened = expect_ok(
        worker,
        "{\"op\":\"open\",\"path\":\"scene.png\",\"preview\":\"scene_for_adobe.png\"}",
        label + " reopen scene");
    if (reopened) {
        auto adobe = expect_ok(
            worker,
            "{\"op\":\"export\",\"recipe\":" +
                recipe("portra800_wide_gamut_glow", 1.0) +
                ",\"out\":\"adobe_export.jpg\",\"quality\":92}",
            label + " Adobe RGB");
        if (adobe) {
            std::string profile;
            CHECK(verify_output(join(g_session, "adobe_export.jpg"), 96, 72,
                                "public.jpeg", label + " Adobe output", &profile),
                  "Adobe JPEG decode");
            CHECK(profile.find("Adobe") != std::string::npos,
                  "Adobe RGB profile preserved (got '" + profile + "')");
        }
    }
    std::cout << "PASS: " << label << " (" << elapsed_ms(test_start)
              << " ms)\n";
}


void test_writer_color_semantics() {
    const std::string label = "output color/CCTF semantics";
    const Clock::time_point test_start = Clock::now();
    const int width = 8;
    const int height = 6;
    std::vector<float> rgb(static_cast<size_t>(width) * height * 3);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const size_t i = (static_cast<size_t>(y) * width + x) * 3;
            rgb[i + 0] = static_cast<float>(x) / (width - 1);
            rgb[i + 1] = static_cast<float>(y) / (height - 1);
            rgb[i + 2] = 0.5f;
        }
    }
    std::string error;
    CHECK(spkdesk::write_engine_rgb(join(g_session, "writer_srgb.png"), rgb.data(),
                                    width, height, SPK_CS_SRGB, true, g_assets,
                                    spkdesk::ImageFormat::Png, 92, &error),
          "write sRGB PNG: " + error);
    spkdesk::ImageInfo info;
    CHECK(spkdesk::probe_image(join(g_session, "writer_srgb.png"), &info, &error),
          "probe sRGB PNG: " + error);
    CHECK(info.bits_per_component == 8, "sRGB PNG is 8-bit");
    CHECK(info.profile_name.find("sRGB") != std::string::npos,
          "sRGB PNG profile: " + info.profile_name);

    CHECK(spkdesk::write_engine_rgb(join(g_session, "writer_linear.png"), rgb.data(),
                                    width, height, SPK_CS_SRGB, false, g_assets,
                                    spkdesk::ImageFormat::Png, 92, &error),
          "write linear PNG: " + error);
    CHECK(spkdesk::probe_image(join(g_session, "writer_linear.png"), &info, &error),
          "probe linear PNG: " + error);
    CHECK(info.bits_per_component == 16, "linear PNG is 16-bit");
    CHECK(info.profile_name.find("Linear") != std::string::npos,
          "linear PNG profile: " + info.profile_name);

    CHECK(!spkdesk::write_engine_rgb(join(g_session, "writer_linear.jpg"), rgb.data(),
                                     width, height, SPK_CS_SRGB, false, g_assets,
                                     spkdesk::ImageFormat::Jpeg, 92, &error),
          "linear JPEG must be rejected");
    CHECK(!fs::exists(join(g_session, "writer_linear.jpg")),
          "rejected linear JPEG left a file");

    for (int space : {static_cast<int>(SPK_CS_PROPHOTO),
                      static_cast<int>(SPK_CS_REC2020),
                      static_cast<int>(SPK_CS_ACES2065_1),
                      static_cast<int>(SPK_CS_LINEAR_SRGB)}) {
        const std::string path = join(g_session,
                                      "writer_space_" + std::to_string(space) + ".png");
        CHECK(spkdesk::write_engine_rgb(path, rgb.data(), width, height, space, true,
                                        g_assets, spkdesk::ImageFormat::Png, 92,
                                        &error),
              "write output space " + std::to_string(space) + ": " + error);
        CHECK(spkdesk::probe_image(path, &info, &error),
              "probe output space " + std::to_string(space) + ": " + error);
        CHECK(info.width == width && info.height == height,
              "output space dimensions");
        CHECK(!info.profile_name.empty(),
              "output space " + std::to_string(space) + " has no profile tag");
    }
    std::cout << "PASS: " << label << " (" << elapsed_ms(test_start) << " ms)\n";
}

void test_reset_and_close(WorkerProcess& worker) {
    const std::string label = "reset/close";
    auto reset = expect_ok(worker, "{\"op\":\"reset\"}", label + " reset");
    CHECK(reset != nullptr, "reset failed");
    expect_error(worker,
                 "{\"op\":\"preview\",\"recipe\":" + recipe("neutral_adobe_like", 1.0) +
                     ",\"out\":\"after_reset.png\"}",
                 label + " preview after reset");
    auto reopened = expect_ok(
        worker,
        "{\"op\":\"open\",\"path\":\"scene.png\",\"preview\":\"after_reset_source.png\"}",
        label + " reopen");
    CHECK(reopened != nullptr, "open after reset failed");
    auto closed = expect_ok(worker, "{\"op\":\"close\"}", label + " close");
    CHECK(closed != nullptr, "close failed");
    std::cout << "PASS: " << label << "\n";
}

void make_fixtures() {
    std::string error;
    CHECK(write_png(join(g_session, "scene.png"), gradient_image(96, 72), &error),
          "write scene.png: " + error);
    CHECK(write_png(join(g_session, "large_source.png"), gradient_image(1100, 700),
                    &error),
          "write large_source.png: " + error);
    CHECK(write_png(join(g_outside, "secret.png"), gradient_image(8, 8), &error),
          "write outside secret.png: " + error);

    std::ofstream bad(join(g_session, "bad.jpg"), std::ios::binary);
    bad << "this is not an image\n";
    bad.close();

    spkdesk::Rgba8Image gif = gradient_image(8, 8);
    std::string gif_error;
    if (write_gif(join(g_session, "notpng.gif"), gif, &gif_error)) {
        /* generated unsupported-format fixture */
    } else {
        std::ofstream fallback(join(g_session, "notpng.gif"), std::ios::binary);
        fallback << "GIF89a not really\n";
    }

    std::error_code ec;
    fs::create_symlink(join(g_outside, "secret.png"), join(g_session, "escape.png"),
                       ec);
    CHECK(!ec, "create input symlink: " + ec.message());
    ec.clear();
    fs::create_directory_symlink(g_outside, join(g_session, "outdir"), ec);
    CHECK(!ec, "create directory symlink: " + ec.message());
    ec.clear();
    fs::create_symlink(join(g_outside, "evil_target.png"),
                       join(g_session, "evil_out.png"), ec);
    CHECK(!ec, "create output symlink: " + ec.message());
}

}  // namespace

int main(int argc, char** argv) {
    signal(SIGPIPE, SIG_IGN);
    alarm(900);

    if (argc != 3) {
        std::fprintf(stderr, "usage: %s <repo_root> <build_dir>\n", argv[0]);
        return 2;
    }
    g_root = fs::canonical(argv[1]).string();
    g_build = fs::canonical(argv[2]).string();
    g_assets = join(join(join(g_root, "engine"), "spektra-core/src/main/assets"),
                    "spektra");
    g_session = join(g_build, "test-session");
    g_outside = join(g_build, "test-outside");

    std::error_code ec;
    fs::remove_all(g_session, ec);
    fs::remove_all(g_outside, ec);
    fs::create_directories(g_session, ec);
    CHECK(!ec, "create session dir: " + ec.message());
    fs::create_directories(g_outside, ec);
    CHECK(!ec, "create outside dir: " + ec.message());
    if (g_failures != 0) return 1;

    make_fixtures();
    if (g_failures != 0) return 1;

    const std::string binary = join(g_build, "spektra_worker");
    CHECK(fs::is_regular_file(binary), "worker binary exists: " + binary);
    if (g_failures != 0) return 1;

    WorkerProcess worker;
    std::string error;
    if (!worker.start(binary, g_assets, g_session, &error)) {
        fail("cannot start worker: " + error);
        return 1;
    }

    test_open_orientation_and_linear(worker);
    test_all_orientations(worker);
    test_path_rejections(worker);
    test_recipe_rejections(worker);
    test_all_presets(worker);
    test_full_size_export(worker);
    test_writer_color_semantics();
    test_reset_and_close(worker);

    /* close already stopped the worker; make sure it exited cleanly. */
    int status = 0;
    const pid_t pid = worker.pid;
    if (pid > 0) {
        ::waitpid(pid, &status, 0);
        worker.pid = -1;
        if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) {
            fail("worker did not exit cleanly after close (status " +
                 std::to_string(status) + ")");
        }
    }

    if (g_failures == 0) {
        std::cout << "ALL TESTS PASSED\n";
        return 0;
    }
    std::cerr << g_failures << " TEST FAILURE(S)\n";
    return 1;
}
