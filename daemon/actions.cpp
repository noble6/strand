// SPDX-License-Identifier: Apache-2.0
#include "actions.h"

#include <algorithm>
#include <cstdlib>

#include "util.h"

namespace strand {

namespace {

// zen_mode values (Settings.Global) to `cmd notification set_dnd` names
const char* zenName(const std::string& mode) {
    if (mode == "1") return "priority";
    if (mode == "2") return "none";
    if (mode == "3") return "alarms";
    return "off";
}

bool getSetting(const std::string& ns, const std::string& key, std::string* out) {
    return cmd({"settings", "get", ns, key}, out) == 0;
}

bool putSetting(const std::string& ns, const std::string& key, const std::string& value) {
    if (value == "null") return cmd({"settings", "delete", ns, key}) == 0;
    return cmd({"settings", "put", ns, key, value}) == 0;
}

bool setPowerSave(bool on) {
    return cmd({"power", "set-mode", on ? "1" : "0"}) == 0;
}

// Power HAL FIXED_PERFORMANCE mode. Framework has no getter; it is off
// unless something (us) turned it on.
bool setFixedPerf(bool on) {
    return cmd({"power", "set-fixed-performance-mode-enabled", on ? "true" : "false"}) == 0;
}

bool setDnd(const std::string& mode) {
    return cmd({"notification", "set_dnd", mode}) == 0;
}

// Frequencies for a cpufreq or devfreq node, lowest first: the available
// table if the directory has one, else cpuinfo min/max.
std::vector<long long> frequencies(const std::string& dir) {
    std::vector<long long> f;
    std::string text;
    for (auto name : {"scaling_available_frequencies", "available_frequencies"}) {
        if (readLine(dir + "/" + name, &text)) {
            for (auto& w : split(text)) f.push_back(atoll(w.c_str()));
            break;
        }
    }
    if (f.empty()) {
        std::string mn, mx;
        if (readLine(dir + "/cpuinfo_min_freq", &mn)) f.push_back(atoll(mn.c_str()));
        if (readLine(dir + "/cpuinfo_max_freq", &mx)) f.push_back(atoll(mx.c_str()));
    }
    f.erase(std::remove(f.begin(), f.end(), 0), f.end());
    std::sort(f.begin(), f.end());
    return f;
}

// "@max", "@min" and "@pct:N" (the step nearest to N% of the highest) are resolved from the frequencies next to the written node, so
// one config fits every speed bin of a chip.
bool resolveValue(const std::string& path, const std::string& value, std::string* out) {
    if (value.empty() || value[0] != '@') {
        *out = value;
        return true;
    }
    auto f = frequencies(path.substr(0, path.rfind('/')));
    if (f.empty()) {
        logw("%s: no frequency table for %s", path.c_str(), value.c_str());
        return false;
    }
    if (value == "@max") {
        *out = std::to_string(f.back());
    } else if (value == "@min") {
        *out = std::to_string(f.front());
    } else if (startsWith(value, "@pct:")) {
        long long want = f.back() * atoll(value.c_str() + 5) / 100;
        long long best = f.front();
        for (long long x : f)
            if (llabs(x - want) < llabs(best - want)) best = x;
        *out = std::to_string(best);
    } else {
        logw("bad value %s", value.c_str());
        return false;
    }
    return true;
}

// Returns false if the action did nothing that needs undoing.
bool apply(const Action& a, std::string* original) {
    const auto& v = a.args;
    switch (a.kind) {
        case Action::Kick:
            writeFile(v[0], v[1]);
            return false;
        case Action::Write: {
            std::string value;
            if (!resolveValue(v[0], v[1], &value)) return false;
            if (!readLine(v[0], original)) {
                logw("skip %s: can't read it", v[0].c_str());
                return false;
            }
            *original = trim(*original);
            return writeFile(v[0], value);
        }
        case Action::Prop:
            *original = getProp(v[0]);
            return setProp(v[0], v[1]);
        case Action::Setting:
            if (!getSetting(v[0], v[1], original)) return false;
            return putSetting(v[0], v[1], v[2]);
        case Action::PowerSave:
            if (!getSetting("global", "low_power", original)) return false;
            return setPowerSave(v[0] == "on");
        case Action::DeviceConfig:
            if (cmd({"device_config", "get", v[0], v[1]}, original) != 0) return false;
            return cmd({"device_config", "put", v[0], v[1], v[2]}) == 0;
        case Action::FixedPerf:
            *original = "false";
            return setFixedPerf(v[0] == "on");
        case Action::Dnd:
            if (!getSetting("global", "zen_mode", original)) return false;
            return setDnd(v[0]);
    }
    return false;
}

void revert(const Applied& ap) {
    const auto& v = ap.action.args;
    switch (ap.action.kind) {
        case Action::Kick:
            break;
        case Action::Prop:
            setProp(v[0], ap.original);
            break;
        case Action::Write:
            writeFile(v[0], ap.original);
            break;
        case Action::Setting:
            putSetting(v[0], v[1], ap.original);
            break;
        case Action::PowerSave:
            setPowerSave(ap.original == "1");
            break;
        case Action::FixedPerf:
            setFixedPerf(ap.original == "true");
            break;
        case Action::DeviceConfig:
            if (ap.original == "null" || ap.original.empty())
                cmd({"device_config", "delete", v[0], v[1]});
            else
                cmd({"device_config", "put", v[0], v[1], ap.original});
            break;
        case Action::Dnd:
            setDnd(zenName(ap.original));
            break;
    }
}

}  // namespace

std::vector<Applied> applyActions(const std::vector<Action>& actions) {
    std::vector<Applied> done;
    for (auto& a : actions) {
        Applied ap{a, ""};
        if (apply(a, &ap.original)) done.push_back(ap);
    }
    return done;
}

void revertActions(std::vector<Applied>* applied) {
    for (auto it = applied->rbegin(); it != applied->rend(); ++it) revert(*it);
    applied->clear();
}

}  // namespace strand
