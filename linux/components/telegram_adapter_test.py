"""Секрет локального прокси не должен попадать в журналы upstream."""
import importlib.util
import logging
from pathlib import Path
import unittest
import asyncio
import contextlib
import tempfile
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location('adapter', Path(__file__).with_name('qnzapret_telegram.py'))
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)


class RedactionTests(unittest.TestCase):
    def test_per_user_media_setting_preserves_other_proxy_features(self):
        with tempfile.TemporaryDirectory() as directory:
            config = SimpleNamespace(cfproxy_h2_media=True, fallback_cfproxy=True)
            path = Path(directory) / 'telegram.transport.json'
            path.write_text('{"h2Media": false}')
            adapter.configure_media_transport(config, Path(directory) / 'telegram.secret')
            self.assertFalse(config.cfproxy_h2_media)
            self.assertTrue(config.fallback_cfproxy)

    def test_invalid_media_setting_keeps_upstream_default(self):
        with tempfile.TemporaryDirectory() as directory:
            config = SimpleNamespace(cfproxy_h2_media=True)
            (Path(directory) / 'telegram.transport.json').write_text('{"h2Media": "false"}')
            adapter.configure_media_transport(config, Path(directory) / 'telegram.secret')
            self.assertTrue(config.cfproxy_h2_media)

    def test_cdn_dc_is_not_misrouted_to_main_dc2_pool(self):
        import sys
        sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'runtime/telegram/flowseal'))
        from proxy import tg_ws_proxy as core
        from proxy.utils import ws_domains
        config = SimpleNamespace()
        adapter.configure_routes(core, config)
        self.assertNotIn(203, config.dc_redirects)
        self.assertEqual(['kws2.web.telegram.org'], ws_domains(203, False))
        self.assertEqual({2, 4}, set(config.dc_redirects))

    def test_secret_and_uri_are_redacted_after_message_formatting(self):
        secret = 'a' * 32
        record = logging.LogRecord('proxy', logging.INFO, '', 0,
            'Secret: %s link: tg://proxy?server=127.0.0.1&port=1443&secret=dd%s',
            (secret, secret), None)
        self.assertTrue(adapter.SecretFilter(secret).filter(record))
        self.assertNotIn(secret, record.getMessage())
        self.assertNotIn('tg://proxy?', record.getMessage())
        self.assertIn('[REDACTED]', record.getMessage())

    def test_safe_diagnostics_remain_readable(self):
        record = logging.LogRecord('proxy', logging.INFO, '', 0,
            'Listening on %s:%d', ('127.0.0.1', 1443), None)
        adapter.SecretFilter('a' * 32).filter(record)
        self.assertEqual(record.getMessage(), 'Listening on 127.0.0.1:1443')


class MediaPoolTests(unittest.IsolatedAsyncioTestCase):
    async def test_ready_refill_avoids_public_fallback_on_cdn_pool_miss(self):
        pool = SimpleNamespace(_refilling={}, ready=None)
        async def original(*args, **kwargs): return pool.ready
        pool.get = original
        async def refill():
            await asyncio.sleep(0)
            pool.ready = object()
        pool._refilling[(203, False, False)] = asyncio.create_task(refill())
        adapter.install_media_pool_wait(SimpleNamespace(ws_pool=pool), SimpleNamespace(dc_redirects={203: 'test'}))
        self.assertIs(await pool.get(203, False), pool.ready)

    async def test_timeout_does_not_cancel_shared_refill(self):
        task = asyncio.create_task(asyncio.Event().wait())
        async def original(*args, **kwargs): return None
        pool = SimpleNamespace(get=original, _refilling={(2, True, False): task})
        adapter.install_media_pool_wait(SimpleNamespace(ws_pool=pool), SimpleNamespace(dc_redirects={2: 'test'}), .01)
        self.assertIsNone(await pool.get(2, True))
        self.assertFalse(task.cancelled())
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError): await task

    async def test_cancelled_client_does_not_cancel_shared_refill(self):
        refill = asyncio.create_task(asyncio.Event().wait())
        async def original(*args, **kwargs): return None
        pool = SimpleNamespace(get=original, _refilling={(2, True, False): refill})
        adapter.install_media_pool_wait(SimpleNamespace(ws_pool=pool), SimpleNamespace(dc_redirects={2: 'test'}))
        client = asyncio.create_task(pool.get(2, True))
        await asyncio.sleep(.01)
        client.cancel()
        with contextlib.suppress(asyncio.CancelledError): await client
        self.assertFalse(refill.cancelled())
        refill.cancel()
        with contextlib.suppress(asyncio.CancelledError): await refill


if __name__ == '__main__':
    unittest.main()
