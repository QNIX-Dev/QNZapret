"""Адаптер headless tg-ws-proxy; upstream proxy/ хранится без правок."""

import argparse
import asyncio
import inspect
import json
import logging
import logging.handlers
import os
from pathlib import Path
import re


def check_contract():
    from proxy import tg_ws_proxy as core
    from proxy import bridge
    from proxy.config import proxy_config

    for name in ("_run", "bridge_ws_reencrypt", "do_fallback"):
        if not inspect.iscoroutinefunction(getattr(core, name, None)):
            raise RuntimeError("Несовместимый tg-ws-proxy: " + name)
    for name in ("host", "port", "secret", "dc_redirects", "pool_size"):
        if not hasattr(proxy_config, name):
            raise RuntimeError("Несовместимый proxy_config: " + name)
    if not inspect.iscoroutinefunction(getattr(bridge, "bridge_h2", None)):
        raise RuntimeError("Несовместимый Telegram H2 bridge")
    if not isinstance(getattr(getattr(core, "ws_pool", None), "_refilling", None), dict):
        raise RuntimeError("Несовместимый Telegram WS pool")
    if not callable(getattr(core, "_generate_relay_init", None)) or not inspect.iscoroutinefunction(getattr(bridge, "_tcp_fallback", None)):
        raise RuntimeError("Несовместимый Telegram CDN transport")
    try:
        inspect.signature(core.do_fallback).bind(*([None] * 10), h2_pool=None, proto_tag=None)
        inspect.signature(bridge._tcp_fallback).bind(*([None] * 7))
        inspect.signature(core._generate_relay_init).bind(None, None)
        inspect.signature(core.ws_pool.get).bind(None, None, is_test_dc=False)
    except TypeError as error:
        raise RuntimeError("Несовместимый Telegram routing contract") from error
    import telegram_cdn  # Validate the packaged helper in updater --self-test too.
    return core, proxy_config


def configure_routes(core, config):
    # CDN203 must not use the ordinary DC2 Web endpoint: it answers with main-DC
    # RSA keys. Keep CDN on a validated kws203 gateway, not a misleading WS 101.
    config.dc_redirects = core.parse_dc_ip_list([
        "2:149.154.167.220", "4:149.154.167.220",
    ])


def configure_media_transport(config, secret_file):
    """Optional per-user transport preference, separate from credentials/upstream."""
    path = Path(secret_file).with_name("telegram.transport.json")
    if not path.exists():
        return
    try:
        with path.open("rb") as stream:
            data = stream.read(4097)
        if len(data) > 4096:
            raise ValueError("size")
        value = json.loads(data)
        if not isinstance(value, dict) or set(value) != {"h2Media"} or type(value["h2Media"]) is not bool:
            raise ValueError("schema")
        config.cfproxy_h2_media = value["h2Media"]
    except (OSError, ValueError):
        logging.getLogger(__name__).warning("Некорректный telegram.transport.json; используется стандартный транспорт.")


def install_media_pool_wait(core, config, wait_seconds=1.0):
    """Let an already running refill finish before pushing media to shared CF."""
    pool = core.ws_pool
    original_get = pool.get

    async def get(dc, is_media, *, is_test_dc=False):
        result = await original_get(dc, is_media, is_test_dc=is_test_dc)
        if result is not None or not (is_media or dc == 203) or dc not in config.dc_redirects:
            return result
        refill = pool._refilling.get((dc, is_media, is_test_dc))
        if refill is None or refill.done():
            return None
        try:
            # One client's timeout/cancellation must not cancel the shared refill.
            await asyncio.wait_for(asyncio.shield(refill), timeout=wait_seconds)
        except Exception:
            return None
        return await original_get(dc, is_media, is_test_dc=is_test_dc)

    pool.get = get


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
    configure_routes(core, config)
    configure_media_transport(config, args.secret_file)
    install_media_pool_wait(core, config)
    from telegram_cdn import install_cdn_validation
    install_cdn_validation(core)
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
