// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace strand {

void logi(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
void logw(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
void loge(const char* fmt, ...) __attribute__((format(printf, 1, 2)));

// Every sysfs/proc/cgroup path goes through here, so a host test can point
// the daemon at a fake tree with STRAND_ROOT.
std::string realPath(const std::string& path);

bool readFile(const std::string& path, std::string* out);
// First line of a file, without the newline.
bool readLine(const std::string& path, std::string* out);
bool writeFile(const std::string& path, const std::string& value);
// Our own files (journal, state): created if missing, unlike sysfs writes.
bool writeOwnFile(const std::string& path, const std::string& value);

std::string trim(const std::string& s);
std::vector<std::string> split(const std::string& s);  // on whitespace
bool startsWith(const std::string& s, const std::string& prefix);

// Run a program and collect its stdout. Returns the exit status, or -1.
int run(const std::vector<std::string>& argv, std::string* out, int timeoutSec);

// Run /system/bin/cmd (or $STRAND_CMD) with the given arguments.
int cmd(const std::vector<std::string>& args, std::string* out = nullptr, int timeoutSec = 20);

int64_t nowMs();

// System properties (a file per property under $STRAND_ROOT/props on the host)
std::string getProp(const std::string& name);
bool setProp(const std::string& name, const std::string& value);

}  // namespace strand
