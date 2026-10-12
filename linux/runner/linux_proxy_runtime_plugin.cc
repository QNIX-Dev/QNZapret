#include "linux_proxy_runtime_plugin.h"

#include <gio/gio.h>

#include <sstream>
#include <string>
#include <vector>

#include "../runtime/runtime_contract.h"

struct LinuxProxyRuntimePlugin {
  FlMethodChannel* method_channel = nullptr;
  FlEventChannel* event_channel = nullptr;
  GDBusConnection* runtime_connection = nullptr;
  GDBusConnection* telegram_connection = nullptr;
  GVariant* runtime_snapshot = nullptr;
  GVariant* telegram_snapshot = nullptr;
  GCancellable* cancellable = g_cancellable_new();
  guint runtime_signal_id = 0;
  guint telegram_signal_id = 0;
  int refs = 1;
  bool listening = false;
  bool disposed = false;
};
namespace {
FlValue* VariantToValue(GVariant* variant) {
  if (variant == nullptr) {
    return fl_value_new_null();
  }
  if (g_variant_is_of_type(variant, G_VARIANT_TYPE_VARIANT)) {
    g_autoptr(GVariant) inner = g_variant_get_variant(variant);
    return VariantToValue(inner);
  }
  if (g_variant_is_of_type(variant, G_VARIANT_TYPE_STRING) ||
      g_variant_is_of_type(variant, G_VARIANT_TYPE_OBJECT_PATH) ||
      g_variant_is_of_type(variant, G_VARIANT_TYPE_SIGNATURE)) {
    return fl_value_new_string(g_variant_get_string(variant, nullptr));
  }
  if (g_variant_is_of_type(variant, G_VARIANT_TYPE_BOOLEAN)) {
    return fl_value_new_bool(g_variant_get_boolean(variant));
  }
  if (g_variant_is_of_type(variant, G_VARIANT_TYPE_INT64)) {
    return fl_value_new_int(g_variant_get_int64(variant));
  }
  if (g_variant_is_of_type(variant, G_VARIANT_TYPE_UINT64)) {
    return fl_value_new_int(
        static_cast<std::int64_t>(g_variant_get_uint64(variant)));
  }
  if (g_variant_is_of_type(variant, G_VARIANT_TYPE_INT32)) {
    return fl_value_new_int(g_variant_get_int32(variant));
  }
  if (g_variant_is_of_type(variant, G_VARIANT_TYPE_UINT32)) {
    return fl_value_new_int(g_variant_get_uint32(variant));
  }
  if (g_variant_is_of_type(variant, G_VARIANT_TYPE_DOUBLE)) {
    return fl_value_new_float(g_variant_get_double(variant));
  }
  if (g_variant_is_of_type(variant, G_VARIANT_TYPE_VARDICT)) {
    FlValue* map = fl_value_new_map();
    GVariantIter iterator;
    const gchar* key = nullptr;
    GVariant* value = nullptr;
    g_variant_iter_init(&iterator, variant);
    while (g_variant_iter_next(&iterator, "{&sv}", &key, &value)) {
      fl_value_set_string_take(map, key, VariantToValue(value));
      g_variant_unref(value);
    }
    return map;
  }
  if (g_variant_is_container(variant)) {
    FlValue* list = fl_value_new_list();
    for (gsize index = 0; index < g_variant_n_children(variant); ++index) {
      g_autoptr(GVariant) child = g_variant_get_child_value(variant, index);
      fl_value_append_take(list, VariantToValue(child));
    }
    return list;
  }
  return fl_value_new_null();
}

std::string ErrorCode(GError* error, const char* fallback) {
  if (error == nullptr) {
    return fallback;
  }
  g_autofree gchar* remote_name = g_dbus_error_get_remote_error(error);
  if (remote_name == nullptr) {
    return fallback;
  }
  const std::string name = remote_name;
  const auto separator = name.find_last_of('.');
  return separator == std::string::npos ? name : name.substr(separator + 1);
}

std::string StringValue(FlValue* map, const char* key,
                        const std::string& fallback = "") {
  if (map == nullptr || fl_value_get_type(map) != FL_VALUE_TYPE_MAP) {
    return fallback;
  }
  FlValue* value = fl_value_lookup_string(map, key);
  if (value == nullptr || fl_value_get_type(value) != FL_VALUE_TYPE_STRING) {
    return fallback;
  }
  return fl_value_get_string(value);
}

bool BoolValue(FlValue* map, const char* key, bool fallback) {
  if (map == nullptr || fl_value_get_type(map) != FL_VALUE_TYPE_MAP) {
    return fallback;
  }
  FlValue* value = fl_value_lookup_string(map, key);
  return value != nullptr && fl_value_get_type(value) == FL_VALUE_TYPE_BOOL
             ? fl_value_get_bool(value)
             : fallback;
}

std::string JoinIntList(FlValue* list) {
  if (list == nullptr || fl_value_get_type(list) != FL_VALUE_TYPE_LIST) {
    return "";
  }
  std::ostringstream output;
  for (std::size_t index = 0; index < fl_value_get_length(list); ++index) {
    FlValue* value = fl_value_get_list_value(list, index);
    if (fl_value_get_type(value) != FL_VALUE_TYPE_INT) {
      continue;
    }
    if (output.tellp() > 0) {
      output << ',';
    }
    output << fl_value_get_int(value);
  }
  return output.str();
}

std::string JoinStringList(FlValue* list) {
  if (list == nullptr || fl_value_get_type(list) != FL_VALUE_TYPE_LIST) {
    return "";
  }
  std::ostringstream output;
  for (std::size_t index = 0; index < fl_value_get_length(list); ++index) {
    FlValue* value = fl_value_get_list_value(list, index);
    if (fl_value_get_type(value) != FL_VALUE_TYPE_STRING) {
      continue;
    }
    if (output.tellp() > 0) {
      output << ',';
    }
    output << fl_value_get_string(value);
  }
  return output.str();
}

bool SerializeProfile(FlValue* arguments, std::string* wire,
                      std::string* error) {
  if (arguments == nullptr ||
      fl_value_get_type(arguments) != FL_VALUE_TYPE_MAP) {
    *error = "Linux start ожидает map arguments.";
    return false;
  }
  FlValue* config = fl_value_lookup_string(arguments, "config");
  if (config == nullptr || fl_value_get_type(config) != FL_VALUE_TYPE_MAP) {
    *error = "Linux start не получил launch config.";
    return false;
  }
  FlValue* profile = fl_value_lookup_string(config, "strategyProfile");
  if (profile == nullptr || fl_value_get_type(profile) != FL_VALUE_TYPE_MAP) {
    *wire = qnzapret::DefaultStrategyProfileWire();
    return true;
  }
  FlValue* endpoint_policies =
      fl_value_lookup_string(profile, "endpointPolicies");
  if (endpoint_policies != nullptr &&
      fl_value_get_type(endpoint_policies) == FL_VALUE_TYPE_LIST &&
      fl_value_get_length(endpoint_policies) != 0) {
    *error = "Linux profile пока не принимает remote relay credentials.";
    return false;
  }

  std::ostringstream output;
  output << "profileId=" << StringValue(profile, "id", "default-lightweight")
         << '\n';
  output << "profileName="
         << StringValue(profile, "name", "Default lightweight") << '\n';
  output << "unmatched="
         << StringValue(profile, "unmatchedTrafficPolicy", "direct") << '\n';

  FlValue* blobs = fl_value_lookup_string(profile, "blobs");
  if (blobs != nullptr && fl_value_get_type(blobs) == FL_VALUE_TYPE_MAP) {
    for (std::size_t index = 0; index < fl_value_get_length(blobs); ++index) {
      FlValue* key = fl_value_get_map_key(blobs, index);
      FlValue* value = fl_value_get_map_value(blobs, index);
      if (fl_value_get_type(key) == FL_VALUE_TYPE_STRING &&
          fl_value_get_type(value) == FL_VALUE_TYPE_STRING) {
        output << "blob=" << fl_value_get_string(key) << '|'
               << fl_value_get_string(value) << '\n';
      }
    }
  }

  FlValue* rules = fl_value_lookup_string(profile, "rules");
  if (rules == nullptr || fl_value_get_type(rules) != FL_VALUE_TYPE_LIST) {
    *error = "Linux profile не содержит rules.";
    return false;
  }
  for (std::size_t index = 0; index < fl_value_get_length(rules); ++index) {
    FlValue* rule = fl_value_get_list_value(rules, index);
    if (fl_value_get_type(rule) != FL_VALUE_TYPE_MAP) {
      *error = "Linux profile содержит некорректный rule.";
      return false;
    }
    output << "rule=" << StringValue(rule, "id") << '|'
           << JoinIntList(fl_value_lookup_string(rule, "tcpPorts")) << '|'
           << JoinIntList(fl_value_lookup_string(rule, "udpPorts")) << '|'
           << JoinStringList(fl_value_lookup_string(rule, "protocols")) << '|'
           << JoinStringList(fl_value_lookup_string(rule, "hostlists")) << '|';
    FlValue* actions = fl_value_lookup_string(rule, "actions");
    if (actions == nullptr ||
        fl_value_get_type(actions) != FL_VALUE_TYPE_LIST) {
      *error = "Linux profile rule не содержит actions.";
      return false;
    }
    for (std::size_t action_index = 0;
         action_index < fl_value_get_length(actions); ++action_index) {
      FlValue* action = fl_value_get_list_value(actions, action_index);
      if (fl_value_get_type(action) != FL_VALUE_TYPE_MAP) {
        *error = "Linux profile содержит некорректный action.";
        return false;
      }
      if (action_index != 0) {
        output << ';';
      }
      output << StringValue(action, "kind") << ':'
             << StringValue(action, "blobKey") << ':';
      FlValue* position = fl_value_lookup_string(action, "position");
      if (position != nullptr &&
          fl_value_get_type(position) == FL_VALUE_TYPE_INT) {
        output << fl_value_get_int(position);
      }
      output << ':';
      FlValue* repeats = fl_value_lookup_string(action, "repeats");
      output << (repeats != nullptr &&
                         fl_value_get_type(repeats) == FL_VALUE_TYPE_INT
                     ? fl_value_get_int(repeats)
                     : 1);
    }
    output << '\n';
  }
  *wire = output.str();
  return true;
}

struct Request {
  LinuxProxyRuntimePlugin* plugin;
  FlMethodCall* call;
  std::string method;
  std::string profile;
  std::string error_code;
  std::string error_message;
  bool telegram_required = true;
  GAppInfo* telegram_app = nullptr;
  std::string setup_uri;
};

void UnrefPlugin(LinuxProxyRuntimePlugin* plugin) {
  if (--plugin->refs != 0) return;
  g_clear_pointer(&plugin->runtime_snapshot, g_variant_unref);
  g_clear_pointer(&plugin->telegram_snapshot, g_variant_unref);
  g_clear_object(&plugin->cancellable);
  g_clear_object(&plugin->method_channel);
  g_clear_object(&plugin->event_channel);
  delete plugin;
}

void CacheSnapshot(LinuxProxyRuntimePlugin* plugin, bool telegram,
                   GVariant* result) {
  auto** slot =
      telegram ? &plugin->telegram_snapshot : &plugin->runtime_snapshot;
  g_clear_pointer(slot, g_variant_unref);
  if (result != nullptr) *slot = g_variant_get_child_value(result, 0);
}

FlValue* MergedSnapshot(LinuxProxyRuntimePlugin* plugin) {
  if (plugin->runtime_snapshot == nullptr) return nullptr;
  FlValue* output = VariantToValue(plugin->runtime_snapshot);
  gboolean ready = false, setup = false;
  const gchar* state = "unavailable";
  const gchar* message = "Telegram sidecar недоступен.";
  const gchar* endpoint = "";
  if (plugin->telegram_snapshot != nullptr) {
    g_variant_lookup(plugin->telegram_snapshot, "ready", "b", &ready);
    g_variant_lookup(plugin->telegram_snapshot, "setupRequired", "b", &setup);
    g_variant_lookup(plugin->telegram_snapshot, "state", "&s", &state);
    g_variant_lookup(plugin->telegram_snapshot, "message", "&s", &message);
    g_variant_lookup(plugin->telegram_snapshot, "endpoint", "&s", &endpoint);
  }
  fl_value_set_string_take(output, "telegramCompatibilityProxyReady",
                           fl_value_new_bool(ready));
  fl_value_set_string_take(output, "telegramCompatibilitySetupRequired",
                           fl_value_new_bool(setup));
  fl_value_set_string_take(output, "telegramCompatibilityProxyEndpoint",
                           fl_value_new_string(endpoint));
  fl_value_set_string_take(output, "telegramCompatibilityProxyMessage",
                           fl_value_new_string(message));
  fl_value_set_string_take(output, "telegramSidecarState",
                           fl_value_new_string(state));
  const bool failed =
      std::string(state) == "failed" || std::string(state) == "unavailable";
  fl_value_set_string_take(output, "degraded", fl_value_new_bool(failed));
  if (failed) {
    fl_value_set_string_take(output, "partialFailureCode",
                             fl_value_new_string("linux_telegram_unavailable"));
    fl_value_set_string_take(output, "partialFailureMessage",
                             fl_value_new_string(message));
  }
  return output;
}

void SendEvent(LinuxProxyRuntimePlugin* plugin, const char* type,
               const char* field, FlValue* payload) {
  if (payload == nullptr) return;
  if (plugin->disposed || !plugin->listening) {
    fl_value_unref(payload);
    return;
  }
  g_autoptr(FlValue) event = fl_value_new_map();
  fl_value_set_string_take(event, "type", fl_value_new_string(type));
  fl_value_set_string_take(event, field, payload);
  fl_event_channel_send(plugin->event_channel, event, nullptr, nullptr);
}

void HandleDbusSignal(GDBusConnection*, const gchar*, const gchar*,
                      const gchar* interface, const gchar* name,
                      GVariant* parameters, gpointer data) {
  auto* plugin = static_cast<LinuxProxyRuntimePlugin*>(data);
  if (std::string(name) == "LogEvent") {
    g_autoptr(GVariant) log = g_variant_get_child_value(parameters, 0);
    SendEvent(plugin, "log", "log", VariantToValue(log));
  } else if (std::string(name) == "SnapshotChanged") {
    CacheSnapshot(plugin,
                  std::string(interface) == qnzapret::kTelegramInterface,
                  parameters);
    SendEvent(plugin, "snapshot", "snapshot", MergedSnapshot(plugin));
  }
}

// Все callback выполняются в GTK main context, ожидание D-Bus его не блокирует.
struct BusCall {
  LinuxProxyRuntimePlugin* plugin;
  bool telegram;
  std::string method;
  GVariant* arguments;
  GAsyncReadyCallback callback;
  gpointer data;
  int timeout;
};

void OnBusReady(GObject*, GAsyncResult* result, gpointer data) {
  auto* call = static_cast<BusCall*>(data);
  auto* plugin = call->plugin;
  g_autoptr(GError) error = nullptr;
  g_autoptr(GDBusConnection) connection = g_bus_get_finish(result, &error);
  if (connection == nullptr) {
    GTask* task = g_task_new(nullptr, nullptr, call->callback, call->data);
    g_task_return_error(task, g_steal_pointer(&error));
    g_object_unref(task);
  } else {
    auto** slot = call->telegram ? &plugin->telegram_connection
                                 : &plugin->runtime_connection;
    auto* signal_id = call->telegram ? &plugin->telegram_signal_id
                                     : &plugin->runtime_signal_id;
    if (*slot == nullptr && !plugin->disposed) {
      *slot = G_DBUS_CONNECTION(g_object_ref(connection));
      *signal_id = g_dbus_connection_signal_subscribe(
          connection,
          call->telegram ? qnzapret::kTelegramBusName
                         : qnzapret::kRuntimeBusName,
          call->telegram ? qnzapret::kTelegramInterface
                         : qnzapret::kRuntimeInterface,
          nullptr,
          call->telegram ? qnzapret::kTelegramObjectPath
                         : qnzapret::kRuntimeObjectPath,
          nullptr, G_DBUS_SIGNAL_FLAGS_NONE, HandleDbusSignal, plugin, nullptr);
    }
    g_dbus_connection_call(
        connection,
        call->telegram ? qnzapret::kTelegramBusName : qnzapret::kRuntimeBusName,
        call->telegram ? qnzapret::kTelegramObjectPath
                       : qnzapret::kRuntimeObjectPath,
        call->telegram ? qnzapret::kTelegramInterface
                       : qnzapret::kRuntimeInterface,
        call->method.c_str(), call->arguments, nullptr, G_DBUS_CALL_FLAGS_NONE,
        call->timeout, plugin->cancellable, call->callback, call->data);
  }
  g_clear_pointer(&call->arguments, g_variant_unref);
  UnrefPlugin(plugin);
  delete call;
}

void Call(LinuxProxyRuntimePlugin* plugin, bool telegram, const char* method,
          GVariant* arguments, int timeout, GAsyncReadyCallback callback,
          gpointer data) {
  ++plugin->refs;
  auto* call = new BusCall{
      plugin,   telegram,
      method,   arguments == nullptr ? nullptr : g_variant_ref_sink(arguments),
      callback, data,
      timeout};
  g_bus_get(telegram ? G_BUS_TYPE_SESSION : G_BUS_TYPE_SYSTEM,
            plugin->cancellable, OnBusReady, call);
}

GVariant* Finish(GObject* source, GAsyncResult* result, GError** error) {
  if (source == nullptr)
    return static_cast<GVariant*>(
        g_task_propagate_pointer(G_TASK(result), error));
  return g_dbus_connection_call_finish(G_DBUS_CONNECTION(source), result,
                                       error);
}

void Complete(Request* request, FlValue* value = nullptr) {
  if (!request->plugin->disposed && request->call != nullptr) {
    if (request->error_code.empty())
      fl_method_call_respond_success(request->call, value, nullptr);
    else
      fl_method_call_respond_error(request->call, request->error_code.c_str(),
                                   request->error_message.c_str(), nullptr,
                                   nullptr);
  }
  if (value != nullptr) fl_value_unref(value);
  g_clear_object(&request->call);
  g_clear_object(&request->telegram_app);
  UnrefPlugin(request->plugin);
  delete request;
}

void CaptureError(Request* request, GError* error, const char* code) {
  if (!request->error_code.empty()) return;
  request->error_code = ErrorCode(error, code);
  request->error_message =
      error ? error->message : "Команда Linux runtime не выполнена.";
}

void OnTelegramSnapshot(GObject* source, GAsyncResult* result, gpointer data) {
  auto* request = static_cast<Request*>(data);
  g_autoptr(GError) error = nullptr;
  g_autoptr(GVariant) value = Finish(source, result, &error);
  CacheSnapshot(request->plugin, true, value);
  FlValue* snapshot = MergedSnapshot(request->plugin);
  if (request->call == nullptr)
    SendEvent(request->plugin, "snapshot", "snapshot", snapshot);
  Complete(request, request->call == nullptr ? nullptr : snapshot);
}

void OnRuntimeSnapshot(GObject* source, GAsyncResult* result, gpointer data) {
  auto* request = static_cast<Request*>(data);
  g_autoptr(GError) error = nullptr;
  g_autoptr(GVariant) value = Finish(source, result, &error);
  if (value == nullptr) {
    CaptureError(request, error, "linux_backend_unavailable");
    Complete(request);
    return;
  }
  CacheSnapshot(request->plugin, false, value);
  Call(request->plugin, true, "GetSnapshot", nullptr, 5000, OnTelegramSnapshot,
       request);
}

void OnTelegramPrepared(GObject* source, GAsyncResult* result, gpointer data) {
  auto* request = static_cast<Request*>(data);
  g_autoptr(GError) error = nullptr;
  g_autoptr(GVariant) value = Finish(source, result, &error);
  gboolean granted = false;
  const gchar* message = "Telegram sidecar недоступен.";
  if (value != nullptr) {
    g_autoptr(GVariant) dictionary = g_variant_get_child_value(value, 0);
    g_variant_lookup(dictionary, "granted", "b", &granted);
    g_variant_lookup(dictionary, "message", "&s", &message);
    if (!granted) request->error_message = message;
  } else
    request->error_message = message;
  FlValue* response = fl_value_new_map();
  fl_value_set_string_take(
      response, "granted",
      fl_value_new_bool(granted && request->error_message.empty()));
  fl_value_set_string_take(
      response, "message",
      fl_value_new_string(request->error_message.empty()
                              ? "Сервисы готовы к запуску."
                              : request->error_message.c_str()));
  Complete(request, response);
}

void OnRuntimePrepared(GObject* source, GAsyncResult* result, gpointer data) {
  auto* request = static_cast<Request*>(data);
  g_autoptr(GError) error = nullptr;
  g_autoptr(GVariant) value = Finish(source, result, &error);
  if (value == nullptr) {
    CaptureError(request, error, "linux_backend_unavailable");
    Complete(request);
    return;
  }
  g_autoptr(GVariant) dictionary = g_variant_get_child_value(value, 0);
  gboolean granted = false;
  const gchar* message = "Linux runtime не готов.";
  g_variant_lookup(dictionary, "granted", "b", &granted);
  g_variant_lookup(dictionary, "message", "&s", &message);
  if (!granted) request->error_message = message;
  Call(request->plugin, true, "Prepare", nullptr, 10000, OnTelegramPrepared,
       request);
}

void OnRollback(GObject* source, GAsyncResult* result, gpointer data) {
  auto* request = static_cast<Request*>(data);
  g_autoptr(GError) error = nullptr;
  g_autoptr(GVariant) value = Finish(source, result, &error);
  if (value == nullptr)
    request->error_message +=
        " Не удалось откатить системный сервис: " + std::string(error->message);
  Complete(request);
}

void FinishTelegramSetup(Request* request, bool opened) {
  if (!opened) {
    const char* message =
        "Не удалось открыть настройку Telegram. Установите Telegram Desktop и "
        "повторите «Настроить Telegram».";
    if (request->method == "setupTelegram") {
      request->error_code = "linux_telegram_setup_failed";
      request->error_message = message;
    } else {
      FlValue* log = fl_value_new_map();
      fl_value_set_string_take(log, "timestampMillis",
                               fl_value_new_int(g_get_real_time() / 1000));
      fl_value_set_string_take(log, "level", fl_value_new_string("warning"));
      fl_value_set_string_take(log, "source",
                               fl_value_new_string("telegram-setup"));
      fl_value_set_string_take(
          log, "code", fl_value_new_string("linux_telegram_setup_failed"));
      fl_value_set_string_take(log, "message", fl_value_new_string(message));
      SendEvent(request->plugin, "log", "log", log);
    }
  }
  Complete(request);
}

void OnTelegramLaunched(GObject* source, GAsyncResult* result, gpointer data) {
  g_autoptr(GError) error = nullptr;
  const bool opened =
      g_app_info_launch_uris_finish(G_APP_INFO(source), result, &error);
  // GError может содержать URI с secret: наружу передаётся только безопасный
  // текст.
  FinishTelegramSetup(static_cast<Request*>(data), opened);
}

void LaunchTelegramFallback(Request* request) {
  GList* uris =
      g_list_append(nullptr, const_cast<char*>(request->setup_uri.c_str()));
  g_app_info_launch_uris_async(request->telegram_app, uris, nullptr,
                               request->plugin->cancellable, OnTelegramLaunched,
                               request);
  g_list_free(uris);
}

void OnTelegramOpened(GObject* source, GAsyncResult* result, gpointer data) {
  auto* request = static_cast<Request*>(data);
  g_autoptr(GError) error = nullptr;
  g_autoptr(GVariant) response = Finish(source, result, &error);
  if (response != nullptr)
    FinishTelegramSetup(request, true);
  else
    LaunchTelegramFallback(request);
}

void OnTelegramSetupUri(GObject* source, GAsyncResult* result, gpointer data) {
  auto* request = static_cast<Request*>(data);
  g_autoptr(GError) error = nullptr;
  g_autoptr(GVariant) response = Finish(source, result, &error);
  if (response == nullptr) {
    FinishTelegramSetup(request, false);
    return;
  }
  const gchar* uri = nullptr;
  g_variant_get(response, "(&s)", &uri);
  if (uri == nullptr ||
      !g_str_has_prefix(uri,
                        "tg://proxy?server=127.0.0.1&port=1443&secret=dd")) {
    FinishTelegramSetup(request, false);
    return;
  }
  request->setup_uri = uri;
  request->telegram_app = g_app_info_get_default_for_uri_scheme("tg");
  if (request->telegram_app == nullptr) {
    FinishTelegramSetup(request, false);
    return;
  }
  const char* id = g_app_info_get_id(request->telegram_app);
  std::string name = id == nullptr ? "" : id;
  if (g_str_has_suffix(name.c_str(), ".desktop")) name.resize(name.size() - 8);
  if (!g_dbus_is_name(name.c_str()) || name.empty() || name.front() == ':') {
    LaunchTelegramFallback(request);
    return;
  }
  std::string path = "/";
  for (const char ch : name) path += ch == '.' ? '/' : ch == '-' ? '_' : ch;
  if (!g_variant_is_object_path(path.c_str())) {
    LaunchTelegramFallback(request);
    return;
  }
  const gchar* uris[] = {request->setup_uri.c_str(), nullptr};
  g_dbus_connection_call(
      G_DBUS_CONNECTION(source), name.c_str(), path.c_str(),
      "org.freedesktop.Application", "Open",
      g_variant_new("(^as@a{sv})", uris,
                    g_variant_new_array(G_VARIANT_TYPE("{sv}"), nullptr, 0)),
      nullptr, G_DBUS_CALL_FLAGS_NONE, 5000, request->plugin->cancellable,
      OnTelegramOpened, request);
}

void SetupTelegram(Request* request) {
  Call(request->plugin, true, "GetSetupUri", nullptr, 5000, OnTelegramSetupUri,
       request);
}

void OnTelegramStarted(GObject* source, GAsyncResult* result, gpointer data) {
  auto* request = static_cast<Request*>(data);
  g_autoptr(GError) error = nullptr;
  g_autoptr(GVariant) value = Finish(source, result, &error);
  if (value == nullptr) {
    CaptureError(request, error, "linux_telegram_start_failed");
    Call(request->plugin, false, "Stop", nullptr, 150000, OnRollback, request);
  } else {
    SetupTelegram(request);
  }
}

void OnRuntimeStarted(GObject* source, GAsyncResult* result, gpointer data) {
  auto* request = static_cast<Request*>(data);
  g_autoptr(GError) error = nullptr;
  g_autoptr(GVariant) value = Finish(source, result, &error);
  if (value == nullptr) {
    CaptureError(request, error, "linux_nfqws_start_failed");
    Complete(request);
    return;
  }
  if (!request->telegram_required) {
    Complete(request);
    return;
  }
  Call(request->plugin, true, "Start", nullptr, 15000, OnTelegramStarted,
       request);
}

void OnTelegramStopped(GObject* source, GAsyncResult* result, gpointer data) {
  auto* request = static_cast<Request*>(data);
  g_autoptr(GError) error = nullptr;
  g_autoptr(GVariant) value = Finish(source, result, &error);
  if (value == nullptr)
    CaptureError(request, error, "linux_telegram_stop_failed");
  Complete(request);
}

void OnRuntimeStopped(GObject* source, GAsyncResult* result, gpointer data) {
  auto* request = static_cast<Request*>(data);
  g_autoptr(GError) error = nullptr;
  g_autoptr(GVariant) value = Finish(source, result, &error);
  if (value == nullptr) {
    // Отказ в авторизации не должен выключать только половину сервисов.
    CaptureError(request, error, "linux_stop_failed");
    Complete(request);
    return;
  }
  Call(request->plugin, true, "Stop", nullptr, 15000, OnTelegramStopped,
       request);
}

Request* NewRequest(LinuxProxyRuntimePlugin* plugin, FlMethodCall* call) {
  ++plugin->refs;
  return new Request{
      plugin, call ? FL_METHOD_CALL(g_object_ref(call)) : nullptr,
      "",     "",
      "",     "",
      true};
}

void HandleMethodCall(FlMethodChannel*, FlMethodCall* call, gpointer data) {
  auto* plugin = static_cast<LinuxProxyRuntimePlugin*>(data);
  const std::string method = fl_method_call_get_name(call);
  auto* request = NewRequest(plugin, call);
  request->method = method;
  if (method == "getSnapshot")
    Call(plugin, false, "GetSnapshot", nullptr, 5000, OnRuntimeSnapshot,
         request);
  else if (method == "prepare")
    Call(plugin, false, "Prepare", nullptr, 10000, OnRuntimePrepared, request);
  else if (method == "start") {
    if (!SerializeProfile(fl_method_call_get_args(call), &request->profile,
                          &request->error_message)) {
      request->error_code = "linux_profile_invalid";
      Complete(request);
      return;
    }
    request->telegram_required = BoolValue(
        fl_value_lookup_string(fl_method_call_get_args(call), "config"),
        "cloudflareEnabled", true);
    Call(plugin, false, "Start", g_variant_new("(s)", request->profile.c_str()),
         150000, OnRuntimeStarted, request);
  } else if (method == "setupTelegram") {
    SetupTelegram(request);
  } else if (method == "stop")
    Call(plugin, false, "Stop", nullptr, 150000, OnRuntimeStopped, request);
  else {
    fl_method_call_respond_not_implemented(call, nullptr);
    g_clear_object(&request->call);
    Complete(request);
  }
}

FlMethodErrorResponse* ListenHandler(FlEventChannel*, FlValue*, gpointer data) {
  auto* plugin = static_cast<LinuxProxyRuntimePlugin*>(data);
  plugin->listening = true;
  Call(plugin, false, "GetSnapshot", nullptr, 5000, OnRuntimeSnapshot,
       NewRequest(plugin, nullptr));
  return nullptr;
}
FlMethodErrorResponse* CancelHandler(FlEventChannel*, FlValue*, gpointer data) {
  static_cast<LinuxProxyRuntimePlugin*>(data)->listening = false;
  return nullptr;
}
}  // namespace

LinuxProxyRuntimePlugin* linux_proxy_runtime_plugin_new(FlView* view) {
  auto* plugin = new LinuxProxyRuntimePlugin();
  auto* messenger = fl_engine_get_binary_messenger(fl_view_get_engine(view));
  g_autoptr(FlStandardMethodCodec) codec = fl_standard_method_codec_new();
  plugin->method_channel = fl_method_channel_new(
      messenger, "dev.qnzapret/proxy_runtime", FL_METHOD_CODEC(codec));
  fl_method_channel_set_method_call_handler(plugin->method_channel,
                                            HandleMethodCall, plugin, nullptr);
  plugin->event_channel = fl_event_channel_new(
      messenger, "dev.qnzapret/proxy_runtime/events", FL_METHOD_CODEC(codec));
  fl_event_channel_set_stream_handlers(plugin->event_channel, ListenHandler,
                                       CancelHandler, plugin, nullptr);
  return plugin;
}

void linux_proxy_runtime_plugin_free(LinuxProxyRuntimePlugin* plugin) {
  if (plugin == nullptr) return;
  plugin->disposed = true;
  g_cancellable_cancel(plugin->cancellable);
  if (plugin->runtime_signal_id)
    g_dbus_connection_signal_unsubscribe(plugin->runtime_connection,
                                         plugin->runtime_signal_id);
  if (plugin->telegram_signal_id)
    g_dbus_connection_signal_unsubscribe(plugin->telegram_connection,
                                         plugin->telegram_signal_id);
  g_clear_object(&plugin->runtime_connection);
  g_clear_object(&plugin->telegram_connection);
  fl_method_channel_set_method_call_handler(plugin->method_channel, nullptr,
                                            nullptr, nullptr);
  fl_event_channel_set_stream_handlers(plugin->event_channel, nullptr, nullptr,
                                       nullptr, nullptr);
  UnrefPlugin(plugin);
}
