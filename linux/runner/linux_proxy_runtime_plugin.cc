#include "linux_proxy_runtime_plugin.h"

#include <gio/gio.h>

#include <algorithm>
#include <sstream>
#include <string>
#include <vector>

#include "../runtime/runtime_contract.h"

struct LinuxProxyRuntimePlugin {
  FlMethodChannel* method_channel = nullptr;
  FlEventChannel* event_channel = nullptr;
  GDBusProxy* runtime_proxy = nullptr;
  GDBusProxy* telegram_proxy = nullptr;
  guint runtime_signal_id = 0;
  guint telegram_signal_id = 0;
  bool listening = false;
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

void RespondError(FlMethodCall* call,
                  GError* error,
                  const char* fallback_code) {
  const auto code = ErrorCode(error, fallback_code);
  const auto message =
      error == nullptr ? "Linux runtime command failed." : error->message;
  fl_method_call_respond_error(call, code.c_str(), message, nullptr, nullptr);
}

GDBusProxy* EnsureProxy(LinuxProxyRuntimePlugin* plugin,
                        bool telegram,
                        GError** error) {
  GDBusProxy** slot =
      telegram ? &plugin->telegram_proxy : &plugin->runtime_proxy;
  if (*slot != nullptr) {
    return *slot;
  }
  *slot = g_dbus_proxy_new_for_bus_sync(
      telegram ? G_BUS_TYPE_SESSION : G_BUS_TYPE_SYSTEM,
      G_DBUS_PROXY_FLAGS_NONE, nullptr,
      telegram ? qnzapret::kTelegramBusName : qnzapret::kRuntimeBusName,
      telegram ? qnzapret::kTelegramObjectPath
               : qnzapret::kRuntimeObjectPath,
      telegram ? qnzapret::kTelegramInterface
               : qnzapret::kRuntimeInterface,
      nullptr, error);
  return *slot;
}

GVariant* CallProxy(LinuxProxyRuntimePlugin* plugin,
                    bool telegram,
                    const char* method,
                    GVariant* arguments,
                    int timeout,
                    GError** error) {
  GDBusProxy* proxy = EnsureProxy(plugin, telegram, error);
  if (proxy == nullptr) {
    return nullptr;
  }
  return g_dbus_proxy_call_sync(proxy, method, arguments,
                                G_DBUS_CALL_FLAGS_NONE, timeout, nullptr,
                                error);
}

std::string StringValue(FlValue* map,
                        const char* key,
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

bool SerializeProfile(FlValue* arguments,
                      std::string* wire,
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
  output << "profileId="
         << StringValue(profile, "id", "default-lightweight") << '\n';
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

GVariant* ExtractDictionary(GVariant* result) {
  if (result == nullptr || g_variant_n_children(result) == 0) {
    return nullptr;
  }
  return g_variant_get_child_value(result, 0);
}

FlValue* MergedSnapshot(LinuxProxyRuntimePlugin* plugin, GError** error) {
  g_autoptr(GVariant) runtime_result =
      CallProxy(plugin, false, "GetSnapshot", nullptr, 5000, error);
  if (runtime_result == nullptr) {
    return nullptr;
  }
  g_autoptr(GVariant) runtime_snapshot =
      ExtractDictionary(runtime_result);
  FlValue* output = VariantToValue(runtime_snapshot);

  g_autoptr(GError) telegram_error = nullptr;
  g_autoptr(GVariant) telegram_result =
      CallProxy(plugin, true, "GetSnapshot", nullptr, 5000,
                &telegram_error);
  if (telegram_result == nullptr) {
    fl_value_set_string_take(
        output, "telegramCompatibilityProxyReady", fl_value_new_bool(false));
    fl_value_set_string_take(
        output, "telegramCompatibilitySetupRequired",
        fl_value_new_bool(false));
    fl_value_set_string_take(
        output, "telegramCompatibilityProxyMessage",
        fl_value_new_string("Telegram sidecar недоступен."));
    fl_value_set_string_take(output, "telegramSidecarState",
                             fl_value_new_string("unavailable"));
    fl_value_set_string_take(output, "degraded", fl_value_new_bool(true));
    fl_value_set_string_take(
        output, "partialFailureCode",
        fl_value_new_string("linux_telegram_unavailable"));
    fl_value_set_string_take(
        output, "partialFailureMessage",
        fl_value_new_string("Telegram sidecar недоступен."));
    return output;
  }
  g_autoptr(GVariant) telegram_snapshot =
      ExtractDictionary(telegram_result);
  gboolean listener_ready = false;
  gboolean setup_required = false;
  const gchar* state = "idle";
  const gchar* endpoint = "";
  const gchar* message = "";
  g_variant_lookup(telegram_snapshot, "listenerReady", "b",
                   &listener_ready);
  g_variant_lookup(telegram_snapshot, "setupRequired", "b", &setup_required);
  g_variant_lookup(telegram_snapshot, "state", "&s", &state);
  g_variant_lookup(telegram_snapshot, "endpoint", "&s", &endpoint);
  g_variant_lookup(telegram_snapshot, "message", "&s", &message);
  fl_value_set_string_take(output, "telegramCompatibilityProxyReady",
                           fl_value_new_bool(listener_ready));
  fl_value_set_string_take(output, "telegramCompatibilitySetupRequired",
                           fl_value_new_bool(setup_required));
  fl_value_set_string_take(output, "telegramCompatibilityProxyEndpoint",
                           fl_value_new_string(endpoint));
  fl_value_set_string_take(output, "telegramCompatibilityProxyMessage",
                           fl_value_new_string(message));
  fl_value_set_string_take(output, "telegramSidecarState",
                           fl_value_new_string(state));
  const bool failed = std::string(state) == "failed";
  fl_value_set_string_take(output, "degraded", fl_value_new_bool(failed));
  if (failed) {
    fl_value_set_string_take(
        output, "partialFailureCode",
        fl_value_new_string("linux_telegram_sidecar_failed"));
    fl_value_set_string_take(output, "partialFailureMessage",
                             fl_value_new_string(message));
  }
  return output;
}

void SendEvent(LinuxProxyRuntimePlugin* plugin,
               const char* type,
               const char* field,
               FlValue* payload) {
  if (!plugin->listening) {
    fl_value_unref(payload);
    return;
  }
  g_autoptr(FlValue) event = fl_value_new_map();
  fl_value_set_string_take(event, "type", fl_value_new_string(type));
  fl_value_set_string_take(event, field, payload);
  fl_event_channel_send(plugin->event_channel, event, nullptr, nullptr);
}

void HandleDbusSignal(GDBusConnection*,
                      const gchar*,
                      const gchar*,
                      const gchar*,
                      const gchar* signal_name,
                      GVariant* parameters,
                      gpointer user_data) {
  auto* plugin = static_cast<LinuxProxyRuntimePlugin*>(user_data);
  if (std::string(signal_name) == "LogEvent") {
    g_autoptr(GVariant) log = ExtractDictionary(parameters);
    SendEvent(plugin, "log", "log", VariantToValue(log));
    return;
  }
  g_autoptr(GError) error = nullptr;
  FlValue* snapshot = MergedSnapshot(plugin, &error);
  if (snapshot != nullptr) {
    SendEvent(plugin, "snapshot", "snapshot", snapshot);
  }
}

void SubscribeSignals(LinuxProxyRuntimePlugin* plugin) {
  g_autoptr(GError) error = nullptr;
  GDBusProxy* runtime = EnsureProxy(plugin, false, &error);
  if (runtime != nullptr && plugin->runtime_signal_id == 0) {
    plugin->runtime_signal_id = g_dbus_connection_signal_subscribe(
        g_dbus_proxy_get_connection(runtime), qnzapret::kRuntimeBusName,
        qnzapret::kRuntimeInterface, nullptr, qnzapret::kRuntimeObjectPath,
        nullptr, G_DBUS_SIGNAL_FLAGS_NONE, HandleDbusSignal, plugin, nullptr);
  }
  g_clear_error(&error);
  GDBusProxy* telegram = EnsureProxy(plugin, true, &error);
  if (telegram != nullptr && plugin->telegram_signal_id == 0) {
    plugin->telegram_signal_id = g_dbus_connection_signal_subscribe(
        g_dbus_proxy_get_connection(telegram), qnzapret::kTelegramBusName,
        qnzapret::kTelegramInterface, nullptr,
        qnzapret::kTelegramObjectPath, nullptr, G_DBUS_SIGNAL_FLAGS_NONE,
        HandleDbusSignal, plugin, nullptr);
  }
}

FlMethodErrorResponse* ListenHandler(FlEventChannel*,
                                     FlValue*,
                                     gpointer user_data) {
  auto* plugin = static_cast<LinuxProxyRuntimePlugin*>(user_data);
  plugin->listening = true;
  SubscribeSignals(plugin);
  g_autoptr(GError) error = nullptr;
  FlValue* snapshot = MergedSnapshot(plugin, &error);
  if (snapshot != nullptr) {
    SendEvent(plugin, "snapshot", "snapshot", snapshot);
  }
  return nullptr;
}

FlMethodErrorResponse* CancelHandler(FlEventChannel*,
                                     FlValue*,
                                     gpointer user_data) {
  static_cast<LinuxProxyRuntimePlugin*>(user_data)->listening = false;
  return nullptr;
}

struct AsyncMethodContext {
  FlMethodCall* call = nullptr;
  GDBusProxy* runtime = nullptr;
  GDBusProxy* telegram = nullptr;
  std::string profile;
  std::string error_code;
  std::string error_message;
};

AsyncMethodContext* NewAsyncContext(FlMethodCall* call,
                                    GDBusProxy* runtime,
                                    GDBusProxy* telegram) {
  auto* context = new AsyncMethodContext();
  context->call = FL_METHOD_CALL(g_object_ref(call));
  context->runtime =
      runtime == nullptr ? nullptr : G_DBUS_PROXY(g_object_ref(runtime));
  context->telegram =
      telegram == nullptr ? nullptr : G_DBUS_PROXY(g_object_ref(telegram));
  return context;
}

void DeleteAsyncContext(AsyncMethodContext* context) {
  g_clear_object(&context->call);
  g_clear_object(&context->runtime);
  g_clear_object(&context->telegram);
  delete context;
}

void CaptureAsyncError(AsyncMethodContext* context,
                       GError* error,
                       const char* fallback_code) {
  if (!context->error_code.empty()) {
    return;
  }
  context->error_code = ErrorCode(error, fallback_code);
  context->error_message =
      error == nullptr ? "Linux runtime command failed." : error->message;
}

void RespondAsync(AsyncMethodContext* context) {
  if (context->error_code.empty()) {
    fl_method_call_respond_success(context->call, nullptr, nullptr);
  } else {
    fl_method_call_respond_error(
        context->call, context->error_code.c_str(),
        context->error_message.empty() ? "Linux runtime command failed."
                                       : context->error_message.c_str(),
        nullptr, nullptr);
  }
  DeleteAsyncContext(context);
}

void OnStartRollback(GObject* source, GAsyncResult* result, gpointer data) {
  auto* context = static_cast<AsyncMethodContext*>(data);
  g_autoptr(GError) rollback_error = nullptr;
  g_autoptr(GVariant) response =
      g_dbus_proxy_call_finish(G_DBUS_PROXY(source), result, &rollback_error);
  (void)response;
  RespondAsync(context);
}

void OnRuntimeStarted(GObject* source, GAsyncResult* result, gpointer data) {
  auto* context = static_cast<AsyncMethodContext*>(data);
  g_autoptr(GError) error = nullptr;
  g_autoptr(GVariant) response =
      g_dbus_proxy_call_finish(G_DBUS_PROXY(source), result, &error);
  if (response == nullptr) {
    CaptureAsyncError(context, error, "linux_nfqws_start_failed");
    if (context->telegram != nullptr) {
      g_dbus_proxy_call(context->telegram, "Stop", nullptr,
                        G_DBUS_CALL_FLAGS_NONE, 15000, nullptr,
                        OnStartRollback, context);
      return;
    }
    RespondAsync(context);
    return;
  }
  RespondAsync(context);
}

void StartSystemRuntime(AsyncMethodContext* context) {
  g_dbus_proxy_call(context->runtime, "Start",
                    g_variant_new("(s)", context->profile.c_str()),
                    G_DBUS_CALL_FLAGS_NONE, 120000, nullptr,
                    OnRuntimeStarted, context);
}

void OnTelegramStarted(GObject* source, GAsyncResult* result, gpointer data) {
  auto* context = static_cast<AsyncMethodContext*>(data);
  g_autoptr(GError) error = nullptr;
  g_autoptr(GVariant) response =
      g_dbus_proxy_call_finish(G_DBUS_PROXY(source), result, &error);
  if (response == nullptr) {
    CaptureAsyncError(context, error, "linux_telegram_start_failed");
    RespondAsync(context);
    return;
  }
  StartSystemRuntime(context);
}

void OnTelegramStopped(GObject* source, GAsyncResult* result, gpointer data) {
  auto* context = static_cast<AsyncMethodContext*>(data);
  g_autoptr(GError) error = nullptr;
  g_autoptr(GVariant) response =
      g_dbus_proxy_call_finish(G_DBUS_PROXY(source), result, &error);
  if (response == nullptr) {
    CaptureAsyncError(context, error, "linux_telegram_stop_failed");
  }
  RespondAsync(context);
}

void StopTelegram(AsyncMethodContext* context) {
  if (context->telegram == nullptr) {
    RespondAsync(context);
    return;
  }
  g_dbus_proxy_call(context->telegram, "Stop", nullptr,
                    G_DBUS_CALL_FLAGS_NONE, 15000, nullptr,
                    OnTelegramStopped, context);
}

void OnRuntimeStopped(GObject* source, GAsyncResult* result, gpointer data) {
  auto* context = static_cast<AsyncMethodContext*>(data);
  g_autoptr(GError) error = nullptr;
  g_autoptr(GVariant) response =
      g_dbus_proxy_call_finish(G_DBUS_PROXY(source), result, &error);
  if (response == nullptr) {
    CaptureAsyncError(context, error, "linux_stop_failed");
  }
  StopTelegram(context);
}

void HandleMethodCall(FlMethodChannel*,
                      FlMethodCall* call,
                      gpointer user_data) {
  auto* plugin = static_cast<LinuxProxyRuntimePlugin*>(user_data);
  const std::string method = fl_method_call_get_name(call);
  if (method == "getSnapshot") {
    g_autoptr(GError) error = nullptr;
    g_autoptr(FlValue) snapshot = MergedSnapshot(plugin, &error);
    if (snapshot == nullptr) {
      RespondError(call, error, "linux_backend_unavailable");
      return;
    }
    fl_method_call_respond_success(call, snapshot, nullptr);
    return;
  }
  if (method == "prepare") {
    g_autoptr(GError) error = nullptr;
    g_autoptr(GVariant) runtime =
        CallProxy(plugin, false, "Prepare", nullptr, 10000, &error);
    if (runtime == nullptr) {
      RespondError(call, error, "linux_backend_unavailable");
      return;
    }
    g_autoptr(GVariant) result = ExtractDictionary(runtime);
    gboolean granted = false;
    const gchar* message = "";
    g_variant_lookup(result, "granted", "b", &granted);
    g_variant_lookup(result, "message", "&s", &message);

    g_autoptr(GError) telegram_error = nullptr;
    g_autoptr(GVariant) telegram =
        CallProxy(plugin, true, "Prepare", nullptr, 10000, &telegram_error);
    if (telegram == nullptr) {
      granted = false;
      message = "Telegram sidecar недоступен.";
    } else {
      gboolean telegram_granted = false;
      const gchar* telegram_message = "";
      g_autoptr(GVariant) telegram_result = ExtractDictionary(telegram);
      g_variant_lookup(telegram_result, "granted", "b", &telegram_granted);
      g_variant_lookup(telegram_result, "message", "&s", &telegram_message);
      if (!telegram_granted) {
        granted = false;
        message = telegram_message;
      }
    }
    g_autoptr(FlValue) response = fl_value_new_map();
    fl_value_set_string_take(response, "granted",
                             fl_value_new_bool(granted));
    fl_value_set_string_take(response, "message",
                             fl_value_new_string(message));
    fl_method_call_respond_success(call, response, nullptr);
    return;
  }
  if (method == "start") {
    std::string profile;
    std::string serialization_error;
    if (!SerializeProfile(fl_method_call_get_args(call), &profile,
                          &serialization_error)) {
      fl_method_call_respond_error(call, "linux_profile_invalid",
                                   serialization_error.c_str(), nullptr,
                                   nullptr);
      return;
    }
    FlValue* config = fl_value_lookup_string(fl_method_call_get_args(call),
                                             "config");
    const bool telegram_required =
        BoolValue(config, "cloudflareEnabled", true);
    g_autoptr(GError) runtime_error = nullptr;
    GDBusProxy* runtime = EnsureProxy(plugin, false, &runtime_error);
    if (runtime == nullptr) {
      RespondError(call, runtime_error, "linux_backend_unavailable");
      return;
    }
    g_autoptr(GError) telegram_error = nullptr;
    GDBusProxy* telegram = telegram_required
                               ? EnsureProxy(plugin, true, &telegram_error)
                               : nullptr;
    if (telegram_required && telegram == nullptr) {
      RespondError(call, telegram_error, "linux_telegram_start_failed");
      return;
    }
    auto* context = NewAsyncContext(call, runtime, telegram);
    context->profile = profile;
    if (telegram == nullptr) {
      StartSystemRuntime(context);
    } else {
      g_dbus_proxy_call(telegram, "Start", nullptr, G_DBUS_CALL_FLAGS_NONE,
                        15000, nullptr, OnTelegramStarted, context);
    }
    return;
  }
  if (method == "stop") {
    g_autoptr(GError) runtime_error = nullptr;
    GDBusProxy* runtime = EnsureProxy(plugin, false, &runtime_error);
    if (runtime == nullptr) {
      RespondError(call, runtime_error, "linux_backend_unavailable");
      return;
    }
    g_autoptr(GError) telegram_error = nullptr;
    GDBusProxy* telegram = EnsureProxy(plugin, true, &telegram_error);
    auto* context = NewAsyncContext(call, runtime, telegram);
    if (telegram == nullptr) {
      CaptureAsyncError(context, telegram_error, "linux_telegram_stop_failed");
    }
    g_dbus_proxy_call(runtime, "Stop", nullptr, G_DBUS_CALL_FLAGS_NONE,
                      120000, nullptr, OnRuntimeStopped, context);
    return;
  }
  fl_method_call_respond_not_implemented(call, nullptr);
}

}  // namespace

LinuxProxyRuntimePlugin* linux_proxy_runtime_plugin_new(FlView* view) {
  auto* plugin = new LinuxProxyRuntimePlugin();
  FlBinaryMessenger* messenger = fl_engine_get_binary_messenger(
      fl_view_get_engine(view));
  g_autoptr(FlStandardMethodCodec) codec = fl_standard_method_codec_new();
  plugin->method_channel = fl_method_channel_new(
      messenger, "dev.qnzapret/proxy_runtime", FL_METHOD_CODEC(codec));
  fl_method_channel_set_method_call_handler(
      plugin->method_channel, HandleMethodCall, plugin, nullptr);
  plugin->event_channel = fl_event_channel_new(
      messenger, "dev.qnzapret/proxy_runtime/events",
      FL_METHOD_CODEC(codec));
  fl_event_channel_set_stream_handlers(
      plugin->event_channel, ListenHandler, CancelHandler, plugin, nullptr);
  return plugin;
}

void linux_proxy_runtime_plugin_free(LinuxProxyRuntimePlugin* plugin) {
  if (plugin == nullptr) {
    return;
  }
  if (plugin->runtime_signal_id != 0 && plugin->runtime_proxy != nullptr) {
    g_dbus_connection_signal_unsubscribe(
        g_dbus_proxy_get_connection(plugin->runtime_proxy),
        plugin->runtime_signal_id);
  }
  if (plugin->telegram_signal_id != 0 && plugin->telegram_proxy != nullptr) {
    g_dbus_connection_signal_unsubscribe(
        g_dbus_proxy_get_connection(plugin->telegram_proxy),
        plugin->telegram_signal_id);
  }
  g_clear_object(&plugin->runtime_proxy);
  g_clear_object(&plugin->telegram_proxy);
  g_clear_object(&plugin->method_channel);
  g_clear_object(&plugin->event_channel);
  delete plugin;
}
