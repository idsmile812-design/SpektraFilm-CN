/*
 * Spektrafilm for Android — local native crash records. GPLv3.
 * Film modeling powered by spektrafilm.
 */
#pragma once

namespace spk::diagnostics {

bool install_native_crash_handler(const char* record_path) noexcept;

}
