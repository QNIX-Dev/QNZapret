#include "runtime_contract.h"

#include <gio/gio.h>
#include <glib/gstdio.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <array>
#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <deque>
#include <fstream>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

namespace {

std::string g_asset_root = "/usr/lib/qnzapret/runtime";
std::string g_runtime_directory = "/run/qnzapret";
std::string g_nfqws_path = "/usr/lib/qnzapret/runtime/bin/nfqws2";
bool g_integration_test = false;
bool g_integration_ready = false;
bool g_integration_failed = false;
constexpr char kPolkitAction[] = "dev.qnzapret.runtime.manage";

const char kIntrospectionXml[] = R"XML(
<node>
  <interface name="dev.qnzapret.Runtime1">
    <method name="GetVersion">
      <arg name="version" type="s" direction="out"/>
    </method>
    <method name="Prepare">
      <arg name="result" type="a{sv}" direction="out"/>
    </method>
    <method name="GetSnapshot">
      <arg name="snapshot" type="a{sv}" direction="out"/>
    </method>
    <method name="Start">
      <arg name="profile" type="s" direction="in"/>
    </method>
    <method name="Stop"/>
    <signal name="SnapshotChanged">
      <arg name="snapshot" type="a{sv}"/>
    </signal>
    <signal name="LogEvent">
      <arg name="event" type="a{sv}"/>
    </signal>
  </interface>
</node>
)XML";

struct DaemonState {
  GDBusConnection* connection = nullptr;
  guint registration_id = 0;
  guint owner_id = 0;
  qnzapret::RuntimeSnapshot snapshot;
  GSubprocess* nfqws_process = nullptr;
  GDataInputStream* nfqws_output = nullptr;
  bool stopping = false;
  bool nfqws_wait_completed = false;
  bool cleanup_requested = false;
  bool cleanup_mark_idle = false;
  guint stop_timeout_id = 0;
  guint queue_poll_id = 0;
  GDBusMethodInvocation* pending_start_invocation = nullptr;
  GDBusMethodInvocation* pending_stop_invocation = nullptr;
  qnzapret::CompiledProfile pending_profile;
  int queue_poll_attempts = 0;
  int lock_fd = -1;
  std::deque<qnzapret::RuntimeLogEvent> logs;
};

DaemonState g_state;
GMainLoop* g_loop = nullptr;

std::string LockPath() {
  return g_runtime_directory + "/runtime.lock";
}

std::string ActiveProfilePath() {
  return g_runtime_directory + "/active-profile";
}

std::string ManifestPath() {
  return g_asset_root + "/manifest.sha256";
}

std::int64_t NowMillis() {
  return g_get_real_time() / 1000;
}

GVariant* SnapshotVariant() {
  GVariantBuilder builder;
  g_variant_builder_init(&builder, G_VARIANT_TYPE_VARDICT);
  g_variant_builder_add(&builder, "{sv}", "platform",
                        g_variant_new_string("linux"));
  g_variant_builder_add(&builder, "{sv}", "state",
                        g_variant_new_string(g_state.snapshot.state.c_str()));
  g_variant_builder_add(&builder, "{sv}", "message",
                        g_variant_new_string(g_state.snapshot.message.c_str()));
  g_variant_builder_add(&builder, "{sv}", "backendConnected",
                        g_variant_new_boolean(
                            g_state.snapshot.backend_connected));
  g_variant_builder_add(&builder, "{sv}", "vpnPermissionGranted",
                        g_variant_new_boolean(true));
  g_variant_builder_add(&builder, "{sv}", "serviceActive",
                        g_variant_new_boolean(
                            g_state.snapshot.service_active));
  g_variant_builder_add(&builder, "{sv}", "strategyEngineReady",
                        g_variant_new_boolean(
                            g_state.snapshot.strategy_engine_ready));
  g_variant_builder_add(&builder, "{sv}", "trafficForwarderReady",
                        g_variant_new_boolean(
                            g_state.snapshot.traffic_forwarder_ready));
  g_variant_builder_add(&builder, "{sv}", "tunnelActive",
                        g_variant_new_boolean(false));
  g_variant_builder_add(&builder, "{sv}", "trafficInterceptionMode",
                        g_variant_new_string(
                            g_state.snapshot.traffic_interception_active
                                ? "linuxNfqueue"
                                : "none"));
  g_variant_builder_add(&builder, "{sv}", "trafficInterceptionActive",
                        g_variant_new_boolean(
                            g_state.snapshot.traffic_interception_active));
  g_variant_builder_add(&builder, "{sv}", "queueRegistered",
                        g_variant_new_boolean(
                            g_state.snapshot.queue_registered));
  g_variant_builder_add(&builder, "{sv}", "nftRulesInstalled",
                        g_variant_new_boolean(
                            g_state.snapshot.nft_rules_installed));
  g_variant_builder_add(&builder, "{sv}", "interceptionReady",
                        g_variant_new_boolean(
                            g_state.snapshot.interception_ready));
  g_variant_builder_add(&builder, "{sv}", "packetCodecReady",
                        g_variant_new_boolean(false));
  g_variant_builder_add(&builder, "{sv}", "udpForwarderReady",
                        g_variant_new_boolean(false));
  g_variant_builder_add(&builder, "{sv}", "ipv6PacketCodecReady",
                        g_variant_new_boolean(false));
  g_variant_builder_add(&builder, "{sv}", "ipv6UdpForwarderReady",
                        g_variant_new_boolean(false));
  g_variant_builder_add(&builder, "{sv}", "tcpForwarderReady",
                        g_variant_new_boolean(false));
  g_variant_builder_add(
      &builder, "{sv}", "activeProfileName",
      g_variant_new_string(g_state.snapshot.active_profile_name.c_str()));
  g_variant_builder_add(&builder, "{sv}", "backendVersion",
                        g_variant_new_string(qnzapret::kRuntimeVersion));
  if (g_state.snapshot.owner_uid >= 0) {
    g_variant_builder_add(
        &builder, "{sv}", "runtimeOwnerUid",
        g_variant_new_int64(g_state.snapshot.owner_uid));
  }
  g_variant_builder_add(&builder, "{sv}",
                        "telegramCompatibilityProxyReady",
                        g_variant_new_boolean(false));
  g_variant_builder_add(&builder, "{sv}",
                        "telegramCompatibilitySetupRequired",
                        g_variant_new_boolean(false));
  return g_variant_builder_end(&builder);
}

GVariant* LogVariant(const qnzapret::RuntimeLogEvent& event) {
  GVariantBuilder builder;
  g_variant_builder_init(&builder, G_VARIANT_TYPE_VARDICT);
  g_variant_builder_add(&builder, "{sv}", "timestampMillis",
                        g_variant_new_int64(event.timestamp_millis));
  g_variant_builder_add(&builder, "{sv}", "level",
                        g_variant_new_string(event.level.c_str()));
  g_variant_builder_add(&builder, "{sv}", "source",
                        g_variant_new_string(event.source.c_str()));
  g_variant_builder_add(&builder, "{sv}", "code",
                        g_variant_new_string(event.code.c_str()));
  g_variant_builder_add(&builder, "{sv}", "message",
                        g_variant_new_string(event.message.c_str()));
  return g_variant_builder_end(&builder);
}

void EmitSnapshot() {
  if (g_state.connection == nullptr) {
    return;
  }
  g_dbus_connection_emit_signal(
      g_state.connection, nullptr, qnzapret::kRuntimeObjectPath,
      qnzapret::kRuntimeInterface, "SnapshotChanged",
      g_variant_new("(@a{sv})", SnapshotVariant()), nullptr);
}

void EmitLog(const char* level,
             const char* code,
             const std::string& raw_message) {
  qnzapret::RuntimeLogEvent event{
      NowMillis(), level, "linux-runtime", code,
      qnzapret::RedactRuntimeMessage(raw_message)};
  g_state.logs.push_back(event);
  while (g_state.logs.size() > 240) {
    g_state.logs.pop_front();
  }
  if (g_state.connection != nullptr) {
    g_dbus_connection_emit_signal(
        g_state.connection, nullptr, qnzapret::kRuntimeObjectPath,
        qnzapret::kRuntimeInterface, "LogEvent",
        g_variant_new("(@a{sv})", LogVariant(event)), nullptr);
  }
}

void SetFailed(const std::string& code, const std::string& message) {
  g_state.snapshot.state = "failed";
  g_state.snapshot.message = message;
  g_state.snapshot.service_active = false;
  g_state.snapshot.strategy_engine_ready = false;
  g_state.snapshot.traffic_forwarder_ready = false;
  g_state.snapshot.traffic_interception_active = false;
  g_state.snapshot.queue_registered = false;
  g_state.snapshot.nft_rules_installed = false;
  g_state.snapshot.interception_ready = false;
  EmitLog("error", code.c_str(), message);
  EmitSnapshot();
}

std::vector<const gchar*> Argv(const std::vector<std::string>& arguments) {
  std::vector<const gchar*> argv;
  argv.reserve(arguments.size() + 1);
  for (const auto& argument : arguments) {
    argv.push_back(argument.c_str());
  }
  argv.push_back(nullptr);
  return argv;
}

bool RunProcess(const std::vector<std::string>& arguments,
                const std::string* stdin_text,
                std::string* stdout_text,
                std::string* error_text,
                qnzapret::SubprocessTermination* termination,
                int* status) {
  const auto argv = Argv(arguments);
  g_autoptr(GError) error = nullptr;
  g_autoptr(GSubprocess) process = g_subprocess_newv(
      argv.data(),
      static_cast<GSubprocessFlags>(G_SUBPROCESS_FLAGS_STDOUT_PIPE |
                                    G_SUBPROCESS_FLAGS_STDERR_PIPE |
                                    (stdin_text == nullptr
                                         ? G_SUBPROCESS_FLAGS_NONE
                                         : G_SUBPROCESS_FLAGS_STDIN_PIPE)),
      &error);
  if (process == nullptr) {
    *termination = qnzapret::SubprocessTermination::kSpawnFailed;
    *status = -1;
    *error_text = error != nullptr ? error->message : "spawn failed";
    return false;
  }
  gchar* output = nullptr;
  gchar* errors = nullptr;
  if (!g_subprocess_communicate_utf8(
          process, stdin_text == nullptr ? nullptr : stdin_text->c_str(),
          nullptr, &output, &errors, &error)) {
    *error_text = error != nullptr ? error->message : "communicate failed";
    *termination = qnzapret::SubprocessTermination::kSpawnFailed;
    *status = -1;
    g_free(output);
    g_free(errors);
    return false;
  }
  *stdout_text = output == nullptr ? "" : output;
  *error_text = errors == nullptr ? "" : errors;
  g_free(output);
  g_free(errors);
  if (g_subprocess_get_if_exited(process)) {
    *termination = qnzapret::SubprocessTermination::kExited;
    *status = g_subprocess_get_exit_status(process);
    return *status == 0;
  }
  *termination = qnzapret::SubprocessTermination::kSignaled;
  *status = g_subprocess_get_term_sig(process);
  return false;
}

std::string FindNft() {
  g_autofree gchar* path = g_find_program_in_path("nft");
  return path == nullptr ? "" : path;
}

bool DeleteOwnTable(std::string* error) {
  const auto nft = FindNft();
  if (nft.empty()) {
    *error = "nft executable не найден.";
    return false;
  }
  std::string output;
  std::string errors;
  qnzapret::SubprocessTermination termination;
  int status = 0;
  const std::string rules = "delete table inet qnzapret\n";
  if (!RunProcess({nft, "-f", "-"}, &rules, &output, &errors,
                  &termination, &status)) {
    if (errors.find("No such file") != std::string::npos ||
        errors.find("No such") != std::string::npos) {
      return true;
    }
    *error = errors.empty() ? output : errors;
    return false;
  }
  return true;
}

bool TableExists() {
  const auto nft = FindNft();
  if (nft.empty()) {
    return false;
  }
  std::string output;
  std::string errors;
  qnzapret::SubprocessTermination termination;
  int status = 0;
  return RunProcess({nft, "list", "table", "inet", "qnzapret"}, nullptr,
                    &output, &errors, &termination, &status);
}

bool QueueRegistered() {
  std::ifstream queues("/proc/net/netfilter/nfnetlink_queue");
  std::string line;
  while (std::getline(queues, line)) {
    std::istringstream fields(line);
    int queue = -1;
    fields >> queue;
    if (queue == qnzapret::kQueueNumber) {
      return true;
    }
  }
  return false;
}

bool VerifyFileSha256(const std::string& path,
                      const std::string& expected_hash) {
  g_autoptr(GChecksum) checksum = g_checksum_new(G_CHECKSUM_SHA256);
  if (checksum == nullptr) {
    return false;
  }
  std::ifstream input(path, std::ios::binary);
  if (!input) {
    return false;
  }
  std::array<char, 65536> buffer{};
  while (input) {
    input.read(buffer.data(), buffer.size());
    const auto count = input.gcount();
    if (count > 0) {
      g_checksum_update(checksum,
                        reinterpret_cast<const guchar*>(buffer.data()), count);
    }
  }
  return expected_hash == g_checksum_get_string(checksum);
}

bool VerifyAssets(std::string* error) {
  std::ifstream manifest(ManifestPath());
  if (!manifest) {
    *error = "Manifest runtime assets отсутствует.";
    return false;
  }
  std::string line;
  int checked = 0;
  while (std::getline(manifest, line)) {
    if (line.empty() || line.front() == '#') {
      continue;
    }
    std::istringstream fields(line);
    std::string hash;
    std::string relative_path;
    fields >> hash >> relative_path;
    if (hash.size() != 64 || relative_path.empty() ||
        relative_path.front() == '/' ||
        relative_path.find("..") != std::string::npos) {
      *error = "Manifest runtime assets поврежден.";
      return false;
    }
    if (!VerifyFileSha256(g_asset_root + "/" + relative_path,
                          hash)) {
      *error = "Checksum не совпал для " + relative_path + ".";
      return false;
    }
    ++checked;
  }
  if (checked < 7) {
    *error = "Manifest runtime assets неполон.";
    return false;
  }
  return true;
}

bool AcquireLock(std::string* error) {
  if (g_mkdir_with_parents(g_runtime_directory.c_str(), 0750) != 0) {
    *error = std::string("Не удалось создать /run/qnzapret: ") +
             std::strerror(errno);
    return false;
  }
  g_state.lock_fd =
      g_open(LockPath().c_str(), O_CREAT | O_RDWR | O_CLOEXEC, 0640);
  if (g_state.lock_fd < 0 ||
      flock(g_state.lock_fd, LOCK_EX | LOCK_NB) != 0) {
    *error = "Другой экземпляр QNZapret уже владеет runtime lock.";
    if (g_state.lock_fd >= 0) {
      close(g_state.lock_fd);
      g_state.lock_fd = -1;
    }
    return false;
  }
  return true;
}

void ReleaseLock() {
  if (g_state.lock_fd >= 0) {
    flock(g_state.lock_fd, LOCK_UN);
    close(g_state.lock_fd);
    g_state.lock_fd = -1;
  }
}

std::int64_t SenderUid(GDBusMethodInvocation* invocation,
                       std::string* error_text) {
  const gchar* sender = g_dbus_method_invocation_get_sender(invocation);
  g_autoptr(GError) error = nullptr;
  g_autoptr(GVariant) result = g_dbus_connection_call_sync(
      g_state.connection, "org.freedesktop.DBus", "/org/freedesktop/DBus",
      "org.freedesktop.DBus", "GetConnectionUnixUser",
      g_variant_new("(s)", sender), G_VARIANT_TYPE("(u)"),
      G_DBUS_CALL_FLAGS_NONE, 3000, nullptr, &error);
  if (result == nullptr) {
    *error_text = error != nullptr ? error->message : "UID lookup failed";
    return -1;
  }
  guint32 uid = 0;
  g_variant_get(result, "(u)", &uid);
  return uid;
}

bool Authorize(GDBusMethodInvocation* invocation,
               std::int64_t* sender_uid,
               std::string* error_text) {
  *sender_uid = SenderUid(invocation, error_text);
  if (*sender_uid < 0) {
    return false;
  }
  const gchar* sender = g_dbus_method_invocation_get_sender(invocation);
  GVariantBuilder subject_details;
  g_variant_builder_init(&subject_details, G_VARIANT_TYPE_VARDICT);
  g_variant_builder_add(&subject_details, "{sv}", "name",
                        g_variant_new_string(sender));
  GVariantBuilder details;
  g_variant_builder_init(&details, G_VARIANT_TYPE("a{ss}"));

  g_autoptr(GError) error = nullptr;
  g_autoptr(GVariant) result = g_dbus_connection_call_sync(
      g_state.connection, "org.freedesktop.PolicyKit1",
      "/org/freedesktop/PolicyKit1/Authority",
      "org.freedesktop.PolicyKit1.Authority", "CheckAuthorization",
      g_variant_new("((s@a{sv})s@a{ss}us)", "system-bus-name",
                    g_variant_builder_end(&subject_details), kPolkitAction,
                    g_variant_builder_end(&details), 1u, ""),
      G_VARIANT_TYPE("((bba{ss}))"), G_DBUS_CALL_FLAGS_NONE, 120000, nullptr,
      &error);
  if (result == nullptr) {
    *error_text = error != nullptr ? error->message : "Polkit unavailable";
    return false;
  }
  gboolean authorized = false;
  gboolean challenge = false;
  GVariant* result_details = nullptr;
  g_variant_get(result, "((bb@a{ss}))", &authorized, &challenge,
                &result_details);
  g_variant_unref(result_details);
  if (!authorized) {
    *error_text = "Polkit не разрешил изменение системного runtime.";
    return false;
  }
  return true;
}

void ReturnError(GDBusMethodInvocation* invocation,
                 const char* code,
                 const std::string& message) {
  const std::string name = std::string("dev.qnzapret.Error.") + code;
  g_dbus_method_invocation_return_dbus_error(invocation, name.c_str(),
                                             message.c_str());
}

void ClearPendingStart() {
  if (g_state.pending_start_invocation != nullptr) {
    g_object_unref(g_state.pending_start_invocation);
    g_state.pending_start_invocation = nullptr;
  }
  g_state.pending_profile = qnzapret::CompiledProfile{};
  g_state.queue_poll_attempts = 0;
}

void FinalizeCleanup(bool mark_idle) {
  if (g_state.stop_timeout_id != 0) {
    g_source_remove(g_state.stop_timeout_id);
    g_state.stop_timeout_id = 0;
  }
  if (g_state.queue_poll_id != 0) {
    g_source_remove(g_state.queue_poll_id);
    g_state.queue_poll_id = 0;
  }
  g_clear_object(&g_state.nfqws_output);
  g_clear_object(&g_state.nfqws_process);
  g_state.nfqws_wait_completed = false;
  ReleaseLock();
  g_unlink(ActiveProfilePath().c_str());
  if (mark_idle) {
    g_state.snapshot = qnzapret::RuntimeSnapshot{};
    g_state.snapshot.message = "Linux runtime остановлен.";
    EmitLog("info", "linux_runtime_stopped",
            "Системный перехват и nfqws2 остановлены.");
    EmitSnapshot();
  }
  if (g_state.pending_stop_invocation != nullptr) {
    g_dbus_method_invocation_return_value(g_state.pending_stop_invocation,
                                          nullptr);
    g_clear_object(&g_state.pending_stop_invocation);
  }
  g_state.cleanup_requested = false;
  g_state.cleanup_mark_idle = false;
  g_state.stopping = false;
}

gboolean ForceNfqwsStop(gpointer) {
  g_state.stop_timeout_id = 0;
  if (g_state.nfqws_process != nullptr &&
      !g_state.nfqws_wait_completed) {
    EmitLog("warning", "linux_nfqws_force_stop",
            "nfqws2 не завершился после SIGTERM; применен bounded force-exit.");
    g_subprocess_force_exit(g_state.nfqws_process);
  }
  return G_SOURCE_REMOVE;
}

void RequestProcessStop(bool mark_idle,
                        GDBusMethodInvocation* stop_invocation = nullptr) {
  g_state.stopping = true;
  g_state.cleanup_requested = true;
  g_state.cleanup_mark_idle = mark_idle;
  if (stop_invocation != nullptr &&
      g_state.pending_stop_invocation == nullptr) {
    g_state.pending_stop_invocation = G_DBUS_METHOD_INVOCATION(
        g_object_ref(stop_invocation));
  }
  std::string cleanup_error;
  if (!DeleteOwnTable(&cleanup_error) && !cleanup_error.empty()) {
    EmitLog("warning", "linux_nft_cleanup_failed", cleanup_error);
  }
  g_state.snapshot.traffic_forwarder_ready = false;
  g_state.snapshot.traffic_interception_active = false;
  g_state.snapshot.nft_rules_installed = false;
  g_state.snapshot.interception_ready = false;
  if (g_state.nfqws_process == nullptr || g_state.nfqws_wait_completed) {
    FinalizeCleanup(mark_idle);
    return;
  }
  g_subprocess_send_signal(g_state.nfqws_process, SIGTERM);
  if (g_state.stop_timeout_id == 0) {
    g_state.stop_timeout_id =
        g_timeout_add_seconds(3, ForceNfqwsStop, nullptr);
  }
}

void CleanupProcessForShutdown() {
  std::string cleanup_error;
  DeleteOwnTable(&cleanup_error);
  if (g_state.nfqws_process != nullptr &&
      !g_state.nfqws_wait_completed) {
    g_subprocess_force_exit(g_state.nfqws_process);
    g_subprocess_wait(g_state.nfqws_process, nullptr, nullptr);
  }
  ClearPendingStart();
  g_clear_object(&g_state.pending_stop_invocation);
  g_clear_object(&g_state.nfqws_output);
  g_clear_object(&g_state.nfqws_process);
  ReleaseLock();
  g_unlink(ActiveProfilePath().c_str());
}

void ReadNfqwsLine(GObject* source, GAsyncResult* result, gpointer) {
  g_autoptr(GError) error = nullptr;
  gsize length = 0;
  g_autofree gchar* line =
      g_data_input_stream_read_line_finish(G_DATA_INPUT_STREAM(source), result,
                                           &length, &error);
  if (line == nullptr) {
    return;
  }
  if (g_integration_test) {
    g_printerr("nfqws2: %.*s\n", static_cast<int>(length), line);
  }
  EmitLog("debug", "linux_nfqws_output", std::string(line, length));
  g_data_input_stream_read_line_async(
      G_DATA_INPUT_STREAM(source), G_PRIORITY_DEFAULT, nullptr,
      ReadNfqwsLine, nullptr);
}

void NfqwsExited(GObject* source, GAsyncResult* result, gpointer) {
  g_autoptr(GError) error = nullptr;
  const bool waited = g_subprocess_wait_finish(G_SUBPROCESS(source), result,
                                                &error);
  if (source != G_OBJECT(g_state.nfqws_process)) {
    return;
  }
  g_state.nfqws_wait_completed = true;
  if (g_state.cleanup_requested) {
    FinalizeCleanup(g_state.cleanup_mark_idle);
    if (g_integration_test && g_loop != nullptr) {
      g_main_loop_quit(g_loop);
    }
    return;
  }
  std::string outcome;
  if (!waited) {
    outcome = error != nullptr ? error->message : "wait failed";
  } else if (g_subprocess_get_if_signaled(G_SUBPROCESS(source))) {
    outcome = "signal=" +
              std::to_string(g_subprocess_get_term_sig(G_SUBPROCESS(source)));
  } else {
    outcome = "exitCode=" + std::to_string(
                                g_subprocess_get_exit_status(
                                    G_SUBPROCESS(source)));
  }
  if (g_integration_test) {
    g_printerr("integration nfqws exited: %s\n", outcome.c_str());
  }
  if (g_state.pending_start_invocation != nullptr) {
    ReturnError(g_state.pending_start_invocation,
                "linux_nfqws_unexpected_exit",
                "nfqws2 завершился до регистрации NFQUEUE: " + outcome);
    ClearPendingStart();
  }
  std::string cleanup_error;
  DeleteOwnTable(&cleanup_error);
  ReleaseLock();
  g_unlink(ActiveProfilePath().c_str());
  g_clear_object(&g_state.nfqws_output);
  g_clear_object(&g_state.nfqws_process);
  g_state.snapshot.state = "failed";
  g_state.snapshot.message =
      "nfqws2 неожиданно завершился (" + outcome +
      "); правила перехвата удалены в fail-open режиме.";
  g_state.snapshot.service_active = false;
  g_state.snapshot.strategy_engine_ready = false;
  g_state.snapshot.traffic_forwarder_ready = false;
  g_state.snapshot.traffic_interception_active = false;
  g_state.snapshot.queue_registered = false;
  g_state.snapshot.nft_rules_installed = false;
  g_state.snapshot.interception_ready = false;
  EmitLog("error", "linux_nfqws_unexpected_exit", g_state.snapshot.message);
  EmitSnapshot();
  if (g_integration_test && g_loop != nullptr) {
    g_main_loop_quit(g_loop);
  }
}

bool StartNfqws(const qnzapret::CompiledProfile& profile,
                std::string* error_text) {
  std::vector<std::string> command{g_nfqws_path};
  command.insert(command.end(), profile.arguments.begin(),
                 profile.arguments.end());
  const auto argv = Argv(command);
  g_autoptr(GError) error = nullptr;
  g_state.nfqws_process = g_subprocess_newv(
      argv.data(),
      static_cast<GSubprocessFlags>(G_SUBPROCESS_FLAGS_STDOUT_PIPE |
                                    G_SUBPROCESS_FLAGS_STDERR_MERGE),
      &error);
  if (g_state.nfqws_process == nullptr) {
    *error_text = error != nullptr ? error->message : "spawn failed";
    return false;
  }
  g_state.nfqws_wait_completed = false;
  g_state.nfqws_output = g_data_input_stream_new(
      g_subprocess_get_stdout_pipe(g_state.nfqws_process));
  g_data_input_stream_read_line_async(g_state.nfqws_output,
                                      G_PRIORITY_DEFAULT, nullptr,
                                      ReadNfqwsLine, nullptr);
  g_subprocess_wait_async(g_state.nfqws_process, nullptr, NfqwsExited,
                          nullptr);
  return true;
}

void FailPendingStart(const char* code, const std::string& message) {
  if (g_state.pending_start_invocation != nullptr) {
    ReturnError(g_state.pending_start_invocation, code, message);
  }
  ClearPendingStart();
  SetFailed(code, message);
  RequestProcessStop(false);
}

gboolean PollQueueReadiness(gpointer) {
  if (g_state.pending_start_invocation == nullptr) {
    g_state.queue_poll_id = 0;
    return G_SOURCE_REMOVE;
  }
  if (!QueueRegistered()) {
    ++g_state.queue_poll_attempts;
    if (g_state.queue_poll_attempts < 80) {
      return G_SOURCE_CONTINUE;
    }
    g_state.queue_poll_id = 0;
    FailPendingStart(
        "linux_queue_registration_timeout",
        "NFQUEUE 200 не зарегистрирована за 4 секунды после запуска nfqws2.");
    return G_SOURCE_REMOVE;
  }

  g_state.snapshot.queue_registered = true;
  g_state.snapshot.strategy_engine_ready = true;
  EmitLog("info", "linux_nfqws_queue_registered",
          "nfqws2 зарегистрировал NFQUEUE 200.");

  const auto nft = FindNft();
  const auto rules = qnzapret::BuildNftRules(qnzapret::kQueueNumber);
  std::string output;
  std::string errors;
  qnzapret::SubprocessTermination termination;
  int status = 0;
  if (!RunProcess({nft, "-f", "-"}, &rules, &output, &errors,
                  &termination, &status)) {
    g_state.queue_poll_id = 0;
    const auto details = errors.empty() ? output : errors;
    const auto failure = qnzapret::MapSubprocessFailure(
        termination, status, details, "linux_nft_transaction");
    FailPendingStart("linux_nft_transaction_failed", failure.message);
    return G_SOURCE_REMOVE;
  }

  g_state.snapshot.state = "running";
  g_state.snapshot.message =
      "NFQUEUE зарегистрирована, nftables rules установлены.";
  g_state.snapshot.service_active = true;
  g_state.snapshot.traffic_forwarder_ready = true;
  g_state.snapshot.traffic_interception_active = true;
  g_state.snapshot.nft_rules_installed = true;
  g_state.snapshot.interception_ready = true;
  g_file_set_contents(ActiveProfilePath().c_str(),
                      g_state.pending_profile.id.c_str(), -1, nullptr);
  chmod(ActiveProfilePath().c_str(), 0640);
  EmitLog("info", "linux_runtime_started",
          "NFQUEUE 200 и атомарная inet qnzapret topology готовы.");
  EmitSnapshot();
  g_dbus_method_invocation_return_value(g_state.pending_start_invocation,
                                        nullptr);
  ClearPendingStart();
  g_state.queue_poll_id = 0;
  return G_SOURCE_REMOVE;
}

bool RunPreflight(std::string* code, std::string* error) {
  if (FindNft().empty()) {
    *code = "linux_nft_unavailable";
    *error = "nftables CLI не найден.";
    return false;
  }
  if (!g_file_test("/proc/net/netfilter", G_FILE_TEST_IS_DIR)) {
    *code = "linux_nfqueue_unavailable";
    *error = "Kernel netfilter interface недоступен.";
    return false;
  }
  if (!g_file_test(g_nfqws_path.c_str(),
                   static_cast<GFileTest>(G_FILE_TEST_EXISTS |
                                          G_FILE_TEST_IS_EXECUTABLE))) {
    *code = "linux_backend_unavailable";
    *error = "Packaged nfqws2 отсутствует или не исполняемый.";
    return false;
  }
  if (!g_file_test("/usr/lib/qnzapret/telegram/proxy/tg_ws_proxy.py",
                   G_FILE_TEST_EXISTS)) {
    *code = "linux_telegram_start_failed";
    *error = "Telegram sidecar assets не установлены.";
    return false;
  }
  if (QueueRegistered()) {
    *code = "linux_queue_conflict";
    *error = "NFQUEUE 200 уже занята.";
    return false;
  }
  if (TableExists()) {
    *code = "linux_queue_conflict";
    *error = "Таблица inet qnzapret уже существует.";
    return false;
  }
  if (!VerifyAssets(error)) {
    *code = "linux_backend_unavailable";
    return false;
  }

  qnzapret::CompiledProfile compiled;
  if (!qnzapret::CompileStrategyProfile(
          qnzapret::DefaultStrategyProfileWire(), g_asset_root,
          qnzapret::kQueueNumber, &compiled, error)) {
    *code = "linux_profile_parse_rejected";
    return false;
  }
  std::string output;
  std::string errors;
  qnzapret::SubprocessTermination termination;
  int status = 0;
  if (!RunProcess({g_nfqws_path, "--version"}, nullptr, &output, &errors,
                  &termination, &status) ||
      output.find("v0.9.5.2") == std::string::npos ||
      output.find("7a69d56a4b35f814fb2d42e7bddb2f21c2314ff9") ==
          std::string::npos) {
    *code = "linux_backend_version_mismatch";
    *error = "Bundled nfqws2 не соответствует pinned v0.9.5.2/7a69d56.";
    return false;
  }
  std::vector<std::string> dry_run{g_nfqws_path, "--dry-run"};
  dry_run.insert(dry_run.end(), compiled.arguments.begin(),
                 compiled.arguments.end());
  output.clear();
  errors.clear();
  if (!RunProcess(dry_run, nullptr, &output, &errors, &termination,
                  &status)) {
    const auto failure = qnzapret::MapSubprocessFailure(
        termination, status, errors.empty() ? output : errors,
        "linux_profile_dry_run");
    *code = failure.code;
    *error = failure.message;
    return false;
  }
  return true;
}

void HandleStart(const std::string& wire_profile,
                 std::int64_t sender_uid,
                 GDBusMethodInvocation* invocation) {
  if (g_state.snapshot.service_active) {
    if (g_state.snapshot.owner_uid == sender_uid) {
      g_dbus_method_invocation_return_value(invocation, nullptr);
      return;
    }
    ReturnError(invocation, "linux_runtime_owned_by_other_user",
                "Runtime уже активен в другой пользовательской сессии.");
    return;
  }

  std::string code;
  std::string error;
  if (!RunPreflight(&code, &error)) {
    ReturnError(invocation, code.c_str(), error);
    return;
  }
  if (!AcquireLock(&error)) {
    ReturnError(invocation, "linux_queue_conflict", error);
    return;
  }

  qnzapret::CompiledProfile profile;
  if (!qnzapret::CompileStrategyProfile(
          wire_profile, g_asset_root, qnzapret::kQueueNumber, &profile,
          &error)) {
    ReleaseLock();
    ReturnError(invocation, "linux_profile_parse_rejected", error);
    return;
  }

  g_state.snapshot.state = "starting";
  g_state.snapshot.message = "Проверяем стратегию и запускаем nfqws2.";
  g_state.snapshot.service_active = true;
  g_state.snapshot.owner_uid = sender_uid;
  g_state.snapshot.active_profile_name = profile.name;
  EmitSnapshot();

  std::vector<std::string> dry_run{g_nfqws_path, "--dry-run"};
  dry_run.insert(dry_run.end(), profile.arguments.begin(),
                 profile.arguments.end());
  std::string output;
  std::string errors;
  qnzapret::SubprocessTermination termination;
  int status = 0;
  if (!RunProcess(dry_run, nullptr, &output, &errors, &termination,
                  &status)) {
    const auto failure = qnzapret::MapSubprocessFailure(
        termination, status, errors.empty() ? output : errors,
        "linux_profile_dry_run");
    ReleaseLock();
    SetFailed(failure.code, failure.message);
    ReturnError(invocation, failure.code.c_str(), failure.message);
    return;
  }
  if (!StartNfqws(profile, &error)) {
    ReleaseLock();
    const auto failure = qnzapret::MapSubprocessFailure(
        qnzapret::SubprocessTermination::kSpawnFailed, -1, error,
        "linux_nfqws");
    SetFailed(failure.code, failure.message);
    ReturnError(invocation, failure.code.c_str(), failure.message);
    return;
  }
  g_state.pending_start_invocation =
      G_DBUS_METHOD_INVOCATION(g_object_ref(invocation));
  g_state.pending_profile = profile;
  g_state.queue_poll_attempts = 0;
  g_state.queue_poll_id = g_timeout_add(50, PollQueueReadiness, nullptr);
}

void HandleMethodCall(GDBusConnection*,
                      const gchar*,
                      const gchar*,
                      const gchar*,
                      const gchar* method,
                      GVariant* parameters,
                      GDBusMethodInvocation* invocation,
                      gpointer) {
  const std::string method_name = method;
  if (method_name == "GetVersion") {
    g_dbus_method_invocation_return_value(
        invocation, g_variant_new("(s)", qnzapret::kRuntimeVersion));
    return;
  }
  if (method_name == "GetSnapshot") {
    g_dbus_method_invocation_return_value(
        invocation, g_variant_new("(@a{sv})", SnapshotVariant()));
    return;
  }
  if (method_name == "Prepare") {
    std::string code;
    std::string error;
    const bool ready = RunPreflight(&code, &error);
    GVariantBuilder builder;
    g_variant_builder_init(&builder, G_VARIANT_TYPE_VARDICT);
    g_variant_builder_add(&builder, "{sv}", "granted",
                          g_variant_new_boolean(ready));
    g_variant_builder_add(
        &builder, "{sv}", "message",
        g_variant_new_string(
            ready ? "Linux runtime прошел read-only preflight." : error.c_str()));
    if (!ready) {
      g_variant_builder_add(&builder, "{sv}", "code",
                            g_variant_new_string(code.c_str()));
    }
    g_dbus_method_invocation_return_value(
        invocation,
        g_variant_new("(@a{sv})", g_variant_builder_end(&builder)));
    return;
  }

  if (method_name != "Start" && method_name != "Stop") {
    ReturnError(invocation, "linux_backend_unavailable", "Неизвестный метод.");
    return;
  }
  std::int64_t sender_uid = -1;
  std::string authorization_error;
  if (!Authorize(invocation, &sender_uid, &authorization_error)) {
    ReturnError(invocation, "linux_authorization_denied",
                authorization_error);
    return;
  }
  if (method_name == "Start") {
    const gchar* profile = nullptr;
    g_variant_get(parameters, "(&s)", &profile);
    HandleStart(profile == nullptr ? "" : profile, sender_uid, invocation);
    return;
  }

  if (!g_state.snapshot.service_active &&
      g_state.snapshot.state != "failed") {
    g_dbus_method_invocation_return_value(invocation, nullptr);
    return;
  }
  g_state.snapshot.state = "stopping";
  g_state.snapshot.message = "Останавливаем системный перехват.";
  EmitSnapshot();
  if (g_state.pending_start_invocation != nullptr) {
    ReturnError(g_state.pending_start_invocation, "linux_start_cancelled",
                "Запуск отменен командой Stop.");
    ClearPendingStart();
  }
  RequestProcessStop(true, invocation);
}

const GDBusInterfaceVTable kInterfaceVtable = {
    HandleMethodCall,
    nullptr,
    nullptr,
    {nullptr},
};

void OnBusAcquired(GDBusConnection* connection, const gchar*, gpointer) {
  g_state.connection = G_DBUS_CONNECTION(g_object_ref(connection));
  g_autoptr(GError) error = nullptr;
  g_autoptr(GDBusNodeInfo) introspection =
      g_dbus_node_info_new_for_xml(kIntrospectionXml, &error);
  if (introspection == nullptr) {
    g_error("Invalid D-Bus introspection: %s", error->message);
  }
  g_state.registration_id = g_dbus_connection_register_object(
      connection, qnzapret::kRuntimeObjectPath,
      introspection->interfaces[0], &kInterfaceVtable, nullptr, nullptr,
      &error);
  if (g_state.registration_id == 0) {
    g_error("Unable to register runtime object: %s", error->message);
  }
}

void OnNameLost(GDBusConnection*, const gchar*, gpointer) {
  if (g_loop != nullptr) {
    g_main_loop_quit(g_loop);
  }
}

gboolean PollIntegrationQueue(gpointer) {
  static int attempts = 0;
  if (!QueueRegistered()) {
    ++attempts;
    if (attempts < 80) {
      return G_SOURCE_CONTINUE;
    }
    g_integration_failed = true;
    g_printerr("integration queue 200 registration timeout\n");
    RequestProcessStop(false);
    return G_SOURCE_REMOVE;
  }
  const auto nft = FindNft();
  const auto rules = qnzapret::BuildNftRules(qnzapret::kQueueNumber);
  std::string output;
  std::string errors;
  qnzapret::SubprocessTermination termination;
  int status = 0;
  if (!RunProcess({nft, "-f", "-"}, &rules, &output, &errors,
                  &termination, &status)) {
    g_printerr("integration nft transaction failed: %s\n",
               (errors.empty() ? output : errors).c_str());
    g_integration_failed = true;
    RequestProcessStop(false);
    return G_SOURCE_REMOVE;
  }
  g_integration_ready = true;
  const gchar* child = g_subprocess_get_identifier(g_state.nfqws_process);
  g_print("QNZAPRET_INTEGRATION_READY child=%s\n",
          child == nullptr ? "unknown" : child);
  fflush(stdout);
  return G_SOURCE_REMOVE;
}

int RunIntegrationTest() {
  if (geteuid() != 0 ||
      g_strcmp0(g_getenv("QNZAPRET_INTEGRATION_TEST"), "1") != 0) {
    g_printerr("integration mode requires root and explicit test marker\n");
    return 77;
  }
  const gchar* asset_root = g_getenv("QNZAPRET_ASSET_ROOT");
  const gchar* nfqws_path = g_getenv("QNZAPRET_NFQWS");
  const gchar* runtime_directory = g_getenv("QNZAPRET_RUNTIME_DIRECTORY");
  if (asset_root == nullptr || nfqws_path == nullptr ||
      runtime_directory == nullptr || !g_path_is_absolute(asset_root) ||
      !g_path_is_absolute(nfqws_path) ||
      !g_str_has_prefix(runtime_directory, "/tmp/qnzapret-netns-")) {
    g_printerr("integration paths are missing or unsafe\n");
    return 77;
  }
  g_integration_test = true;
  g_asset_root = asset_root;
  g_nfqws_path = nfqws_path;
  g_runtime_directory = runtime_directory;

  std::string error;
  if (!VerifyAssets(&error) || !AcquireLock(&error)) {
    g_printerr("integration preflight failed: %s\n", error.c_str());
    return 1;
  }
  qnzapret::CompiledProfile profile;
  if (!qnzapret::CompileStrategyProfile(
          qnzapret::DefaultStrategyProfileWire(), g_asset_root,
          qnzapret::kQueueNumber, &profile, &error)) {
    g_printerr("integration profile failed: %s\n", error.c_str());
    ReleaseLock();
    return 1;
  }
  profile.arguments.push_back("--debug=1");
  if (!StartNfqws(profile, &error)) {
    g_printerr("integration nfqws spawn failed: %s\n", error.c_str());
    ReleaseLock();
    return 1;
  }
  g_autoptr(GMainLoop) loop = g_main_loop_new(nullptr, false);
  g_loop = loop;
  g_timeout_add(50, PollIntegrationQueue, nullptr);
  g_main_loop_run(loop);
  const bool table_removed = !TableExists();
  CleanupProcessForShutdown();
  return g_integration_ready && !g_integration_failed && table_removed ? 0
                                                                       : 1;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc == 2 && std::string(argv[1]) == "--integration-test") {
    return RunIntegrationTest();
  }
  if (argc == 2 && std::string(argv[1]) == "--cleanup") {
    std::string error;
    const bool cleaned = DeleteOwnTable(&error);
    g_unlink(ActiveProfilePath().c_str());
    g_unlink(LockPath().c_str());
    if (!cleaned && !error.empty()) {
      g_printerr("%s\n", qnzapret::RedactRuntimeMessage(error).c_str());
      return 1;
    }
    return 0;
  }
  g_autoptr(GMainLoop) loop = g_main_loop_new(nullptr, false);
  g_loop = loop;
  g_state.owner_id = g_bus_own_name(
      G_BUS_TYPE_SYSTEM, qnzapret::kRuntimeBusName,
      G_BUS_NAME_OWNER_FLAGS_ALLOW_REPLACEMENT, OnBusAcquired, nullptr,
      OnNameLost, nullptr, nullptr);

  while (g_state.connection == nullptr) {
    g_main_context_iteration(nullptr, true);
  }
  EmitLog("info", "linux_backend_ready",
          "Системный Linux runtime доступен.");
  g_main_loop_run(loop);

  CleanupProcessForShutdown();
  if (g_state.registration_id != 0 && g_state.connection != nullptr) {
    g_dbus_connection_unregister_object(g_state.connection,
                                        g_state.registration_id);
  }
  if (g_state.owner_id != 0) {
    g_bus_unown_name(g_state.owner_id);
  }
  g_clear_object(&g_state.connection);
  return 0;
}
