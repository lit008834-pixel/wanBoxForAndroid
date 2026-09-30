# @author 雾晚
"""Check both source and merged manifests; fail on exposed control broadcasts."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parents[1]
manifest = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "app/src/main/AndroidManifest.xml"
android = "{http://schemas.android.com/apk/res/android}"
tree = ET.parse(manifest)
app = tree.getroot().find("application")
receivers = app.findall("receiver")
private = [r for r in receivers if r.get(android+"name", "").endswith("WidgetControlReceiver")]
assert len(private) == 1 and private[0].get(android+"exported") == "false", "widget control must be private"
widgets = [r for r in receivers if "OwnBoxWidget" in r.get(android+"name", "")]
assert len(widgets) == 5
for receiver in widgets:
    actions = [a.get(android+"name") for a in receiver.findall("./intent-filter/action")]
    assert actions == ["android.appwidget.action.APPWIDGET_UPDATE"], actions
for name in ["QuickEnableShortcut", "QuickDisableShortcut", "QuickToggleShortcut"]:
    matches = list((root/"app/src/main/java").rglob(name+".kt"))
    text = matches[0].read_text(encoding="utf-8")
    assert "confirmControl()" in text and "setPositiveButton" in text
    assert ".filterTouchesWhenObscured = true" in text
db = (root/"app/src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt").read_text(encoding="utf-8")
assert "fallbackToDestructiveMigration" not in db and "deleteDatabase" not in db
security = ET.parse(root/"app/src/main/res/xml/network_security_config.xml").getroot()
assert security.find("base-config").get("cleartextTrafficPermitted") == "false"
assert {d.text for d in security.findall("./domain-config/domain")} == {"127.0.0.1", "localhost"}
print("Manifest control isolation, migration preservation and TLS policy checks passed")
