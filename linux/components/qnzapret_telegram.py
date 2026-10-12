"""Адаптер headless tg-ws-proxy; upstream proxy/ хранится без правок."""

import argparse
import asyncio
import inspect
import logging
import logging.handlers
import os
from pathlib import Path
import re


def check_contract():
    from proxy import tg_ws_proxy as core
    from proxy import bridge
    from proxy.config import proxy_config

    for name in ("_run", "bridge_ws_reencrypt"):
        if not inspect.iscoroutinefunction(getattr(core, name, None)):
            raise RuntimeError("Несовместимый tg-ws-proxy: " + name)
    for name in ("host", "port", "secret", "dc_redirects", "pool_size"):
        if not hasattr(proxy_config, name):
            raise RuntimeError("Несовместимый proxy_config: " + name)
    if not inspect.iscoroutinefunction(getattr(bridge, "bridge_h2", None)):
        raise RuntimeError("Несовместимый Telegram H2 bridge")
    return core, proxy_config


class SecretFilter(logging.Filter):
    def __init__(self, secret):
        super().__init__()
        self.secret = secret

    def filter(self, record):
        message = record.getMessage().replace(self.secret, "[REDACTED]")
        record.msg = re.sub(r"tg://proxy\?\S+", "[REDACTED setup URI]", message)
        record.args = ()
        return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=1443)
    parser.add_argument("--secret-file")
    parser.add_argument("--health-file")
    parser.add_argument("--log-file")
    parser.add_argument("--log-max-mb", type=float, default=2)
    parser.add_argument("--log-backups", type=int, default=1)
    args = parser.parse_args()
    core, config = check_contract()
    if args.self_test:
        return
    secret = Path(args.secret_file).read_text(encoding="ascii").strip()
    if not re.fullmatch(r"[0-9a-fA-F]{32}", secret):
        raise ValueError("Некорректный Telegram secret file")
    config.host, config.port, config.secret = args.host, args.port, secret
    # Сохраняем upstream defaults маршрутизации и поддержки Cloudflare.
    config.dc_redirects = core.parse_dc_ip_list(["2:149.154.167.220", "4:149.154.167.220"])
    handlers = [logging.StreamHandler()]
    if args.log_file:
        handlers.append(logging.handlers.RotatingFileHandler(
            args.log_file, maxBytes=max(32768, int(args.log_max_mb * 1048576)),
            backupCount=max(0, args.log_backups), encoding="utf-8"))
    for handler in handlers:
        handler.addFilter(SecretFilter(secret))
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        handlers=handlers)
    for name in ("asyncio", "httpx", "httpcore"):
        logging.getLogger(name).setLevel(logging.WARNING)

    def health(value):
        if not args.health_file:
            return
        path = Path(args.health_file)
        temporary = path.with_suffix(".tmp")
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as stream:
            stream.write(value)
        os.replace(temporary, path)

    start_server = asyncio.start_server
    from proxy import bridge as bridge_module
    active_bridges = 0

    async def observed_listener(*positional, **keywords):
        server = await start_server(*positional, **keywords)
        health("listening")
        return server

    def observe_bridge(bridge):
        async def observed(*positional, **keywords):
            nonlocal active_bridges
            active_bridges += 1
            health("bridge_ready")
            try:
                return await bridge(*positional, **keywords)
            finally:
                active_bridges -= 1
                if active_bridges == 0:
                    health("listening")
        return observed

    asyncio.start_server = observed_listener
    core.bridge_ws_reencrypt = observe_bridge(core.bridge_ws_reencrypt)
    bridge_module.bridge_ws_reencrypt = core.bridge_ws_reencrypt
    bridge_module.bridge_h2 = observe_bridge(bridge_module.bridge_h2)
    try:
        asyncio.run(core._run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
