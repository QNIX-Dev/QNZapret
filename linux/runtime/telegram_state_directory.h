#ifndef QNZAPRET_LINUX_RUNTIME_TELEGRAM_STATE_DIRECTORY_H_
#define QNZAPRET_LINUX_RUNTIME_TELEGRAM_STATE_DIRECTORY_H_

#include <glib.h>

#include <string>

namespace qnzapret {

inline std::string TelegramStateDirectory() {
  const gchar* systemd_state = g_getenv("STATE_DIRECTORY");
  if (systemd_state != nullptr && g_path_is_absolute(systemd_state)) {
    const std::string directories(systemd_state);
    return directories.substr(0, directories.find(':'));
  }
  const gchar* xdg_state = g_getenv("XDG_STATE_HOME");
  if (xdg_state != nullptr && g_path_is_absolute(xdg_state)) {
    return std::string(xdg_state) + "/qnzapret";
  }
  return std::string(g_get_home_dir()) + "/.local/state/qnzapret";
}

}  // namespace qnzapret

#endif  // QNZAPRET_LINUX_RUNTIME_TELEGRAM_STATE_DIRECTORY_H_
