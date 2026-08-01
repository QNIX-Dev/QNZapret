#include "runtime_contract.h"
#include "telegram_state_directory.h"

#include <arpa/inet.h>
#include <gio/gio.h>
#include <glib/gstdio.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

#include <array>
#include <cerrno>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

namespace {

const char kIntrospectionXml[] = R"XML(
<node>
  <interface name="dev.qnzapret.Telegram1">
    <method name="Prepare">
      <arg name="result" type="a{sv}" direction="out"/>
    </method>
    <method name="GetSnapshot">
      <arg name="snapshot" type="a{sv}" direction="out"/>
    </method>
    <method name="Start"/>
    <method name="Stop"/>
    <method name="Retry"/>
    <method name="GetSetupUri">
      <arg name="uri" type="s" direction="out"/>
    </method>
    <signal name="SnapshotChanged">
      <arg name="snapshot" type="a{sv}"/>
    </signal>
    <signal name="LogEvent">
      <arg name="event" type="a{sv}"/>
    </signal>
  </interface>
</node>
)XML";

struct SidecarState {
  GDBusConnection* connection = nullptr;
  guint registration_id = 0;
  guint owner_id = 0;
  guint health_timer = 0;
  guint start_timer = 0;
  guint stop_timer = 0;
  GSubprocess* process = nullptr;
  GDBusMethodInvocation* pending_start = nullptr;
  GDBusMethodInvocation* pending_stop = nullptr;
  GDBusMethodInvocation* pending_retry = nullptr;
  std::string state = "idle";
  std::string message = "Telegram compatibility mode готов.";
  std::string state_directory;
  std::string secret_path;
  std::string health_path;
  bool listener_ready = false;
  bool bridge_ready = false;
  bool stopping = false;
  int start_attempts = 0;
};

SidecarState g_state;
GMainLoop* g_loop = nullptr;

std::int64_t NowMillis() {
  return g_get_real_time() / 1000;
}

GVariant* SnapshotVariant() {
  GVariantBuilder builder;
  g_variant_builder_init(&builder, G_VARIANT_TYPE_VARDICT);
  g_variant_builder_add(&builder, "{sv}", "state",
                        g_variant_new_string(g_state.state.c_str()));
  g_variant_builder_add(&builder, "{sv}", "ready",
                        g_variant_new_boolean(g_state.bridge_ready));
  g_variant_builder_add(&builder, "{sv}", "listenerReady",
                        g_variant_new_boolean(g_state.listener_ready));
  g_variant_builder_add(&builder, "{sv}", "setupRequired",
                        g_variant_new_boolean(g_state.listener_ready &&
                                              !g_state.bridge_ready));
  g_variant_builder_add(&builder, "{sv}", "endpoint",
                        g_variant_new_string("127.0.0.1:1443"));
  g_variant_builder_add(&builder, "{sv}", "message",
                        g_variant_new_string(g_state.message.c_str()));
  return g_variant_builder_end(&builder);
}

void EmitSnapshot() {
  if (g_state.connection != nullptr) {
    g_dbus_connection_emit_signal(
        g_state.connection, nullptr, qnzapret::kTelegramObjectPath,
        qnzapret::kTelegramInterface, "SnapshotChanged",
        g_variant_new("(@a{sv})", SnapshotVariant()), nullptr);
  }
}

void EmitLog(const char* level,
             const char* code,
             const std::string& message) {
  if (g_state.connection == nullptr) {
    return;
  }
  GVariantBuilder builder;
  g_variant_builder_init(&builder, G_VARIANT_TYPE_VARDICT);
  g_variant_builder_add(&builder, "{sv}", "timestampMillis",
                        g_variant_new_int64(NowMillis()));
  g_variant_builder_add(&builder, "{sv}", "level",
                        g_variant_new_string(level));
  g_variant_builder_add(&builder, "{sv}", "source",
                        g_variant_new_string("telegram-sidecar"));
  g_variant_builder_add(&builder, "{sv}", "code",
                        g_variant_new_string(code));
  const auto redacted = qnzapret::RedactRuntimeMessage(message);
  g_variant_builder_add(&builder, "{sv}", "message",
                        g_variant_new_string(redacted.c_str()));
  g_dbus_connection_emit_signal(
      g_state.connection, nullptr, qnzapret::kTelegramObjectPath,
      qnzapret::kTelegramInterface, "LogEvent",
      g_variant_new("(@a{sv})", g_variant_builder_end(&builder)), nullptr);
}

bool ReadSecret(std::string* secret) {
  gchar* contents = nullptr;
  gsize length = 0;
  if (!g_file_get_contents(g_state.secret_path.c_str(), &contents, &length,
                           nullptr)) {
    return false;
  }
  secret->assign(contents, length);
  g_free(contents);
  while (!secret->empty() &&
         (secret->back() == '\n' || secret->back() == '\r')) {
    secret->pop_back();
  }
  return secret->size() == 32;
}

bool EnsureSecret(std::string* error) {
  if (g_mkdir_with_parents(g_state.state_directory.c_str(), 0700) != 0) {
    *error = std::string("Не удалось создать XDG state directory: ") +
             std::strerror(errno);
    return false;
  }
  chmod(g_state.state_directory.c_str(), 0700);
  std::string existing;
  if (ReadSecret(&existing)) {
    chmod(g_state.secret_path.c_str(), 0600);
    return true;
  }

  std::array<unsigned char, 16> bytes{};
  std::ifstream random("/dev/urandom", std::ios::binary);
  random.read(reinterpret_cast<char*>(bytes.data()), bytes.size());
  if (random.gcount() != static_cast<std::streamsize>(bytes.size())) {
    *error = "Не удалось получить криптографически стойкий random.";
    return false;
  }
  static constexpr char kHex[] = "0123456789abcdef";
  std::string secret;
  secret.reserve(32);
  for (const auto byte : bytes) {
    secret.push_back(kHex[byte >> 4]);
    secret.push_back(kHex[byte & 0x0f]);
  }
  if (!g_file_set_contents(g_state.secret_path.c_str(), secret.c_str(),
                           secret.size(), nullptr)) {
    *error = "Не удалось сохранить Telegram secret.";
    return false;
  }
  chmod(g_state.secret_path.c_str(), 0600);
  return true;
}

bool ProbeListener() {
  const int socket_fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (socket_fd < 0) {
    return false;
  }
  sockaddr_in address{};
  address.sin_family = AF_INET;
  address.sin_port = htons(1443);
  const bool parsed =
      inet_pton(AF_INET, "127.0.0.1", &address.sin_addr) == 1;
  const bool connected =
      parsed && connect(socket_fd, reinterpret_cast<sockaddr*>(&address),
                        sizeof(address)) == 0;
  close(socket_fd);
  return connected;
}

void ReturnError(GDBusMethodInvocation* invocation,
                 const char* code,
                 const std::string& message);
bool StartProcess(std::string* error_text);
gboolean PollStartReadiness(gpointer);
void BeginStop(GDBusMethodInvocation* invocation);

void ClearInvocation(GDBusMethodInvocation** invocation) {
  g_clear_object(invocation);
}

gboolean CheckHealth(gpointer) {
  if (g_state.process == nullptr) {
    return G_SOURCE_CONTINUE;
  }
  const bool previous_listener = g_state.listener_ready;
  const bool previous_bridge = g_state.bridge_ready;
  g_state.listener_ready = ProbeListener();
  gchar* contents = nullptr;
  gsize length = 0;
  if (g_file_get_contents(g_state.health_path.c_str(), &contents, &length,
                          nullptr)) {
    const std::string health(contents, length);
    g_state.bridge_ready =
        health.find("bridge_ready") != std::string::npos;
    g_free(contents);
  }
  g_state.state = g_state.listener_ready ? "running" : "starting";
  g_state.message = g_state.bridge_ready
                        ? "Telegram proxy подтвердил живой upstream bridge."
                        : g_state.listener_ready
                              ? "Telegram proxy слушает loopback; требуется "
                                "подтверждение в Telegram."
                              : "Запускаем Telegram compatibility mode.";
  if (previous_listener != g_state.listener_ready ||
      previous_bridge != g_state.bridge_ready) {
    EmitSnapshot();
    if (g_state.bridge_ready) {
      EmitLog("info", "linux_telegram_bridge_ready",
              "Telegram MTProxy handshake и upstream bridge подтверждены.");
    }
  }
  return G_SOURCE_CONTINUE;
}

void ProcessExited(GObject* source, GAsyncResult* result, gpointer) {
  g_autoptr(GError) error = nullptr;
  g_subprocess_wait_finish(G_SUBPROCESS(source), result, &error);
  if (source != G_OBJECT(g_state.process)) {
    return;
  }
  if (g_state.start_timer != 0) {
    g_source_remove(g_state.start_timer);
    g_state.start_timer = 0;
  }
  if (g_state.stop_timer != 0) {
    g_source_remove(g_state.stop_timer);
    g_state.stop_timer = 0;
  }
  g_clear_object(&g_state.process);
  g_state.listener_ready = false;
  g_state.bridge_ready = false;
  if (g_state.pending_start != nullptr) {
    ReturnError(g_state.pending_start, "linux_telegram_start_failed",
                "Telegram sidecar завершился до готовности listener.");
    ClearInvocation(&g_state.pending_start);
  }
  if (g_state.stopping) {
    g_state.state = "idle";
    g_state.message = "Telegram compatibility mode остановлен.";
    g_unlink(g_state.health_path.c_str());
    EmitLog("info", "linux_telegram_stopped",
            "Telegram sidecar остановлен.");
    if (g_state.pending_stop != nullptr) {
      g_dbus_method_invocation_return_value(g_state.pending_stop, nullptr);
      ClearInvocation(&g_state.pending_stop);
    }
  } else {
    g_state.state = "failed";
    g_state.message = "Telegram sidecar неожиданно завершился.";
    EmitLog("error", "linux_telegram_exited", g_state.message);
  }
  g_state.stopping = false;
  if (g_state.pending_retry != nullptr) {
    auto* retry = g_state.pending_retry;
    g_state.pending_retry = nullptr;
    std::string retry_error;
    if (!StartProcess(&retry_error)) {
      ReturnError(retry, "linux_telegram_start_failed", retry_error);
      g_object_unref(retry);
    } else {
      g_state.pending_start = retry;
      g_state.start_attempts = 0;
      g_state.start_timer = g_timeout_add(100, PollStartReadiness, nullptr);
    }
  }
  EmitSnapshot();
}

bool StartProcess(std::string* error_text) {
  if (g_state.process != nullptr) {
    return true;
  }
  if (!EnsureSecret(error_text)) {
    return false;
  }
  g_unlink(g_state.health_path.c_str());
  const std::vector<std::string> command = {
      "/usr/bin/python3",
      "-m",
      "proxy.tg_ws_proxy",
      "--host=127.0.0.1",
      "--port=1443",
      "--secret-file=" + g_state.secret_path,
      "--health-file=" + g_state.health_path,
      "--log-file=" + g_state.state_directory + "/telegram.log",
      "--log-max-mb=2",
      "--log-backups=1",
  };
  std::vector<const gchar*> argv;
  for (const auto& argument : command) {
    argv.push_back(argument.c_str());
  }
  argv.push_back(nullptr);

  g_autoptr(GSubprocessLauncher) launcher =
      g_subprocess_launcher_new(G_SUBPROCESS_FLAGS_NONE);
  g_subprocess_launcher_setenv(launcher, "PYTHONPATH",
                               "/usr/lib/qnzapret/telegram", true);
  g_subprocess_launcher_setenv(launcher, "PYTHONUNBUFFERED", "1", true);
  g_subprocess_launcher_set_cwd(launcher, g_state.state_directory.c_str());
  g_autoptr(GError) error = nullptr;
  g_state.process = g_subprocess_launcher_spawnv(launcher, argv.data(), &error);
  if (g_state.process == nullptr) {
    *error_text = error != nullptr ? error->message : "spawn failed";
    return false;
  }
  g_state.state = "starting";
  g_state.message = "Запускаем Telegram compatibility mode.";
  g_state.listener_ready = false;
  g_state.bridge_ready = false;
  g_subprocess_wait_async(g_state.process, nullptr, ProcessExited, nullptr);
  EmitLog("info", "linux_telegram_starting",
          "Запущен непривилегированный Telegram sidecar.");
  EmitSnapshot();
  return true;
}

gboolean PollStartReadiness(gpointer) {
  if (g_state.pending_start == nullptr) {
    g_state.start_timer = 0;
    return G_SOURCE_REMOVE;
  }
  if (ProbeListener()) {
    g_state.listener_ready = true;
    g_state.state = "running";
    g_state.message =
        "Telegram proxy слушает loopback; требуется подтверждение в Telegram.";
    g_dbus_method_invocation_return_value(g_state.pending_start, nullptr);
    ClearInvocation(&g_state.pending_start);
    g_state.start_timer = 0;
    EmitLog("info", "linux_telegram_listener_ready",
            "Telegram sidecar слушает 127.0.0.1:1443.");
    EmitSnapshot();
    return G_SOURCE_REMOVE;
  }
  ++g_state.start_attempts;
  if (g_state.start_attempts < 50) {
    return G_SOURCE_CONTINUE;
  }
  ReturnError(g_state.pending_start, "linux_telegram_start_failed",
              "Telegram listener 127.0.0.1:1443 не готов за 5 секунд.");
  ClearInvocation(&g_state.pending_start);
  g_state.start_timer = 0;
  BeginStop(nullptr);
  return G_SOURCE_REMOVE;
}

gboolean ForceStop(gpointer) {
  g_state.stop_timer = 0;
  if (g_state.process != nullptr) {
    g_subprocess_force_exit(g_state.process);
  }
  return G_SOURCE_REMOVE;
}

void BeginStop(GDBusMethodInvocation* invocation) {
  if (g_state.process == nullptr) {
    g_state.state = "idle";
    g_state.message = "Telegram compatibility mode уже остановлен.";
    EmitSnapshot();
    if (invocation != nullptr) {
      g_dbus_method_invocation_return_value(invocation, nullptr);
    }
    return;
  }
  if (g_state.pending_start != nullptr) {
    ReturnError(g_state.pending_start, "linux_telegram_start_cancelled",
                "Запуск Telegram sidecar отменен командой Stop.");
    ClearInvocation(&g_state.pending_start);
  }
  g_state.stopping = true;
  g_state.state = "stopping";
  g_state.message = "Останавливаем Telegram compatibility mode.";
  if (invocation != nullptr && g_state.pending_stop == nullptr) {
    g_state.pending_stop =
        G_DBUS_METHOD_INVOCATION(g_object_ref(invocation));
  }
  g_subprocess_send_signal(g_state.process, SIGTERM);
  if (g_state.stop_timer == 0) {
    g_state.stop_timer = g_timeout_add_seconds(3, ForceStop, nullptr);
  }
  EmitSnapshot();
}

void ReturnError(GDBusMethodInvocation* invocation,
                 const char* code,
                 const std::string& message) {
  const std::string name = std::string("dev.qnzapret.Error.") + code;
  g_dbus_method_invocation_return_dbus_error(invocation, name.c_str(),
                                             message.c_str());
}

void HandleMethodCall(GDBusConnection*,
                      const gchar*,
                      const gchar*,
                      const gchar*,
                      const gchar* method,
                      GVariant*,
                      GDBusMethodInvocation* invocation,
                      gpointer) {
  const std::string method_name = method;
  if (method_name == "Prepare") {
    std::string error;
    const bool granted =
        g_file_test("/usr/lib/qnzapret/telegram/proxy/tg_ws_proxy.py",
                    G_FILE_TEST_EXISTS) &&
        EnsureSecret(&error);
    GVariantBuilder builder;
    g_variant_builder_init(&builder, G_VARIANT_TYPE_VARDICT);
    g_variant_builder_add(&builder, "{sv}", "granted",
                          g_variant_new_boolean(granted));
    g_variant_builder_add(
        &builder, "{sv}", "message",
        g_variant_new_string(
            granted ? "Telegram sidecar установлен и готов." : error.c_str()));
    g_dbus_method_invocation_return_value(
        invocation,
        g_variant_new("(@a{sv})", g_variant_builder_end(&builder)));
    return;
  }
  if (method_name == "GetSnapshot") {
    g_dbus_method_invocation_return_value(
        invocation, g_variant_new("(@a{sv})", SnapshotVariant()));
    return;
  }
  if (method_name == "GetSetupUri") {
    std::string secret;
    if (!EnsureSecret(&secret) || !ReadSecret(&secret)) {
      ReturnError(invocation, "linux_telegram_start_failed",
                  "Не удалось прочитать Telegram secret.");
      return;
    }
    const auto uri = "tg://proxy?server=127.0.0.1&port=1443&secret=dd" +
                     secret;
    g_dbus_method_invocation_return_value(invocation,
                                          g_variant_new("(s)", uri.c_str()));
    return;
  }
  if (method_name == "Start") {
    if (g_state.process != nullptr && g_state.listener_ready) {
      g_dbus_method_invocation_return_value(invocation, nullptr);
      return;
    }
    if (g_state.pending_start != nullptr) {
      ReturnError(invocation, "linux_telegram_start_in_progress",
                  "Запуск Telegram sidecar уже выполняется.");
      return;
    }
    std::string error;
    if (!StartProcess(&error)) {
      ReturnError(invocation, "linux_telegram_start_failed", error);
      return;
    }
    g_state.pending_start =
        G_DBUS_METHOD_INVOCATION(g_object_ref(invocation));
    g_state.start_attempts = 0;
    g_state.start_timer = g_timeout_add(100, PollStartReadiness, nullptr);
    return;
  }
  if (method_name == "Stop") {
    BeginStop(invocation);
    return;
  }
  if (method_name == "Retry") {
    if (g_state.pending_retry != nullptr) {
      ReturnError(invocation, "linux_telegram_start_in_progress",
                  "Retry Telegram sidecar уже выполняется.");
      return;
    }
    if (g_state.process == nullptr) {
      std::string error;
      if (!StartProcess(&error)) {
        ReturnError(invocation, "linux_telegram_start_failed", error);
        return;
      }
      g_state.pending_start =
          G_DBUS_METHOD_INVOCATION(g_object_ref(invocation));
      g_state.start_attempts = 0;
      g_state.start_timer = g_timeout_add(100, PollStartReadiness, nullptr);
      return;
    }
    g_state.pending_retry =
        G_DBUS_METHOD_INVOCATION(g_object_ref(invocation));
    BeginStop(nullptr);
    return;
  }
  ReturnError(invocation, "linux_backend_unavailable", "Неизвестный метод.");
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
    g_error("Invalid sidecar introspection: %s", error->message);
  }
  g_state.registration_id = g_dbus_connection_register_object(
      connection, qnzapret::kTelegramObjectPath,
      introspection->interfaces[0], &kInterfaceVtable, nullptr, nullptr,
      &error);
  if (g_state.registration_id == 0) {
    g_error("Unable to register sidecar object: %s", error->message);
  }
}

void OnNameLost(GDBusConnection*, const gchar*, gpointer) {
  if (g_loop != nullptr) {
    g_main_loop_quit(g_loop);
  }
}

}  // namespace

int main() {
  g_state.state_directory = qnzapret::TelegramStateDirectory();
  g_state.secret_path = g_state.state_directory + "/telegram.secret";
  g_state.health_path = g_state.state_directory + "/telegram.health";
  g_autoptr(GMainLoop) loop = g_main_loop_new(nullptr, false);
  g_loop = loop;
  g_state.owner_id = g_bus_own_name(
      G_BUS_TYPE_SESSION, qnzapret::kTelegramBusName,
      G_BUS_NAME_OWNER_FLAGS_ALLOW_REPLACEMENT, OnBusAcquired, nullptr,
      OnNameLost, nullptr, nullptr);
  g_state.health_timer = g_timeout_add_seconds(1, CheckHealth, nullptr);
  g_main_loop_run(loop);

  if (g_state.process != nullptr) {
    g_subprocess_force_exit(g_state.process);
    g_subprocess_wait(g_state.process, nullptr, nullptr);
    g_clear_object(&g_state.process);
  }
  ClearInvocation(&g_state.pending_start);
  ClearInvocation(&g_state.pending_stop);
  ClearInvocation(&g_state.pending_retry);
  if (g_state.health_timer != 0) {
    g_source_remove(g_state.health_timer);
  }
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
