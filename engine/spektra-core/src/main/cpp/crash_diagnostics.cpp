/*
 * Spektrafilm for Android — local native crash records. GPLv3.
 * Film modeling powered by spektrafilm.
 */
#include "crash_diagnostics.h"

#include <cerrno>
#include <csignal>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>

namespace spk::diagnostics {
namespace {

constexpr int kSignals[] = {SIGABRT, SIGSEGV, SIGBUS, SIGFPE, SIGILL};
constexpr size_t kSignalCount = sizeof(kSignals) / sizeof(kSignals[0]);
constexpr size_t kPathCapacity = 512;
constexpr size_t kRecordCapacity = 512;
constexpr size_t kSignalStackSize = 64 * 1024;

char g_record_path[kPathCapacity];
struct sigaction g_previous[kSignalCount];
unsigned char g_signal_stack[kSignalStackSize];
bool g_installed = false;

size_t literal_length(const char* text) {
    size_t length = 0;
    while (text[length] != '\0') ++length;
    return length;
}

void append_literal(char* output, size_t& offset, const char* text) {
    const size_t length = literal_length(text);
    if (offset + length >= kRecordCapacity) return;
    __builtin_memcpy(output + offset, text, length);
    offset += length;
}

void append_unsigned(char* output, size_t& offset, uintptr_t value, unsigned base) {
    char digits[2 * sizeof(uintptr_t) + 1];
    size_t count = 0;
    do {
        const unsigned digit = value % base;
        digits[count++] = digit < 10 ? static_cast<char>('0' + digit)
                                    : static_cast<char>('a' + digit - 10);
        value /= base;
    } while (value != 0 && count < sizeof(digits));
    while (count > 0 && offset + 1 < kRecordCapacity) output[offset++] = digits[--count];
}

void write_all(int fd, const char* data, size_t length) {
    size_t offset = 0;
    while (offset < length) {
        const ssize_t written = write(fd, data + offset, length - offset);
        if (written > 0) {
            offset += static_cast<size_t>(written);
        } else if (written < 0 && errno != EINTR) {
            return;
        }
    }
}

void write_record(int signal, const siginfo_t* info) {
    char record[kRecordCapacity];
    size_t offset = 0;
    append_literal(record, offset, "spektrafilm-diagnostics/1\n");
    append_literal(record, offset, "=== Spektrafilm native crash ===\n");
    append_literal(record, offset, "thread: native\nsignal: ");
    append_unsigned(record, offset, static_cast<uintptr_t>(signal), 10);
    append_literal(record, offset, "\ncode: ");
    append_unsigned(record, offset, static_cast<uintptr_t>(info == nullptr ? 0 : info->si_code), 10);
    append_literal(record, offset, "\naddress: 0x");
    append_unsigned(
        record,
        offset,
        reinterpret_cast<uintptr_t>(info == nullptr ? nullptr : info->si_addr),
        16
    );
    append_literal(record, offset, "\n");

    const int fd = open(g_record_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return;
    write_all(fd, record, offset);
    close(fd);
}

[[noreturn]] void chain_previous(int index, int signal, siginfo_t* info, void* context) {
    const struct sigaction previous = g_previous[index];
    if ((previous.sa_flags & SA_SIGINFO) != 0 && previous.sa_sigaction != nullptr) {
        previous.sa_sigaction(signal, info, context);
        _exit(128 + signal);
    }
    if (previous.sa_handler != SIG_DFL && previous.sa_handler != SIG_IGN &&
        previous.sa_handler != nullptr) {
        previous.sa_handler(signal);
        _exit(128 + signal);
    }

    struct sigaction action;
    __builtin_memset(&action, 0, sizeof(action));
    action.sa_handler = SIG_DFL;
    sigemptyset(&action.sa_mask);
    sigaction(signal, &action, nullptr);
    sigset_t unblocked;
    sigemptyset(&unblocked);
    sigaddset(&unblocked, signal);
    sigprocmask(SIG_UNBLOCK, &unblocked, nullptr);
    raise(signal);
    _exit(128 + signal);
}

void handle_crash(int signal, siginfo_t* info, void* context) {
    const int saved_errno = errno;
    write_record(signal, info);
    for (size_t index = 0; index < kSignalCount; ++index) {
        if (kSignals[index] == signal) chain_previous(index, signal, info, context);
    }
    errno = saved_errno;
    _exit(128 + signal);
}

} // namespace

bool install_native_crash_handler(const char* record_path) noexcept {
    if (g_installed) return true;
    if (record_path == nullptr || record_path[0] == '\0') return false;
    const size_t length = literal_length(record_path);
    if (length >= kPathCapacity) return false;
    __builtin_memcpy(g_record_path, record_path, length + 1);

    stack_t stack;
    __builtin_memset(&stack, 0, sizeof(stack));
    stack.ss_sp = g_signal_stack;
    stack.ss_size = sizeof(g_signal_stack);
    if (sigaltstack(&stack, nullptr) != 0) return false;

    for (size_t index = 0; index < kSignalCount; ++index) {
        struct sigaction action;
        __builtin_memset(&action, 0, sizeof(action));
        action.sa_sigaction = handle_crash;
        action.sa_flags = SA_SIGINFO | SA_ONSTACK;
        sigemptyset(&action.sa_mask);
        if (sigaction(kSignals[index], &action, &g_previous[index]) != 0) return false;
    }
    g_installed = true;
    return true;
}

} // namespace spk::diagnostics
