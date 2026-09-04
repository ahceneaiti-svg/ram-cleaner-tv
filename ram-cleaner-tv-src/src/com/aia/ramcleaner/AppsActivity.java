package com.aia.ramcleaner;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppsActivity extends Activity {

    /** Paquets systeme connus comme sans risque a desactiver sur une box Amlogic / Mecool. */
    private static final Set<String> BLOAT = new HashSet<>(Arrays.asList(
            "com.android.egg",
            "com.android.dreams.basic",
            "com.android.dreams.phototable",
            "com.android.wallpaper.livepicker",
            "com.android.bookmarkprovider",
            "com.android.bluetoothmidiservice",
            "com.android.printspooler",
            "com.android.gallery3d",
            "com.android.music",
            "com.google.android.feedback",
            "com.google.android.partnersetup",
            "com.google.android.printservice.recommendation",
            "com.google.android.marvin.talkback",
            "com.google.android.tts",
            "com.google.android.syncadapters.contacts",
            "com.google.android.syncadapters.calendar",
            "com.droidlogic.PPPoE",
            "com.droidlogic.mboxlauncher",
            "com.droidlogic.tv.settings",
            "com.droidlogic.FileBrowser"
    ));

    /** Paquets a ne jamais toucher : coeur du systeme, la box ne demarre plus sinon. */
    private static final Set<String> PROTECT = new HashSet<>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.settings.intelligence",
            "com.android.tv.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.shell",
            "com.android.keychain",
            "com.android.certinstaller",
            "com.android.externalstorage",
            "com.android.documentsui",
            "com.android.webview",
            "com.google.android.webview",
            "com.android.wifi.resources",
            "com.android.inputdevices",
            "com.android.location.fused",
            "com.android.se",
            "com.android.providers.settings",
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.android.providers.downloads",
            "com.android.providers.contacts",
            "com.android.providers.telephony",
            "com.android.providers.tv",
            "com.google.android.gsf",
            "com.google.android.gms"
    ));

    private static final class Item {
        String label;
        String pkg;
        boolean bloat;
    }

    private DevicePolicyManager dpm;
    private ComponentName admin;
    private boolean owner;
    private PackageManager pm;
    private ActivityManager am;

    private final List<Item> items = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ListView list;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);

        pm = getPackageManager();
        am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = AdminReceiver.component(this);
        owner = dpm != null && dpm.isDeviceOwnerApp(getPackageName());

        ((TextView) findViewById(R.id.hint)).setText(
                owner ? getString(R.string.owner_active) : getString(R.string.no_owner_hint));

        list = findViewById(R.id.list);
        adapter = new Adapter();
        list.setAdapter(adapter);

        loadAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void loadAsync() {
        ((TextView) findViewById(R.id.hint)).append("\n" + getString(R.string.loading));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Item> built = build();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        items.clear();
                        items.addAll(built);
                        adapter.notifyDataSetChanged();
                        ((TextView) findViewById(R.id.hint)).setText(
                                (owner ? getString(R.string.owner_active)
                                        : getString(R.string.no_owner_hint))
                                        + "\n" + items.size() + " apps preinstallees");
                    }
                });
            }
        }).start();
    }

    private List<Item> build() {
        Set<String> protect = new HashSet<>(PROTECT);
        protect.add(getPackageName());

        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            if (ri.activityInfo != null) protect.add(ri.activityInfo.packageName);
        }
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            for (InputMethodInfo imi : imm.getInputMethodList()) {
                protect.add(imi.getPackageName());
            }
        }

        List<Item> out = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            boolean system = (ai.flags
                    & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            if (!system) continue;
            if (protect.contains(ai.packageName)) continue;

            Item it = new Item();
            it.pkg = ai.packageName;
            CharSequence l = pm.getApplicationLabel(ai);
            it.label = l == null ? ai.packageName : l.toString();
            it.bloat = BLOAT.contains(ai.packageName);
            out.add(it);
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                if (a.bloat != b.bloat) return a.bloat ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    private boolean isDisabled(String pkg) {
        if (owner) {
            try {
                if (dpm.isApplicationHidden(admin, pkg)) return true;
            } catch (Exception ignored) {
            }
        }
        try {
            int s = pm.getApplicationEnabledSetting(pkg);
            return s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void toggle(Item it) {
        boolean disabled = isDisabled(it.pkg);

        if (owner) {
            try {
                dpm.setApplicationHidden(admin, it.pkg, !disabled);
                adapter.notifyDataSetChanged();
            } catch (Exception e) {
                toast("Echec : " + e.getMessage());
            }
            return;
        }

        try {
            pm.setApplicationEnabledSetting(
                    it.pkg,
                    disabled ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                    0);
            adapter.notifyDataSetChanged();
        } catch (SecurityException se) {
            openInfo(it.pkg);
        } catch (IllegalArgumentException iae) {
            toast("Paquet introuvable");
        }
    }

    /** Force l'arret des processus en arriere-plan du paquet (permission KILL_BACKGROUND_PROCESSES). */
    private void stop(Item it) {
        try {
            am.killBackgroundProcesses(it.pkg);
            toast(getString(R.string.stopped, it.label));
        } catch (SecurityException se) {
            // OEM a refuse la permission : on ouvre la fiche systeme (Forcer l'arret manuel).
            openInfo(it.pkg);
        }
        adapter.notifyDataSetChanged();
    }

    private void openInfo(String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_SETTINGS));
            } catch (Exception e2) {
                toast("Fiche systeme indisponible");
            }
        }
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(AppsActivity.this)
                        .inflate(R.layout.row_app, parent, false);
            }
            final Item it = items.get(position);
            TextView label = v.findViewById(R.id.label);
            TextView pkg = v.findViewById(R.id.pkg);
            TextView state = v.findViewById(R.id.state);
            Button stop = v.findViewById(R.id.stop);
            Button action = v.findViewById(R.id.action);

            label.setText(it.label);
            pkg.setText(it.bloat ? it.pkg + "  ·  bloat" : it.pkg);

            boolean disabled = isDisabled(it.pkg);
            if (disabled) {
                state.setText("desactivee");
                state.setTextColor(0xFFE57373);
                action.setText(R.string.enable);
            } else {
                state.setText("active");
                state.setTextColor(0xFF5DD39E);
                action.setText(owner ? getString(R.string.disable) : getString(R.string.disable));
            }

            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    toggle(it);
                }
            });
            stop.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    stop(it);
                }
            });
            return v;
        }
    }
}
