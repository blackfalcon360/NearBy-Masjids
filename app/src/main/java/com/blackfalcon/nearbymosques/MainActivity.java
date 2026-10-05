package com.blackfalcon.nearbymosques;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements LocationListener {

    private static final int S_IDLE = 0, S_LOC = 1, S_FETCH = 2, S_DONE = 3, S_NONET = 4, S_NOPERM = 5, S_NOLOC = 6;
    private static final int GREEN = Color.parseColor("#2ECC71");
    private static final int CARD = Color.parseColor("#121212");
    private static final int BORDER = Color.parseColor("#2A2A2A");
    private static final int GRAY = Color.parseColor("#9E9E9E");
    private static final String[] ENDPOINTS = {
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
    };
    private static final String[] DIR_EN = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final String[] DIR_AR = {"\u0634\u0645\u0627\u0644", "\u0634\u0645\u0627\u0644 \u0634\u0631\u0642", "\u0634\u0631\u0642",
            "\u062C\u0646\u0648\u0628 \u0634\u0631\u0642", "\u062C\u0646\u0648\u0628", "\u062C\u0646\u0648\u0628 \u063A\u0631\u0628",
            "\u063A\u0631\u0628", "\u0634\u0645\u0627\u0644 \u063A\u0631\u0628"};

    static class Mosque {
        String name, nameAr, nameEn;
        double lat, lon;
        float dist, bearing;
    }

    private boolean arabic;
    private int state = S_IDLE;
    private int radius = 3000;
    private int generation = 0;
    private Location loc;
    private final List<Mosque> mosques = new ArrayList<>();

    private LocationManager lm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timeout;

    private LinearLayout root, listBox;
    private TextView titleTv, subtitleTv, refreshBtn, langBtn, statusTv, widerBtn, creditTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        SharedPreferences sp = getSharedPreferences("prefs", MODE_PRIVATE);
        arabic = sp.getBoolean("arabic", false);

        buildUi();
        renderAll();

        if (hasPerm()) {
            startRefresh();
        } else {
            state = S_NOPERM;
            renderAll();
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (state == S_LOC) startRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLocation();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (hasPerm()) startRefresh(); else { state = S_NOPERM; renderAll(); }
    }

    private boolean hasPerm() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private String t(String en, String ar) { return arabic ? ar : en; }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ------------------------------------------------------------------ UI
    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(16), dp(18), dp(8));

        titleTv = new TextView(this);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(26);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);

        subtitleTv = new TextView(this);
        subtitleTv.setTextColor(GREEN);
        subtitleTv.setTextSize(14);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(14), 0, 0);

        refreshBtn = pill();
        refreshBtn.setOnClickListener(v -> startRefresh());
        langBtn = pill();
        langBtn.setOnClickListener(v -> {
            arabic = !arabic;
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("arabic", arabic).apply();
            renderAll();
        });

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp1.setMarginEnd(dp(8));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp2.setMarginStart(dp(8));
        row.addView(refreshBtn, lp1);
        row.addView(langBtn, lp2);

        header.addView(titleTv);
        header.addView(subtitleTv);
        header.addView(row);

        statusTv = new TextView(this);
        statusTv.setTextColor(GRAY);
        statusTv.setTextSize(14);
        statusTv.setPadding(dp(20), dp(10), dp(20), dp(6));

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(dp(14), dp(4), dp(14), dp(14));

        widerBtn = pill();
        widerBtn.setOnClickListener(v -> {
            if (loc == null) return;
            radius = radius < 6000 ? 6000 : 12000;
            generation++;
            state = S_FETCH;
            renderAll();
            fetch(loc, radius, generation);
        });

        ScrollView sv = new ScrollView(this);
        LinearLayout scrollContent = new LinearLayout(this);
        scrollContent.setOrientation(LinearLayout.VERTICAL);
        scrollContent.addView(listBox);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wl.setMargins(dp(14), 0, dp(14), dp(16));
        scrollContent.addView(widerBtn, wl);
        sv.addView(scrollContent);

        creditTv = new TextView(this);
        creditTv.setText("By: Black Falcon \uD83E\uDD85");
        creditTv.setTextColor(Color.WHITE);
        creditTv.setTextSize(14);
        creditTv.setTypeface(Typeface.DEFAULT_BOLD);
        creditTv.setGravity(Gravity.RIGHT);
        creditTv.setPadding(dp(16), dp(8), dp(16), dp(10));
        creditTv.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        root.addView(header);
        root.addView(statusTv);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(creditTv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private TextView pill() {
        TextView b = new TextView(this);
        b.setTextColor(GREEN);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#0D1F14"));
        g.setStroke(dp(1), GREEN);
        g.setCornerRadius(dp(24));
        b.setBackground(g);
        b.setClickable(true);
        return b;
    }

    private void renderAll() {
        root.setLayoutDirection(arabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        titleTv.setText("\uD83D\uDD4C " + t("Nearby Mosques", "\u0627\u0644\u0645\u0633\u0627\u062C\u062F \u0627\u0644\u0642\u0631\u064A\u0628\u0629"));
        subtitleTv.setText(t("Find a place to pray near you", "\u0627\u0628\u062D\u062B \u0639\u0646 \u0645\u0643\u0627\u0646 \u0644\u0644\u0635\u0644\u0627\u0629 \u0628\u0627\u0644\u0642\u0631\u0628 \u0645\u0646\u0643"));
        refreshBtn.setText("\u21BB  " + t("Refresh location", "\u062A\u062D\u062F\u064A\u062B \u0627\u0644\u0645\u0648\u0642\u0639"));
        langBtn.setText(arabic ? "English" : "\u0627\u0644\u0639\u0631\u0628\u064A\u0629");
        langBtn.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        String km = String.format(Locale.US, "%.0f", radius / 1000f);
        switch (state) {
            case S_LOC:
                statusTv.setText(t("Finding your location\u2026", "\u062C\u0627\u0631\u064D \u062A\u062D\u062F\u064A\u062F \u0645\u0648\u0642\u0639\u0643\u2026")); break;
            case S_FETCH:
                statusTv.setText(t("Searching for nearby mosques\u2026", "\u062C\u0627\u0631\u064D \u0627\u0644\u0628\u062D\u062B \u0639\u0646 \u0627\u0644\u0645\u0633\u0627\u062C\u062F \u0627\u0644\u0642\u0631\u064A\u0628\u0629\u2026")); break;
            case S_DONE:
                if (mosques.isEmpty()) {
                    statusTv.setText(t("No mosques found within " + km + " km", "\u0644\u0645 \u064A\u062A\u0645 \u0627\u0644\u0639\u062B\u0648\u0631 \u0639\u0644\u0649 \u0645\u0633\u0627\u062C\u062F \u0636\u0645\u0646 " + km + " \u0643\u0645"));
                } else {
                    statusTv.setText(t(mosques.size() + " mosques within " + km + " km",
                            mosques.size() + " \u0645\u0633\u062C\u062F \u0636\u0645\u0646 " + km + " \u0643\u0645"));
                }
                break;
            case S_NONET:
                statusTv.setText(t("Couldn't load mosques. Check your internet and refresh.",
                        "\u062A\u0639\u0630\u0651\u0631 \u062A\u062D\u0645\u064A\u0644 \u0627\u0644\u0645\u0633\u0627\u062C\u062F. \u062A\u062D\u0642\u0642 \u0645\u0646 \u0627\u0644\u0625\u0646\u062A\u0631\u0646\u062A \u062B\u0645 \u062D\u062F\u0651\u062B.")); break;
            case S_NOPERM:
                statusTv.setText(t("Location permission is needed to find mosques near you.",
                        "\u0645\u0637\u0644\u0648\u0628 \u0625\u0630\u0646 \u0627\u0644\u0645\u0648\u0642\u0639 \u0644\u0644\u0639\u062B\u0648\u0631 \u0639\u0644\u0649 \u0627\u0644\u0645\u0633\u0627\u062C\u062F \u0627\u0644\u0642\u0631\u064A\u0628\u0629 \u0645\u0646\u0643.")); break;
            case S_NOLOC:
                statusTv.setText(t("Couldn't get your location. Turn on GPS and refresh.",
                        "\u062A\u0639\u0630\u0651\u0631 \u062A\u062D\u062F\u064A\u062F \u0645\u0648\u0642\u0639\u0643. \u0634\u063A\u0651\u0644 \u0627\u0644\u0640 GPS \u062B\u0645 \u062D\u062F\u0651\u062B.")); break;
            default:
                statusTv.setText("");
        }

        widerBtn.setText(t("Search wider", "\u062A\u0648\u0633\u064A\u0639 \u0627\u0644\u0628\u062D\u062B"));
        widerBtn.setVisibility(state == S_DONE && radius < 12000 ? View.VISIBLE : View.GONE);

        listBox.removeAllViews();
        if (state == S_DONE) {
            for (Mosque m : mosques) listBox.addView(card(m));
        }
    }

    private View card(final Mosque m) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setStroke(dp(1), BORDER);
        g.setCornerRadius(dp(18));
        c.setBackground(g);
        c.setClickable(true);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(6), 0, dp(6));
        c.setLayoutParams(clp);

        TextView icon = new TextView(this);
        icon.setText("\uD83D\uDD4C");
        icon.setTextSize(28);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable ig = new GradientDrawable();
        ig.setShape(GradientDrawable.OVAL);
        ig.setColor(Color.parseColor("#0D1F14"));
        icon.setBackground(ig);
        c.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        colp.setMarginStart(dp(12));
        colp.setMarginEnd(dp(8));

        TextView name = new TextView(this);
        name.setText(displayName(m));
        name.setTextColor(Color.WHITE);
        name.setTextSize(17);
        name.setTypeface(Typeface.DEFAULT_BOLD);

        TextView sub = new TextView(this);
        sub.setText(t("Direction: ", "\u0627\u0644\u0627\u062A\u062C\u0627\u0647: ") + dirName(m.bearing)
                + "  \u2022  " + t("Tap for directions", "\u0627\u0636\u063A\u0637 \u0644\u0644\u0645\u0633\u0627\u0631"));
        sub.setTextColor(GRAY);
        sub.setTextSize(13);

        col.addView(name);
        col.addView(sub);
        c.addView(col, colp);

        TextView dist = new TextView(this);
        dist.setText(fmtDist(m.dist));
        dist.setTextColor(Color.BLACK);
        dist.setTextSize(14);
        dist.setTypeface(Typeface.DEFAULT_BOLD);
        dist.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable dg = new GradientDrawable();
        dg.setColor(GREEN);
        dg.setCornerRadius(dp(16));
        dist.setBackground(dg);
        dist.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        c.addView(dist);

        c.setOnClickListener(v -> openMap(m));
        return c;
    }

    private String displayName(Mosque m) {
        String n;
        if (arabic) n = first(m.nameAr, m.name, m.nameEn);
        else n = first(m.nameEn, m.name, m.nameAr);
        return n != null ? n : t("Mosque", "\u0645\u0633\u062C\u062F");
    }

    private static String first(String... s) {
        for (String x : s) if (x != null && !x.trim().isEmpty()) return x.trim();
        return null;
    }

    private String dirName(float bearing) {
        float b = (bearing % 360 + 360) % 360;
        int i = Math.round(b / 45f) % 8;
        return arabic ? DIR_AR[i] : DIR_EN[i];
    }

    private String fmtDist(float meters) {
        if (meters < 1000) return String.format(Locale.US, "%d %s", Math.round(meters), arabic ? "\u0645" : "m");
        return String.format(Locale.US, "%.1f %s", meters / 1000f, arabic ? "\u0643\u0645" : "km");
    }

    private void openMap(Mosque m) {
        String label = Uri.encode(displayName(m));
        Uri geo = Uri.parse("geo:" + m.lat + "," + m.lon + "?q=" + m.lat + "," + m.lon + "(" + label + ")");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, geo));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.openstreetmap.org/?mlat=" + m.lat + "&mlon=" + m.lon + "#map=17/" + m.lat + "/" + m.lon)));
            } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------ Location
    private void startRefresh() {
        generation++;
        stopLocation();
        mosques.clear();
        loc = null;
        radius = 3000;

        if (!hasPerm()) {
            state = S_NOPERM;
            renderAll();
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            }
            return;
        }

        state = S_LOC;
        renderAll();

        boolean any = false;
        try {
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 0, 0, this);
                    any = true;
                }
            }
        } catch (SecurityException e) {
            any = false;
        }
        if (!any) {
            Location last = lastKnown();
            if (last != null) { gotFix(last); } else { state = S_NOLOC; renderAll(); }
            return;
        }

        timeout = () -> {
            if (state != S_LOC) return;
            Location last = lastKnown();
            stopLocation();
            if (last != null) gotFix(last); else { state = S_NOLOC; renderAll(); }
        };
        handler.postDelayed(timeout, 15000);
    }

    private Location lastKnown() {
        Location best = null;
        try {
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) { }
        return best;
    }

    private void stopLocation() {
        if (timeout != null) { handler.removeCallbacks(timeout); timeout = null; }
        try { lm.removeUpdates(this); } catch (SecurityException ignored) { }
    }

    private void gotFix(Location l) {
        loc = l;
        state = S_FETCH;
        renderAll();
        fetch(l, radius, generation);
    }

    @Override
    public void onLocationChanged(Location l) {
        if (state != S_LOC) return;
        stopLocation();
        gotFix(l);
    }

    @Override public void onStatusChanged(String p, int s, Bundle e) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }

    // --------------------------------------------------------------- Fetch
    private void fetch(final Location l, final int r, final int gen) {
        new Thread(() -> {
            try {
                String json = query(l, r);
                final List<Mosque> res = parse(json, l);
                runOnUiThread(() -> {
                    if (gen != generation || isFinishing()) return;
                    mosques.clear();
                    mosques.addAll(res);
                    state = S_DONE;
                    renderAll();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (gen != generation || isFinishing()) return;
                    state = S_NONET;
                    renderAll();
                });
            }
        }).start();
    }

    private String query(Location l, int r) throws Exception {
        String around = "(around:" + r + "," + l.getLatitude() + "," + l.getLongitude() + ")";
        String q = "[out:json][timeout:25];("
                + "node[\"amenity\"=\"place_of_worship\"][\"religion\"=\"muslim\"]" + around + ";"
                + "way[\"amenity\"=\"place_of_worship\"][\"religion\"=\"muslim\"]" + around + ";"
                + "node[\"building\"=\"mosque\"]" + around + ";"
                + "way[\"building\"=\"mosque\"]" + around + ";"
                + ");out center tags;";
        Exception last = null;
        for (String ep : ENDPOINTS) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(ep).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setDoOutput(true);
                c.setRequestProperty("User-Agent", "NearbyMosques/1.0 (Android)");
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                OutputStream os = c.getOutputStream();
                os.write(("data=" + URLEncoder.encode(q, "UTF-8")).getBytes("UTF-8"));
                os.close();
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                return bo.toString("UTF-8");
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("fail");
    }

    private List<Mosque> parse(String json, Location me) throws Exception {
        JSONArray els = new JSONObject(json).getJSONArray("elements");
        List<Mosque> out = new ArrayList<>();
        for (int i = 0; i < els.length(); i++) {
            JSONObject e = els.getJSONObject(i);
            double la, lo;
            if (e.has("lat")) { la = e.getDouble("lat"); lo = e.getDouble("lon"); }
            else if (e.has("center")) { la = e.getJSONObject("center").getDouble("lat"); lo = e.getJSONObject("center").getDouble("lon"); }
            else continue;

            Mosque m = new Mosque();
            m.lat = la; m.lon = lo;
            JSONObject tags = e.optJSONObject("tags");
            if (tags != null) {
                m.name = tags.has("name") ? tags.optString("name") : null;
                m.nameAr = tags.has("name:ar") ? tags.optString("name:ar") : null;
                m.nameEn = tags.has("name:en") ? tags.optString("name:en") : null;
            }
            float[] res = new float[2];
            Location.distanceBetween(me.getLatitude(), me.getLongitude(), la, lo, res);
            m.dist = res[0];
            m.bearing = res[1];

            boolean dup = false;
            for (Mosque o : out) {
                float[] d = new float[1];
                Location.distanceBetween(o.lat, o.lon, la, lo, d);
                if (d[0] < 40) {
                    dup = true;
                    if (o.name == null && m.name != null) { o.name = m.name; o.nameAr = m.nameAr; o.nameEn = m.nameEn; }
                    break;
                }
            }
            if (!dup) out.add(m);
        }
        Collections.sort(out, (a, b) -> Float.compare(a.dist, b.dist));
        return out;
    }
}
