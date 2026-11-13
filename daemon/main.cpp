// SPDX-License-Identifier: Apache-2.0
//
// strandd: the Strand performance daemon.
//
// Listens on the init socket "strandd" for line commands (see README.md),
// applies profiles, follows the foreground app for per-app profiles and
// reacts to memory pressure through PSI triggers.
#include <cerrno>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <dirent.h>


#include "config.h"
#include "engine.h"
#include "util.h"

using namespace strand;

namespace {

constexpr const char* kVersion = "2";
constexpr int kMaxClients = 8;

std::vector<std::string> configFiles() {
    if (const char* env = getenv("STRAND_CONFIG")) return split(env);
    // The generic config ships in the Strand APEX (updatable); the device's on system_ext
    std::string generic = access("/apex/org.strand.core/etc/strand.conf", R_OK) == 0
            ? "/apex/org.strand.core/etc/strand.conf"
            : "/system_ext/etc/strand/strand.conf";
    return {generic, "/system_ext/etc/strand/device.conf"};
}

int listenSocket() {
    // init passes the socket from the rc file as ANDROID_SOCKET_strandd=<fd>
    // (what libcutils' android_get_control_socket reads)
    const char* env = getenv("ANDROID_SOCKET_strandd");
    int fd = env ? atoi(env) : -1;
    if (fd > 2) {
        if (listen(fd, 4)) {
            loge("listen: %s", strerror(errno));
            return -1;
        }
        return fd;
    }
    // Host tests, or started by hand
    const char* path = getenv("STRAND_SOCKET");
    if (!path) path = "/dev/socket/strandd";
    int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un addr = {};
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
    unlink(path);
    if (bind(s, (struct sockaddr*)&addr, sizeof(addr)) || listen(s, 4)) {
        loge("bind %s: %s", path, strerror(errno));
        return -1;
    }
    chmod(path, 0660);
    return s;
}

// PSI trigger: POLLPRI when memory stalls pass the threshold in a window
int openPsi(const Config& cfg) {
    int fd = open(realPath("/proc/pressure/memory").c_str(), O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        logw("no memory PSI: %s", strerror(errno));
        return -1;
    }
    std::string trig = "some " + std::to_string(cfg.psiSomeMs * 1000) + " " +
                       std::to_string(cfg.psiWindowMs * 1000);
    if (write(fd, trig.c_str(), trig.size() + 1) < 0) {
        logw("PSI trigger '%s': %s", trig.c_str(), strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

// The foreground app: first process in the top-app cpuset that belongs to an
// app (uid >= 10000) and isn't a :service process.
std::string topApp() {
    std::string procs;
    if (!readFile("/dev/cpuset/top-app/cgroup.procs", &procs)) return "";
    for (auto& pid : split(procs)) {
        std::string dir = "/proc/" + pid;
        struct stat st;
        if (stat(realPath(dir).c_str(), &st)) continue;
#ifdef __ANDROID__
        if (st.st_uid % 100000 < 10000) continue;
#endif
        std::string cmdline;
        if (!readFile(dir + "/cmdline", &cmdline)) continue;
        cmdline = cmdline.c_str();  // up to the first NUL
        if (cmdline.empty() || cmdline.find(':') != std::string::npos) continue;
        return cmdline;
    }
    return "";
}

bool validName(const std::string& s) {
    if (s.empty() || s.size() > 200) return false;
    for (char c : s)
        if (!isalnum((unsigned char)c) && c != '.' && c != '_') return false;
    return true;
}

struct Client {
    int fd;
    std::string in;
};

std::string handle(Engine& engine, const std::string& line) {
    auto w = split(line);
    if (w.empty()) return "err empty\n";
    const std::string& c = w[0];
    const Config& cfg = engine.config();

    if (c == "ping") return std::string("ok strandd ") + kVersion + "\n";
    if (c == "status") return "ok\n" + engine.status();
    if (c == "stats") return "ok\n" + engine.stats();
    if (c == "profiles") {
        std::string s = "ok\n";
        for (auto& p : cfg.profiles) s += p.name + " " + p.label + "\n";
        return s;
    }
    if (c == "profile") {
        if (w.size() == 1) return "ok " + engine.userProfile() + "\n";
        if (w.size() != 2 || !cfg.find(w[1])) return "err unknown profile\n";
        engine.setUserProfile(w[1]);
        return "ok\n";
    }
    if (c == "app") {
        // app PKG PROFILE | app PKG none
        if (w.size() != 3 || !validName(w[1])) return "err usage: app PKG PROFILE|none\n";
        if (w[2] == "none") engine.setAppProfile(w[1], "");
        else if (cfg.find(w[2])) engine.setAppProfile(w[1], w[2]);
        else return "err unknown profile\n";
        return "ok\n";
    }
    if (c == "apps") {
        if (w.size() == 2 && w[1] == "clear") {
            engine.clearAppProfiles();
            return "ok\n";
        }
        return "ok\n" + engine.apps();
    }
    if (c == "version") return std::string("ok ") + kVersion + "\n";
    if (c == "trim") return engine.queueTrim() ? "ok queued\n" : "err busy\n";
    if (c == "thermal") {
        if (w.size() != 2 || w[1].find_first_not_of("0123456789") != std::string::npos)
            return "err usage: thermal STATUS\n";
        engine.setThermal(atoi(w[1].c_str()));
        return "ok\n";
    }
    if (c == "gc") return engine.queueGc() ? "ok queued\n" : "err busy\n";
    if (c == "stop") {
        engine.stopMaintenance();
        return "ok\n";
    }
    if (c == "autogame") {
        if (w.size() != 2 || (w[1] != "on" && w[1] != "off")) return "err usage: autogame on|off\n";
        engine.setAutoGame(w[1] == "on");
        return "ok\n";
    }
    if (c == "games") {
        // games clear | games add PKG...
        if (w.size() >= 2 && w[1] == "clear") {
            engine.setGames({}, false);
            return "ok\n";
        }
        if (w.size() < 3 || w[1] != "add") return "err usage: games clear | games add PKG...\n";
        std::vector<std::string> pkgs;
        for (size_t i = 2; i < w.size(); i++) {
            if (!validName(w[i])) return "err bad package " + w[i] + "\n";
            pkgs.push_back(w[i]);
        }
        engine.setGames(pkgs, true);
        return "ok\n";
    }
    if (c == "clearcache") return engine.queueClearCache() ? "ok queued\n" : "err busy\n";
    if (c == "dexopt") {
        // dexopt FILTER PKG...
        static const char* filters[] = {"speed-profile", "speed", "verify", "everything"};
        if (w.size() < 3) return "err usage: dexopt FILTER PKG...\n";
        bool okFilter = false;
        for (auto f : filters) okFilter |= w[1] == f;
        if (!okFilter) return "err bad filter\n";
        std::vector<std::string> pkgs;
        for (size_t i = 2; i < w.size(); i++) {
            if (!validName(w[i])) return "err bad package " + w[i] + "\n";
            pkgs.push_back(w[i]);
        }
        return engine.queueDexopt(w[1], pkgs) ? "ok queued\n" : "err busy\n";
    }
    return "err unknown command\n";
}

bool allowedPeer(int fd) {
    struct ucred cred;
    socklen_t len = sizeof(cred);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &len)) return false;
    // root, system (the Strand app runs as system) and shell for strandctl.
    // SELinux limits the socket to those domains too; the 0666 mode is so
    // plain adb shell gets that far.
    return cred.uid == 0 || cred.uid == 1000 || cred.uid == 2000 || cred.uid == getuid();
}

}  // namespace

int main() {
    signal(SIGPIPE, SIG_IGN);

    Config cfg;
    std::string err;
    if (!cfg.load(configFiles(), &err)) {
        loge("config: %s", err.c_str());
        return 1;
    }
    logi("%zu profiles, %zu pressure actions", cfg.profiles.size(), cfg.pressure.size());

    int lfd = listenSocket();
    if (lfd < 0) return 1;
    int psi = openPsi(cfg);

    Engine engine(cfg);
    engine.start();

    std::vector<Client> clients;
    int64_t lastTick = 0;
    for (;;) {
        std::vector<struct pollfd> pfds;
        pfds.push_back({lfd, POLLIN, 0});
        if (psi >= 0) pfds.push_back({psi, POLLPRI, 0});
        for (auto& cl : clients) pfds.push_back({cl.fd, POLLIN, 0});

        int r = poll(pfds.data(), pfds.size(), 1000);
        if (r < 0 && errno != EINTR) {
            loge("poll: %s", strerror(errno));
            sleep(1);
        }

        size_t i = 0;
        if (pfds[i++].revents & POLLIN) {
            int fd = accept4(lfd, nullptr, nullptr, SOCK_CLOEXEC);
            if (fd >= 0) {
                if (!allowedPeer(fd) || (int)clients.size() >= kMaxClients) {
                    close(fd);
                } else {
                    clients.push_back({fd, ""});
                }
            }
        }
        if (psi >= 0) {
            short ev = pfds[i++].revents;
            if (ev & POLLPRI) engine.onPressureEvent();
            if (ev & POLLERR) {
                logw("PSI trigger gone");
                close(psi);
                psi = -1;
            }
        }
        // Clients added this round have no pollfd yet; only walk the polled ones.
        size_t polled = pfds.size() - i;
        std::vector<Client> keep;
        for (size_t k = 0; k < clients.size(); k++) {
            Client& cl = clients[k];
            bool alive = true;
            if (k < polled && (pfds[i + k].revents & (POLLIN | POLLHUP | POLLERR))) {
                char buf[1024];
                ssize_t n = read(cl.fd, buf, sizeof(buf));
                if (n <= 0) {
                    alive = false;
                } else {
                    cl.in.append(buf, n);
                    size_t nl;
                    while (alive && (nl = cl.in.find('\n')) != std::string::npos) {
                        std::string line = cl.in.substr(0, nl);
                        cl.in.erase(0, nl + 1);
                        std::string reply = handle(engine, line) + ".\n";
                        if (write(cl.fd, reply.data(), reply.size()) != (ssize_t)reply.size())
                            alive = false;
                    }
                    if (cl.in.size() > 8192) alive = false;
                }
            }
            if (alive) keep.push_back(cl);
            else close(cl.fd);
        }
        clients.swap(keep);

        int64_t now = nowMs();
        if (now - lastTick >= 1000) {
            lastTick = now;
            engine.tick();
            if (engine.wantsTopApp()) engine.onTopApp(topApp());
        }
    }
}
