/*
 * Spektrafilm desktop worker — ImageIO/CoreGraphics image boundary.
 * GPLv3. Film modeling powered by spektrafilm.
 *
 * The engine consumes linear ProPhoto float RGB. This module owns the only
 * platform image code: decode JPEG/PNG (EXIF orientation + sRGB inverse CCTF +
 * linear sRGB -> linear ProPhoto), and write display-referred JPEG/PNG tagged
 * with the engine's declared output color space. No Python/Pillow dependency.
 */
#ifndef SPK_DESKTOP_IMAGE_IO_H
#define SPK_DESKTOP_IMAGE_IO_H

#include <cstdint>
#include <string>
#include <vector>

namespace spkdesk {

enum class ImageFormat { Png, Jpeg };

struct Rgba8Image {
    int width = 0;
    int height = 0;
    std::vector<uint8_t> pixels;  /* RGBA, row-major; alpha is opaque */
};

struct ImageInfo {
    int width = 0;
    int height = 0;
    int bits_per_component = 0;
    std::string type;          /* ImageIO UTI, e.g. public.jpeg */
    std::string profile_name;  /* embedded ICC description, if any */
    std::string color_model;   /* e.g. RGB */
};

/* Decode a JPEG/PNG to display sRGB RGBA8, applying EXIF orientation and
 * color-managing into sRGB. Rejects unsupported types, corrupt files, bad
 * orientations, and pathological dimensions. max_side > 0 downscales the long
 * edge (high-quality resample) after orientation; 0 keeps full resolution. */
bool decode_srgb(const std::string& path, int max_side, Rgba8Image* out,
                 int* applied_orientation, std::string* error);

/* Decode enough metadata to independently verify an output file. */
bool probe_image(const std::string& path, ImageInfo* out, std::string* error);

/* Write an opaque sRGB RGBA8 image. PNG honors max_side > 0 by downscaling
 * (long edge); out_width/out_height report the written dimensions. JPEG ignores
 * max_side (callers use it for preview PNGs) and uses quality [1,100]. */
bool write_rgba8(const std::string& path, const Rgba8Image& image,
                 ImageFormat format, int max_side, int quality,
                 int* out_width, int* out_height, std::string* error);

/* Write engine output. `rgb` is row-major interleaved float RGB in
 * `color_space`; `cctf_encoded` says whether the engine already applied that
 * space's output transfer function (io.outputCctfEncoding). Encoded output is
 * written 8-bit; linear output is written as 16-bit PNG only (JPEG has no
 * honest 8-bit linear representation here). ImageIO embeds the matching ICC
 * profile so no viewer applies a second gamma by default. */
bool write_engine_rgb(const std::string& path, const float* rgb, int width,
                      int height, int color_space, bool cctf_encoded,
                      const std::string& asset_dir, ImageFormat format,
                      int quality, std::string* error);

}  // namespace spkdesk

#endif  // SPK_DESKTOP_IMAGE_IO_H
