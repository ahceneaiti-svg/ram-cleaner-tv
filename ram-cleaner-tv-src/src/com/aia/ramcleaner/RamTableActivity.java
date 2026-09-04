package com.aia.ramcleaner;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tableau de la consommation RAM par application, rafraichi en continu.
 *
 * Source des donnees, par ordre de preference :
 *   1. su -c "dumpsys meminfo"   (box rootee : chiffres reels de tous les process)
 *   2. dumpsys meminfo           (si la permission DUMP est accordee, ex. lancement adb)
 *   3. getRunningAppProcesses + getProcessMemoryInfo (repli : souvent limite a cette app)
 *
 * Sur Android 7+ un simple droit "device owner" ne suffit pas a voir la memoire
 * des autres applications : d'ou le repli partiel signale a l'utilisateur.
 */
public class RamTableActivity extends Activity {

    private static final int ACCENT = 0xFF5DD39E;
    private static final int WARN = 0xFFFFB454;
    private static final int DANGER = 0xFFFF5C6C;
    private static final long REFRESH_MS = 2000L;

    private static final Pattern PSS_LINE =
            Pattern.compile("^\\s*([0-9,]+)K:\\s+(\\S+)\\s+\\(pid\\s+(\\d+)");

    private final List<Row> rows = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ListView list;
    private Adapter adapter;
    private TextView hint;

    private long totalMem = 1L;
    private boolean partial;
    private boolean running;
    private Thread worker;

    static final class Row {
        String label;
        String pkg;
        long bytes;
        float pct;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_ram_table);
        hint = findViewById(R.id.hint);
        list = findViewById(R.id.list);
        adapter = new Adapter();
        list.setAdapter(adapter);

        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        totalMem = Math.max(1L, mi.totalMem);
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        tick();
    }

    @Override
    protected void onPause() {
        running = false;
        ui.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private void tick() {
        if (!running) return;
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    final List<Row> built = collect();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!running) return;
                            rows.clear();
                            rows.addAll(built);
                            adapter.notifyDataSetChanged();
                            String s = getString(R.string.ram_table_hint) + "  •  "
                                    + getString(R.string.ram_summary, built.size(), human(totalMem));
                            if (partial) s += "\n" + getString(R.string.ram_need_root);
                            hint.setText(s);
                        }
                    });
                }
            });
            worker.start();
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, REFRESH_MS);
    }

    private List<Row> collect() {
        partial = false;

        String out = exec(new String[]{"su", "-c", "dumpsys meminfo"});
        if (out == null || !out.contains("Total PSS by process")) {
            out = exec(new String[]{"dumpsys", "meminfo"});
        }

        Map<String, long[]> byPkg = new HashMap<>();
        if (out != null && out.contains("Total PSS by process")) {
            boolean in = false;
            for (String line : out.split("\n")) {
                if (!in) {
                    if (line.contains("Total PSS by process")) in = true;
                    continue;
                }
                if (line.trim().isEmpty()) break;
                Matcher m = PSS_LINE.matcher(line);
                if (!m.find()) continue;
                long kb = Long.parseLong(m.group(1).replace(",", ""));
                String proc = m.group(2);
                int c = proc.indexOf(':');
                String pkg = c > 0 ? proc.substring(0, c) : proc;
                add(byPkg, pkg, kb * 1024L);
            }
        }

        if (byPkg.isEmpty()) {
            partial = true;
            fallback(byPkg);
        }

        List<Row> res = new ArrayList<>();
        for (Map.Entry<String, long[]> e : byPkg.entrySet()) {
            Row r = new Row();
            r.pkg = e.getKey();
            r.label = labelOf(r.pkg);
            r.bytes = e.getValue()[0];
            r.pct = 100f * r.bytes / totalMem;
            res.add(r);
        }
        Collections.sort(res, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                return Long.compare(b.bytes, a.bytes);
            }
        });
        return res;
    }

    private static void add(Map<String, long[]> map, String key, long bytes) {
        long[] acc = map.get(key);
        if (acc == null) {
            acc = new long[1];
            map.put(key, acc);
        }
        acc[0] += bytes;
    }

    private void fallback(Map<String, long[]> byPkg) {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs == null || procs.isEmpty()) return;
        int[] pids = new int[procs.size()];
        for (int i = 0; i < procs.size(); i++) pids[i] = procs.get(i).pid;
        Debug.MemoryInfo[] mem = am.getProcessMemoryInfo(pids);
        for (int i = 0; i < procs.size(); i++) {
            String proc = procs.get(i).processName;
            int c = proc.indexOf(':');
            String pkg = c > 0 ? proc.substring(0, c) : proc;
            long bytes = (mem != null && i < mem.length) ? mem[i].getTotalPss() * 1024L : 0L;
            add(byPkg, pkg, bytes);
        }
    }

    private String labelOf(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private static String exec(String[] cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l;
            while ((l = br.readLine()) != null) sb.append(l).append('\n');
            br.close();
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static String human(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024.0) return String.format(Locale.US, "%.1f Go", mb / 1024.0);
        return String.format(Locale.US, "%.0f Mo", mb);
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            View v = cv;
            if (v == null) {
                v = LayoutInflater.from(RamTableActivity.this)
                        .inflate(R.layout.row_ram, parent, false);
            }
            Row r = rows.get(pos);
            ((TextView) v.findViewById(R.id.label)).setText(r.label);
            ((TextView) v.findViewById(R.id.pkg)).setText(r.pkg);
            ((TextView) v.findViewById(R.id.mem)).setText(human(r.bytes));

            int col = r.pct < 5f ? ACCENT : (r.pct < 15f ? WARN : DANGER);

            TextView pctv = v.findViewById(R.id.pct);
            pctv.setText(String.format(Locale.US, "%.1f %%", r.pct));
            pctv.setTextColor(col);

            ProgressBar bar = v.findViewById(R.id.bar);
            int prog = Math.round(r.pct * 10f);
            bar.setProgress(Math.max(0, Math.min(1000, prog)));
            bar.setProgressTintList(ColorStateList.valueOf(col));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF242D37));
            return v;
        }
    }
}
