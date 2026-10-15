#ifndef QNZAPRET_LINUX_RUNNER_LINUX_PROXY_RUNTIME_PLUGIN_H_
#define QNZAPRET_LINUX_RUNNER_LINUX_PROXY_RUNTIME_PLUGIN_H_

#include <flutter_linux/flutter_linux.h>

struct LinuxProxyRuntimePlugin;

LinuxProxyRuntimePlugin* linux_proxy_runtime_plugin_new(FlView* view);
void linux_proxy_runtime_plugin_free(LinuxProxyRuntimePlugin* plugin);

#endif  // QNZAPRET_LINUX_RUNNER_LINUX_PROXY_RUNTIME_PLUGIN_H_
