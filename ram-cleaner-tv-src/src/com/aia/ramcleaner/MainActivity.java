package com.aia.ramcleaner;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    // Palette (miroir de res/values/colors.xml, evite getColor selon l'API).
    private static final int ACCENT = 0xFF5DD39E;
    private static final int WARN = 0xFFFFB454;
    private static final int DANGER = 0xFFFF5C6C;

    private GaugeView gaugeTotal;
    private GaugeView gaugeUsage;
    private TextView pct;
    private TextView sub;
    private TextView result;
    private Button btnClean;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        gaugeTotal = findViewById(R.id.gaugeTotal);
        gaugeUsage = findViewById(R.id.gaugeUsage);
        pct = findViewById(R.id.pct);
        sub = findViewById(R.id.sub);
        result = findViewById(R.id.result);
        btnClean = findViewById(R.id.btnClean);

        gaugeTotal.setArcColor(DANGER);

        btnClean.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clean();
            }
        });

        findViewById(R.id.btnApps).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AppsActivity.class));
            }
        });

        findViewById(R.id.btnRam).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, RamTableActivity.class));
            }
        });

        updateStats();
        btnClean.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStats();
    }

    private ActivityManager.MemoryInfo memInfo() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi;
    }

    private static String human(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024.0) {
            return String.format(Locale.US, "%.1f Go", mb / 1024.0);
        }
        return String.format(Locale.US, "%.0f Mo", mb);
    }

    private void updateStats() {
        ActivityManager.MemoryInfo mi = memInfo();
        long total = mi.totalMem;
        long avail = mi.availMem;
        long used = total - avail;
        int used_pct = total > 0 ? (int) (used * 100 / total) : 0;

        int pressure = used_pct < 60 ? ACCENT : (used_pct < 85 ? WARN : DANGER);

        gaugeTotal.setCenterText(human(total));
        gaugeTotal.setSubText(getString(R.string.mem_total));
        gaugeTotal.setProgressAnimated(1f);

        gaugeUsage.setArcColor(pressure);
        gaugeUsage.setCenterText(human(used));
        gaugeUsage.setSubText(getString(R.string.pct_used, used_pct));
        gaugeUsage.setProgressAnimated(total > 0 ? (float) used / total : 0f);

        pct.setText(used_pct + " %");
        pct.setTextColor(pressure);

        String s = getString(R.string.free_ram, human(avail));
        if (mi.lowMemory) {
            s = s + "   •   " + getString(R.string.low_mem);
        }
        sub.setText(s);
    }

    private void clean() {
        btnClean.setEnabled(false);
        result.setText(R.string.scanning);

        final long freeBefore = memInfo().availMem;

        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        PackageManager pm = getPackageManager();
        String self = getPackageName();

        int killed = 0;
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        for (ApplicationInfo app : apps) {
            if (app.packageName == null || app.packageName.equals(self)) continue;
            boolean isSystem = (app.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            if (isSystem) continue;
            try {
                am.killBackgroundProcesses(app.packageName);
                killed++;
            } catch (SecurityException ignored) {
                // permission refusee par l'OEM
            }
        }

        System.gc();
        System.runFinalization();

        final int killedFinal = killed;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                long freeAfter = memInfo().availMem;
                long freed = freeAfter - freeBefore;
                if (freed < 0) freed = 0;
                result.setText(getString(R.string.killed_apps, killedFinal) + "\n"
                        + getString(R.string.freed_ram, human(freed)));
                updateStats();
                btnClean.setEnabled(true);
                btnClean.requestFocus();
            }
        }, 1500);
    }
}
