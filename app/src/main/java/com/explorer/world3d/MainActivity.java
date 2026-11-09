package com.explorer.world3d;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
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
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.media.AudioManager;
import android.media.ToneGenerator;

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
            {"القاهرة", 30.05056, 31.24051}};        // Talaat Harb Street

    private CityRenderer renderer;
    private CityGLView glView;
    private TextView loading, hud;
    private EditText query;
    private Button timeBtn;
    private boolean keyUp, keyDown, keyLeft, keyRight;
    private int gamepadCameraPreset;
    private AlertDialog pauseDialog;
    private ToneGenerator hornTone;
    private boolean padL2, padR2;
    private TextView healthHud;
    private MiniMapView miniMap;
    private long lastHudRefresh;
    private final String[] CAMERA_LABELS = {"شارع", "قريب", "مرتفع", "درون"};
    private final String[] CAMERA_PRESETS = {"street", "near", "high", "drone"};

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private void updateRemoteMovement() {
        if (renderer == null) return;
        renderer.game.sx = (keyRight ? 1f : 0f) - (keyLeft ? 1f : 0f);
        renderer.game.sy = (keyUp ? 1f : 0f) - (keyDown ? 1f : 0f);
    }

    private void runOnGl(final Runnable action) { if (glView != null) glView.queueEvent(action); }
    private void setGamePaused(final boolean paused) {
        runOnGl(new Runnable() { @Override public void run() { renderer.game.paused = paused; } });
    }
    private void cycleGameCamera() {
        gamepadCameraPreset = (gamepadCameraPreset + 1) % CAMERA_PRESETS.length;
        final String preset = CAMERA_PRESETS[gamepadCameraPreset];
        runOnGl(new Runnable() { @Override public void run() { renderer.game.setCameraPreset(preset); } });
    }
    private void attackOrHeal() {
        if (renderer.game.driving) { playHorn(); return; }
        runOnGl(new Runnable() { @Override public void run() { renderer.game.attackRequested = true; } });
    }
    private void showGameSettings() {
        final String[] options = {"الصوت", "جودة الرسومات", "حساسية الكاميرا", "إعدادات التحكم", "إغلاق"};
        new AlertDialog.Builder(this).setTitle("إعدادات Adam World").setItems(options, (d, which) -> {
            if (which == 0) new AlertDialog.Builder(this).setTitle("الصوت").setItems(new String[]{"تشغيل الصوت", "كتم الصوت"}, (x,v) -> Toast.makeText(this, v==0?"تم تشغيل الصوت":"تم كتم الصوت", Toast.LENGTH_SHORT).show()).show();
            else if (which == 1) new AlertDialog.Builder(this).setTitle("جودة الرسومات").setItems(new String[]{"تلقائي", "عالية", "متوازنة", "موفرة للطاقة"}, (x,v) -> Toast.makeText(this,"سيتم تطبيق إعداد الجودة في إصدار إعدادات الرسومات",Toast.LENGTH_SHORT).show()).show();
            else if (which == 2) new AlertDialog.Builder(this).setTitle("حساسية الكاميرا").setItems(new String[]{"منخفضة", "متوسطة", "عالية"}, (x,v) -> renderer.setLookSensitivity(v==0?0.65f:v==1?1f:1.45f)).show();
            else if (which == 3) showControlsSettings();
        }).show();
    }
    private void playHorn() {
        try {
            if (hornTone == null) hornTone = new ToneGenerator(AudioManager.STREAM_MUSIC, 75);
            hornTone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 280);
        } catch (Exception ignored) { }
    }
    private void showMapDialog(final boolean returnToPauseMenu) {
        double lat = renderer.game.driving ? Geo.latOf(renderer.game.cz) : Geo.latOf(renderer.game.pz);
        double lon = renderer.game.driving ? Geo.lonOf(renderer.game.cx) : Geo.lonOf(renderer.game.px);
        double west = lon - 0.006, east = lon + 0.006, south = lat - 0.004, north = lat + 0.004;
        String mapUrl = "https://www.openstreetmap.org/export/embed.html?bbox=" + west + "%2C" + south + "%2C" + east + "%2C" + north
                + "&layer=mapnik&marker=" + lat + "%2C" + lon;
        WebView map = new WebView(this);
        map.setBackgroundColor(Color.WHITE);
        WebSettings settings = map.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setDomStorageEnabled(false);
        map.loadDataWithBaseURL("https://www.openstreetmap.org/",
                "<html><body style='margin:0'><iframe width='100%' height='100%' frameborder='0' scrolling='no' src='" + mapUrl
                        + "'></iframe></body></html>", "text/html", "UTF-8", null);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("خريطة Adam World").setView(map)
                .setNegativeButton("إغلاق", null).create();
        dialog.setOnDismissListener(d -> { map.destroy(); if (returnToPauseMenu && !isFinishing()) showPauseMenu(); });
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, dp(560));
    }
    private void showControlsSettings() {
        String controls = "التحكم المعتمد — نمط GTA V\n\n"
                + "المشي: العصا اليسرى للحركة، L3 للجري، A للقفز، العصا اليمنى للكاميرا.\n"
                + "السيارة: L2 تسارع للأمام، R2 فرامل/رجوع، العصا اليسرى للتوجيه، A فرامل اليد، B البوق.\n"
                + "LB/RB: النظر جانبياً أثناء الضغط. R3: النظر للخلف. X: تغيير منظور الكاميرا.\n"
                + "Y: ركوب/خروج. View/SELECT: الخريطة. Menu/START: الإيقاف المؤقت.";
        new AlertDialog.Builder(this).setTitle("إعدادات التحكم").setMessage(controls).setPositiveButton("إغلاق", null).show();
    }
    private void showPauseMenu() {
        if (isFinishing() || (pauseDialog != null && pauseDialog.isShowing())) return;
        setGamePaused(true);
        final String[] items = {"متابعة اللعبة", "الخريطة", "إعدادات اللعبة", "إنهاء اللعبة"};
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("إيقاف مؤقت").setItems(items, (d, which) -> {
            if (which == 0) setGamePaused(false);
            else if (which == 1) { d.dismiss(); showMapDialog(true); }
            else if (which == 2) showGameSettings();
            else if (which == 3) { setGamePaused(false); finish(); }
        }).create();
        dialog.setOnCancelListener(d -> setGamePaused(false));
        pauseDialog = dialog;
        dialog.show();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean pressed = down && event.getRepeatCount() == 0;
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_W) { keyUp = down; updateRemoteMovement(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_S) { keyDown = down; updateRemoteMovement(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_A) { keyLeft = down; updateRemoteMovement(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_D) { keyRight = down; updateRemoteMovement(); return true; }
        if (renderer == null) return super.dispatchKeyEvent(event);
        if (code == KeyEvent.KEYCODE_BUTTON_A) {
            if (renderer.game.driving) renderer.game.handbrake = down;
            else if (pressed) runOnGl(new Runnable() { @Override public void run() { renderer.game.jumpRequested = true; } });
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_B) { if (pressed) attackOrHeal(); return true; }
        if (code == KeyEvent.KEYCODE_BUTTON_X) { if (pressed) cycleGameCamera(); return true; }
        if (code == KeyEvent.KEYCODE_BUTTON_Y) {
            if (pressed) runOnGl(new Runnable() { @Override public void run() { renderer.toggleCar(); } });
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_L1) { renderer.game.cameraLookOffset = down ? -0.95f : 0f; return true; }
        if (code == KeyEvent.KEYCODE_BUTTON_R1) { renderer.game.cameraLookOffset = down ? 0.95f : 0f; return true; }
        if (code == KeyEvent.KEYCODE_BUTTON_L2 || code == KeyEvent.KEYCODE_BUTTON_R2) {
            padL2 = code == KeyEvent.KEYCODE_BUTTON_L2 ? down : padL2;
            padR2 = code == KeyEvent.KEYCODE_BUTTON_R2 ? down : padR2;
            if (renderer.game.driving) renderer.game.sy = (padL2 ? 1f : 0f) - (padR2 ? 1f : 0f);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_THUMBL) { renderer.game.sprint = down; return true; }
        if (code == KeyEvent.KEYCODE_BUTTON_THUMBR) { renderer.game.cameraLookOffset = down ? (float)Math.PI : 0f; return true; }
        if (code == KeyEvent.KEYCODE_BUTTON_SELECT) { if (pressed) showMapDialog(false); return true; }
        if (code == KeyEvent.KEYCODE_BUTTON_START || code == KeyEvent.KEYCODE_BUTTON_MODE) { if (pressed) showPauseMenu(); return true; }
        if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (pressed) runOnGl(new Runnable() { @Override public void run() { renderer.toggleCar(); } });
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed() {
        if (pauseDialog != null && pauseDialog.isShowing()) { pauseDialog.dismiss(); setGamePaused(false); }
        else showPauseMenu();
    }

    private final class MiniMapView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        MiniMapView(Context c) { super(c); setLayerType(View.LAYER_TYPE_SOFTWARE,null); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w=getWidth(), h=getHeight(), cx=w/2f, cy=h/2f, rad=Math.min(w,h)*.47f;
            p.setColor(0xd91a2028); c.drawCircle(cx,cy,rad,p);
            c.save(); android.graphics.Path clip=new android.graphics.Path(); clip.addCircle(cx,cy,rad,android.graphics.Path.Direction.CW); c.clipPath(clip);
            p.setColor(0xff56616b); p.setStrokeWidth(dp(5)); p.setStyle(Paint.Style.STROKE);
            for(int i=-2;i<=2;i++){ c.drawLine(cx+i*dp(27),cy-rad,cx+i*dp(27),cy+rad,p); c.drawLine(cx-rad,cy+i*dp(27),cx+rad,cy+i*dp(27),p); }
            p.setColor(0xffd4d9df); p.setStrokeWidth(dp(1.4f)); c.drawLine(cx,cy-rad*.72f,cx,cy-rad*.9f,p); p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE); p.setTextSize(dp(10)); c.drawText("N",cx-dp(3),cy-rad*.76f,p);
            c.restore();
            p.setColor(0xff38d978); android.graphics.Path arrow=new android.graphics.Path();
            arrow.moveTo(cx,cy-dp(12)); arrow.lineTo(cx-dp(8),cy+dp(8)); arrow.lineTo(cx,cy+dp(4)); arrow.lineTo(cx+dp(8),cy+dp(8)); arrow.close();
            c.save(); c.rotate((float)Math.toDegrees(renderer.game.driving?renderer.game.cyaw:renderer.game.pyaw),cx,cy); c.drawPath(arrow,p); c.restore();
            p.setColor(Color.WHITE); p.setTextSize(dp(9)); c.drawText("MAP",cx-dp(11),h-dp(7),p);
        }
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
        hornTone = new ToneGenerator(AudioManager.STREAM_MUSIC, 75);
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
        Button localHome = btn("البيت");
        localHome.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { teleportOfflineTabarbour(); }
        });
        chips.addView(localHome);
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
        Button liftUp = btn("⬆");
        liftUp.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                int a=e.getActionMasked();
                if(a==MotionEvent.ACTION_DOWN) renderer.game.liftInput=1f;
                else if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL) renderer.game.liftInput=0f;
                return true;
            }
        });
        Button liftDown = btn("⬇");
        liftDown.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                int a=e.getActionMasked();
                if(a==MotionEvent.ACTION_DOWN) renderer.game.liftInput=-1f;
                else if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL) renderer.game.liftInput=0f;
                return true;
            }
        });
        Button vehicleType = btn("نوع المركبة");
        vehicleType.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                glView.queueEvent(new Runnable() { @Override public void run() { renderer.cycleVehicleType(); } });
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
        acts.addView(vehicleType, new LinearLayout.LayoutParams(dp(96), dp(48)));
        acts.addView(liftUp, new LinearLayout.LayoutParams(dp(72), dp(44)));
        acts.addView(liftDown, new LinearLayout.LayoutParams(dp(72), dp(44)));
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

        healthHud = new TextView(this);
        healthHud.setTextColor(0xff65e58b);
        healthHud.setTextSize(16);
        healthHud.setShadowLayer(4,0,0,Color.BLACK);
        healthHud.setText("♥ 100 / 100");
        FrameLayout.LayoutParams healthParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        healthParams.setMargins(0, dp(112), dp(18), 0);
        root.addView(healthHud, healthParams);

        miniMap = new MiniMapView(this);
        FrameLayout.LayoutParams miniParams = new FrameLayout.LayoutParams(dp(142), dp(142), Gravity.TOP | Gravity.START);
        miniParams.setMargins(dp(14), dp(112), 0, 0);
        root.addView(miniMap, miniParams);


        Button settings = btn("⚙");
        settings.setOnClickListener(v -> showGameSettings());
        FrameLayout.LayoutParams settingsParams = new FrameLayout.LayoutParams(dp(48),dp(44),Gravity.TOP | Gravity.END);
        settingsParams.setMargins(0,dp(8),dp(8),0);
        root.addView(settings,settingsParams);

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
        loading.setPadding(dp(18), dp(9), dp(18), dp(9));
        loading.setClickable(false);
        loading.setFocusable(false);
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        loadingParams.setMargins(dp(8), dp(104), dp(8), 0);
        root.addView(loading, loadingParams);

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

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (renderer != null && glView != null
                && level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            glView.queueEvent(new Runnable() {
                @Override public void run() { renderer.trimMemory(); }
            });
        }
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (renderer != null && glView != null) {
            glView.queueEvent(new Runnable() {
                @Override public void run() { renderer.trimMemory(); }
            });
        }
    }
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

    private void teleportOfflineTabarbour() {
        runOnUiThread(new Runnable() { @Override public void run() {
            loading.setText("جاري تحميل بيانات البيت المحلية…");
            loading.setVisibility(View.VISIBLE);
        }});
        glView.queueEvent(new Runnable() { @Override public void run() { renderer.teleportOfflineTabarbour(); }});
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
        runOnUiThread(new Runnable() { @Override public void run() {
            hud.setText(s);
            if (System.currentTimeMillis()-lastHudRefresh>250 && renderer!=null) {
                lastHudRefresh=System.currentTimeMillis();
                int hp=renderer.game.health;
                healthHud.setText("♥ "+hp+" / 100"+(renderer.game.dead?"  — متوفى | عودة تلقائية قريباً":""));
                healthHud.setTextColor(hp>60?0xff65e58b:hp>25?0xffffc857:0xffff5555);
                miniMap.invalidate();
            }
        }});
    }

    @Override public void onToast(final String s) {
        runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show(); } });
    }
}
