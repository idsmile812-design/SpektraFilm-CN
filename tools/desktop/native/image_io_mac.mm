/*
 * Spektrafilm desktop worker — ImageIO/CoreGraphics implementation.
 * GPLv3. Film modeling powered by spektrafilm.
 */
#include "image_io.h"

#include <ImageIO/ImageIO.h>
#include <CoreFoundation/CoreFoundation.h>
#include <CoreGraphics/CoreGraphics.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <limits>
#include <string>
#include <vector>

#include "spektra.h"

namespace spkdesk {
namespace {

constexpr int kMaxDimension = 32768;
constexpr int64_t kMaxPixels = 268435456;  /* 256 MP */

std::string cf_to_string(CFStringRef value) {
    if (!value) return {};
    char buffer[512];
    if (!CFStringGetCString(value, buffer, sizeof(buffer), kCFStringEncodingUTF8))
        return {};
    return buffer;
}


bool read_binary(const std::string& path, std::vector<uint8_t>* out) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;
    in.seekg(0, std::ios::end);
    const std::streamoff size = in.tellg();
    if (size <= 0) return false;
    in.seekg(0, std::ios::beg);
    out->resize(static_cast<size_t>(size));
    in.read(reinterpret_cast<char*>(out->data()), size);
    return in.good() || in.eof();
}

CGColorSpaceRef load_icc_space(const std::string& asset_dir,
                               const std::string& relative,
                               std::string* error) {
    std::vector<uint8_t> bytes;
    const std::string path = asset_dir + "/" + relative;
    if (!read_binary(path, &bytes)) {
        if (error) *error = "cannot read ICC profile " + path;
        return nullptr;
    }
    CFDataRef data = CFDataCreate(kCFAllocatorDefault, bytes.data(),
                                  static_cast<CFIndex>(bytes.size()));
    if (!data) {
        if (error) *error = "cannot allocate ICC profile data";
        return nullptr;
    }
    CGColorSpaceRef space = CGColorSpaceCreateWithICCData(data);
    CFRelease(data);
    if (!space && error) *error = "invalid ICC profile " + path;
    return space;
}

/* Build the color space that describes the engine output exactly. */
CGColorSpaceRef make_color_space(int color_space, bool cctf_encoded,
                                 const std::string& asset_dir,
                                 std::string* error) {
    switch (color_space) {
        case SPK_CS_SRGB:
            return CGColorSpaceCreateWithName(cctf_encoded
                                                  ? kCGColorSpaceSRGB
                                                  : kCGColorSpaceLinearSRGB);
        case SPK_CS_LINEAR_SRGB:
            return CGColorSpaceCreateWithName(cctf_encoded
                                                  ? kCGColorSpaceSRGB
                                                  : kCGColorSpaceLinearSRGB);
        case SPK_CS_ADOBE_RGB:
            if (!cctf_encoded) {
                if (error) *error = "linear Adobe RGB output has no honest 8-bit profile";
                return nullptr;
            }
            return CGColorSpaceCreateWithName(kCGColorSpaceAdobeRGB1998);
        case SPK_CS_PROPHOTO:
            if (!cctf_encoded) {
                if (error) *error = "linear ProPhoto output is not supported by the writer";
                return nullptr;
            }
            return load_icc_space(asset_dir, "icc/saucecontrol/ProPhoto-v2-magic.icc",
                                  error);
        case SPK_CS_REC2020:
            if (cctf_encoded)
                return CGColorSpaceCreateWithName(kCGColorSpaceITUR_2020);
            return load_icc_space(asset_dir, "icc/ellelstone/Rec2020-elle-V2-g10.icc",
                                  error);
        case SPK_CS_ACES2065_1:
            /* ACES2065-1's output CCTF is identity, so the linear ICC is correct
             * whether or not the engine's encoding flag is set. */
            return load_icc_space(asset_dir, "icc/ellelstone/ACES-elle-V4-g10.icc",
                                  error);
        default:
            if (error) *error = "unknown output color space";
            return nullptr;
    }
}

CGBitmapInfo rgba8_bitmap_info() {
    return static_cast<CGBitmapInfo>(kCGImageAlphaNoneSkipLast |
                                     kCGBitmapByteOrder32Big);
}

CGImageRef make_rgba8_image(const uint8_t* pixels, int width, int height,
                            CGColorSpaceRef space) {
    const size_t byte_count = static_cast<size_t>(width) * height * 4;
    CGDataProviderRef provider = CGDataProviderCreateWithData(
        nullptr, pixels, byte_count, nullptr);
    if (!provider) return nullptr;
    CGImageRef image = CGImageCreate(
        static_cast<size_t>(width), static_cast<size_t>(height), 8, 32,
        static_cast<size_t>(width) * 4, space, rgba8_bitmap_info(), provider,
        nullptr, false, kCGRenderingIntentDefault);
    CGDataProviderRelease(provider);
    return image;
}

/* Downscale in sRGB with high-quality interpolation. Returns a +1 reference. */
CGImageRef resize_image(CGImageRef source, int max_side, int* out_width,
                        int* out_height, std::string* error) {
    const int width = static_cast<int>(CGImageGetWidth(source));
    const int height = static_cast<int>(CGImageGetHeight(source));
    const int longest = std::max(width, height);
    int target_w = width;
    int target_h = height;
    if (max_side > 0 && longest > max_side) {
        const double scale = static_cast<double>(max_side) / longest;
        target_w = std::max(1, static_cast<int>(std::llround(width * scale)));
        target_h = std::max(1, static_cast<int>(std::llround(height * scale)));
    }
    if (out_width) *out_width = target_w;
    if (out_height) *out_height = target_h;
    if (target_w == width && target_h == height) {
        CGImageRetain(source);
        return source;
    }

    CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    if (!space) {
        if (error) *error = "cannot create sRGB color space";
        return nullptr;
    }
    CGContextRef context = CGBitmapContextCreate(
        nullptr, static_cast<size_t>(target_w), static_cast<size_t>(target_h),
        8, static_cast<size_t>(target_w) * 4, space, rgba8_bitmap_info());
    if (!context) {
        CGColorSpaceRelease(space);
        if (error) *error = "cannot allocate preview bitmap context";
        return nullptr;
    }
    CGContextSetInterpolationQuality(context, kCGInterpolationHigh);
    CGContextSetRGBFillColor(context, 1.0, 1.0, 1.0, 1.0);
    CGContextFillRect(context,
                      CGRectMake(0, 0, target_w, target_h));
    CGContextDrawImage(context, CGRectMake(0, 0, target_w, target_h), source);
    CGImageRef result = CGBitmapContextCreateImage(context);
    CGContextRelease(context);
    CGColorSpaceRelease(space);
    if (!result && error) *error = "cannot render preview image";
    return result;
}

bool write_cgimage(const std::string& path, CGImageRef image,
                   ImageFormat format, int max_side, int quality,
                   int* out_width, int* out_height, std::string* error) {
    if (quality < 1) quality = 1;
    if (quality > 100) quality = 100;

    CGImageRef emit = resize_image(image, max_side, out_width, out_height, error);
    if (!emit) return false;

    CFURLRef url = CFURLCreateFromFileSystemRepresentation(
        kCFAllocatorDefault,
        reinterpret_cast<const UInt8*>(path.c_str()),
        static_cast<CFIndex>(path.size()), false);
    if (!url) {
        CGImageRelease(emit);
        if (error) *error = "cannot create output URL";
        return false;
    }
    CFStringRef uti = format == ImageFormat::Png ? CFSTR("public.png")
                                                 : CFSTR("public.jpeg");
    CGImageDestinationRef destination =
        CGImageDestinationCreateWithURL(url, uti, 1, nullptr);
    CFRelease(url);
    if (!destination) {
        CGImageRelease(emit);
        if (error) *error = "cannot create ImageIO destination";
        return false;
    }

    CFMutableDictionaryRef properties = CFDictionaryCreateMutable(
        kCFAllocatorDefault, 0, &kCFTypeDictionaryKeyCallBacks,
        &kCFTypeDictionaryValueCallBacks);
    if (format == ImageFormat::Jpeg) {
        const float normalized = quality / 100.0f;
        CFNumberRef quality_value =
            CFNumberCreate(kCFAllocatorDefault, kCFNumberFloat32Type, &normalized);
        if (quality_value) {
            CFDictionarySetValue(properties,
                                 kCGImageDestinationLossyCompressionQuality,
                                 quality_value);
            CFRelease(quality_value);
        }
    }
    CGImageDestinationAddImage(destination, emit, properties);
    const bool ok = CGImageDestinationFinalize(destination);
    CFRelease(properties);
    CFRelease(destination);
    CGImageRelease(emit);
    if (!ok && error) *error = "ImageIO failed to write " + path;
    return ok;
}

bool supported_source_type(CFStringRef type) {
    if (!type) return false;
    return CFStringCompare(type, CFSTR("public.jpeg"), 0) == kCFCompareEqualTo ||
           CFStringCompare(type, CFSTR("public.png"), 0) == kCFCompareEqualTo;
}

CGAffineTransform orientation_transform(int orientation, int width, int height,
                                        int out_width, int out_height) {
    switch (orientation) {
        case 1:
            return CGAffineTransformMake(1, 0, 0, 1, 0, 0);
        case 2:
            return CGAffineTransformMake(-1, 0, 0, 1, width, 0);
        case 3:
            return CGAffineTransformMake(-1, 0, 0, -1, width, height);
        case 4:
            return CGAffineTransformMake(1, 0, 0, -1, 0, height);
        case 5:
            return CGAffineTransformMake(0, -1, -1, 0, height, width);
        case 6:
            return CGAffineTransformMake(0, -1, 1, 0, 0, width);
        case 7:
            return CGAffineTransformMake(0, 1, 1, 0, 0, 0);
        case 8:
            return CGAffineTransformMake(0, 1, -1, 0, height, 0);
        default:
            (void)out_width;
            (void)out_height;
            return CGAffineTransformIdentity;
    }
}

bool validate_dimensions(int64_t width, int64_t height, std::string* error) {
    if (width <= 0 || height <= 0) {
        if (error) *error = "image has non-positive dimensions";
        return false;
    }
    if (width > kMaxDimension || height > kMaxDimension) {
        if (error) *error = "image dimension exceeds " +
                            std::to_string(kMaxDimension) + " px";
        return false;
    }
    if (width * height > kMaxPixels) {
        if (error) *error = "image exceeds the 256 MP decode limit";
        return false;
    }
    return true;
}

}  // namespace

bool decode_srgb(const std::string& path, int max_side, Rgba8Image* out,
                 int* applied_orientation, std::string* error) {
    if (!out) {
        if (error) *error = "null image output";
        return false;
    }
    out->width = 0;
    out->height = 0;
    out->pixels.clear();
    if (applied_orientation) *applied_orientation = 1;

    CFURLRef url = CFURLCreateFromFileSystemRepresentation(
        kCFAllocatorDefault,
        reinterpret_cast<const UInt8*>(path.c_str()),
        static_cast<CFIndex>(path.size()), false);
    if (!url) {
        if (error) *error = "cannot create input URL for " + path;
        return false;
    }
    CGImageSourceRef source = CGImageSourceCreateWithURL(url, nullptr);
    CFRelease(url);
    if (!source) {
        if (error) *error = "cannot open image " + path;
        return false;
    }

    CFStringRef type = CGImageSourceGetType(source);
    if (!supported_source_type(type)) {
        const std::string type_name = cf_to_string(type);
        CFRelease(source);
        if (error)
            *error = "unsupported image type" +
                     (type_name.empty() ? std::string() : " '" + type_name + "'") +
                     " (JPEG/PNG only)";
        return false;
    }
    if (CGImageSourceGetCount(source) < 1) {
        CFRelease(source);
        if (error) *error = "image source contains no frames";
        return false;
    }

    int orientation = 1;
    CFDictionaryRef properties =
        CGImageSourceCopyPropertiesAtIndex(source, 0, nullptr);
    if (properties) {
        CFNumberRef orientation_value = static_cast<CFNumberRef>(
            CFDictionaryGetValue(properties, kCGImagePropertyOrientation));
        if (orientation_value)
            CFNumberGetValue(orientation_value, kCFNumberIntType, &orientation);
        CFRelease(properties);
    }
    if (orientation < 1 || orientation > 8) {
        CFRelease(source);
        if (error) *error = "invalid EXIF orientation " + std::to_string(orientation);
        return false;
    }

    CGImageRef image = CGImageSourceCreateImageAtIndex(source, 0, nullptr);
    CFRelease(source);
    if (!image) {
        if (error) *error = "ImageIO could not decode " + path;
        return false;
    }
    const int width = static_cast<int>(CGImageGetWidth(image));
    const int height = static_cast<int>(CGImageGetHeight(image));
    if (!validate_dimensions(width, height, error)) {
        CGImageRelease(image);
        return false;
    }

    const bool swap = orientation >= 5;
    const int rotated_w = swap ? height : width;
    const int rotated_h = swap ? width : height;
    if (!validate_dimensions(rotated_w, rotated_h, error)) {
        CGImageRelease(image);
        return false;
    }

    CGColorSpaceRef srgb = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    if (!srgb) {
        CGImageRelease(image);
        if (error) *error = "cannot create sRGB color space";
        return false;
    }
    CGContextRef context = CGBitmapContextCreate(
        nullptr, static_cast<size_t>(rotated_w), static_cast<size_t>(rotated_h),
        8, static_cast<size_t>(rotated_w) * 4, srgb, rgba8_bitmap_info());
    if (!context) {
        CGImageRelease(image);
        CGColorSpaceRelease(srgb);
        if (error) *error = "cannot allocate decode bitmap context";
        return false;
    }
    CGContextSetInterpolationQuality(context, kCGInterpolationHigh);
    CGContextSetRGBFillColor(context, 1.0, 1.0, 1.0, 1.0);
    CGContextFillRect(context, CGRectMake(0, 0, rotated_w, rotated_h));
    CGContextConcatCTM(context,
                       orientation_transform(orientation, width, height,
                                             rotated_w, rotated_h));
    CGContextDrawImage(context, CGRectMake(0, 0, width, height), image);
    CGImageRelease(image);

    CGImageRef oriented = CGBitmapContextCreateImage(context);
    CGContextRelease(context);
    CGColorSpaceRelease(srgb);
    if (!oriented) {
        if (error) *error = "cannot finalize decoded image";
        return false;
    }

    int final_w = rotated_w;
    int final_h = rotated_h;
    CGImageRef emit = nullptr;
    if (max_side > 0 && std::max(rotated_w, rotated_h) > max_side) {
        emit = resize_image(oriented, max_side, &final_w, &final_h, error);
        CGImageRelease(oriented);
        if (!emit) return false;
    } else {
        emit = oriented;
    }

    /* Re-draw through a known RGBA8 sRGB context so the byte layout is
     * deterministic regardless of ImageIO's internal storage. */
    CGColorSpaceRef out_space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    CGContextRef out_context = CGBitmapContextCreate(
        nullptr, static_cast<size_t>(final_w), static_cast<size_t>(final_h),
        8, static_cast<size_t>(final_w) * 4, out_space, rgba8_bitmap_info());
    if (!out_context) {
        CGImageRelease(emit);
        CGColorSpaceRelease(out_space);
        if (error) *error = "cannot allocate output bitmap context";
        return false;
    }
    CGContextSetInterpolationQuality(out_context, kCGInterpolationHigh);
    CGContextSetRGBFillColor(out_context, 1.0, 1.0, 1.0, 1.0);
    CGContextFillRect(out_context, CGRectMake(0, 0, final_w, final_h));
    CGContextDrawImage(out_context, CGRectMake(0, 0, final_w, final_h), emit);
    CGImageRelease(emit);

    const uint8_t* data =
        static_cast<const uint8_t*>(CGBitmapContextGetData(out_context));
    if (!data) {
        CGContextRelease(out_context);
        CGColorSpaceRelease(out_space);
        if (error) *error = "decoded bitmap has no backing store";
        return false;
    }
    const size_t byte_count = static_cast<size_t>(final_w) * final_h * 4;
    out->pixels.assign(data, data + byte_count);
    for (size_t i = 0; i < static_cast<size_t>(final_w) * final_h; ++i)
        out->pixels[i * 4 + 3] = 255;
    out->width = final_w;
    out->height = final_h;
    if (applied_orientation) *applied_orientation = orientation;
    CGContextRelease(out_context);
    CGColorSpaceRelease(out_space);
    return true;
}

bool probe_image(const std::string& path, ImageInfo* out, std::string* error) {
    if (!out) {
        if (error) *error = "null image info output";
        return false;
    }
    *out = {};
    CFURLRef url = CFURLCreateFromFileSystemRepresentation(
        kCFAllocatorDefault,
        reinterpret_cast<const UInt8*>(path.c_str()),
        static_cast<CFIndex>(path.size()), false);
    if (!url) {
        if (error) *error = "cannot create probe URL for " + path;
        return false;
    }
    CGImageSourceRef source = CGImageSourceCreateWithURL(url, nullptr);
    CFRelease(url);
    if (!source) {
        if (error) *error = "cannot open image " + path;
        return false;
    }
    CFStringRef type = CGImageSourceGetType(source);
    out->type = cf_to_string(type);

    CFDictionaryRef properties =
        CGImageSourceCopyPropertiesAtIndex(source, 0, nullptr);
    if (properties) {
        CFStringRef profile = static_cast<CFStringRef>(
            CFDictionaryGetValue(properties, kCGImagePropertyProfileName));
        out->profile_name = cf_to_string(profile);
        CFStringRef model = static_cast<CFStringRef>(
            CFDictionaryGetValue(properties, kCGImagePropertyColorModel));
        out->color_model = cf_to_string(model);
        CFRelease(properties);
    }

    CGImageRef image = CGImageSourceCreateImageAtIndex(source, 0, nullptr);
    CFRelease(source);
    if (!image) {
        if (error) *error = "ImageIO could not decode " + path;
        return false;
    }
    out->width = static_cast<int>(CGImageGetWidth(image));
    out->height = static_cast<int>(CGImageGetHeight(image));
    out->bits_per_component = static_cast<int>(CGImageGetBitsPerComponent(image));
    CGImageRelease(image);
    return true;
}

bool write_rgba8(const std::string& path, const Rgba8Image& image,
                 ImageFormat format, int max_side, int quality,
                 int* out_width, int* out_height, std::string* error) {
    if (image.width <= 0 || image.height <= 0 ||
        image.pixels.size() < static_cast<size_t>(image.width) * image.height * 4) {
        if (error) *error = "invalid RGBA image buffer";
        return false;
    }
    if (format == ImageFormat::Jpeg && max_side > 0) {
        /* JPEG previews are not used by the worker; keep full resolution. */
    }
    CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    if (!space) {
        if (error) *error = "cannot create sRGB color space";
        return false;
    }
    CGImageRef image_ref = make_rgba8_image(image.pixels.data(), image.width,
                                            image.height, space);
    CGColorSpaceRelease(space);
    if (!image_ref) {
        if (error) *error = "cannot create CGImage";
        return false;
    }
    const bool ok = write_cgimage(path, image_ref, format, max_side, quality,
                                  out_width, out_height, error);
    CGImageRelease(image_ref);
    return ok;
}

bool write_engine_rgb(const std::string& path, const float* rgb, int width,
                      int height, int color_space, bool cctf_encoded,
                      const std::string& asset_dir, ImageFormat format,
                      int quality, std::string* error) {
    if (!rgb || width <= 0 || height <= 0) {
        if (error) *error = "invalid engine output buffer";
        return false;
    }
    if (!validate_dimensions(width, height, error)) return false;
    if (format == ImageFormat::Jpeg && !cctf_encoded) {
        if (error) *error = "linear output cannot be written as JPEG";
        return false;
    }

    CGColorSpaceRef space =
        make_color_space(color_space, cctf_encoded, asset_dir, error);
    if (!space) return false;

    const size_t count = static_cast<size_t>(width) * height;
    CGImageRef image = nullptr;
    if (cctf_encoded) {
        std::vector<uint8_t> rgba(count * 4);
        for (size_t i = 0; i < count; ++i) {
            for (int c = 0; c < 3; ++c) {
                const float v = rgb[i * 3 + c];
                const float clamped = std::isfinite(v) ? std::min(1.0f, std::max(0.0f, v)) : 0.0f;
                rgba[i * 4 + c] =
                    static_cast<uint8_t>(std::lround(clamped * 255.0f));
            }
            rgba[i * 4 + 3] = 255;
        }
        image = make_rgba8_image(rgba.data(), width, height, space);
        if (image) {
            const bool ok = write_cgimage(path, image, format, 0, quality,
                                          nullptr, nullptr, error);
            CGImageRelease(image);
            CGColorSpaceRelease(space);
            return ok;
        }
    } else {
        if (format != ImageFormat::Png) {
            CGColorSpaceRelease(space);
            if (error) *error = "linear output requires PNG";
            return false;
        }
        std::vector<uint16_t> rgba(count * 4);
        for (size_t i = 0; i < count; ++i) {
            for (int c = 0; c < 3; ++c) {
                const float v = rgb[i * 3 + c];
                const float clamped = std::isfinite(v) ? std::min(1.0f, std::max(0.0f, v)) : 0.0f;
                rgba[i * 4 + c] =
                    static_cast<uint16_t>(std::lround(clamped * 65535.0f));
            }
            rgba[i * 4 + 3] = 65535;
        }
        CGDataProviderRef provider = CGDataProviderCreateWithData(
            nullptr, rgba.data(), rgba.size() * sizeof(uint16_t), nullptr);
        if (!provider) {
            CGColorSpaceRelease(space);
            if (error) *error = "cannot create 16-bit data provider";
            return false;
        }
        image = CGImageCreate(
            static_cast<size_t>(width), static_cast<size_t>(height), 16, 64,
            static_cast<size_t>(width) * 8, space,
            static_cast<CGBitmapInfo>(kCGImageAlphaNoneSkipLast |
                                      kCGBitmapByteOrder16Host),
            provider, nullptr, false, kCGRenderingIntentDefault);
        CGDataProviderRelease(provider);
        if (image) {
            const bool ok = write_cgimage(path, image, format, 0, quality,
                                          nullptr, nullptr, error);
            CGImageRelease(image);
            CGColorSpaceRelease(space);
            return ok;
        }
    }

    CGColorSpaceRelease(space);
    if (error) *error = "cannot create output CGImage";
    return false;
}

}  // namespace spkdesk
