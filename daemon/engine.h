// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <condition_variable>
#include <deque>
#include <map>
#include <set>
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
    void setThermal(int status);  // PowerManager thermal status, from the app
    void tick();  // once a second: pressure calm-down
    bool wantsTopApp();
    bool interesting(const std::string& pkg);  // has an app profile, or is a game

    // Maintenance, run one at a time on their own thread
    bool queueTrim();
    bool queueClearCache();
    bool queueDexopt(const std::string& filter, const std::vector<std::string>& pkgs);
    bool queueGc();
    void stopMaintenance();  // the idle window ended: finish the current step, drop the rest

    // Games: the app sends the installed games; one in front means quiet,
    // and with auto game on, the gaming profile.
    void setGames(const std::vector<std::string>& pkgs, bool add);
    void setAutoGame(bool on);
    bool quiet();

    const Config& config() const { return cfg_; }
    std::string userProfile();
    std::string status();  // key value lines
    std::string apps();
    std::string stats();

  private:
    struct Job {
        enum { Trim, ClearCache, Dexopt, Gc } kind;
        std::string filter;
        std::vector<std::string> pkgs;
    };

    void applyLoop();
    void maintLoop();
    void runJob(const Job& job);
    void runGc();
    bool waitWhileQuiet();  // maintenance pauses while quiet; false if stopped
    bool quietLocked();
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
    bool onBattery_ = false;
    int64_t lastPowerCheckMs_ = 0;
    uint64_t cpuPrevBusy_ = 0, cpuPrevTotal_ = 0;
    std::set<std::string> games_;
    bool autoGame_ = true;
    int64_t gpuBusySinceMs_ = 0;
    bool gpuHeavy_ = false;
    bool stopMaint_ = false;

    // What is applied, owned by the apply thread (read under m_)
    std::string appliedProfile_;
    bool pressureApplied_ = false;
    std::vector<Applied> profileUndo_, pressureUndo_, hotUndo_, baseUndo_;
    int thermal_ = 0;
    bool hotApplied_ = false;
    bool lowStorage_ = false;
    int dataFreePct_ = -1;
    int64_t lastStorageCheckMs_ = 0, lastAutoClearMs_ = 0;

    int64_t lastPressureMs_ = 0, lastKickMs_ = 0;
    unsigned long pressureEvents_ = 0, kicks_ = 0;

    std::deque<Job> jobs_;
    std::string maintState_ = "idle";
    std::string lastTrim_ = "never", lastClearCache_ = "never", lastDexopt_ = "never",
                lastGc_ = "never";

    std::thread applyThread_, maintThread_;
};

}  // namespace strand
