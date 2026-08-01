#include "runtime_contract.h"

#include <algorithm>
#include <cctype>
#include <filesystem>
#include <map>
#include <regex>
#include <sstream>
#include <unordered_map>
#include <unordered_set>

namespace qnzapret {
namespace {

using Fields = std::vector<std::string>;

Fields Split(const std::string& value, char delimiter) {
  Fields output;
  std::stringstream stream(value);
  std::string item;
  while (std::getline(stream, item, delimiter)) {
    output.push_back(item);
  }
  if (!value.empty() && value.back() == delimiter) {
    output.emplace_back();
  }
  return output;
}

bool IsSafeIdentifier(const std::string& value) {
  static const std::regex kPattern("^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$");
  return std::regex_match(value, kPattern);
}

bool ParsePortList(const std::string& raw,
                   const std::unordered_set<int>& allowed,
                   std::string* normalized) {
  if (raw.empty()) {
    normalized->clear();
    return true;
  }
  std::vector<int> ports;
  for (const auto& token : Split(raw, ',')) {
    if (token.empty() ||
        !std::all_of(token.begin(), token.end(), ::isdigit)) {
      return false;
    }
    const int port = std::stoi(token);
    if (allowed.find(port) == allowed.end()) {
      return false;
    }
    ports.push_back(port);
  }
  std::sort(ports.begin(), ports.end());
  ports.erase(std::unique(ports.begin(), ports.end()), ports.end());
  std::ostringstream stream;
  for (std::size_t index = 0; index < ports.size(); ++index) {
    if (index != 0) {
      stream << ',';
    }
    stream << ports[index];
  }
  *normalized = stream.str();
  return true;
}

bool ParseProtocols(const std::string& raw, std::string* normalized) {
  static const std::unordered_set<std::string> kAllowed = {
      "http", "tls", "quic"};
  auto protocols = Split(raw, ',');
  if (protocols.empty()) {
    return false;
  }
  for (const auto& protocol : protocols) {
    if (kAllowed.find(protocol) == kAllowed.end()) {
      return false;
    }
  }
  std::sort(protocols.begin(), protocols.end());
  protocols.erase(std::unique(protocols.begin(), protocols.end()),
                  protocols.end());
  std::ostringstream stream;
  for (std::size_t index = 0; index < protocols.size(); ++index) {
    if (index != 0) {
      stream << ',';
    }
    stream << protocols[index];
  }
  *normalized = stream.str();
  return true;
}

std::string JoinAssetPath(const std::string& root,
                          const std::string& relative) {
  return (std::filesystem::path(root) / relative.substr(9)).string();
}

bool ValidateActions(const std::string& raw,
                     const std::string& protocol,
                     const std::map<std::string, std::string>& blobs,
                     std::string* error) {
  const auto actions = Split(raw, ';');
  const std::vector<std::string> expected =
      protocol == "quic" ? std::vector<std::string>{"udpFake"}
                         : std::vector<std::string>{"fake", "split"};
  if (actions.size() != expected.size()) {
    *error = "Набор actions не входит в Linux production allowlist.";
    return false;
  }
  for (std::size_t index = 0; index < actions.size(); ++index) {
    const auto& action = actions[index];
    const auto fields = Split(action, ':');
    if (fields.size() != 4) {
      *error = "Некорректный формат action.";
      return false;
    }
    const auto& kind = fields[0];
    const auto& blob_key = fields[1];
    const auto& position = fields[2];
    const auto& repeats = fields[3];
    if (kind != expected[index]) {
      *error = "Порядок actions не входит в Linux production allowlist.";
      return false;
    }
    if (repeats != "1") {
      *error = "Входной профиль разрешает только один повтор action.";
      return false;
    }
    if (kind == "split") {
      if (position != "1") {
        *error = "Входной профиль разрешает split только в позиции 1.";
        return false;
      }
      continue;
    }
    if (kind != "fake" && kind != "udpFake") {
      *error = "Action не входит в Linux allowlist.";
      return false;
    }
    if (!blob_key.empty()) {
      const auto blob_iterator = blobs.find(blob_key);
      if (blob_iterator == blobs.end()) {
        *error = "Action ссылается на неизвестный blob.";
        return false;
      }
    }
  }
  return true;
}

void AppendLinuxProductionStrategy(const std::string& protocol,
                                   std::vector<std::string>* arguments) {
  if (protocol == "http") {
    arguments->push_back("--out-range=-d5");
    arguments->push_back("--payload=http_req");
    arguments->push_back(
        "--lua-desync=fake:blob=fake_default_http:tcp_ts=-100:repeats=2");
    arguments->push_back(
        "--lua-desync=multisplit:seqovl=2:"
        "seqovl_pattern=fake_default_http:pos=midsld:tcp_ts_up");
    return;
  }
  if (protocol == "tls") {
    arguments->push_back("--out-range=-d5");
    arguments->push_back("--payload=tls_client_hello");
    arguments->push_back(
        "--lua-desync=fake:blob=tls_google:tcp_ts=-100:repeats=2");
    arguments->push_back(
        "--lua-desync=multisplit:seqovl=2:seqovl_pattern=tls_google:"
        "pos=midsld:tcp_ts_up");
    return;
  }
  arguments->push_back("--out-range=-d5");
  arguments->push_back("--payload=quic_initial");
  arguments->push_back(
      "--lua-desync=fake:blob=quic_google:ip_autottl=-1,3-10:"
      "ip6_autottl=-1,3-10:repeats=2");
  arguments->push_back(
      "--lua-desync=send:ipfrag:ipfrag_pos_udp=8:ipfrag_disorder");
}

}  // namespace

bool IsSafeAssetPath(const std::string& path) {
  static const std::regex kPattern(
      "^qnzapret/(lists|payloads)/[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
  return std::regex_match(path, kPattern) &&
         path.find("..") == std::string::npos;
}

bool CompileStrategyProfile(const std::string& wire_profile,
                            const std::string& asset_root,
                            std::uint16_t queue_number,
                            CompiledProfile* output,
                            std::string* error) {
  if (output == nullptr || error == nullptr || wire_profile.size() > 65536) {
    return false;
  }
  if (queue_number != kQueueNumber) {
    *error = "Linux runtime разрешает только NFQUEUE 200.";
    return false;
  }

  std::string profile_id;
  std::string profile_name;
  std::string unmatched;
  std::vector<std::string> rule_lines;
  std::map<std::string, std::string> blobs;
  for (const auto& line : Split(wire_profile, '\n')) {
    if (line.empty()) {
      continue;
    }
    const auto separator = line.find('=');
    if (separator == std::string::npos) {
      *error = "Профиль содержит строку без ключа.";
      return false;
    }
    const auto key = line.substr(0, separator);
    const auto value = line.substr(separator + 1);
    if (key == "profileId") {
      profile_id = value;
    } else if (key == "profileName") {
      profile_name = value;
    } else if (key == "unmatched") {
      unmatched = value;
    } else if (key == "blob") {
      const auto fields = Split(value, '|');
      if (fields.size() != 2 || !IsSafeIdentifier(fields[0]) ||
          !IsSafeAssetPath(fields[1])) {
        *error = "Blob не прошел allowlist-проверку.";
        return false;
      }
      blobs[fields[0]] = fields[1];
    } else if (key == "rule") {
      rule_lines.push_back(value);
    } else {
      *error = "Профиль содержит неизвестное поле.";
      return false;
    }
  }

  if (!IsSafeIdentifier(profile_id) || profile_name.empty() ||
      profile_name.size() > 128 || profile_name.find('\n') != std::string::npos ||
      unmatched != "direct" || rule_lines.empty() || rule_lines.size() > 16) {
    *error = "Основные поля профиля не прошли проверку.";
    return false;
  }
  const std::map<std::string, std::string> expected_blobs = {
      {"tls_google",
       "qnzapret/payloads/tls_clienthello_www_google_com.bin"},
      {"quic_google",
       "qnzapret/payloads/quic_initial_www_google_com.bin"},
  };
  if (blobs != expected_blobs) {
    *error = "Linux production profile требует canonical TLS/QUIC blobs.";
    return false;
  }

  CompiledProfile compiled;
  compiled.id = profile_id;
  compiled.name = profile_name;
  compiled.arguments = {
      "--qnum=" + std::to_string(queue_number),
      "--user=qnzapret-runtime",
      "--fwmark=0x40000000",
      "--bind-fix4",
      "--bind-fix6",
      "--lua-init=@" + asset_root + "/lua/zapret-lib.lua",
      "--lua-init=@" + asset_root + "/lua/zapret-antidpi.lua",
      "--blob=tls_google:@" +
          JoinAssetPath(asset_root, expected_blobs.at("tls_google")),
      "--blob=quic_google:@" +
          JoinAssetPath(asset_root, expected_blobs.at("quic_google")),
  };

  for (std::size_t index = 0; index < rule_lines.size(); ++index) {
    const auto fields = Split(rule_lines[index], '|');
    if (fields.size() != 6 || !IsSafeIdentifier(fields[0])) {
      *error = "Rule не прошел структурную проверку.";
      return false;
    }
    std::string tcp_ports;
    std::string udp_ports;
    std::string protocols;
    if (!ParsePortList(fields[1], {80, 443}, &tcp_ports) ||
        !ParsePortList(fields[2], {443}, &udp_ports) ||
        !ParseProtocols(fields[3], &protocols) ||
        (tcp_ports.empty() && udp_ports.empty()) || protocols.find(',') !=
                                                        std::string::npos) {
      *error = "Порты или протоколы rule не входят в allowlist.";
      return false;
    }
    if ((protocols == "http" && (tcp_ports != "80" || !udp_ports.empty())) ||
        (protocols == "tls" && (tcp_ports != "443" || !udp_ports.empty())) ||
        (protocols == "quic" && (udp_ports != "443" || !tcp_ports.empty()))) {
      *error = "L7 protocol не соответствует Linux production ports.";
      return false;
    }
    if (index != 0) {
      compiled.arguments.push_back("--new");
    }
    if (!tcp_ports.empty()) {
      compiled.arguments.push_back("--filter-tcp=" + tcp_ports);
    }
    if (!udp_ports.empty()) {
      compiled.arguments.push_back("--filter-udp=" + udp_ports);
    }
    compiled.arguments.push_back("--filter-l7=" + protocols);
    std::vector<std::string> requested_hostlists;
    if (!fields[4].empty()) {
      for (const auto& hostlist : Split(fields[4], ',')) {
        if (!IsSafeAssetPath(hostlist) ||
            hostlist.rfind("qnzapret/lists/", 0) != 0) {
          *error = "Hostlist path не прошел allowlist-проверку.";
          return false;
        }
        requested_hostlists.push_back(hostlist);
      }
    }
    auto normalized_hostlists = requested_hostlists;
    std::sort(normalized_hostlists.begin(), normalized_hostlists.end());
    const std::vector<std::string> expected_input_hostlists =
        protocols == "quic"
            ? std::vector<std::string>{}
            : std::vector<std::string>{
                  "qnzapret/lists/list-general.txt",
                  "qnzapret/lists/list-google.txt",
                  "qnzapret/lists/list-user.txt",
              };
    auto normalized_expected = expected_input_hostlists;
    std::sort(normalized_expected.begin(), normalized_expected.end());
    if (normalized_hostlists != normalized_expected) {
      *error = "Hostlist set не соответствует Linux production профилю.";
      return false;
    }
    const std::vector<std::string> compiled_hostlists =
        protocols == "quic"
            ? std::vector<std::string>{
                  "qnzapret/lists/list-google.txt",
                  "qnzapret/lists/list-user.txt",
              }
            : std::vector<std::string>{
                  "qnzapret/lists/list-general.txt",
                  "qnzapret/lists/list-google.txt",
                  "qnzapret/lists/list-user.txt",
              };
    for (const auto& hostlist : compiled_hostlists) {
      compiled.arguments.push_back("--hostlist=" +
                                   JoinAssetPath(asset_root, hostlist));
    }
    if (!ValidateActions(fields[5], protocols, blobs, error)) {
      return false;
    }
    AppendLinuxProductionStrategy(protocols, &compiled.arguments);
  }

  *output = std::move(compiled);
  return true;
}

std::string DefaultStrategyProfileWire() {
  return
      "profileId=default-lightweight\n"
      "profileName=Default lightweight\n"
      "unmatched=direct\n"
      "blob=tls_google|qnzapret/payloads/"
      "tls_clienthello_www_google_com.bin\n"
      "blob=quic_google|qnzapret/payloads/"
      "quic_initial_www_google_com.bin\n"
      "rule=http-hostlist-fake-split|80||http|"
      "qnzapret/lists/list-general.txt,qnzapret/lists/list-google.txt,"
      "qnzapret/lists/list-user.txt|fake:::1;split::1:1\n"
      "rule=tls-hostlist-split|443||tls|"
      "qnzapret/lists/list-general.txt,qnzapret/lists/list-google.txt,"
      "qnzapret/lists/list-user.txt|fake:tls_google::1;split::1:1\n"
      "rule=quic-initial-fake||443|quic||udpFake:quic_google::1\n";
}

std::string BuildNftRules(std::uint16_t queue_number) {
  const auto queue = std::to_string(queue_number);
  return
      "add table inet qnzapret\n"
      "add chain inet qnzapret predefrag { type filter hook output priority "
      "-401; policy accept; }\n"
      "add chain inet qnzapret predefrag_nfqws\n"
      "add rule inet qnzapret predefrag meta mark and 0x40000000 != 0 "
      "counter jump predefrag_nfqws comment \"nfqws generated packet\"\n"
      "add rule inet qnzapret predefrag_nfqws meta mark and 0x20000000 != 0 "
      "counter notrack comment \"postnat injected packet\"\n"
      "add rule inet qnzapret predefrag_nfqws ip frag-off & 0x1fff != 0 "
      "counter notrack comment \"injected IPv4 fragment\"\n"
      "add rule inet qnzapret predefrag_nfqws exthdr frag exists counter "
      "notrack comment \"injected IPv6 fragment\"\n"
      "add rule inet qnzapret predefrag_nfqws tcp flags ! syn,rst,ack "
      "counter notrack comment \"injected TCP data without ACK\"\n"
      "add chain inet qnzapret postrouting { type filter hook postrouting "
      "priority 101; policy accept; }\n"
      "add rule inet qnzapret postrouting meta mark and 0x40000000 == 0 "
      "meta l4proto tcp tcp dport { 80, 443 } ct original packets 1-20 "
      "meta mark set meta mark or 0x20000000 counter queue num " +
      queue +
      " bypass comment \"outgoing TCP 1-20\"\n"
      "add rule inet qnzapret postrouting meta mark and 0x40000000 == 0 "
      "meta l4proto udp udp dport 443 ct original packets 1-5 "
      "meta mark set meta mark or 0x20000000 counter queue num " +
      queue +
      " bypass comment \"outgoing QUIC 1-5\"\n"
      "add chain inet qnzapret prerouting { type filter hook prerouting "
      "priority -101; policy accept; }\n"
      "add rule inet qnzapret prerouting meta l4proto tcp tcp sport { 80, "
      "443 } ct reply packets 1-10 counter queue num " +
      queue +
      " bypass comment \"reply TCP 1-10\"\n"
      "add rule inet qnzapret prerouting meta l4proto udp udp sport 443 "
      "ct reply packets 1-3 counter queue num " +
      queue + " bypass comment \"reply QUIC 1-3\"\n";
}

SubprocessFailure MapSubprocessFailure(SubprocessTermination termination,
                                      int status,
                                      const std::string& stderr_text,
                                      const std::string& operation) {
  const auto details = RedactRuntimeMessage(stderr_text);
  if (termination == SubprocessTermination::kSpawnFailed) {
    return {operation + "_spawn_failed",
            details.empty() ? "Не удалось запустить дочерний процесс."
                            : details};
  }
  if (termination == SubprocessTermination::kSignaled) {
    return {operation + "_signaled",
            "Дочерний процесс завершен сигналом " +
                std::to_string(status) +
                (details.empty() ? "." : ": " + details)};
  }
  return {operation + "_nonzero",
          "Дочерний процесс завершился с кодом " +
              std::to_string(status) +
              (details.empty() ? "." : ": " + details)};
}

std::string RedactRuntimeMessage(const std::string& message) {
  std::string output = message;
  output = std::regex_replace(
      output, std::regex("(secret|password|token)=([^\\s&]+)",
                         std::regex::icase),
      "$1=[REDACTED]");
  output = std::regex_replace(
      output,
      std::regex("(tg://proxy\\?[^\\s]*secret=)([^\\s&]+)",
                 std::regex::icase),
      "$1[REDACTED]");
  if (output.size() > 1024) {
    output.resize(1024);
  }
  return output;
}

}  // namespace qnzapret
