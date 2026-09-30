// @author 雾晚
package com.wanbox.auditprobe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public class ControlProbe extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        String target = getIntent().getStringExtra("target");
        if (target == null) target = "com.lit008834.pixel.wanboxforandroid";
        String base = "io.nekohasekai.sagernet.";
        try {
            startActivity(new Intent().setComponent(new ComponentName(target, base+"ui.NodeSelectDialogActivity")));
            throw new AssertionError("private activity allowed an external caller");
        } catch (SecurityException expected) {
            Log.i("WanBoxAuditProbe", "PRIVATE_ACTIVITY_REJECTED");
        }
        sendBroadcast(new Intent(target+".widget.ACTION_TOGGLE").setComponent(
            new ComponentName(target, base+"widget.WidgetControlReceiver")));
        for (String widget : new String[] {"OwnBoxWidgetProvider","OwnBoxWidget1x1","OwnBoxWidget2x2","OwnBoxWidget4x1","OwnBoxWidget4x2"}) {
            sendBroadcast(new Intent(target+".widget.ACTION_TOGGLE").setComponent(
                new ComponentName(target, base+"widget."+widget)));
        }
        String quick = getIntent().getStringExtra("quick");
        if (quick == null) quick = "ui.QuickEnableShortcut";
        startActivity(new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(target, base+quick)));
        TextView result = new TextView(this);
        result.setText("External probe sent; connection must stay stopped until user confirmation.");
        setContentView(result);
        Log.i("WanBoxAuditProbe", "PROBE_SENT");
    }
}
