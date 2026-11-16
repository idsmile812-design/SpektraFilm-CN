/*
 * Spektrafilm desktop worker entry point.
 * GPLv3. Film modeling powered by spektrafilm.
 *
 *   spektra_worker <asset_dir> <session_work_dir>
 *
 * The first argument is the extracted assets/spektra directory; the second is
 * the only directory request paths may resolve inside. stdout is reserved for
 * NDJSON responses; startup/diagnostic messages go to stderr.
 */
#include <cstdio>
#include <string>

#include "worker.h"

int main(int argc, char** argv) {
    if (argc != 3) {
        std::fprintf(stderr,
                     "usage: %s <asset_dir> <session_work_dir>\n",
                     argc > 0 ? argv[0] : "spektra_worker");
        return 2;
    }
    spkdesk::Worker worker{std::string(argv[1]), std::string(argv[2])};
    return worker.serve();
}
