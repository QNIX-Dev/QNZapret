#ifndef QNZAPRET_COMPONENT_PATHS_H_
#define QNZAPRET_COMPONENT_PATHS_H_

#include <glib.h>
#include <sys/stat.h>

#include <cstdlib>
#include <string>

namespace qnzapret {
inline std::string ComponentPath(const char* slot, const char* bundled) {
  const std::string base = "/var/lib/qnzapret/components/";
  g_autofree char* resolved = realpath((base + slot).c_str(), nullptr);
  struct stat status{};
  struct stat root_owner{};
  struct stat base_status{};
  // systemd user sandbox может отображать host root как overflow UID.
  // Сравниваем владельца с корнем filesystem в том же namespace.
  if (resolved == nullptr || std::string(resolved).rfind(base, 0) != 0 ||
      stat("/", &root_owner) != 0 || stat(base.c_str(), &base_status) != 0 ||
      base_status.st_uid != root_owner.st_uid ||
      (base_status.st_mode & 0022) != 0 || stat(resolved, &status) != 0 ||
      status.st_uid != root_owner.st_uid || (status.st_mode & 0022) != 0 ||
      !S_ISDIR(status.st_mode)) {
    return bundled;
  }
  return resolved;
}
}  // namespace qnzapret
#endif
