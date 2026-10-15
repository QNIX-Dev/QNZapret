import asyncio
from pathlib import Path
import struct
import sys
from types import SimpleNamespace
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'runtime/telegram/flowseal'))
from telegram_cdn import parse_public_keys, validate_domain, CdnRouteSelector


class CdnReplyTests(unittest.TestCase):
    def reply(self, nonce, keys):
        body = struct.pack('<I', 0x05162463) + nonce + bytes(16) + bytes([1, 15, 0, 0])
        body += struct.pack('<II', 0x1cb5c415, len(keys)) + b''.join(struct.pack('<Q', k) for k in keys)
        envelope = bytes(16) + struct.pack('<I', len(body)) + body
        return struct.pack('<I', len(envelope)) + envelope

    def test_nonce_matched_reply_yields_only_public_fingerprints(self):
        nonce = bytes(range(16))
        self.assertEqual({11, 22}, parse_public_keys(self.reply(nonce, [11, 22]), nonce))

    def test_wrong_nonce_is_rejected(self):
        with self.assertRaises(ValueError): parse_public_keys(self.reply(bytes(16), [11]), bytes([1]) * 16)

    def test_truncated_fingerprint_vector_is_rejected(self):
        with self.assertRaises(ValueError): parse_public_keys(self.reply(bytes(16), [11])[:-3], bytes(16))


class CdnRouteTests(unittest.IsolatedAsyncioTestCase):
    async def test_http_success_with_main_dc_keys_is_not_a_cdn_route(self):
        async def probe(host, dc): return {1, 2}
        self.assertFalse(await validate_domain('test.example', probe))

    async def test_distinct_cdn_keys_are_required(self):
        async def probe(host, dc): return {3} if dc == 203 else {1, 2}
        self.assertTrue(await validate_domain('test.example', probe))

    async def test_selector_excludes_misrouted_gateway_and_preserves_other_dcs(self):
        domains = ['bad.example', 'good.example']
        balancer = SimpleNamespace(domains=domains, get_domains_for_dc=lambda dc: iter(domains))
        async def probe(host, dc): return {3} if host.endswith('good.example') and dc == 203 else {1, 2}
        selector = CdnRouteSelector(SimpleNamespace(balancer=balancer), probe)
        self.assertTrue(await selector.ensure())
        self.assertEqual(['good.example'], list(selector.ordered_domains(203)))
        self.assertEqual(domains, list(selector.ordered_domains(2)))

    async def test_removed_domain_is_replaced_without_waiting_for_ttl(self):
        balancer = SimpleNamespace(domains=['old.example'])
        balancer.get_domains_for_dc = lambda dc: iter(balancer.domains)
        async def probe(host, dc): return {3} if dc == 203 else {1}
        selector = CdnRouteSelector(SimpleNamespace(balancer=balancer), probe)
        self.assertTrue(await selector.ensure())
        balancer.domains = ['new.example']
        self.assertTrue(await selector.ensure())
        self.assertEqual(['new.example'], list(selector.ordered_domains(203)))

    async def test_later_domains_are_not_permanently_hidden_by_probe_batch_limit(self):
        domains = ['bad%d.example' % i for i in range(8)] + ['good.example']
        balancer = SimpleNamespace(domains=domains, get_domains_for_dc=lambda dc: iter(domains))
        async def probe(host, dc): return {3} if dc == 203 and host.endswith('good.example') else {1}
        selector = CdnRouteSelector(SimpleNamespace(balancer=balancer), probe)
        self.assertFalse(await selector.ensure())
        selector.valid_until = 0
        self.assertTrue(await selector.ensure())
        self.assertEqual(['good.example'], list(selector.ordered_domains(203)))


if __name__ == '__main__': unittest.main()
