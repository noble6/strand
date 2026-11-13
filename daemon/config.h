// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <string>
#include <vector>

namespace strand {

// One thing a profile (or memory pressure) changes. Everything but `kick`
// remembers the value it replaced and puts it back when reverted.
struct Action {
    enum Kind { Write, Setting, PowerSave, Dnd, Kick, Prop, FixedPerf } kind;
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

    std::vector<Stat> stats;
    std::vector<std::string> trimPaths;

    // Later files add to (and override labels from) earlier ones.
    bool load(const std::vector<std::string>& files, std::string* err);
    const Profile* find(const std::string& name) const;
};

}  // namespace strand
