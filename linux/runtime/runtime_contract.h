#ifndef QNZAPRET_LINUX_RUNTIME_RUNTIME_CONTRACT_H_
#define QNZAPRET_LINUX_RUNTIME_RUNTIME_CONTRACT_H_

#include <cstdint>
#include <string>
#include <vector>

namespace qnzapret {

inline constexpr char kRuntimeBusName[] = "dev.qnzapret.Runtime1";
inline constexpr char kRuntimeObjectPath[] = "/dev/qnzapret/Runtime1";
inline constexpr char kRuntimeInterface[] = "dev.qnzapret.Runtime1";
inline constexpr char kTelegramBusName[] = "dev.qnzapret.Telegram1";
inline constexpr char kTelegramObjectPath[] = "/dev/qnzapret/Telegram1";
inline constexpr char kTelegramInterface[] = "dev.qnzapret.Telegram1";
inline constexpr char kRuntimeVersion[] = "1.0.0";
inline constexpr std::uint16_t kQueueNumber = 200;
inline constexpr std::uint32_t kDesyncMark = 0x40000000;
inline constexpr std::uint32_t kDesyncMarkPostNat = 0x20000000;
inline constexpr int kTcpPacketsOut = 20;
inline constexpr int kTcpPacketsIn = 10;
inline constexpr int kUdpPacketsOut = 5;
inline constexpr int kUdpPacketsIn = 3;

struct RuntimeSnapshot {
  std::string state = "idle";
  std::string message = "Linux runtime готов к запуску.";
  bool backend_connected = true;
  bool service_active = false;
  bool strategy_engine_ready = false;
  bool traffic_forwarder_ready = false;
  bool traffic_interception_active = false;
  bool queue_registered = false;
  bool nft_rules_installed = false;
  bool interception_ready = false;
  std::string active_profile_name;
  std::int64_t owner_uid = -1;
  bool telegram_ready = false;
  bool telegram_setup_required = false;
  std::string telegram_endpoint = "127.0.0.1:1443";
  std::string telegram_message;
};

struct RuntimeLogEvent {
  std::int64_t timestamp_millis = 0;
  std::string level;
  std::string source;
  std::string code;
  std::string message;
};

struct CompiledProfile {
  std::string id;
  std::string name;
  std::vector<std::string> arguments;
};

enum class SubprocessTermination {
  kExited,
  kSignaled,
  kSpawnFailed,
};

struct SubprocessFailure {
  std::string code;
  std::string message;
};

bool CompileStrategyProfile(const std::string& wire_profile,
                            const std::string& asset_root,
                            std::uint16_t queue_number,
                            CompiledProfile* output,
                            std::string* error);

std::string DefaultStrategyProfileWire();
std::string BuildNftRules(std::uint16_t queue_number);
SubprocessFailure MapSubprocessFailure(SubprocessTermination termination,
                                      int status,
                                      const std::string& stderr_text,
                                      const std::string& operation);
std::string RedactRuntimeMessage(const std::string& message);
bool IsSafeAssetPath(const std::string& path);

}  // namespace qnzapret

#endif  // QNZAPRET_LINUX_RUNTIME_RUNTIME_CONTRACT_H_
