"""Секрет локального прокси не должен попадать в журналы upstream."""
import importlib.util
import logging
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('adapter', Path(__file__).with_name('qnzapret_telegram.py'))
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)


class RedactionTests(unittest.TestCase):
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


if __name__ == '__main__':
    unittest.main()
