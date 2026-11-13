// SPDX-License-Identifier: Apache-2.0
//
// strandctl: send one command to strandd and print the reply.
//   strandctl profile performance
//   strandctl stats
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr,
                "usage: strandctl COMMAND [ARGS]\n"
                "  ping | status | stats | profiles | profile [NAME]\n"
                "  app PKG PROFILE|none | apps [clear]\n"
                "  trim | clearcache | dexopt FILTER PKG...\n");
        return 2;
    }
    std::string line;
    for (int i = 1; i < argc; i++) line += (i > 1 ? " " : "") + std::string(argv[i]);
    line += "\n";

    const char* path = getenv("STRAND_SOCKET");
    if (!path) path = "/dev/socket/strandd";
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un addr = {};
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
    if (fd < 0 || connect(fd, (struct sockaddr*)&addr, sizeof(addr))) {
        perror("strandctl: connect");
        return 1;
    }
    if (write(fd, line.data(), line.size()) != (ssize_t)line.size()) {
        perror("strandctl: write");
        return 1;
    }
    std::string reply;
    char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        reply.append(buf, n);
        if (reply.size() >= 2 && reply.compare(reply.size() - 2, 2, ".\n") == 0 &&
            (reply.size() == 2 || reply[reply.size() - 3] == '\n'))
            break;
    }
    close(fd);
    if (reply.size() >= 2) reply.resize(reply.size() - 2);  // drop the "." end line
    fwrite(reply.data(), 1, reply.size(), stdout);
    return reply.compare(0, 3, "err") == 0 ? 1 : 0;
}
