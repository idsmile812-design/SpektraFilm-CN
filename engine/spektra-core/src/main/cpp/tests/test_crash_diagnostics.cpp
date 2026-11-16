/*
 * Spektrafilm for Android — host tests for local native crash records. GPLv3.
 */
#include "../crash_diagnostics.h"

#include <csignal>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <string>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

namespace {

std::string read_file(const std::string& path) {
    std::ifstream input(path);
    return std::string(std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>());
}

void previous_handler(int) {
    _exit(42);
}

bool check(bool condition, const char* message) {
    if (!condition) std::cerr << "FAIL: " << message << '\n';
    return condition;
}

bool default_action_test(const std::string& directory) {
    const std::string path = directory + "/default.txt";
    const pid_t child = fork();
    if (child == 0) {
        if (!spk::diagnostics::install_native_crash_handler(path.c_str())) _exit(90);
        raise(SIGSEGV);
        _exit(91);
    }
    int status = 0;
    waitpid(child, &status, 0);
    const std::string record = read_file(path);
    return check(WIFSIGNALED(status) && WTERMSIG(status) == SIGSEGV,
                 "default action still terminates with SIGSEGV") &&
        check(record.find("spektrafilm-diagnostics/1\n") == 0, "versioned record header") &&
        check(record.find("signal: 11") != std::string::npos, "signal number") &&
        check(record.find("address: 0x") != std::string::npos, "fault address");
}

bool previous_action_test(const std::string& directory) {
    const std::string path = directory + "/previous.txt";
    const pid_t child = fork();
    if (child == 0) {
        struct sigaction action;
        std::memset(&action, 0, sizeof(action));
        action.sa_handler = previous_handler;
        sigemptyset(&action.sa_mask);
        sigaction(SIGSEGV, &action, nullptr);
        if (!spk::diagnostics::install_native_crash_handler(path.c_str())) _exit(90);
        raise(SIGSEGV);
        _exit(91);
    }
    int status = 0;
    waitpid(child, &status, 0);
    const std::string record = read_file(path);
    return check(WIFEXITED(status) && WEXITSTATUS(status) == 42,
                 "previous application handler is chained") &&
        check(record.find("=== Spektrafilm native crash ===") != std::string::npos,
              "record is written before chaining");
}

bool invalid_path_test() {
    const std::string too_long(600, 'x');
    return check(!spk::diagnostics::install_native_crash_handler(too_long.c_str()),
                 "oversized path is refused") &&
        check(!spk::diagnostics::install_native_crash_handler(""), "empty path is refused") &&
        check(!spk::diagnostics::install_native_crash_handler(nullptr), "null path is refused");
}

} // namespace

int main() {
    char directory_template[] = "/tmp/spektra-crash-test-XXXXXX";
    char* directory = mkdtemp(directory_template);
    if (directory == nullptr) {
        std::cerr << "could not create test directory\n";
        return 1;
    }
    const bool ok = default_action_test(directory) && previous_action_test(directory) &&
        invalid_path_test();
    unlink((std::string(directory) + "/default.txt").c_str());
    unlink((std::string(directory) + "/previous.txt").c_str());
    rmdir(directory);
    if (!ok) return 1;
    std::cout << "crash diagnostics tests passed\n";
    return 0;
}
