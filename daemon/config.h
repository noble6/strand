// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <string>
#include <vector>

namespace strand {

// One thing a profile (or memory pressure) changes. Everything but `kick`
// remembers the value it replaced and puts it back when reverted.
struct Action {
    enum Kind { Write, Setting, PowerSave, Dnd, Kick, Prop, FixedPerf, DeviceConfig } kind;
    std::vector<std::string> args;
    std::string describe() const;
};

struct Profile {
    std::string name;
    std::string label;
    std::vector<Action> actions;
};

struct Stat {
    std::string name;
    std::string path;
    long div = 1;
};

struct Config {
    std::vector<Profile> profiles;

    // Memory pressure: PSI "some" stall of psiSomeMs within psiWindowMs
    // counts as pressure. The [pressure] writes stay until calmSec pass
    // without another event; kicks run at most every kickIntervalSec.
    int psiSomeMs = 150;
    int psiWindowMs = 1000;
    int calmSec = 30;
    int kickIntervalSec = 120;
    std::vector<Action> pressure;
    // Always applied once the system is up (Android features worth turning on)
    std::vector<Action> base;
    // Applied while the device is hot (thermal status >= hotStatus)
    std::vector<Action> hot;
    int hotStatus = 3;          // PowerManager THERMAL_STATUS_SEVERE
    // Below this much free space on /data, clear app caches (once a day)
    int lowStoragePct = 10;

    std::vector<Stat> stats;
    std::vector<std::string> trimPaths;

    // [adaptive]: stay out of the way of games and heavy GPU work
    std::string gpuBusyPath;   // a "NN %" or "NN" load file; empty = no GPU check
    int gpuBusyPct = 85;       // this load ...
    int gpuBusySec = 5;        // ... for this long counts as heavy
    // Storage GC (f2fs) in idle windows: stop below this many dirty
    // segments, or after gcMaxMin minutes.
    int gcTargetDirty = 500;
    int gcMaxMin = 10;

    // Later files add to (and override labels from) earlier ones.
    bool load(const std::vector<std::string>& files, std::string* err);
    const Profile* find(const std::string& name) const;
};

}  // namespace strand
