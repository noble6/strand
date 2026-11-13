// SPDX-License-Identifier: Apache-2.0
#include "engine.h"

#include <algorithm>
#include <cerrno>
#include <climits>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <sstream>
#include <sys/ioctl.h>
#include <sys/statvfs.h>
#include <unistd.h>

#include "util.h"

namespace strand {

namespace {

// Applied actions survive a daemon restart here, so a crash or an update
// never leaves the device in a profile nobody can undo. Settings outlive a
// reboot too; sysfs writes don't, so those only count within the same boot.
const char* kJournal = "/data/misc/strand/journal";
// The chosen profile and per-app profiles
const char* kState = "/data/misc/strand/state";

std::string bootId() {
    std::string id;
    readLine("/proc/sys/kernel/random/boot_id", &id);
    return id;
}

std::string encode(const Applied& ap) {
    std::string s = std::to_string(ap.action.kind);
    for (auto& a : ap.action.args) s += "\t" + a;
    return s + "\t=" + ap.original;
}

bool decode(const std::string& line, Applied* ap) {
    std::vector<std::string> f;
    size_t start = 0;
    for (;;) {
        size_t tab = line.find('\t', start);
        f.push_back(line.substr(start, tab == std::string::npos ? std::string::npos : tab - start));
        if (tab == std::string::npos) break;
        start = tab + 1;
    }
    if (f.size() < 2 || f.back().empty() || f.back()[0] != '=') return false;
    int kind = atoi(f[0].c_str());
    if (kind < Action::Write || kind > Action::DeviceConfig) return false;
    ap->action.kind = static_cast<Action::Kind>(kind);
    ap->action.args.assign(f.begin() + 1, f.end() - 1);
    ap->original = f.back().substr(1);
    return true;
}

std::string freeBytes(const std::string& path) {
    struct statvfs st;
    if (statvfs(realPath(path).c_str(), &st)) return "?";
    return std::to_string((unsigned long long)st.f_bavail * st.f_frsize);
}

// "avg10=0.12" etc. from a /proc/pressure file, as "some_avg10 0.12" lines
void psiStats(const std::string& name, std::ostringstream& out) {
    std::string text;
    if (!readFile("/proc/pressure/" + name, &text)) return;
    std::istringstream in(text);
    std::string line;
    while (std::getline(in, line)) {
        auto w = split(line);
        if (w.empty()) continue;
        for (size_t i = 1; i < w.size(); i++) {
            size_t eq = w[i].find('=');
            if (eq == std::string::npos) continue;
            out << "psi_" << name << "_" << w[0] << "_" << w[i].substr(0, eq) << " "
                << w[i].substr(eq + 1) << "\n";
        }
    }
}

}  // namespace

Engine::Engine(Config cfg) : cfg_(std::move(cfg)) {}

void Engine::start() {
    loadState();
    applyThread_ = std::thread(&Engine::applyLoop, this);
    maintThread_ = std::thread(&Engine::maintLoop, this);
}

void Engine::recoverJournal() {
    std::string text;
    if (!readFile(kJournal, &text) || text.empty()) return;
    std::istringstream in(text);
    std::string line, boot;
    std::getline(in, boot);
    const bool sameBoot = boot == bootId();
    std::vector<Applied> undo;
    while (std::getline(in, line)) {
        Applied ap;
        if (!decode(line, &ap)) continue;
        if (ap.action.kind == Action::Write && !sameBoot) continue;
        undo.push_back(ap);
    }
    logi("restoring %zu values left by the last run", undo.size());
    revertActions(&undo);
    writeOwnFile(kJournal, "");
}

void Engine::loadState() {
    std::string text;
    if (!readFile(kState, &text)) return;
    std::istringstream in(text);
    std::string line;
    std::lock_guard<std::mutex> lock(m_);
    while (std::getline(in, line)) {
        auto w = split(line);
        if (w.size() == 2 && w[0] == "profile" && cfg_.find(w[1])) userProfile_ = w[1];
        else if (w.size() == 2 && w[0] == "autogame") autoGame_ = w[1] == "1";
        else if (w.size() == 2 && w[0] == "game") games_.insert(w[1]);
        else if (w.size() == 3 && w[0] == "app" && cfg_.find(w[2])) appProfiles_[w[1]] = w[2];
    }
    logi("state: profile %s, %zu app profiles", userProfile_.c_str(), appProfiles_.size());
}

void Engine::saveStateLocked() {
    std::string s = "profile " + userProfile_ + "\n";
    for (auto& [pkg, prof] : appProfiles_) s += "app " + pkg + " " + prof + "\n";
    s += std::string("autogame ") + (autoGame_ ? "1" : "0") + "\n";
    for (auto& g : games_) s += "game " + g + "\n";
    // Write a new file and rename it, so a crash never leaves half a state
    std::string tmp = std::string(kState) + ".new";
    int fd = open(realPath(tmp).c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        logw("save state: %s", strerror(errno));
        return;
    }
    bool ok = write(fd, s.data(), s.size()) == (ssize_t)s.size() && fsync(fd) == 0;
    close(fd);
    if (!ok || rename(realPath(tmp).c_str(), realPath(kState).c_str()))
        logw("save state failed: %s", strerror(errno));
}

void Engine::saveJournal() {
    // Called on the apply thread with m_ held
    std::string s = bootId() + "\n";
    for (auto& ap : profileUndo_) s += encode(ap) + "\n";
    for (auto& ap : pressureUndo_) s += encode(ap) + "\n";
    for (auto& ap : hotUndo_) s += encode(ap) + "\n";
    for (auto& ap : baseUndo_) s += encode(ap) + "\n";
    writeOwnFile(kJournal, s);
}

std::string Engine::effectiveLocked() {
    auto it = appProfiles_.find(topApp_);
    if (it != appProfiles_.end() && cfg_.find(it->second)) return it->second;
    if (autoGame_ && games_.count(topApp_) && cfg_.find("gaming")) return "gaming";
    return userProfile_;
}

bool Engine::quietLocked() {
    return games_.count(topApp_) > 0 || gpuHeavy_ || thermal_ >= cfg_.hotStatus;
}

void Engine::setThermal(int status) {
    std::lock_guard<std::mutex> lock(m_);
    thermal_ = status;
    applyCv_.notify_all();
}

bool Engine::quiet() {
    std::lock_guard<std::mutex> lock(m_);
    return quietLocked();
}

void Engine::setGames(const std::vector<std::string>& pkgs, bool add) {
    std::lock_guard<std::mutex> lock(m_);
    if (!add) games_.clear();
    games_.insert(pkgs.begin(), pkgs.end());
    saveStateLocked();
    applyCv_.notify_all();
}

void Engine::setAutoGame(bool on) {
    std::lock_guard<std::mutex> lock(m_);
    autoGame_ = on;
    saveStateLocked();
    applyCv_.notify_all();
}

void Engine::setUserProfile(const std::string& name) {
    std::lock_guard<std::mutex> lock(m_);
    userProfile_ = name;
    saveStateLocked();
    applyCv_.notify_all();
}

void Engine::setAppProfile(const std::string& pkg, const std::string& profile) {
    std::lock_guard<std::mutex> lock(m_);
    if (profile.empty()) appProfiles_.erase(pkg);
    else appProfiles_[pkg] = profile;
    saveStateLocked();
    applyCv_.notify_all();
}

void Engine::clearAppProfiles() {
    std::lock_guard<std::mutex> lock(m_);
    appProfiles_.clear();
    saveStateLocked();
    applyCv_.notify_all();
}

bool Engine::interesting(const std::string& pkg) {
    std::lock_guard<std::mutex> lock(m_);
    return appProfiles_.count(pkg) || games_.count(pkg);
}

bool Engine::wantsTopApp() {
    std::lock_guard<std::mutex> lock(m_);
    return !appProfiles_.empty() || !games_.empty();
}

void Engine::onTopApp(const std::string& pkg) {
    std::lock_guard<std::mutex> lock(m_);
    if (pkg == topApp_) return;
    topApp_ = pkg;
    applyCv_.notify_all();
}

void Engine::onPressureEvent() {
    std::lock_guard<std::mutex> lock(m_);
    int64_t now = nowMs();
    pressureEvents_++;
    lastPressureMs_ = now;
    if (!pressureWanted_) {
        pressureWanted_ = true;
        applyCv_.notify_all();
    }
    // No compaction while a game or heavy GPU work runs: it stalls the allocating threads.
    if (!quietLocked() && (now - lastKickMs_ >= cfg_.kickIntervalSec * 1000LL || lastKickMs_ == 0)) {
        // Kicks like compact_memory block the writer until the kernel is
        // done, so they run on the apply thread.
        lastKickMs_ = now;
        kicks_++;
        kickPending_ = true;
        applyCv_.notify_all();
    }
}

void Engine::tick() {
    // Heavy GPU work: over the threshold for gpuBusySec, released 15 points below it
    int gpu = -1;
    if (!cfg_.gpuBusyPath.empty()) {
        std::string v;
        if (readLine(cfg_.gpuBusyPath, &v)) gpu = atoi(trim(v).c_str());
    }
    // Battery saver can't turn on while charging: when the device goes on
    // battery with a profile that wants it, ask again.
    {
        int64_t now = nowMs();
        if (now - lastPowerCheckMs_ >= 10000) {
            lastPowerCheckMs_ = now;
            std::string st;
            bool onBattery = readLine("/sys/class/power_supply/battery/status", &st) &&
                             trim(st) == "Discharging";
            bool wantSaver = false;
            {
                std::lock_guard<std::mutex> lock(m_);
                for (auto& ap : profileUndo_)
                    if (ap.action.kind == Action::PowerSave && ap.action.args[0] == "on")
                        wantSaver = true;
                if (!wantSaver) {
                    const Profile* p = cfg_.find(appliedProfile_);
                    if (p)
                        for (auto& a : p->actions)
                            if (a.kind == Action::PowerSave && a.args[0] == "on") wantSaver = true;
                }
            }
            if (onBattery && !onBattery_ && wantSaver) {
                logi("on battery: battery saver again");
                cmd({"power", "set-mode", "1"});
            }
            onBattery_ = onBattery;
        }
    }

    // Free space on /data, every 10 minutes
    bool clearCaches = false;
    {
        std::lock_guard<std::mutex> lock(m_);
        int64_t now = nowMs();
        if (!lastStorageCheckMs_ || now - lastStorageCheckMs_ >= 600000) {
            lastStorageCheckMs_ = now;
            struct statvfs st;
            if (!statvfs(realPath("/data").c_str(), &st) && st.f_blocks) {
                dataFreePct_ = (int)(100.0 * st.f_bavail / st.f_blocks);
                lowStorage_ = dataFreePct_ < cfg_.lowStoragePct;
                // Nearly full flash is slow flash: give back the app caches,
                // at most once a day and never during a game
                if (lowStorage_ && !quietLocked() &&
                    (!lastAutoClearMs_ || now - lastAutoClearMs_ >= 86400000LL)) {
                    lastAutoClearMs_ = now;
                    clearCaches = true;
                }
            }
        }
    }
    if (clearCaches) {
        logi("low storage (%d%% free): clearing app caches", dataFreePct_);
        queueClearCache();
    }

    std::lock_guard<std::mutex> lock(m_);
    if (gpu >= 0) {
        int64_t now = nowMs();
        if (gpu >= cfg_.gpuBusyPct) {
            if (!gpuBusySinceMs_) gpuBusySinceMs_ = now;
            if (now - gpuBusySinceMs_ >= cfg_.gpuBusySec * 1000LL) gpuHeavy_ = true;
        } else {
            gpuBusySinceMs_ = 0;
            if (gpu < cfg_.gpuBusyPct - 15) gpuHeavy_ = false;
        }
    }
    if (pressureWanted_ && nowMs() - lastPressureMs_ >= cfg_.calmSec * 1000LL) {
        pressureWanted_ = false;
        applyCv_.notify_all();
    }
}

void Engine::applyLoop() {
    // Settings, battery saver and DND go through system_server, which comes
    // up after strandd: undo the last run and apply nothing before boot.
    while (getProp("sys.boot_completed") != "1") sleep(1);
    recoverJournal();
    {
        std::vector<Applied> base = applyActions(cfg_.base);
        std::lock_guard<std::mutex> lock(m_);
        baseUndo_ = std::move(base);
        saveJournal();
    }

    std::unique_lock<std::mutex> lock(m_);
    for (;;) {
        applyCv_.wait(lock, [this] {
            return effectiveLocked() != appliedProfile_ || pressureWanted_ != pressureApplied_ ||
                   (thermal_ >= cfg_.hotStatus) != hotApplied_ || kickPending_;
        });
        if (kickPending_) {
            kickPending_ = false;
            lock.unlock();
            for (auto& a : cfg_.pressure)
                if (a.kind == Action::Kick) writeFile(a.args[0], a.args[1]);
            lock.lock();
            continue;
        }
        const std::string want = effectiveLocked();
        const bool wantPressure = pressureWanted_;
        const bool wantHot = thermal_ >= cfg_.hotStatus;
        const Profile* p = cfg_.find(want);
        if (!p) {
            logw("unknown profile %s", want.c_str());
            p = cfg_.find("balanced");
        }
        const bool profileChange = p->name != appliedProfile_;

        // Pressure tweaks sit on top of the profile: take them off first so
        // each layer restores the values it found.
        // Overlays (pressure on top of hot on top of the profile) come off in
        // reverse order so each restores the values it found.
        std::vector<Applied> pUndo, prUndo, hUndo;
        std::swap(prUndo, pressureUndo_);
        std::swap(hUndo, hotUndo_);
        if (profileChange) std::swap(pUndo, profileUndo_);
        lock.unlock();

        revertActions(&prUndo);
        revertActions(&hUndo);
        if (profileChange) {
            revertActions(&pUndo);
            logi("profile %s", p->name.c_str());
            pUndo = applyActions(p->actions);
        }
        std::vector<Action> pressureWrites;
        for (auto& a : cfg_.pressure)
            if (a.kind != Action::Kick) pressureWrites.push_back(a);
        if (wantHot) hUndo = applyActions(cfg_.hot);
        if (wantPressure) prUndo = applyActions(pressureWrites);

        lock.lock();
        if (profileChange) {
            profileUndo_ = std::move(pUndo);
            appliedProfile_ = p->name;
        }
        pressureUndo_ = std::move(prUndo);
        pressureApplied_ = wantPressure;
        hotUndo_ = std::move(hUndo);
        hotApplied_ = wantHot;
        saveJournal();
    }
}

bool Engine::queue(Job job) {
    std::lock_guard<std::mutex> lock(m_);
    if (jobs_.size() >= 8) return false;
    stopMaint_ = false;
    jobs_.push_back(std::move(job));
    maintCv_.notify_all();
    return true;
}

bool Engine::queueTrim() { return queue({Job::Trim, "", {}}); }
bool Engine::queueGc() { return queue({Job::Gc, "", {}}); }

void Engine::stopMaintenance() {
    std::lock_guard<std::mutex> lock(m_);
    stopMaint_ = true;
    jobs_.clear();
}

bool Engine::waitWhileQuiet() {
    for (;;) {
        {
            std::lock_guard<std::mutex> lock(m_);
            if (stopMaint_) return false;
            if (!quietLocked()) return true;
            maintState_ = "waiting: game or heavy GPU work in front";
        }
        sleep(5);
    }
}
bool Engine::queueClearCache() { return queue({Job::ClearCache, "", {}}); }
bool Engine::queueDexopt(const std::string& filter, const std::vector<std::string>& pkgs) {
    return queue({Job::Dexopt, filter, pkgs});
}

void Engine::maintLoop() {
    std::unique_lock<std::mutex> lock(m_);
    for (;;) {
        maintCv_.wait(lock, [this] { return !jobs_.empty(); });
        Job job = jobs_.front();
        jobs_.pop_front();
        lock.unlock();
        if (waitWhileQuiet()) runJob(job);
        lock.lock();
        maintState_ = "idle";
    }
}

void Engine::runJob(const Job& job) {
    auto state = [this](const std::string& s) {
        std::lock_guard<std::mutex> lock(m_);
        maintState_ = s;
    };
    auto done = [this](std::string* field, const std::string& s) {
        std::lock_guard<std::mutex> lock(m_);
        *field = s;
        logi("%s", s.c_str());
    };

    switch (job.kind) {
        case Job::Trim: {
            state("trim");
            unsigned long long total = 0;
            std::string errs;
            for (auto& path : cfg_.trimPaths) {
                int fd = open(realPath(path).c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
                struct fstrim_range range = {0, ULLONG_MAX, 0};
                if (fd < 0 || ioctl(fd, FITRIM, &range)) {
                    errs += " " + path + ":" + strerror(errno);
                } else {
                    total += range.len;
                }
                if (fd >= 0) close(fd);
            }
            done(&lastTrim_, "trimmed " + std::to_string(total) + errs);
            break;
        }
        case Job::ClearCache: {
            state("clearcache");
            std::string before = freeBytes("/data");
            // Asks for more free space than exists, so every app cache goes.
            int rc = cmd({"package", "trim-caches", "999999999999999"}, nullptr, 300);
            done(&lastClearCache_, "rc " + std::to_string(rc) + " free " + before + " -> " +
                                           freeBytes("/data"));
            break;
        }
        case Job::Gc:
            runGc();
            break;
        case Job::Dexopt: {
            int ok = 0, n = 0;
            for (auto& pkg : job.pkgs) {
                if (!waitWhileQuiet()) break;
                n++;
                state("dexopt " + std::to_string(n) + "/" + std::to_string(job.pkgs.size()) +
                      " " + pkg);
                std::string out;
                int rc = cmd({"package", "compile", "-m", job.filter, "-f", pkg}, &out, 600);
                if (rc == 0) ok++;
                else logw("compile %s: %d %s", pkg.c_str(), rc, out.c_str());
            }
            done(&lastDexopt_, job.filter + " " + std::to_string(ok) + "/" +
                                       std::to_string(job.pkgs.size()));
            break;
        }
    }
}

/*
 * Storage GC, like vold's idle maintenance: put f2fs in urgent GC so it
 * merges partly used segments into free ones, the main reason flash storage
 * (and so the whole device) gets slower over weeks. Runs until few dirty
 * segments are left, progress stops, the time is up, the idle window ends
 * (stop) or a game comes to the front.
 */
void Engine::runGc() {
    static const int pollMs = getenv("STRAND_GC_POLL_MS") ? atoi(getenv("STRAND_GC_POLL_MS")) : 5000;
    std::vector<std::string> devs;
    if (DIR* d = opendir(realPath("/sys/fs/f2fs").c_str())) {
        while (auto* e = readdir(d)) {
            std::string base = std::string("/sys/fs/f2fs/") + e->d_name;
            std::string v;
            if (e->d_name[0] != '.' && readLine(base + "/dirty_segments", &v) &&
                readLine(base + "/gc_urgent", &v))
                devs.push_back(base);
        }
        closedir(d);
    }
    if (devs.empty()) {
        std::lock_guard<std::mutex> lock(m_);
        lastGc_ = "no f2fs";
        return;
    }
    auto dirty = [](const std::string& dev) {
        std::string v;
        return readLine(dev + "/dirty_segments", &v) ? atol(v.c_str()) : -1L;
    };
    std::string report;
    for (auto& dev : devs) {
        long start = dirty(dev);
        if (start <= cfg_.gcTargetDirty) {
            report += " " + dev.substr(13) + ":clean(" + std::to_string(start) + ")";
            continue;
        }
        std::string orig;
        readLine(dev + "/gc_urgent", &orig);
        orig = trim(orig);
        writeFile(dev + "/gc_urgent", "1");
        int64_t t0 = nowMs(), lastProgress = t0;
        long best = start, now = start;
        const char* why = "time";
        for (;;) {
            {
                std::lock_guard<std::mutex> lock(m_);
                maintState_ = "gc " + dev.substr(13) + " " + std::to_string(now) + " dirty";
                if (stopMaint_) { why = "stopped"; break; }
                if (quietLocked()) { why = "game"; break; }
            }
            usleep(pollMs * 1000);
            now = dirty(dev);
            if (now < 0) { why = "gone"; break; }
            if (now <= cfg_.gcTargetDirty) { why = "done"; break; }
            if (now < best - 1) { best = now; lastProgress = nowMs(); }
            if (nowMs() - lastProgress > 12LL * pollMs) { why = "no progress"; break; }
            if (nowMs() - t0 > cfg_.gcMaxMin * 60000LL) break;
        }
        writeFile(dev + "/gc_urgent", orig.empty() ? "0" : orig);
        report += " " + dev.substr(13) + ":" + std::to_string(start) + "->" + std::to_string(now) +
                  "(" + why + ")";
    }
    std::lock_guard<std::mutex> lock(m_);
    lastGc_ = "dirty" + report;
    logi("gc%s", report.c_str());
}

std::string Engine::userProfile() {
    std::lock_guard<std::mutex> lock(m_);
    return userProfile_;
}

std::string Engine::status() {
    std::lock_guard<std::mutex> lock(m_);
    std::ostringstream o;
    o << "profile " << userProfile_ << "\n"
      << "effective " << appliedProfile_ << "\n"
      << "top_app " << (topApp_.empty() ? "-" : topApp_) << "\n"
      << "pressure " << (pressureApplied_ ? 1 : 0) << "\n"
      << "pressure_events " << pressureEvents_ << "\n"
      << "kicks " << kicks_ << "\n"
      << "maint " << maintState_ << "\n"
      << "last_trim " << lastTrim_ << "\n"
      << "last_clearcache " << lastClearCache_ << "\n"
      << "last_dexopt " << lastDexopt_ << "\n"
      << "last_gc " << lastGc_ << "\n"
      << "quiet " << (quietLocked() ? 1 : 0) << "\n"
      << "gpu_heavy " << (gpuHeavy_ ? 1 : 0) << "\n"
      << "autogame " << (autoGame_ ? 1 : 0) << "\n"
      << "games " << games_.size() << "\n"
      << "thermal " << thermal_ << "\n"
      << "hot " << (hotApplied_ ? 1 : 0) << "\n"
      << "data_free_pct " << dataFreePct_ << "\n"
      << "low_storage " << (lowStorage_ ? 1 : 0) << "\n";
    return o.str();
}

std::string Engine::apps() {
    std::lock_guard<std::mutex> lock(m_);
    std::string s;
    for (auto& [pkg, prof] : appProfiles_) s += pkg + " " + prof + "\n";
    return s;
}

std::string Engine::stats() {
    std::ostringstream o;
    psiStats("memory", o);
    psiStats("cpu", o);
    psiStats("io", o);

    std::string text;
    if (readFile("/proc/meminfo", &text)) {
        std::istringstream in(text);
        std::string line;
        while (std::getline(in, line)) {
            auto w = split(line);
            if (w.size() < 2) continue;
            std::string k = w[0].substr(0, w[0].size() - 1);
            if (k == "MemTotal" || k == "MemAvailable" || k == "SwapTotal" || k == "SwapFree" ||
                k == "Cached")
                o << "mem_" << k << "_kb " << w[1] << "\n";
        }
    }
    // orig_data_size compr_data_size mem_used_total ...
    if (readLine("/sys/block/zram0/mm_stat", &text)) {
        auto w = split(text);
        if (w.size() >= 3)
            o << "zram_orig " << w[0] << "\nzram_compr " << w[1] << "\nzram_used " << w[2]
              << "\n";
    }
    // CPU load since the previous stats call, from /proc/stat
    if (readLine("/proc/stat", &text)) {
        auto w = split(text);
        if (w.size() >= 8 && w[0] == "cpu") {
            uint64_t v[7] = {0};
            for (int i = 0; i < 7; i++) v[i] = strtoull(w[i + 1].c_str(), nullptr, 10);
            uint64_t idle = v[3] + v[4], total = 0;
            for (auto x : v) total += x;
            uint64_t busy = total - idle;
            std::lock_guard<std::mutex> lock(m_);
            if (cpuPrevTotal_ && total > cpuPrevTotal_) {
                o << "cpu_load_pct " << (100 * (busy - cpuPrevBusy_) / (total - cpuPrevTotal_))
                  << "\n";
            }
            cpuPrevBusy_ = busy;
            cpuPrevTotal_ = total;
        }
    }
    if (DIR* d = opendir(realPath("/sys/devices/system/cpu/cpufreq").c_str())) {
        std::vector<std::string> pols;
        while (auto* e = readdir(d))
            if (startsWith(e->d_name, "policy")) pols.push_back(e->d_name);
        closedir(d);
        std::sort(pols.begin(), pols.end());
        for (auto& p : pols) {
            std::string base = "/sys/devices/system/cpu/cpufreq/" + p + "/";
            std::string cur, mn, mx;
            if (readLine(base + "scaling_cur_freq", &cur)) o << "cpu_" << p << "_cur " << cur << "\n";
            if (readLine(base + "scaling_min_freq", &mn)) o << "cpu_" << p << "_min " << mn << "\n";
            if (readLine(base + "scaling_max_freq", &mx)) o << "cpu_" << p << "_max " << mx << "\n";
        }
    }
    for (auto& s : cfg_.stats) {
        std::string v;
        if (!readLine(s.path, &v)) continue;
        v = trim(v);
        if (s.div != 1) {
            char* end = nullptr;
            long long n = strtoll(v.c_str(), &end, 10);
            if (end && end != v.c_str()) v = std::to_string(n / s.div);
        }
        o << s.name << " " << v << "\n";
    }
    o << "data_free " << freeBytes("/data") << "\n";
    return o.str();
}

}  // namespace strand
