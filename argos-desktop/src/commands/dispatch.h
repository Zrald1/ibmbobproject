#pragma once

// Shared command dispatcher — the single entry point that turns a
// {method, params} pair into an action on this machine.
//
// Used by both transports:
//   phone/phone_server  — direct LAN pairing (QR with ip/port/token)
//   link/link_client    — backend relay (QR with url/desktop_id/pair_code)
//
// Returns {ok:true, result:{...}} or {ok:false, error:"..."} — the same
// envelope the IDE bridge uses, so all three transports speak one protocol.

#include <string>

#include <nlohmann/json.hpp>

namespace argos::commands {

nlohmann::json dispatch(const std::string& method, const nlohmann::json& params);

}  // namespace argos::commands
