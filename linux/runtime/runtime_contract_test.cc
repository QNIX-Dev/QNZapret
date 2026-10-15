#include "runtime_contract.h"

#include <algorithm>
#include <cstdlib>
#include <iostream>
#include <string>

namespace {

void Require(bool condition, const std::string& message) {
  if (!condition) {
    std::cerr << message << '\n';
    std::exit(1);
  }
}

}  // namespace

int main(int argc, char** argv) {
  if (argc == 2 && std::string(argv[1]) == "--print-nft") {
    std::cout << qnzapret::BuildNftRules(qnzapret::kQueueNumber);
    return 0;
  }
  const bool print_profile =
      argc == 2 && std::string(argv[1]) == "--print-profile";
  qnzapret::CompiledProfile profile;
  std::string error;
  Require(qnzapret::CompileStrategyProfile(
              qnzapret::DefaultStrategyProfileWire(),
              "/usr/lib/qnzapret/runtime", qnzapret::kQueueNumber, &profile,
              &error),
          "default profile must compile: " + error);
  if (print_profile) {
    for (const auto& argument : profile.arguments) {
      std::cout << argument << '\n';
    }
    return 0;
  }
  Require(profile.id == "default-lightweight", "profile id mismatch");
  Require(profile.arguments.size() == 37, "golden argument count mismatch");
  Require(profile.arguments.front() == "--qnum=200",
          "queue argument mismatch");
  Require(profile.arguments[2] == "--fwmark=0x40000000",
          "nfqws injected packet mark mismatch");
  Require(std::find(profile.arguments.begin(), profile.arguments.end(),
                    "--user=qnzapret-runtime") != profile.arguments.end(),
          "nfqws worker user missing");
  Require(std::find(profile.arguments.begin(), profile.arguments.end(),
                    "--uid=0:0") == profile.arguments.end(),
          "nfqws worker must never remain root");
  Require(std::find(profile.arguments.begin(), profile.arguments.end(),
                    "--bind-fix4") != profile.arguments.end(),
          "IPv4 generated-packet bind fix missing");
  Require(std::find(profile.arguments.begin(), profile.arguments.end(),
                    "--bind-fix6") != profile.arguments.end(),
          "IPv6 generated-packet bind fix missing");
  Require(std::find(profile.arguments.begin(), profile.arguments.end(),
                    "--blob=tls_google:@/usr/lib/qnzapret/runtime/payloads/"
                    "tls_clienthello_www_google_com.bin") !=
              profile.arguments.end(),
          "canonical TLS blob binding missing");
  Require(std::find(profile.arguments.begin(), profile.arguments.end(),
                    "--payload=http_req") != profile.arguments.end(),
          "HTTP payload filter missing");
  Require(std::find(profile.arguments.begin(), profile.arguments.end(),
                    "--payload=tls_client_hello") != profile.arguments.end(),
          "TLS ClientHello payload filter missing");
  Require(std::find(profile.arguments.begin(), profile.arguments.end(),
                    "--payload=quic_initial") != profile.arguments.end(),
          "QUIC Initial payload filter missing");
  Require(std::find(profile.arguments.begin(), profile.arguments.end(),
                    "--lua-desync=fake:blob=tls_google:tcp_ts=-100:"
                    "repeats=2") != profile.arguments.end(),
          "verified TLS fake strategy missing");
  Require(std::find(profile.arguments.begin(), profile.arguments.end(),
                    "--lua-desync=multisplit:seqovl=2:"
                    "seqovl_pattern=tls_google:pos=midsld:tcp_ts_up") !=
              profile.arguments.end(),
          "verified TLS multisplit strategy missing");
  Require(std::find(profile.arguments.begin(), profile.arguments.end(),
                    "--lua-desync=fake:blob=quic_google:"
                    "ip_autottl=-1,3-10:ip6_autottl=-1,3-10:repeats=2") !=
              profile.arguments.end(),
          "verified QUIC fake strategy missing");
  Require(std::find(profile.arguments.begin(), profile.arguments.end(),
                    "--lua-desync=send:ipfrag:ipfrag_pos_udp=8:"
                    "ipfrag_disorder") != profile.arguments.end(),
          "verified QUIC fragmentation strategy missing");

  const auto nft_rules = qnzapret::BuildNftRules(200);
  Require(nft_rules.find("table inet qnzapret") != std::string::npos,
          "nft table ownership missing");
  Require(nft_rules.find("queue num 200 bypass") != std::string::npos,
          "fail-open queue rule missing");
  Require(nft_rules.find("ct state new") == std::string::npos,
          "ct state new must not exclude ClientHello");
  Require(nft_rules.find("hook postrouting priority 101") !=
              std::string::npos,
          "post-NAT outgoing hook missing");
  Require(nft_rules.find("hook prerouting priority -101") !=
              std::string::npos,
          "pre-NAT reply hook missing");
  Require(nft_rules.find("hook output priority -401") != std::string::npos,
          "pre-defrag injected packet hook missing");
  Require(nft_rules.find("meta mark and 0x40000000 == 0") !=
              std::string::npos,
          "outgoing injected packet guard missing");
  Require(nft_rules.find("ct original packets 1-20") != std::string::npos,
          "upstream TCP outgoing packet range missing");
  Require(nft_rules.find("ct reply packets 1-10") != std::string::npos,
          "upstream TCP reply packet range missing");
  Require(nft_rules.find("ct original packets 1-5") != std::string::npos,
          "upstream UDP outgoing packet range missing");
  Require(nft_rules.find("ct reply packets 1-3") != std::string::npos,
          "upstream UDP reply packet range missing");
  Require(nft_rules.find("counter queue num 200 bypass") !=
              std::string::npos,
          "diagnostic queue counter missing");
  Require(nft_rules.find("flush") == std::string::npos,
          "foreign table flush must never be generated");

  qnzapret::CompiledProfile invalid_profile;
  error.clear();
  const std::string malicious =
      "profileId=bad\nprofileName=Bad\nunmatched=direct\n"
      "rule=x|22||http||split::1:1\n";
  Require(!qnzapret::CompileStrategyProfile(
              malicious, "/usr/lib/qnzapret/runtime", 200, &invalid_profile,
              &error),
          "non-allowlisted port must be rejected");

  error.clear();
  const std::string traversal =
      "profileId=bad\nprofileName=Bad\nunmatched=direct\n"
      "rule=x|80||http|qnzapret/lists/../secret|split::1:1\n";
  Require(!qnzapret::CompileStrategyProfile(
              traversal, "/usr/lib/qnzapret/runtime", 200, &invalid_profile,
              &error),
          "path traversal must be rejected");

  error.clear();
  Require(!qnzapret::CompileStrategyProfile(
              qnzapret::DefaultStrategyProfileWire(),
              "/usr/lib/qnzapret/runtime", 201, &invalid_profile, &error),
          "non-production NFQUEUE must be rejected");

  const auto signaled = qnzapret::MapSubprocessFailure(
      qnzapret::SubprocessTermination::kSignaled, 11, "",
      "linux_profile_dry_run");
  Require(signaled.code == "linux_profile_dry_run_signaled",
          "signaled dry-run mapping mismatch");
  Require(signaled.message.find("11") != std::string::npos,
          "signaled dry-run message must include signal");
  const auto nonzero = qnzapret::MapSubprocessFailure(
      qnzapret::SubprocessTermination::kExited, 2, "", "linux_nfqws");
  Require(nonzero.code == "linux_nfqws_nonzero",
          "nonzero subprocess mapping mismatch");
  Require(!nonzero.message.empty(),
          "nonzero subprocess message must never be empty");

  const auto redacted = qnzapret::RedactRuntimeMessage(
      "token=abc password=hunter2 secret=0011");
  Require(redacted.find("hunter2") == std::string::npos,
          "password redaction failed");
  Require(redacted.find("0011") == std::string::npos,
          "secret redaction failed");
  return 0;
}
