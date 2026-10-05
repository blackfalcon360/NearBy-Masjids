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

    private static final String[] DIR_EN = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final String[] DIR_AR = {"شمال", "شمال شرق", "شرق", "جنوب شرق", "جنوب", "جنوب غرب", "غرب", "شمال غرب"};

    static class Mosque {
        String name;
        double lat, lon;
        float dist, bearing;
    }

    private boolean arabic;
    private int state = S_IDLE;
    private int radius = 5000; // 5 km default
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

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(16), dp(18), dp(8));

        titleTv = new TextView(this);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(20);
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
            radius = radius < 10000 ? 10000 : 25000;
            generation++;
            state = S_FETCH;
            renderAll();
            fetchNearby(loc, radius, generation);
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
        creditTv.setText("By: Black Falcon 🦅");
        creditTv.setTextColor(Color.WHITE);
        creditTv.setTextSize(14);
        creditTv.setTypeface(Typeface.DEFAULT_BOLD);
        creditTv.setGravity(Gravity.RIGHT);
        creditTv.setPadding(dp(16), dp(8), dp(16), dp(10));

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
        titleTv.setText("🕌 " + t("Find a place to pray near you...", "ابحث عن مكان للصلاة بالقرب منك..."));
        subtitleTv.setText(t("Nearby Mosques, Jamia & Madrasas", "المساجد والجوامع والمدارس القريبة"));
        refreshBtn.setText("↻  " + t("Refresh location", "تحديث الموقع"));
        langBtn.setText(arabic ? "English" : "العربية");
        langBtn.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        String km = String.format(Locale.US, "%.0f", radius / 1000f);
        switch (state) {
            case S_LOC:
                statusTv.setText(t("Finding your location…", "جاري تحديد موقعك…")); break;
            case S_FETCH:
                statusTv.setText(t("Searching nearby mosques…", "جاري البحث عن المساجد القريبة…")); break;
            case S_DONE:
                if (mosques.isEmpty()) {
                    statusTv.setText(t("No mosques found within " + km + " km. Try searching wider.", "لم يتم العثور على مساجد ضمن " + km + " كم"));
                } else {
                    statusTv.setText(t(mosques.size() + " places found near you", mosques.size() + " مكان للصلاة بالقرب منك"));
                }
                break;
            case S_NONET:
                statusTv.setText(t("Check internet connection and refresh.", "تحقق من الاتصال بالإنترنت ثم حدّث.")); break;
            case S_NOPERM:
                statusTv.setText(t("Location permission is needed.", "مطلوب إذن الموقع.")); break;
            case S_NOLOC:
                statusTv.setText(t("Turn on GPS and refresh.", "شغّل الـ GPS ثم حدّث.")); break;
            default:
                statusTv.setText("");
        }

        widerBtn.setText(t("Search wider", "توسيع البحث"));
        widerBtn.setVisibility(state == S_DONE && radius < 25000 ? View.VISIBLE : View.GONE);

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
        icon.setText("🕌");
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
        name.setText(m.name);
        name.setTextColor(Color.WHITE);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);

        TextView sub = new TextView(this);
        sub.setText(t("Direction: ", "الاتجاه: ") + dirName(m.bearing) + "  •  " + t("Tap for Google Maps", "اضغط لفتح الخريطة"));
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
        dist.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable dg = new GradientDrawable();
        dg.setColor(GREEN);
        dg.setCornerRadius(dp(16));
        dist.setBackground(dg);
        c.addView(dist);

        c.setOnClickListener(v -> openInGoogleMaps(m));
        return c;
    }

    private String dirName(float bearing) {
        float b = (bearing % 360 + 360) % 360;
        int i = Math.round(b / 45f) % 8;
        return arabic ? DIR_AR[i] : DIR_EN[i];
    }

    private String fmtDist(float meters) {
        if (meters < 1000) return String.format(Locale.US, "%d %s", Math.round(meters), arabic ? "م" : "m");
        return String.format(Locale.US, "%.1f %s", meters / 1000f, arabic ? "كم" : "km");
    }

    private void openInGoogleMaps(Mosque m) {
        Uri mapsUri = Uri.parse("google.navigation:q=" + m.lat + "," + m.lon + "&mode=d");
        Intent mapIntent = new Intent(Intent.ACTION_VIEW, mapsUri);
        mapIntent.setPackage("com.google.android.apps.maps");

        if (mapIntent.resolveActivity(getPackageManager()) != null) {
            startActivity(mapIntent);
        } else {
            Uri webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=" + m.lat + "," + m.lon);
            startActivity(new Intent(Intent.ACTION_VIEW, webUri));
        }
    }

    private void startRefresh() {
        generation++;
        stopLocation();
        mosques.clear();
        loc = null;
        radius = 5000;

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
        fetchNearby(l, radius, generation);
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

    private void fetchNearby(final Location l, final int r, final int gen) {
        new Thread(() -> {
            try {
                String around = "(around:" + r + "," + l.getLatitude() + "," + l.getLongitude() + ")";
                
                // Very broad query to catch any worship building
                String q = "[out:json][timeout:25];("
                        + "node[\"amenity\"=\"place_of_worship\"]" + around + ";"
                        + "way[\"amenity\"=\"place_of_worship\"]" + around + ";"
                        + "node[\"building\"=\"mosque\"]" + around + ";"
                        + "way[\"building\"=\"mosque\"]" + around + ";"
                        + ");out center tags;";

                String[] servers = {
                        "https://overpass-api.de/api/interpreter",
                        "https://overpass.kumi.systems/api/interpreter",
                        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
                };

                String response = null;
                for (String srv : servers) {
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(srv).openConnection();
                        c.setRequestMethod("POST");
                        c.setConnectTimeout(12000);
                        c.setReadTimeout(18000);
                        c.setDoOutput(true);
                        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                        OutputStream os = c.getOutputStream();
                        os.write(("data=" + URLEncoder.encode(q, "UTF-8")).getBytes("UTF-8"));
                        os.close();

                        if (c.getResponseCode() == 200) {
                            InputStream in = c.getInputStream();
                            ByteArrayOutputStream bo = new ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                            in.close();
                            response = bo.toString("UTF-8");
                            break;
                        }
                    } catch (Exception ignored) { }
                }

                if (response != null) {
                    JSONArray elements = new JSONObject(response).getJSONArray("elements");
                    List<Mosque> res = new ArrayList<>();

                    for (int i = 0; i < elements.length(); i++) {
                        JSONObject e = elements.getJSONObject(i);
                        double la, lo;
                        if (e.has("lat")) { la = e.getDouble("lat"); lo = e.getDouble("lon"); }
                        else if (e.has("center")) { la = e.getJSONObject("center").getDouble("lat"); lo = e.getDouble("lon"); }
                        else continue;

                        Mosque m = new Mosque();
                        m.lat = la; m.lon = lo;

                        JSONObject tags = e.optJSONObject("tags");
                        if (tags != null) {
                            m.name = tags.optString("name", tags.optString("name:ar", tags.optString("name:en", "Masjid / Mosque")));
                        } else {
                            m.name = "Masjid / Mosque";
                        }

                        float[] results = new float[2];
                        Location.distanceBetween(l.getLatitude(), l.getLongitude(), m.lat, m.lon, results);
                        m.dist = results[0];
                        m.bearing = results[1];

                        res.add(m);
                    }

                    Collections.sort(res, (a, b) -> Float.compare(a.dist, b.dist));

                    runOnUiThread(() -> {
                        if (gen != generation || isFinishing()) return;
                        mosques.clear();
                        mosques.addAll(res);
                        state = S_DONE;
                        renderAll();
                    });
                } else {
                    throw new Exception("Error");
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (gen != generation || isFinishing()) return;
                    state = S_NONET;
                    renderAll();
                });
            }
        }).start();
    }
}
