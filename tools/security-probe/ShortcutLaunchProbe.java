// @author 雾晚
package com.wanbox.auditprobe;

import android.app.Activity;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.widget.TextView;
import java.util.List;

/** Disposable emulator HOME role uses the public launcher API. @author 雾晚 */
public class ShortcutLaunchProbe extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView result = new TextView(this);
        result.setText("Disposable launcher shortcut probe");
        setContentView(result);
        String shortcut = getIntent().getStringExtra("shortcut");
        if (shortcut == null) return;
        String target = getIntent().getStringExtra("target");
        LauncherApps launcher = getSystemService(LauncherApps.class);
        if (!launcher.hasShortcutHostPermission()) throw new AssertionError("HOME role missing");
        List<ShortcutInfo> shortcuts = launcher.getShortcuts(new LauncherApps.ShortcutQuery()
            .setPackage(target).setShortcutIds(java.util.Collections.singletonList(shortcut))
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                | LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED), Process.myUserHandle());
        if (shortcuts == null || shortcuts.size() != 1 || !shortcuts.get(0).isEnabled()) {
            throw new AssertionError("Published shortcut unavailable: " + shortcut);
        }
        launcher.startShortcut(target, shortcut, null, null, Process.myUserHandle());
        Log.i("WanBoxAuditProbe", "LAUNCHER_SHORTCUT_STARTED " + shortcut);
    }
}
