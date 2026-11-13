// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <string>
#include <vector>

#include "config.h"

namespace strand {

struct Applied {
    Action action;
    std::string original;
};

// Applies actions in order and returns what is needed to undo them.
// Actions that fail are logged and left out.
std::vector<Applied> applyActions(const std::vector<Action>& actions);

// Undoes applied actions in reverse order.
void revertActions(std::vector<Applied>* applied);

}  // namespace strand
