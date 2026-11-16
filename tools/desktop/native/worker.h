/*
 * Spektrafilm desktop worker — resident NDJSON engine host.
 * GPLv3. Film modeling powered by spektrafilm.
 */
#ifndef SPK_DESKTOP_WORKER_H
#define SPK_DESKTOP_WORKER_H

#include <memory>
#include <string>

namespace spkdesk {

/* One process owns one spk_engine and at most one decoded source image.
 * stdin/stdout speak NDJSON; diagnostics never go to stdout. */
class Worker {
public:
    Worker(std::string asset_dir, std::string session_dir);
    ~Worker();

    Worker(const Worker&) = delete;
    Worker& operator=(const Worker&) = delete;

    /* Returns the process exit code (0 on EOF/close, 2 on startup failure). */
    int serve();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace spkdesk

#endif  // SPK_DESKTOP_WORKER_H
