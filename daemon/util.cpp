// SPDX-License-Identifier: Apache-2.0
#include "util.h"

#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <android/log.h>
#include <sys/system_properties.h>
#endif

namespace strand {

static void vlog(int prio, const char* fmt, va_list ap) {
#ifdef __ANDROID__
    __android_log_vprint(prio, "strandd", fmt, ap);
#else
    static const char* tags[] = {"?", "?", "?", "?", "I", "W", "E"};
    fprintf(stderr, "strandd %s: ", tags[prio]);
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
#endif
}

#define LOGFN(name, prio)            \
    void name(const char* fmt, ...) { \
        va_list ap;                   \
        va_start(ap, fmt);            \
        vlog(prio, fmt, ap);          \
        va_end(ap);                   \
    }
LOGFN(logi, 4)
LOGFN(logw, 5)
LOGFN(loge, 6)

std::string realPath(const std::string& path) {
    static const char* root = getenv("STRAND_ROOT");
    if (root && *root && !path.empty() && path[0] == '/') return root + path;
    return path;
}

bool readFile(const std::string& path, std::string* out) {
    int fd = open(realPath(path).c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    out->clear();
    char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) out->append(buf, n);
    close(fd);
    return n == 0;
}

bool readLine(const std::string& path, std::string* out) {
    if (!readFile(path, out)) return false;
    size_t nl = out->find('\n');
    if (nl != std::string::npos) out->resize(nl);
    return true;
}

bool writeFile(const std::string& path, const std::string& value) {
    int fd = open(realPath(path).c_str(), O_WRONLY | O_CLOEXEC | O_TRUNC);
    if (fd < 0) {
        logw("open %s: %s", path.c_str(), strerror(errno));
        return false;
    }
    ssize_t n = write(fd, value.data(), value.size());
    int err = errno;
    close(fd);
    if (n != (ssize_t)value.size()) {
        logw("write %s = %s: %s", path.c_str(), value.c_str(), strerror(err));
        return false;
    }
    return true;
}

std::string trim(const std::string& s) {
    size_t b = s.find_first_not_of(" \t\r\n");
    if (b == std::string::npos) return "";
    size_t e = s.find_last_not_of(" \t\r\n");
    return s.substr(b, e - b + 1);
}

std::vector<std::string> split(const std::string& s) {
    std::vector<std::string> v;
    size_t i = 0;
    while (i < s.size()) {
        while (i < s.size() && isspace((unsigned char)s[i])) i++;
        size_t j = i;
        while (j < s.size() && !isspace((unsigned char)s[j])) j++;
        if (j > i) v.push_back(s.substr(i, j - i));
        i = j;
    }
    return v;
}

bool startsWith(const std::string& s, const std::string& prefix) {
    return s.compare(0, prefix.size(), prefix) == 0;
}

int run(const std::vector<std::string>& argv, std::string* out, int timeoutSec) {
    if (argv.empty()) return -1;
    int pipefd[2];
    if (pipe2(pipefd, O_CLOEXEC)) return -1;
    pid_t pid = fork();
    if (pid < 0) {
        close(pipefd[0]);
        close(pipefd[1]);
        return -1;
    }
    if (pid == 0) {
        dup2(pipefd[1], STDOUT_FILENO);
        int devnull = open("/dev/null", O_RDWR);
        if (devnull >= 0) {
            dup2(devnull, STDIN_FILENO);
            dup2(devnull, STDERR_FILENO);
        }
        std::vector<char*> args;
        for (auto& a : argv) args.push_back(const_cast<char*>(a.c_str()));
        args.push_back(nullptr);
        execv(args[0], args.data());
        _exit(127);
    }
    close(pipefd[1]);
    std::string buf;
    int64_t deadline = nowMs() + (int64_t)timeoutSec * 1000;
    bool timedOut = false;
    for (;;) {
        int64_t left = deadline - nowMs();
        if (timeoutSec > 0 && left <= 0) {
            timedOut = true;
            break;
        }
        struct pollfd p = {pipefd[0], POLLIN, 0};
        int r = poll(&p, 1, timeoutSec > 0 ? (int)left : -1);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) continue;
        char tmp[1024];
        ssize_t n = read(pipefd[0], tmp, sizeof(tmp));
        if (n <= 0) break;
        if (buf.size() < 65536) buf.append(tmp, n);
    }
    close(pipefd[0]);
    if (timedOut) {
        logw("%s timed out", argv[0].c_str());
        kill(pid, SIGKILL);
    }
    int status = 0;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {
    }
    if (out) *out = buf;
    if (timedOut || !WIFEXITED(status)) return -1;
    return WEXITSTATUS(status);
}

int cmd(const std::vector<std::string>& args, std::string* out, int timeoutSec) {
    static const char* bin = getenv("STRAND_CMD");
    std::vector<std::string> argv = {bin && *bin ? bin : "/system/bin/cmd"};
    argv.insert(argv.end(), args.begin(), args.end());
    std::string o;
    int rc = run(argv, &o, timeoutSec);
    if (out) *out = trim(o);
    return rc;
}

int64_t nowMs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

std::string getProp(const std::string& name) {
#ifdef __ANDROID__
    char v[PROP_VALUE_MAX] = {};
    __system_property_get(name.c_str(), v);
    return v;
#else
    std::string v;
    readLine("/props/" + name, &v);
    return v;
#endif
}

bool setProp(const std::string& name, const std::string& value) {
#ifdef __ANDROID__
    if (__system_property_set(name.c_str(), value.c_str())) {
        logw("setprop %s %s failed", name.c_str(), value.c_str());
        return false;
    }
    return true;
#else
    return writeFile("/props/" + name, value);
#endif
}

}  // namespace strand
