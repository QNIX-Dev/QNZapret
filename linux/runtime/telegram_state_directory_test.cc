#include "telegram_state_directory.h"

#include <glib.h>

namespace {

void UsesSystemdStateDirectory() {
  g_setenv("STATE_DIRECTORY", "/run/user/1000/qnzapret", true);
  g_setenv("XDG_STATE_HOME", "/tmp/ignored-xdg-state", true);
  const auto directory = qnzapret::TelegramStateDirectory();
  g_assert_cmpstr(directory.c_str(), ==, "/run/user/1000/qnzapret");
}

void UsesFirstSystemdStateDirectory() {
  g_setenv("STATE_DIRECTORY", "/run/user/1000/qnzapret:/tmp/secondary",
           true);
  const auto directory = qnzapret::TelegramStateDirectory();
  g_assert_cmpstr(directory.c_str(), ==, "/run/user/1000/qnzapret");
}

void FallsBackToXdgStateHome() {
  g_setenv("STATE_DIRECTORY", "relative-path", true);
  g_setenv("XDG_STATE_HOME", "/tmp/xdg-state", true);
  const auto directory = qnzapret::TelegramStateDirectory();
  g_assert_cmpstr(directory.c_str(), ==, "/tmp/xdg-state/qnzapret");
}

}  // namespace

int main(int argc, char** argv) {
  g_test_init(&argc, &argv, nullptr);
  g_test_add_func("/telegram-state/systemd", UsesSystemdStateDirectory);
  g_test_add_func("/telegram-state/systemd-first",
                  UsesFirstSystemdStateDirectory);
  g_test_add_func("/telegram-state/xdg-fallback", FallsBackToXdgStateHome);
  return g_test_run();
}
