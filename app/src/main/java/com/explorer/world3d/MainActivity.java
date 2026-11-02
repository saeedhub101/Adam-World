package com.explorer.world3d;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity implements CityRenderer.Listener {
    private static final int REQ_LOC = 77;
    private static final String[] TIME_ICONS = {"☀️", "🌇", "🌙"};
    // جميع نقاط البداية موضوعة على شوارع/تقاطعـات عامة، لا داخل معالم أو مبانٍ.
    private static final Object[][] CITIES = {
            {"عمّان أونلاين", 31.9522646, 35.9389907},       // شارع الهاشمي قرب الساحة الهاشمية
            {"مانيلا", 14.57394, 120.98283},          // تقاطع Pedro Gil St وA. Mabini St
            {"بانكوك", 13.745624, 100.53422},         // Rama I Road
            {"نيويورك", 40.7573398, -73.986011},      // 7th Avenue وW 44th Street
            {"باريس", 48.86022, 2.29436},             // Promenade du Quai Branly
            {"دبي", 25.2012013, 55.2778617},          // Sheikh Mohammed bin Rashid Boulevard
            {"طوكيو", 35.659495, 139.700498},         // Shibuya Scramble Crossing
            {"لندن", 51.500948, -0.124857},           // Bridge Street, Westminster
            {"الرياض", 24.6821, 46.687219},           // King Fahd Road
            {"القاهرة", 30.05056, 31.24051},        // Talaat Harb Street
            {"البيت", 31.997579, 35.940358}};         // صيدلية كوبرا، طبربور

    private CityRenderer renderer;
    private CityGLView glView;
    private TextView loading, hud;
    private EditText query;
    private Button timeBtn;
    private boolean keyUp, keyDown, keyLeft, keyRight;
    private final String[] CAMERA_LABELS = {"شارع", "قريب", "مرتفع", "درون"};
    private final String[] CAMERA_PRESETS = {"street", "near", "high", "drone"};

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private void updateRemoteMovement() {
        renderer.game.sx = (keyRight ? 1f : 0f) - (keyLeft ? 1f : 0f);
        renderer.game.sy = (keyUp ? 1f : 0f) - (keyDown ? 1f : 0f);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_W) {
            keyUp = down; updateRemoteMovement(); return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_S) {
            keyDown = down; updateRemoteMovement(); return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_A) {
            keyLeft = down; updateRemoteMovement(); return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_D) {
            keyRight = down; updateRemoteMovement(); return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
                glView.queueEvent(new Runnable() {
                    @Override public void run() { renderer.toggleCar(); }
                });
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_SHIFT_LEFT) {
            renderer.game.sprint = down;
            return true;
        }
        return super.dispatchKeyEvent(event);
    }


    private Button btn(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackgroundColor(0xC70F0F19);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        return b;
    }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        renderer = new CityRenderer(this, this);
        glView = new CityGLView(this, renderer);
        root.addView(glView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // الشريط العلوي
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        query = new EditText(this);
        query.setHint("ابحث عن مدينة أو مكان…");
        query.setSingleLine(true);
        query.setTextSize(14);
        query.setTextColor(Color.BLACK);
        query.setHintTextColor(0xff777777);
        query.setBackgroundColor(0xF2FFFFFF);
        query.setPadding(dp(10), 0, dp(10), 0);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int a, android.view.KeyEvent e) { search(); return true; }
        });
        row.addView(query, new LinearLayout.LayoutParams(0, dp(40), 1f));
        Button go = btn("بحث");
        go.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { search(); } });
        row.addView(go);
        Button loc = btn("📍");
        loc.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { requestLocation(); } });
        row.addView(loc);
        Button update = btn("تحديث");
        update.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { UpdateManager.check(MainActivity.this, true); }
        });
        row.addView(update);
        timeBtn = btn(TIME_ICONS[0]);
        timeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                renderer.timeIdx = (renderer.timeIdx + 1) % TIME_ICONS.length;
                timeBtn.setText(TIME_ICONS[renderer.timeIdx]);
            }
        });
        row.addView(timeBtn);
        top.addView(row);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        Button localAmman = btn("عمّان محلي");
        localAmman.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { teleportOfflineAmman(); }
        });
        chips.addView(localAmman);
        Button localManila = btn("مانيلا محلي");
        localManila.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { teleportOfflineManila(); }
        });
        chips.addView(localManila);
        for (final Object[] c : CITIES) {
            Button b = btn((String) c[0]);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { teleport((Double) c[1], (Double) c[2]); }
            });
            chips.addView(b);
        }
        hs.addView(chips);
        top.addView(hs);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        tp.setMargins(dp(8), dp(8), dp(8), 0);
        root.addView(top, tp);

        // اختيار منظور الكاميرا والتقريب/الإبعاد
        HorizontalScrollView cameraScroll = new HorizontalScrollView(this);
        cameraScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout cameraBar = new LinearLayout(this);
        cameraBar.setOrientation(LinearLayout.HORIZONTAL);
        cameraBar.setPadding(dp(5), dp(4), dp(5), dp(4));
        cameraBar.setBackgroundColor(0xB80A101A);
        for (int i = 0; i < CAMERA_PRESETS.length; i++) {
            final String preset = CAMERA_PRESETS[i];
            Button cameraButton = btn(CAMERA_LABELS[i]);
            cameraButton.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    glView.queueEvent(new Runnable() {
                        @Override public void run() { renderer.game.setCameraPreset(preset); }
                    });
                }
            });
            cameraBar.addView(cameraButton);
        }
        Button zoomOut = btn("−");
        zoomOut.setTextSize(22);
        zoomOut.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                glView.queueEvent(new Runnable() {
                    @Override public void run() { renderer.game.zoom(1.28f); }
                });
            }
        });
        cameraBar.addView(zoomOut);
        Button zoomIn = btn("+");
        zoomIn.setTextSize(22);
        zoomIn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                glView.queueEvent(new Runnable() {
                    @Override public void run() { renderer.game.zoom(0.78f); }
                });
            }
        });
        cameraBar.addView(zoomIn);
        Button basement = btn("كشف تحت الأرض");
        basement.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                glView.queueEvent(new Runnable() {
                    @Override public void run() {
                        renderer.toggleUndergroundCutaway();
                        final boolean enabled = renderer.undergroundCutaway;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                basement.setText(enabled ? "إظهار الأرض" : "كشف تحت الأرض");
                                Toast.makeText(MainActivity.this, enabled
                                        ? "وضع كشف الطوابق تحت الأرض" : "تمت إعادة إظهار التضاريس", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                });
            }
        });
        cameraBar.addView(basement);
        cameraScroll.addView(cameraBar);
        FrameLayout.LayoutParams cameraParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cameraParams.setMargins(dp(150), 0, dp(90), dp(18));
        root.addView(cameraScroll, cameraParams);

        // العصا
        JoystickView js = new JoystickView(this);
        js.setListener(new JoystickView.Listener() {
            @Override public void onMove(float x, float y) { renderer.game.sx = x; renderer.game.sy = y; }
        });
        FrameLayout.LayoutParams jp = new FrameLayout.LayoutParams(dp(150), dp(150), Gravity.BOTTOM | Gravity.START);
        jp.setMargins(dp(20), 0, 0, dp(30));
        root.addView(js, jp);

        // أزرار الفعل
        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.VERTICAL);
        acts.setGravity(Gravity.CENTER_HORIZONTAL);
        Button sprint = btn("🏃");
        sprint.setTextSize(26);
        sprint.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) renderer.game.sprint = true;
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) renderer.game.sprint = false;
                return true;
            }
        });
        Button car = btn("🚗");
        car.setTextSize(26);
        car.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                glView.queueEvent(new Runnable() { @Override public void run() { renderer.toggleCar(); } });
            }
        });
        LinearLayout.LayoutParams ab = new LinearLayout.LayoutParams(dp(72), dp(72));
        ab.setMargins(0, dp(10), 0, 0);
        acts.addView(sprint, ab);
        acts.addView(car, ab);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        ap.setMargins(0, 0, dp(20), dp(30));
        root.addView(acts, ap);

        hud = new TextView(this);
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(20);
        hud.setShadowLayer(4, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        hp.setMargins(0, 0, dp(24), dp(190));
        root.addView(hud, hp);

        TextView credit = new TextView(this);
        credit.setText("© مساهمو OpenStreetMap · الارتفاعات: Mapzen/AWS Terrain Tiles");
        credit.setTextColor(0xc0ffffff);
        credit.setTextSize(10);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cp.setMargins(0, 0, 0, dp(4));
        root.addView(credit, cp);

        loading = new TextView(this);
        loading.setText("جاري تحميل المدينة…");
        loading.setTextColor(Color.WHITE);
        loading.setTextSize(18);
        loading.setGravity(Gravity.CENTER);
        loading.setBackgroundColor(0xE00A0C14);
        root.addView(loading, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        glView.requestFocus();
        UpdateManager.check(this, false);
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override protected void onResume() { super.onResume(); glView.onResume(); UpdateManager.resumePendingInstall(this); }
    @Override protected void onPause() { glView.onPause(); super.onPause(); }
    @Override protected void onDestroy() { renderer.shutdown(); super.onDestroy(); }

    private void teleport(final double lat, final double lon) {
        runOnUiThread(new Runnable() { @Override public void run() { loading.setVisibility(View.VISIBLE); } });
        glView.queueEvent(new Runnable() { @Override public void run() { renderer.teleport(lat, lon); } });
    }

    private void teleportOfflineManila() {
        runOnUiThread(new Runnable() { @Override public void run() {
            loading.setText("جاري تحميل بيانات مانيلا المحلية…");
            loading.setVisibility(View.VISIBLE);
        }});
        glView.queueEvent(new Runnable() { @Override public void run() { renderer.teleportOfflineManila(); }});
    }

    private void teleportOfflineAmman() {
        runOnUiThread(new Runnable() { @Override public void run() {
            loading.setText("جاري تحميل بيانات عمّان المحلية…");
            loading.setVisibility(View.VISIBLE);
        }});
        glView.queueEvent(new Runnable() { @Override public void run() { renderer.teleportOfflineAmman(); }});
    }

    private void search() {
        final String q = query.getText().toString().trim();
        if (q.isEmpty()) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String r = TileLoader.httpGet("https://nominatim.openstreetmap.org/search?format=json&limit=1&accept-language=ar&q="
                            + java.net.URLEncoder.encode(q, "UTF-8"));
                    JSONArray a = new JSONArray(r);
                    if (a.length() == 0) { onToast("لم يتم العثور على المكان"); return; }
                    JSONObject o = a.getJSONObject(0);
                    teleport(Double.parseDouble(o.getString("lat")), Double.parseDouble(o.getString("lon")));
                } catch (Exception e) { onToast("تعذر البحث - تحقق من الإنترنت"); }
            }
        }).start();
    }

    /* ---------- الموقع ---------- */
    private void requestLocation() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            return;
        }
        final LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getAccuracy() < best.getAccuracy())) best = l;
            }
            if (best != null) { teleport(best.getLatitude(), best.getLongitude()); return; }
            String provider = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
            lm.requestSingleUpdate(provider, new LocationListener() {
                @Override public void onLocationChanged(Location l) { teleport(l.getLatitude(), l.getLongitude()); }
                @Override public void onStatusChanged(String p, int st, Bundle e) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            }, null);
        } catch (SecurityException | IllegalArgumentException e) {
            onToast("تعذر تحديد الموقع");
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_LOC && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) requestLocation();
        else onToast("تعذر تحديد الموقع");
    }

    /* ---------- CityRenderer.Listener ---------- */
    @Override public void onLoading(final boolean show, final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                loading.setText(msg);
                loading.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        });
    }

    @Override public void onHud(final String s) {
        runOnUiThread(new Runnable() { @Override public void run() { hud.setText(s); } });
    }

    @Override public void onToast(final String s) {
        runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show(); } });
    }
}
