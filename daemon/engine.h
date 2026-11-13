// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <condition_variable>
#include <deque>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "actions.h"
#include "config.h"

namespace strand {

class Engine {
  public:
    explicit Engine(Config cfg);
    void start();

    // Called from the main loop
    void setUserProfile(const std::string& name);
    void setAppProfile(const std::string& pkg, const std::string& profile);  // "" clears
    void clearAppProfiles();
    void onTopApp(const std::string& pkg);
    void onPressureEvent();
    void tick();  // once a second: pressure calm-down
    bool wantsTopApp();

    // Maintenance, run one at a time on their own thread
    bool queueTrim();
    bool queueClearCache();
    bool queueDexopt(const std::string& filter, const std::vector<std::string>& pkgs);

    const Config& config() const { return cfg_; }
    std::string userProfile();
    std::string status();  // key value lines
    std::string apps();
    std::string stats();

  private:
    struct Job {
        enum { Trim, ClearCache, Dexopt } kind;
        std::string filter;
        std::vector<std::string> pkgs;
    };

    void applyLoop();
    void maintLoop();
    void runJob(const Job& job);
    void saveJournal();
    void loadState();
    void saveStateLocked();
    void recoverJournal();
    std::string effectiveLocked();
    bool queue(Job job);

    Config cfg_;
    std::mutex m_;
    std::condition_variable applyCv_, maintCv_;

    // Wanted state, set by the main loop
    std::string userProfile_ = "balanced";
    std::map<std::string, std::string> appProfiles_;
    std::string topApp_;
    bool pressureWanted_ = false;
    bool kickPending_ = false;

    // What is applied, owned by the apply thread (read under m_)
    std::string appliedProfile_;
    bool pressureApplied_ = false;
    std::vector<Applied> profileUndo_, pressureUndo_;

    int64_t lastPressureMs_ = 0, lastKickMs_ = 0;
    unsigned long pressureEvents_ = 0, kicks_ = 0;

    std::deque<Job> jobs_;
    std::string maintState_ = "idle";
    std::string lastTrim_ = "never", lastClearCache_ = "never", lastDexopt_ = "never";

    std::thread applyThread_, maintThread_;
};

}  // namespace strand
