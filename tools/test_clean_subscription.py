# @author 雾晚
import unittest
from clean_subscription import clean, clean_name


class CleanupTest(unittest.TestCase):
    def test_names(self):
        self.assertEqual("日本 • 电信 01", clean_name("🇯🇵 日本 • 电信 01 ✨"))

    def test_duplicate_keeps_transport_and_credentials(self):
        nodes = [
            {"type": "trojan", "tag": "🇯🇵 A", "server": "EXAMPLE.COM", "server_port": 443, "password": "one"},
            {"type": "trojan", "tag": "copy", "server": "example.com", "server_port": 443, "password": "one"},
            {"type": "trojan", "tag": "A", "server": "example.com", "server_port": 443, "password": "two"},
            {"type": "trojan", "tag": "套餐到期：2027", "server": "example.com", "server_port": 444},
            {"type": "selector", "tag": "proxy", "outbounds": ["🇯🇵 A", "copy", "A", "套餐到期：2027"]},
        ]
        result = clean({"outbounds": nodes})
        self.assertEqual(4, len(nodes[4]["outbounds"]))  # 不修改输入
        self.assertEqual(3, len(result["outbounds"]))
        self.assertEqual(["A", "A (2)"], result["outbounds"][-1]["outbounds"])

    def test_removed_final_fails(self):
        with self.assertRaises(ValueError):
            clean({"outbounds": [{"tag": "通知", "server": "x"}], "route": {"final": "通知"}})


if __name__ == "__main__":
    unittest.main()
