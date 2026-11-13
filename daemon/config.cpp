// SPDX-License-Identifier: Apache-2.0
#include "config.h"

#include <cstdlib>
#include <fstream>
#include <sstream>

#include "util.h"

namespace strand {

std::string Action::describe() const {
    static const char* names[] = {"write", "setting", "powersave", "dnd", "kick", "prop", "fixedperf", "deviceconfig"};
    std::string s = names[kind];
    for (auto& a : args) s += " " + a;
    return s;
}

static bool parseAction(const std::vector<std::string>& w, Action* a, std::string* err) {
    const std::string& k = w[0];
    size_t n = w.size() - 1;
    if (k == "write" && n == 2) {
        a->kind = Action::Write;
    } else if (k == "kick" && n == 2) {
        a->kind = Action::Kick;
    } else if (k == "prop" && n == 2 && startsWith(w[1], "sys.strand.")) {
        a->kind = Action::Prop;
    } else if (k == "setting" && n == 3 &&
               (w[1] == "system" || w[1] == "secure" || w[1] == "global")) {
        a->kind = Action::Setting;
    } else if (k == "powersave" && n == 1 && (w[1] == "on" || w[1] == "off")) {
        a->kind = Action::PowerSave;
    } else if (k == "deviceconfig" && n == 3) {
        a->kind = Action::DeviceConfig;
    } else if (k == "fixedperf" && n == 1 && (w[1] == "on" || w[1] == "off")) {
        a->kind = Action::FixedPerf;
    } else if (k == "dnd" && n == 1 &&
               (w[1] == "off" || w[1] == "priority" || w[1] == "alarms" || w[1] == "none")) {
        a->kind = Action::Dnd;
    } else {
        *err = "bad action: " + k;
        return false;
    }
    a->args.assign(w.begin() + 1, w.end());
    return true;
}

const Profile* Config::find(const std::string& name) const {
    for (auto& p : profiles)
        if (p.name == name) return &p;
    return nullptr;
}

bool Config::load(const std::vector<std::string>& files, std::string* err) {
    for (auto& file : files) {
        // Not through readFile(): config paths are real paths even in host tests.
        std::ifstream in(file);
        if (!in) continue;  // the device file is optional
        std::string line, section;
        Profile* profile = nullptr;
        int lineno = 0;
        while (std::getline(in, line)) {
            lineno++;
            size_t hash = line.find('#');
            if (hash != std::string::npos) line.resize(hash);
            line = trim(line);
            if (line.empty()) continue;
            auto fail = [&](const std::string& why) {
                *err = file + ":" + std::to_string(lineno) + ": " + why;
                return false;
            };
            if (line.front() == '[') {
                if (line.back() != ']') return fail("bad section");
                auto w = split(line.substr(1, line.size() - 2));
                if (w.empty()) return fail("bad section");
                section = w[0];
                profile = nullptr;
                if (section == "profile") {
                    if (w.size() != 2) return fail("profile needs a name");
                    for (auto& p : profiles)
                        if (p.name == w[1]) profile = &p;
                    if (!profile) {
                        profiles.push_back({w[1], w[1], {}});
                        profile = &profiles.back();
                    }
                } else if (section != "memory" && section != "pressure" && section != "stats" &&
                           section != "trim" && section != "adaptive") {
                    return fail("unknown section " + section);
                }
                continue;
            }
            size_t eq = line.find('=');
            if (eq != std::string::npos && section == "adaptive") {
                std::string key = trim(line.substr(0, eq)), val = trim(line.substr(eq + 1));
                if (key == "gpu_busy_path") gpuBusyPath = val;
                else if (key == "gpu_busy_pct") gpuBusyPct = atoi(val.c_str());
                else if (key == "gpu_busy_s") gpuBusySec = atoi(val.c_str());
                else if (key == "gc_target_dirty") gcTargetDirty = atoi(val.c_str());
                else if (key == "gc_max_min") gcMaxMin = atoi(val.c_str());
                else return fail("unknown key " + key);
                continue;
            }
            if (eq != std::string::npos && (section == "memory" || section == "profile")) {
                std::string key = trim(line.substr(0, eq)), val = trim(line.substr(eq + 1));
                if (section == "profile" && key == "label") {
                    profile->label = val;
                    continue;
                }
                int v = atoi(val.c_str());
                if (key == "psi_some_ms") psiSomeMs = v;
                else if (key == "psi_window_ms") psiWindowMs = v;
                else if (key == "calm_s") calmSec = v;
                else if (key == "kick_interval_s") kickIntervalSec = v;
                else return fail("unknown key " + key);
                continue;
            }
            auto w = split(line);
            if (section == "profile" || section == "pressure") {
                Action a;
                if (!parseAction(w, &a, err)) return fail(*err);
                (profile ? profile->actions : pressure).push_back(a);
            } else if (section == "stats") {
                if (w[0] != "stat" || w.size() < 3 || w.size() > 4) return fail("stat NAME PATH [DIV]");
                Stat s{w[1], w[2], w.size() == 4 ? atol(w[3].c_str()) : 1};
                if (s.div <= 0) s.div = 1;
                stats.push_back(s);
            } else if (section == "trim") {
                if (w[0] != "path" || w.size() != 2) return fail("path DIR");
                trimPaths.push_back(w[1]);
            } else {
                return fail("line outside a section");
            }
        }
    }
    if (!find("balanced")) profiles.insert(profiles.begin(), {"balanced", "Balanced", {}});
    if (trimPaths.empty()) trimPaths.push_back("/data");
    return true;
}

}  // namespace strand
