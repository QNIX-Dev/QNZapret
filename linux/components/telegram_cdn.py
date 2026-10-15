"""Anonymous CDN route checks; no user credentials, auth keys or file requests."""
import asyncio
import os
import struct
import time
import logging

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes


def parse_public_keys(packet, nonce):
    if len(packet) < 72 or packet[24:28] != bytes.fromhex("63241605") or packet[28:44] != nonce:
        raise ValueError("Invalid ResPQ")
    body_end = 24 + int.from_bytes(packet[20:24], "little")
    if body_end > len(packet) or body_end > 4096:
        raise ValueError("Invalid ResPQ length")
    pos = 60
    size = packet[pos]
    pos += 1
    if size == 254:
        size = int.from_bytes(packet[pos:pos + 3], "little")
        pos += 3
    pos = (pos + size + 3) // 4 * 4
    if pos + 8 > body_end:
        raise ValueError("Invalid ResPQ vector")
    tag, count = struct.unpack_from("<II", packet, pos)
    if tag != 0x1cb5c415 or not 1 <= count <= 32 or pos + 8 + count * 8 > body_end:
        raise ValueError("Invalid ResPQ keys")
    return frozenset(struct.unpack_from("<Q", packet, pos + 8 + i * 8)[0] for i in range(count))


async def probe_public_keys(host, dc, core):
    from proxy.raw_websocket import RawWebSocket
    ws = None
    try:
        ws = await RawWebSocket.connect(host, host, timeout=2.0)
        init = core._generate_relay_init(b"\xdd" * 4, dc)
        up = Cipher(algorithms.AES(init[8:40]), modes.CTR(init[40:56])).encryptor()
        up.update(init)
        reverse = init[::-1]
        down = Cipher(algorithms.AES(reverse[8:40]), modes.CTR(reverse[40:56])).decryptor()
        nonce = os.urandom(16)
        body = struct.pack("<I", 0xbe7e8ef1) + nonce
        envelope = bytes(8) + struct.pack("<Q", int(time.time() * 2**32) & ~3) + struct.pack("<I", len(body)) + body
        payload = envelope + os.urandom(8)
        await ws.send(init)
        await ws.send(up.update(struct.pack("<I", len(payload)) + payload))
        response = bytearray()
        while len(response) < 4096:
            chunk = await asyncio.wait_for(ws.recv(), 2.0)
            if chunk is None:
                raise ValueError("No ResPQ")
            response.extend(down.update(chunk))
            if len(response) >= 4:
                size = int.from_bytes(response[:4], "little")
                if not 1 <= size <= 4092:
                    raise ValueError("Invalid response")
                if len(response) >= size + 4:
                    return parse_public_keys(response, nonce)
        raise ValueError("Response too large")
    finally:
        if ws is not None:
            ws.writer.close()


async def validate_domain(domain, probe):
    cdn = await probe("kws203." + domain, 203)
    main = await probe("kws2." + domain, 2)
    # This is a route-consistency check, not authentication. The real Telegram
    # client still verifies CDN RSA keys against help.getCdnConfig itself.
    return bool(cdn and main and cdn.isdisjoint(main))


class CdnRouteSelector:
    def __init__(self, core, probe=None):
        self.core = core
        self.balancer = core.balancer
        self.original_domains = self.balancer.get_domains_for_dc
        self.probe = probe or (lambda host, dc: probe_public_keys(host, dc, core))
        self.healthy = None
        self.valid_until = 0
        self.task = None
        self.offset = 0

    def ordered_domains(self, dc):
        if dc == 203:
            return iter([self.healthy] if self.healthy in self.balancer.domains else [])
        domains = list(self.original_domains(dc))
        return iter(domains)

    async def _select(self):
        from proxy.config import CFPROXY_DEFAULT_DOMAINS, proxy_config
        domains = list(self.balancer.domains)
        preferred = [d for d in proxy_config.cfproxy_user_domains + CFPROXY_DEFAULT_DOMAINS if d in domains]
        all_candidates = list(dict.fromkeys(preferred + domains))
        if not all_candidates:
            self.healthy = None
            self.valid_until = time.monotonic() + 10
            return
        start = self.offset % len(all_candidates)
        candidates = (all_candidates[start:] + all_candidates[:start])[:8]
        self.offset = (start + len(candidates)) % len(all_candidates)
        for domain in candidates:
            try:
                if await asyncio.wait_for(validate_domain(domain, self.probe), 5.0):
                    self.healthy = domain
                    self.valid_until = time.monotonic() + 900
                    logging.getLogger(__name__).info("CDN203: проверен отдельный CDN-шлюз %s", domain)
                    return
            except Exception:
                continue
        self.healthy = None
        self.valid_until = time.monotonic() + 10

    async def ensure(self):
        if self.healthy is not None and self.healthy not in self.balancer.domains:
            self.healthy = None
            self.valid_until = 0
        if self.healthy in self.balancer.domains and time.monotonic() < self.valid_until:
            return True
        if self.task is None or (self.task.done() and time.monotonic() >= self.valid_until):
            self.task = asyncio.create_task(self._select())
        try:
            await asyncio.wait_for(asyncio.shield(self.task), 3.0)
        except asyncio.TimeoutError:
            return False
        return self.healthy in self.balancer.domains


def install_cdn_validation(core):
    from proxy import bridge
    from proxy.utils import DC_DEFAULT_IPS
    selector = CdnRouteSelector(core)
    core.balancer.get_domains_for_dc = selector.ordered_domains
    original = core.do_fallback

    async def fallback(reader, writer, relay_init, label, dc, is_test_dc, is_media,
                       media_tag, ctx, splitter=None, *, h2_pool=None, proto_tag=None):
        if dc == 203 and not is_test_dc and core.proxy_config.fallback_cfproxy and not await selector.ensure():
            return await bridge._tcp_fallback(reader, writer, DC_DEFAULT_IPS[203], 443, relay_init, label, ctx)
        result = await original(reader, writer, relay_init, label, dc, is_test_dc,
                                is_media, media_tag, ctx, splitter, h2_pool=h2_pool, proto_tag=proto_tag)
        if dc == 203 and not result:
            selector.healthy = None
            selector.valid_until = 0
        return result

    core.do_fallback = fallback
