package com.explorer.world3d;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
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
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.CheckBox;

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
    private TextView loading;
    private EditText query;

    private boolean keyUp, keyDown, keyLeft, keyRight;
    private AlertDialog pauseDialog;
    private boolean padL2, padR2, padCameraModifier;
    private float padHatX, padHatY;
    private BattleRoyaleHudView battleRoyaleHud;
    private AimReticleView aimReticle;
    private WeaponWheelView weaponWheelView;
    private boolean weaponWheelOpen;
    private boolean mapOpen;
    private AlertDialog mapDialog;
    private FullMapView fullMapView;
    private long lastHudRefresh;


    private int dp(int v) { return dp((float) v); }
    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private void updateRemoteMovement() {
        if (renderer == null) return;
        float x = (keyRight ? 1f : 0f) - (keyLeft ? 1f : 0f);
        float y = (keyUp ? 1f : 0f) - (keyDown ? 1f : 0f);
        if (renderer.game.driving) {
            renderer.game.vehicleSteeringInput = x;
            renderer.game.vehicleForwardInput = y;
            renderer.game.sx = renderer.game.sy = 0f;
        } else {
            renderer.game.sx = x; renderer.game.sy = y;
            renderer.game.vehicleSteeringInput = renderer.game.vehicleForwardInput = 0f;
        }
    }
    private void runOnGl(final Runnable action) { if (glView != null) glView.queueEvent(action); }
    private void setGamePaused(final boolean paused) {
        runOnGl(new Runnable() { @Override public void run() { renderer.game.paused = paused; } });
    }
    private void attackOrHeal() {
        if (renderer.game.driving) { playHorn(); return; }
        runOnGl(new Runnable() { @Override public void run() { renderer.game.punchRequested = true; } });
    }
    private void showGameSettings() {
        final String[] options = {"اختيار المدينة / الموقع", "اختيار المركبة", "المطارات", "الخريطة الكبيرة",
                "الصوت", "جودة الرسومات", "حساسية الكاميرا", "إعدادات التحكم", "تغيير وقت اليوم", "التحقق من التحديثات"};
        new AlertDialog.Builder(this).setTitle("إعدادات Adam World").setItems(options, (d, which) -> {
            if (which == 0) showCitySelector();
            else if (which == 1) showVehicleSelector();
            else if (which == 2) showAirportSelector();
            else if (which == 3) showMapDialog(false);
            else if (which == 4) showAudioSettings();
            else if (which == 5) new AlertDialog.Builder(this).setTitle("جودة الرسومات")
                    .setItems(new String[]{"عالية", "متوازنة", "موفرة للطاقة"}, (x,v) -> {
                        renderer.setGraphicsQuality(v);
                        getSharedPreferences("adam_world_settings", MODE_PRIVATE).edit().putInt("graphics_quality",v).apply();
                    }).show();
            else if (which == 6) new AlertDialog.Builder(this).setTitle("حساسية الكاميرا")
                    .setItems(new String[]{"منخفضة", "متوسطة", "عالية"}, (x,v) -> renderer.setLookSensitivity(v==0?0.65f:v==1?1f:1.45f)).show();
            else if (which == 7) showControlsSettings();
            else if (which == 8) {
                renderer.timeIdx = (renderer.timeIdx + 1) % TIME_ICONS.length;
                Toast.makeText(MainActivity.this, "تم تغيير وقت اليوم", Toast.LENGTH_SHORT).show();
            } else if (which == 9) UpdateManager.check(MainActivity.this, true);
        }).show();
    }
    private void playHorn() {
        if (renderer != null) renderer.playHorn();
    }

    private void showAudioSettings() {
        final SharedPreferences prefs = getSharedPreferences("adam_world_audio", MODE_PRIVATE);
        final boolean enabled = prefs.getBoolean("enabled", true);
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(12), dp(22), dp(8));
        CheckBox soundToggle = new CheckBox(this);
        soundToggle.setText("تشغيل جميع أصوات اللعبة");
        soundToggle.setTextColor(Color.WHITE);
        soundToggle.setChecked(enabled);
        soundToggle.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("enabled", checked).apply();
            renderer.setAudioEnabled(checked);
        });
        panel.addView(soundToggle);
        final SeekBar[] bars = new SeekBar[4];
        final String[] names = {"الصوت العام", "صوت المحرك والمركبات", "الرياح وحركة الشوارع", "المؤثرات والخطوات"};
        final String[] keys = {"master", "engine", "ambience", "effects"};
        for (int i = 0; i < bars.length; i++) {
            TextView label = new TextView(this);
            label.setText(names[i]);
            label.setTextColor(Color.WHITE);
            panel.addView(label);
            SeekBar bar = new SeekBar(this);
            bar.setMax(100);
            bar.setProgress(prefs.getInt(keys[i], i == 0 ? 82 : i == 1 ? 90 : i == 2 ? 55 : 85));
            bars[i] = bar;
            panel.addView(bar);
        }
        new AlertDialog.Builder(this).setTitle("إعدادات الصوت")
                .setView(panel)
                .setPositiveButton("حفظ", (dialog, which) -> {
                    prefs.edit().putInt("master", bars[0].getProgress())
                            .putInt("engine", bars[1].getProgress())
                            .putInt("ambience", bars[2].getProgress())
                            .putInt("effects", bars[3].getProgress()).apply();
                    renderer.setAudioVolumes(bars[0].getProgress()/100f, bars[1].getProgress()/100f,
                            bars[2].getProgress()/100f, bars[3].getProgress()/100f);
                }).setNegativeButton("إغلاق", null).show();
    }
    private void showMapDialog(final boolean returnToPauseMenu) {
        if (renderer == null) return;
        if (!returnToPauseMenu) setGamePaused(true);
        FullMapView map = new FullMapView(this);
        fullMapView = map;
        mapOpen = true;
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("الخريطة").setView(map)
                .setNegativeButton("إغلاق", null).create();
        mapDialog = dialog;
        dialog.setOnDismissListener(d -> {
            mapOpen = false;
            fullMapView = null;
            mapDialog = null;
            if (returnToPauseMenu && !isFinishing()) showPauseMenu();
            else setGamePaused(false);
        });
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void showCitySelector() {
        final String[] names = new String[CITIES.length + 7];
        names[0] = "العودة إلى المدينة السابقة";
        names[1] = "البيت — طبربور (محلي)";
        names[2] = "عمّان — بيانات محلية";
        names[3] = "مانيلا — بيانات محلية";
        names[4] = "مطار عمّان المدني — ماركا";
        names[5] = "مطار الملكة علياء الدولي";
        names[6] = "بحث عن مدينة أو مكان";
        for (int i=0;i<CITIES.length;i++) names[i+7] = (String)CITIES[i][0];
        new AlertDialog.Builder(this).setTitle("اختيار المدينة / الموقع").setItems(names,(d,which)->{
            if(which==0) runOnGl(()->renderer.leaveFlightSchool());
            else if(which==1) teleportOfflineTabarbour();
            else if(which==2) teleportOfflineAmman();
            else if(which==3) teleportOfflineManila();
            else if(which==4) runOnGl(()->renderer.enterAirport(31.9726,35.9916));
            else if(which==5) runOnGl(()->renderer.enterAirport(31.7226,35.9932));
            else if(which==6) showSearchDialog();
            else { Object[] city=CITIES[which-7]; teleport((Double)city[1],(Double)city[2]); }
        }).show();
    }

    private void showSearchDialog() {
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("اسم المدينة أو المكان");
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(0xffaab4c0);
        field.setPadding(dp(12),dp(8),dp(12),dp(8));
        field.setBackgroundColor(0xff263442);
        new AlertDialog.Builder(this).setTitle("البحث عن موقع").setView(field)
                .setPositiveButton("انتقال",(d,w)->{
                    query = field;
                    search();
                }).setNegativeButton("إلغاء",null).show();
    }

    private void showAirportSelector() {
        new AlertDialog.Builder(this).setTitle("اختيار المطار")
                .setItems(new String[]{"مطار عمّان المدني — ماركا","مطار الملكة علياء الدولي"},(d,w)->{
                    if(w==0) runOnGl(()->renderer.enterAirport(31.9726,35.9916));
                    else runOnGl(()->renderer.enterAirport(31.7226,35.9932));
                }).show();
    }
    private void showControlsSettings() {
        // Keep the controller help free of on-screen action names and button labels.
        new AlertDialog.Builder(this).setTitle("إعدادات التحكم")
                .setMessage("نمط Fortnite: العصا اليسرى للحركة واليمنى للكاميرا. A للقفز، اضغط مطولاً B للانحناء (يتحول إلى انزلاق عند الركض)، X لإعادة التلقيم، Y للدخول/الخروج من المركبة. LT للتصويب وRT للإطلاق، LB لعجلة الأسلحة، RB للتبديل للسلاح التالي، L3 للركض، وMenu للإيقاف. على لوحة المفاتيح: WASD للحركة، Space للقفز، Shift للركض، C/Ctrl للانحناء، R للتلقيم، والأرقام 1–7 لاختيار السلاح. على الهاتف استخدم عصا الحركة وأزرار الركض والانحناء/الانزلاق والقفز والتصويب والإطلاق والتلقيم الظاهرة على الشاشة.")
                .setPositiveButton("إغلاق", null).show();
    }
    private void showPauseMenu() {
        if (isFinishing() || (pauseDialog != null && pauseDialog.isShowing())) return;
        setGamePaused(true);
        final String[] items = {"متابعة اللعبة", "الخريطة الكبيرة", "اختيار المدينة / الموقع", "اختيار المركبة",
                "إعدادات اللعبة", "متجر الأسلحة", "إنهاء اللعبة"};
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("إيقاف مؤقت").setItems(items, (d, which) -> {
            if (which == 0) setGamePaused(false);
            else if (which == 1) { d.dismiss(); showMapDialog(true); }
            else if (which == 2) showCitySelector();
            else if (which == 3) showVehicleSelector();
            else if (which == 4) showGameSettings();
            else if (which == 5) { d.dismiss(); showWeaponShop(); }
            else if (which == 6) { setGamePaused(false); finish(); }
        }).create();
        dialog.setOnCancelListener(d -> setGamePaused(false));
        pauseDialog = dialog;
        dialog.show();
    }
    private void cycleEquippedWeapon(final int direction) {
        runOnGl(() -> {
            Game g = renderer.game;
            int selected = g.equippedWeapon;
            for (int n=0;n<WeaponSystem.NAME.length;n++) {
                selected = (selected + direction + WeaponSystem.NAME.length) % WeaponSystem.NAME.length;
                if (g.ownedWeapons[selected]) break;
            }
            g.equipWeapon(selected);
            runOnUiThread(this::saveWeaponState);
        });
    }

    // GTA-style contextual controller map. All held actions are cleared on release.
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        final int code = normalizeGamepadKeyCode(event.getKeyCode());
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final boolean pressed = down && event.getRepeatCount() == 0;
        if (renderer == null) return super.dispatchKeyEvent(event);
        final Game game = renderer.game;
        final boolean aircraft = game.driving && (game.vehicleType == ProceduralVehicleModels.AIRPLANE
                || game.vehicleType == ProceduralVehicleModels.HELICOPTER);

        if (mapOpen && fullMapView != null) {
            if (pressed && code == KeyEvent.KEYCODE_DPAD_UP) { fullMapView.panBy(0f, dp(72)); return true; }
            if (pressed && code == KeyEvent.KEYCODE_DPAD_DOWN) { fullMapView.panBy(0f, -dp(72)); return true; }
            if (pressed && code == KeyEvent.KEYCODE_DPAD_LEFT) { fullMapView.panBy(dp(72), 0f); return true; }
            if (pressed && code == KeyEvent.KEYCODE_DPAD_RIGHT) { fullMapView.panBy(-dp(72), 0f); return true; }
            if (pressed && code == KeyEvent.KEYCODE_BUTTON_L1) { fullMapView.zoomBy(1.25f); return true; }
            if (pressed && code == KeyEvent.KEYCODE_BUTTON_R1) { fullMapView.zoomBy(0.8f); return true; }
            if (pressed && (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BUTTON_SELECT || code == KeyEvent.KEYCODE_BACK)) {
                if (mapDialog != null) mapDialog.dismiss();
                return true;
            }
            return true;
        }

        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (weaponWheelOpen && pressed) {
                if (code == KeyEvent.KEYCODE_DPAD_UP) weaponWheelView.moveSelection(1);
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN) weaponWheelView.moveSelection(-1);
                else weaponWheelView.moveSelection(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            } else if (glView != null) {
                // D-pad is the digital equivalent of the LEFT analogue stick.
                glView.setDigitalMoveDirection(code, down);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_W || code == KeyEvent.KEYCODE_S
                || code == KeyEvent.KEYCODE_A || code == KeyEvent.KEYCODE_D) {
            if (code == KeyEvent.KEYCODE_W) keyUp = down;
            if (code == KeyEvent.KEYCODE_S) keyDown = down;
            if (code == KeyEvent.KEYCODE_A) keyLeft = down;
            if (code == KeyEvent.KEYCODE_D) keyRight = down;
            updateRemoteMovement();
            return true;
        }

        // Y is reserved for contextual enter/exit vehicle interaction.
        if (code == KeyEvent.KEYCODE_BUTTON_Y) {
            if (pressed) runOnGl(() -> renderer.toggleCar());
            return true;
        }
        // Fortnite combat layout: A jump, hold B crouch/slide, X reload.
        // Aim/fire stay on LT/RT; camera is exclusively on the right stick.
        if (code == KeyEvent.KEYCODE_BUTTON_A) {
            if (pressed) runOnGl(() -> { if (!game.driving && !game.dead) game.jumpRequested = true; });
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_B) {
            if (!game.driving && !game.dead) game.crouching = down;
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_X) {
            if (pressed && !game.driving && !game.dead) runOnGl(() -> game.reloadRequested = true);
            return true;
        }
        // Keyboard fallback: Space jump, Left Shift sprint, C/Ctrl crouch, R reload.
        if (code == KeyEvent.KEYCODE_SPACE || code == KeyEvent.KEYCODE_C
                || code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_CTRL_RIGHT
                || code == KeyEvent.KEYCODE_R || code == KeyEvent.KEYCODE_SHIFT_LEFT
                || code == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            if (code == KeyEvent.KEYCODE_C || code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_CTRL_RIGHT)
                game.crouching = down;
            else if (code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT)
                game.sprint = down;
            else if (pressed && code == KeyEvent.KEYCODE_SPACE) runOnGl(() -> { if (!game.driving && !game.dead) game.jumpRequested = true; });
            else if (pressed && code == KeyEvent.KEYCODE_R) runOnGl(() -> game.reloadRequested = true);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_L1) {
            if (!game.driving) {
                if (down && !weaponWheelOpen) {
                    weaponWheelOpen = true;
                    glView.setWeaponWheelOpen(true);
                    runOnUiThread(() -> weaponWheelView.showWheel());
                } else if (!down && weaponWheelOpen) {
                    weaponWheelOpen = false;
                    glView.setWeaponWheelOpen(false);
                    final int chosen = weaponWheelView.selectedWeapon();
                    runOnGl(() -> {
                        game.equipWeapon(chosen);
                        runOnUiThread(this::saveWeaponState);
                    });
                    runOnUiThread(() -> weaponWheelView.hideWheel());
                }
            } else if (aircraft) game.rudderInput = down ? -1f : 0f;
            else game.weaponAimHeld = down;
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_R1) {
            if (game.driving) {
                if (aircraft) game.rudderInput = down ? 1f : 0f;
                else game.handbrake = down;
            } else if (pressed) cycleEquippedWeapon(1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_L2 || code == KeyEvent.KEYCODE_BUTTON_R2) {
            if (code == KeyEvent.KEYCODE_BUTTON_L2) padL2 = down; else padR2 = down;
            if (game.driving) {
                if (game.vehicleType == ProceduralVehicleModels.AIRPLANE) {
                    game.brakeRequested = padL2 || game.handbrake;
                    game.throttleInput = padR2 ? 1f : 0f;
                } else if (game.vehicleType == ProceduralVehicleModels.HELICOPTER) {
                    game.throttleInput = 0f;
                    game.liftInput = (padR2 ? 1f : 0f) - (padL2 ? 1f : 0f);
                } else {
                    game.vehicleForwardInput = (padR2 ? 1f : 0f) - (padL2 ? 1f : 0f);
                }
            } else {
                game.weaponAimHeld = padL2;
                game.weaponFireHeld = padR2;
            }
            return true;
        }
        if (code >= KeyEvent.KEYCODE_1 && code <= KeyEvent.KEYCODE_7) {
            if (pressed && !game.driving) {
                final int slot = code - KeyEvent.KEYCODE_1;
                runOnGl(() -> { if (game.equipWeapon(slot)) runOnUiThread(this::saveWeaponState); });
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_THUMBL) {
            if (!game.driving) {
                game.sprint = down;
                game.jogging = down;
            } else if (pressed && game.vehicleType == ProceduralVehicleModels.AIRPLANE) {
                runOnGl(() -> renderer.toggleAircraftGear());
            } else if (pressed && !aircraft) playHorn();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_THUMBR) {
            // Hold R3 to look behind; release restores the normal chase camera.
            game.cameraLookOffset = down ? (float)Math.PI : 0f;
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (pressed) {
                game.cameraMode = (game.cameraMode + 1) % 3;
                game.cinematicCamera = false;
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_START || code == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (pressed) showPauseMenu();
            return true;
        }
        if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (pressed) runOnGl(() -> {
                game.sprint = false; game.weaponAimHeld = false; game.weaponFireHeld = false;
                game.handbrake = false; game.brakeRequested = false;
                game.liftInput = 0f; game.rudderInput = 0f; game.throttleInput = 0f;
                renderer.toggleCar();
            });
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static int normalizeGamepadKeyCode(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_1: return KeyEvent.KEYCODE_BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_2: return KeyEvent.KEYCODE_BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_3: return KeyEvent.KEYCODE_BUTTON_X;
            case KeyEvent.KEYCODE_BUTTON_4: return KeyEvent.KEYCODE_BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_5: return KeyEvent.KEYCODE_BUTTON_L1;
            case KeyEvent.KEYCODE_BUTTON_6: return KeyEvent.KEYCODE_BUTTON_R1;
            case KeyEvent.KEYCODE_BUTTON_7: return KeyEvent.KEYCODE_BUTTON_L2;
            case KeyEvent.KEYCODE_BUTTON_8: return KeyEvent.KEYCODE_BUTTON_R2;
            case KeyEvent.KEYCODE_BUTTON_9: return KeyEvent.KEYCODE_BUTTON_THUMBL;
            case KeyEvent.KEYCODE_BUTTON_10: return KeyEvent.KEYCODE_BUTTON_THUMBR;
            case KeyEvent.KEYCODE_BUTTON_11: return KeyEvent.KEYCODE_BUTTON_SELECT;
            case KeyEvent.KEYCODE_BUTTON_12: return KeyEvent.KEYCODE_BUTTON_START;
            case KeyEvent.KEYCODE_BUTTON_13: return KeyEvent.KEYCODE_DPAD_UP;
            case KeyEvent.KEYCODE_BUTTON_14: return KeyEvent.KEYCODE_DPAD_DOWN;
            case KeyEvent.KEYCODE_BUTTON_15: return KeyEvent.KEYCODE_DPAD_LEFT;
            case KeyEvent.KEYCODE_BUTTON_16: return KeyEvent.KEYCODE_DPAD_RIGHT;
            default: return code;
        }
    }

    private static float applyStickDeadZone(float value, float deadZone) {
        float magnitude = Math.abs(value);
        if (magnitude <= deadZone) return 0f;
        return Math.copySign(Math.min(1f, (magnitude - deadZone) / (1f - deadZone)), value);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_START || keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.getRepeatCount() == 0) showPauseMenu();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onBackPressed() {
        if (pauseDialog != null && pauseDialog.isShowing()) { pauseDialog.dismiss(); setGamePaused(false); }
        else showPauseMenu();
    }

    /** Fortnite-style movable reticle: drag it while aiming to steer the shot ray. */
    private final class AimReticleView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean aiming,tracking;
        private float offsetX,offsetY,lastX,lastY;
        AimReticleView(Context c){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);setClickable(true);setFocusable(false);}
        void setAiming(boolean value){aiming=value;if(!value){offsetX=offsetY=0f;if(renderer!=null)runOnGl(()->{renderer.game.aimYawOffset=0f;renderer.game.aimPitchOffset=0f;});}invalidate();}
        @Override public boolean onTouchEvent(MotionEvent e){
            float x=e.getX(),y=e.getY();
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                if(!aiming)return false;
                float cx=getWidth()/2f+offsetX,cy=getHeight()/2f+offsetY;
                if(Math.hypot(x-cx,y-cy)>dp(58))return false;
                tracking=true;lastX=x;lastY=y;return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&tracking){
                offsetX=Math.max(-getWidth()*.46f,Math.min(getWidth()*.46f,offsetX+x-lastX));
                offsetY=Math.max(-getHeight()*.42f,Math.min(getHeight()*.42f,offsetY+y-lastY));lastX=x;lastY=y;
                if(renderer!=null){final float yaw=offsetX/Math.max(1f,getWidth())*.95f;final float pitch=-offsetY/Math.max(1f,getHeight())*.72f;runOnGl(()->{renderer.game.aimYawOffset=yaw;renderer.game.aimPitchOffset=pitch;});}
                invalidate();return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){tracking=false;return true;}
            return tracking;
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            float cx=getWidth()/2f+(aiming?offsetX:0f),cy=getHeight()/2f+(aiming?offsetY:0f);
            paint.setColor(aiming?0xffffe6a3:0xddefffff);paint.setStrokeWidth(dp(1.6f));paint.setStyle(Paint.Style.STROKE);
            float r=aiming?dp(9):dp(6),gap=aiming?dp(6):dp(4),len=aiming?dp(10):dp(7);
            c.drawCircle(cx,cy,r,paint);c.drawLine(cx-len-gap,cy,cx-gap,cy,paint);c.drawLine(cx+gap,cy,cx+len+gap,cy,paint);
            c.drawLine(cx,cy-len-gap,cx,cy-gap,paint);c.drawLine(cx,cy+gap,cx,cy+len+gap,paint);
            paint.setStyle(Paint.Style.FILL);c.drawCircle(cx,cy,dp(1.3f),paint);
        }
    }

    /** GTA-like right-side camera look zone: drag to rotate view, never drag the reticle. */
    private final class FullMapView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ScaleGestureDetector scaleDetector;
        private float zoom = 1f, panX, panY, lastX, lastY;
        FullMapView(Context c) {
            super(c);
            setBackgroundColor(0xff101923);
            setFocusable(true);
            scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    zoom = Math.max(0.55f, Math.min(4.5f, zoom * detector.getScaleFactor()));
                    invalidate();
                    return true;
                }
            });
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(0xff101923);
            float w=getWidth(), h=getHeight(), cx=w*.5f, cy=h*.5f;
            float px=renderer.game.driving?renderer.game.cx:renderer.game.px;
            float pz=renderer.game.driving?renderer.game.cz:renderer.game.pz;
            float scale=Math.min(w,h)/1400f*zoom;
            float ox=cx+panX, oy=cy+panY;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0x243c5267);
            for(int i=-8;i<=8;i++){
                canvas.drawLine(ox+i*70*zoom,0,ox+i*70*zoom,h,paint);
                canvas.drawLine(0,oy+i*70*zoom,w,oy+i*70*zoom,paint);
            }
            float[] roads=renderer.fullMapRoads();
            paint.setStrokeCap(Paint.Cap.ROUND);
            for(int i=0;i+3<roads.length;i+=4){
                float x1=ox+(roads[i]-px)*scale, y1=oy+(roads[i+1]-pz)*scale;
                float x2=ox+(roads[i+2]-px)*scale, y2=oy+(roads[i+3]-pz)*scale;
                paint.setColor(0xff66798a); paint.setStrokeWidth(dp(5)*zoom);
                canvas.drawLine(x1,y1,x2,y2,paint);
                paint.setColor(0xffb9c4cf); paint.setStrokeWidth(dp(1.6f)*zoom);
                canvas.drawLine(x1,y1,x2,y2,paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xff36d77a);
            canvas.drawCircle(ox,oy,dp(8),paint);
            android.graphics.Path arrow=new android.graphics.Path();
            arrow.moveTo(ox,oy-dp(17)); arrow.lineTo(ox-dp(7),oy+dp(8)); arrow.lineTo(ox,oy+dp(4)); arrow.lineTo(ox+dp(7),oy+dp(8)); arrow.close();
            canvas.save(); canvas.rotate((float)Math.toDegrees(renderer.game.driving?renderer.game.cyaw:renderer.game.pyaw),ox,oy); canvas.drawPath(arrow,paint); canvas.restore();
            paint.setColor(Color.WHITE); paint.setTextSize(dp(14)); paint.setFakeBoldText(true);
            canvas.drawText("N",w-dp(30),dp(36),paint);
            paint.setTextSize(dp(12)); paint.setFakeBoldText(false);
            canvas.drawText("اسحب لتحريك الخريطة · قرّب بإصبعين",dp(18),h-dp(22),paint);
            canvas.drawText(String.format(java.util.Locale.US,"%.5f, %.5f",Geo.latOf(pz),Geo.lonOf(px)),dp(18),h-dp(42),paint);
            postInvalidateDelayed(600);
        }
        void panBy(float dx, float dy) { panX += dx; panY += dy; invalidate(); }
        void zoomBy(float factor) { zoom = Math.max(0.55f, Math.min(4.5f, zoom * factor)); invalidate(); }

        @Override public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            if(scaleDetector.isInProgress()) return true;
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN: lastX=event.getX(); lastY=event.getY(); return true;
                case MotionEvent.ACTION_MOVE:
                    if(event.getPointerCount()==1){panX+=event.getX()-lastX;panY+=event.getY()-lastY;lastX=event.getX();lastY=event.getY();invalidate();}
                    return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: return true;
            }
            return true;
        }
    }

    private void loadWeaponState(){
        SharedPreferences p=getSharedPreferences("adam_world_weapons",MODE_PRIVATE);Game g=renderer.game;g.cash=p.getInt("cash",g.cash);g.medkits=Math.max(0,Math.min(5,p.getInt("medkits",g.medkits)));g.shieldPotions=Math.max(0,Math.min(5,p.getInt("shield_potions",g.shieldPotions)));g.woodMaterials=Math.max(0,Math.min(999,p.getInt("wood_materials",g.woodMaterials)));if(!p.getBoolean("weapon_cash_boost_v1",false)){g.cash=Math.max(g.cash,100000);p.edit().putBoolean("weapon_cash_boost_v1",true).putInt("cash",g.cash).apply();}
        for(int i=0;i<WeaponSystem.NAME.length;i++){g.ownedWeapons[i]=p.getBoolean("owned_"+i,g.ownedWeapons[i]);g.reserveAmmo[i]=p.getInt("reserve_"+i,g.reserveAmmo[i]);g.weaponMagazines[i]=p.getInt("magazine_"+i,g.weaponMagazines[i]);}
        for(int slot=0;slot<g.quickbarWeapons.length;slot++){
            int id=p.getInt("quickbar_"+slot,slot==0?0:-1);
            g.quickbarWeapons[slot]=id>=0&&id<g.ownedWeapons.length&&g.ownedWeapons[id]?id:-1;
        }
        if(g.quickbarWeapons[0]<0)g.quickbarWeapons[0]=0;
        int selected=p.getInt("equipped",0);if(selected<0||selected>=g.ownedWeapons.length||!g.ownedWeapons[selected])selected=0;g.equippedWeapon=selected;g.magazineAmmo=p.contains("magazine_"+selected)?g.weaponMagazines[selected]:WeaponSystem.MAGAZINE[selected];
    }
    private void saveWeaponState(){if(renderer==null)return;Game g=renderer.game;g.weaponMagazines[g.equippedWeapon]=g.magazineAmmo;SharedPreferences.Editor e=getSharedPreferences("adam_world_weapons",MODE_PRIVATE).edit().putInt("cash",g.cash).putInt("equipped",g.equippedWeapon).putInt("medkits",g.medkits).putInt("shield_potions",g.shieldPotions).putInt("wood_materials",g.woodMaterials);for(int i=0;i<WeaponSystem.NAME.length;i++)e.putBoolean("owned_"+i,g.ownedWeapons[i]).putInt("reserve_"+i,g.reserveAmmo[i]).putInt("magazine_"+i,g.weaponMagazines[i]);for(int slot=0;slot<g.quickbarWeapons.length;slot++)e.putInt("quickbar_"+slot,g.quickbarWeapons[slot]);e.apply();}

    private void showWeaponShop(){
        final String[] items=new String[WeaponSystem.NAME.length+1];
        for(int i=0;i<WeaponSystem.NAME.length;i++)items[i]=WeaponSystem.NAME[i]+(renderer.game.ownedWeapons[i]?" — مملوك":" — $"+WeaponSystem.PRICE[i]);
        items[items.length-1]="شراء ذخيرة للسلاح الحالي";
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("متجر الأسلحة · الرصيد $"+renderer.game.cash)
                .setItems(items,(d,which)->runOnGl(()->{
                    Game game=renderer.game;
                    if(which==items.length-1){if(game.buyAmmo())onToast("تم شراء الذخيرة");else onToast("الرصيد غير كافٍ");}
                    else if(game.ownedWeapons[which]){game.equipWeapon(which);onToast("تم تجهيز "+WeaponSystem.NAME[which]);}
                    else if(game.purchaseWeapon(which))onToast("تم شراء "+WeaponSystem.NAME[which]);else onToast("الرصيد غير كافٍ");
                    saveWeaponState();
                })).setNegativeButton("إغلاق",null).create();
        dialog.setOnDismissListener(d -> setGamePaused(false));
        dialog.show();
    }

    private void showInventory(){
        Game game=renderer.game;
        java.util.ArrayList<Integer> ids=new java.util.ArrayList<>();
        java.util.ArrayList<String> labels=new java.util.ArrayList<>();
        StringBuilder slotsText=new StringBuilder("الخانات السريعة: ");
        for(int slot=0;slot<game.quickbarWeapons.length;slot++){
            if(slot>0)slotsText.append("  ·  ");
            int weapon=game.quickbarWeapons[slot];
            slotsText.append(slot+1).append(": ").append(weapon>=0?WeaponSystem.NAME[weapon]:"فارغة");
        }
        for(int i=0;i<WeaponSystem.NAME.length;i++)if(game.ownedWeapons[i]){
            ids.add(i);
            labels.add((i==game.equippedWeapon?"✓ ":"")+WeaponSystem.NAME[i]+" · "+WeaponSystem.RARITY_LABEL[WeaponSystem.RARITY[i]]
                    +"\n"+game.weaponMagazines[i]+" / "+game.reserveAmmo[i]);
        }
        String summary=slotsText+"\nعلاجات: "+game.medkits+"   |   جرعات الدرع: "+game.shieldPotions
                +"\nمواد البناء: "+game.woodMaterials+"   |   الرصيد: $"+game.cash;
        new AlertDialog.Builder(this).setTitle("الحقيبة · إدارة العناصر")
                .setMessage(summary)
                .setItems(labels.toArray(new String[0]),(d,which)->{
                    int id=ids.get(which);
                    String[] actions={"تجهيز السلاح الآن","تعيين إلى خانة سريعة","إزالة السلاح من الخانات السريعة","إسقاط السلاح على الأرض"};
                    new AlertDialog.Builder(this).setTitle(WeaponSystem.NAME[id]+" · "+WeaponSystem.RARITY_LABEL[WeaponSystem.RARITY[id]])
                            .setItems(actions,(dialog,action)->{
                                if(action==0)runOnGl(()->{
                                    renderer.game.equipWeapon(id);
                                    saveWeaponState();
                                    onToast("تم تجهيز "+WeaponSystem.NAME[id]);
                                });
                                else if(action==1){
                                    String[] slotNames={"الخانة 1","الخانة 2","الخانة 3","الخانة 4","الخانة 5"};
                                    new AlertDialog.Builder(this).setTitle("اختر الخانة")
                                            .setItems(slotNames,(slotDialog,slot)->runOnGl(()->{
                                                renderer.game.assignQuickbarWeapon(slot,id);
                                                renderer.game.selectQuickbarSlot(slot);
                                                saveWeaponState();
                                                onToast("تم تعيين السلاح إلى الخانة "+(slot+1));
                                            })).setNegativeButton("إلغاء",null).show();
                                }else if(action==2)runOnGl(()->{
                                    boolean removed=renderer.game.unassignQuickbarWeapon(id);
                                    saveWeaponState();
                                    onToast(removed?"تمت إزالة السلاح من الخانات":"السلاح غير معيّن في أي خانة");
                                });else runOnGl(()->{
                                    boolean dropped=renderer.game.dropWeapon(id);
                                    saveWeaponState();
                                    onToast(dropped?"تم إسقاط السلاح على الأرض":"لا يمكن إسقاط هذا السلاح");
                                });
                            }).setNegativeButton("إلغاء",null).show();
                }).setPositiveButton("استخدام علاج +50", (d,w)->runOnGl(()->{
                    renderer.game.useMedkitRequested=true;
                }))
                .setNeutralButton("استخدام درع +25",(d,w)->runOnGl(()->{
                    renderer.game.useShieldPotionRequested=true;
                })).setNegativeButton("إغلاق",null).show();
    }

    private void showVehicleSelector() {
        final String[] names = {"سيدان", "SUV", "بيك أب", "باص", "شرطة", "دراجة نارية",
                "دراجة هوائية", "دبابة", "هليكوبتر", "طائرة"};
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("اختر المركبة")
                .setItems(names, (d, which) -> runOnGl(() -> {
                    if (which == ProceduralVehicleModels.AIRPLANE || which == ProceduralVehicleModels.HELICOPTER)
                        renderer.enterAirport(31.9726, 35.9916);
                    renderer.selectVehicleType(which);
                    renderer.game.paused = false;
                }))
                .setNegativeButton("إلغاء", null).create();
        dialog.setOnDismissListener(d -> setGamePaused(false));
        dialog.show();
    }

    /**
     * One purpose-built Battle Royale HUD. No Android Button/TextView widgets are used
     * for gameplay controls; all visual elements are drawn and hit-tested in this view.
     */
    private final class BattleRoyaleHudView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density = getResources().getDisplayMetrics().density;
        private int movePointer=-1,cameraPointer=-1,firePointer=-1,aimPointer=-1,sprintPointer=-1,crouchPointer=-1,jumpPointer=-1,reloadPointer=-1;
        private boolean pauseMenuOpen=false,fireHeld,aimHeld,sprintHeld,crouchHeld;
        private float cameraLastX,cameraLastY,moveX,moveY,w,h;
        private final float[] quickX=new float[5];
        private int slidePointer=-1;
        private final RectF mapRect=new RectF();
        BattleRoyaleHudView(Context context){super(context);setLayerType(View.LAYER_TYPE_SOFTWARE,null);setFocusable(false);}
        private float d(float v){return v*density;}
        private void fill(Canvas c,int color){paint.setStyle(Paint.Style.FILL);paint.setColor(color);}
        private void stroke(int color,float width){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(d(width));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setColor(color);}
        private void text(Canvas c,String s,float x,float y,float size,int color,boolean bold){fill(c,color);paint.setTextSize(d(size));paint.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));c.drawText(s,x,y,paint);}
        private void panel(Canvas c,float l,float t,float r,float b,float radius,int color){fill(c,color);c.drawRoundRect(l,t,r,b,d(radius),d(radius),paint);}
        private void buttonBase(Canvas c,float x,float y,float r,boolean active){
            fill(c,active?0x9cf5b942:0x66303b49);c.drawCircle(x,y,r,paint);
            stroke(active?0xffffe5a8:0xddeaf2fa,1.5f);c.drawCircle(x,y,r,paint);paint.setStyle(Paint.Style.FILL);
            stroke(0x55ffffff,.7f);c.drawCircle(x,y,r-d(4),paint);paint.setStyle(Paint.Style.FILL);
        }
        private void line(Canvas c,float x1,float y1,float x2,float y2,float width){stroke(Color.WHITE,width);c.drawLine(x1,y1,x2,y2,paint);paint.setStyle(Paint.Style.FILL);}
        private void icon(Canvas c,int type,float x,float y,float size){
            c.save();c.translate(x,y);c.scale(size/48f,size/48f);stroke(Color.WHITE,2.8f);
            Path p=new Path();
            switch(type){
                case 0: // crosshair
                    c.drawCircle(0,0,14,paint);c.drawCircle(0,0,3,paint);
                    c.drawLine(-23,0,-9,0,paint);c.drawLine(9,0,23,0,paint);c.drawLine(0,-23,0,-9,paint);c.drawLine(0,9,0,23,paint);break;
                case 1: // fire / rifle
                    p.moveTo(-19,5);p.lineTo(-5,-2);p.lineTo(9,-2);p.lineTo(19,-8);p.lineTo(22,-5);p.lineTo(11,3);p.lineTo(3,3);p.lineTo(-2,16);p.lineTo(-8,16);p.lineTo(-7,3);p.lineTo(-19,9);p.close();c.drawPath(p,paint);c.drawLine(8,-2,15,-13,paint);break;
                case 2: // runner
                    c.drawCircle(6,-16,4,paint);c.drawLine(3,-10,-4,0,paint);c.drawLine(-4,0,9,4,paint);c.drawLine(0,-6,-13,-3,paint);c.drawLine(0,-6,12,-2,paint);c.drawLine(8,4,17,15,paint);c.drawLine(8,4,-5,17,paint);c.drawLine(-5,17,-16,17,paint);c.drawLine(17,15,23,15,paint);break;
                case 3: // jump
                    c.drawLine(0,18,0,-13,paint);c.drawLine(0,-13,-10,-2,paint);c.drawLine(0,-13,10,-2,paint);c.drawLine(-15,18,15,18,paint);break;
                case 4: // crouch
                    c.drawCircle(5,-13,4,paint);c.drawLine(2,-8,-7,1,paint);c.drawLine(-7,1,8,6,paint);c.drawLine(8,6,17,14,paint);c.drawLine(-7,1,-14,14,paint);c.drawLine(-14,14,-22,14,paint);break;
                case 5: // reload
                    c.drawArc(-17,-17,17,17,35,285,false,paint);c.drawLine(13,-16,18,-5,paint);c.drawLine(13,-16,2,-17,paint);break;
                case 6: // pickaxe
                    c.drawLine(-15,18,15,-18,paint);c.drawLine(-21,-12,3,-21,paint);c.drawLine(-21,-12,-11,-2,paint);c.drawLine(3,-21,13,-13,paint);break;
                case 7: // backpack
                    c.drawRoundRect(-13,-15,13,17,5,5,paint);c.drawArc(-8,-22,8,-8,180,180,false,paint);c.drawRoundRect(-8,-3,8,8,2,2,paint);c.drawLine(-18,-8,-13,-4,paint);c.drawLine(18,-8,13,-4,paint);break;
                case 8: // hand/interact
                    c.drawRoundRect(-13,-4,13,17,5,5,paint);c.drawLine(-8,-4,-8,-17,paint);c.drawLine(-1,-4,-1,-21,paint);c.drawLine(6,-4,6,-17,paint);c.drawLine(13,1,19,-7,paint);break;
                case 9: // vehicle
                    c.drawRoundRect(-19,-10,19,11,4,4,paint);c.drawLine(-12,-10,-7,-17,paint);c.drawLine(-7,-17,10,-17,paint);c.drawLine(10,-17,15,-10,paint);c.drawCircle(-11,12,4,paint);c.drawCircle(11,12,4,paint);break;
                case 10: // menu
                    c.drawLine(-16,-12,16,-12,paint);c.drawLine(-16,0,16,0,paint);c.drawLine(-16,12,16,12,paint);break;
                case 11: // shield
                    p.moveTo(0,-20);p.lineTo(16,-13);p.lineTo(14,7);p.lineTo(0,20);p.lineTo(-14,7);p.lineTo(-16,-13);p.close();c.drawPath(p,paint);break;
                case 12: // health cross
                    c.drawLine(-17,0,17,0,paint);c.drawLine(0,-17,0,17,paint);break;
            }
            paint.setStyle(Paint.Style.FILL);c.restore();
        }
        /** Distinct, original weapon silhouettes for the seven weapon archetypes. */
        private void weaponIcon(Canvas c,int id,float x,float y,float size){
            c.save();c.translate(x,y);c.scale(size/64f,size/64f);
            stroke(0xfff4f6f8,3.2f);Path p=new Path();
            switch(id){
                case 0: // pistol
                    p.moveTo(-23,-3);p.lineTo(8,-3);p.lineTo(17,-9);p.lineTo(25,-9);p.lineTo(25,1);p.lineTo(8,4);p.lineTo(5,20);p.lineTo(-5,20);p.lineTo(-9,4);p.lineTo(-23,4);p.close();c.drawPath(p,paint);break;
                case 1: // SMG
                    p.moveTo(-28,-5);p.lineTo(24,-5);p.lineTo(28,1);p.lineTo(8,5);p.lineTo(4,20);p.lineTo(-5,20);p.lineTo(-8,5);p.lineTo(-28,4);p.close();c.drawPath(p,paint);c.drawLine(5,-5,13,-17,paint);break;
                case 2: // assault rifle
                    p.moveTo(-30,-5);p.lineTo(19,-5);p.lineTo(29,-11);p.lineTo(32,-7);p.lineTo(21,1);p.lineTo(7,3);p.lineTo(3,19);p.lineTo(-6,19);p.lineTo(-10,3);p.lineTo(-30,5);p.close();c.drawPath(p,paint);c.drawLine(-3,3,-1,15,paint);c.drawLine(13,-5,18,-19,paint);break;
                case 3: // shotgun
                    c.drawLine(-29,-7,29,-7,paint);c.drawLine(-29,0,22,0,paint);c.drawLine(-18,0,-12,19,paint);c.drawLine(-12,19,-2,19,paint);c.drawLine(8,0,12,15,paint);c.drawLine(12,15,23,15,paint);break;
                case 4: // sniper
                    c.drawLine(-30,-2,31,-2,paint);c.drawLine(-23,-2,-18,17,paint);c.drawLine(-18,17,-7,17,paint);c.drawLine(5,-2,8,-17,paint);c.drawLine(8,-17,24,-17,paint);c.drawLine(13,-17,13,-24,paint);c.drawCircle(13,-24,3,paint);break;
                case 5: // rocket launcher
                    c.drawLine(-28,-5,23,-5,paint);c.drawLine(-28,5,23,5,paint);c.drawLine(-19,5,-12,18,paint);c.drawLine(-12,18,-3,18,paint);p.moveTo(23,-5);p.lineTo(31,0);p.lineTo(23,5);p.close();c.drawPath(p,paint);break;
                default: // guided launcher
                    c.drawRoundRect(-29,-8,23,8,4,4,paint);c.drawLine(-14,8,-9,20,paint);c.drawLine(-9,20,2,20,paint);c.drawLine(23,-8,31,0,paint);c.drawLine(31,0,23,8,paint);c.drawLine(-25,-13,-15,-13,paint);break;
            }
            paint.setStyle(Paint.Style.FILL);c.restore();
        }
        private void action(Canvas c,float x,float y,float r,int type,boolean active){buttonBase(c,x,y,r,active);icon(c,type,x,y,r*.78f);}
        private boolean inside(float x,float y,float cx,float cy,float r){float dx=x-cx,dy=y-cy;return dx*dx+dy*dy<=r*r;}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);w=getWidth();h=getHeight();if(w<=0||h<=0||renderer==null)return;
            Game g=renderer.game;float s=density, left=d(16),right=w-d(16),top=d(14);
            // Player identity and stacked shield/health meters (Fortnite-style top-left HUD).
            panel(c,left,top,left+d(42),top+d(42),d(7),0x99202b39);
            fill(c,0xffd7e0e9);c.drawCircle(left+d(21),top+d(14),d(7),paint);c.drawRoundRect(left+d(10),top+d(23),left+d(32),top+d(39),d(7),d(7),paint);
            text(c,"ADAM WORLD",left+d(50),top+d(13),12,Color.WHITE,true);
            panel(c,left+d(50),top+d(19),left+d(224),top+d(30),d(3),0xff263442);
            panel(c,left+d(50),top+d(19),left+d(50)+d(174)*Math.max(0,Math.min(1,g.shield/100f)),top+d(30),d(3),0xff249be8);
            icon(c,11,left+d(239),top+d(25),d(18));text(c,String.valueOf((int)g.shield),left+d(252),top+d(30),11,Color.WHITE,true);
            panel(c,left+d(50),top+d(35),left+d(224),top+d(46),d(3),0xff263442);
            panel(c,left+d(50),top+d(35),left+d(50)+d(174)*Math.max(0,Math.min(1,g.health/100f)),top+d(46),d(3),0xff35d477);
            icon(c,12,left+d(239),top+d(41),d(18));text(c,String.valueOf((int)g.health),left+d(252),top+d(46),11,Color.WHITE,true);
            // Compass strip at top centre.
            String[] dirs={"W","NW","N","NE","E"};float cx=w*.5f;
            for(int i=0;i<dirs.length;i++){float x=cx+(i-2)*d(72);text(c,dirs[i],x-d(8),top+d(21),i==2?17:13,i==2?Color.WHITE:0xffb9c8d8,true);fill(c,0xffdce6ef);c.drawCircle(x,top+d(31),d(1.5f),paint);}
            // Minimap top-right.
            float mr=Math.min(d(76),Math.min(w*.105f,h*.14f)),mx=right-mr-d(10),my=top+mr+d(8);
            mapRect.set(mx-mr,my-mr,mx+mr,my+mr);
            panel(c,mx-mr-d(3),my-mr-d(3),mx+mr+d(3),my+mr+d(3),d(8),0xe51b2632);
            stroke(0xffe5edf5,1.6f);c.drawRoundRect(mx-mr,my-mr,mx+mr,my+mr,d(6),d(6),paint);
            c.save();Path clip=new Path();clip.addRoundRect(mx-mr+d(2),my-mr+d(2),mx+mr-d(2),my+mr-d(2),d(5),d(5),Path.Direction.CW);c.clipPath(clip);
            fill(c,0xff34434a);c.drawRect(mx-mr,my-mr,mx+mr,my+mr,paint);
            float[] roads=renderer.miniMapRoads();float scale=mr/210f,px=g.driving?g.cx:g.px,pz=g.driving?g.cz:g.pz;
            stroke(0xffe3e6d7,2.2f);for(int i=0;i+3<roads.length;i+=4)c.drawLine(mx+(roads[i]-px)*scale,my+(roads[i+1]-pz)*scale,mx+(roads[i+2]-px)*scale,my+(roads[i+3]-pz)*scale,paint);
            stroke(0xff9cb7a1,1);c.drawLine(mx-mr,my,my+mr,mx+mr,my,paint);
            fill(c,0xffffd34e);Path arrow=new Path();arrow.moveTo(mx,my-d(9));arrow.lineTo(mx-d(6),my+d(6));arrow.lineTo(mx,my+d(2));arrow.lineTo(mx+d(6),my+d(6));arrow.close();c.save();c.rotate((float)Math.toDegrees(g.driving?g.cyaw:g.pyaw),mx,my);c.drawPath(arrow,paint);c.restore();c.restore();
            text(c,""+g.magazineAmmo+" / "+g.reserveAmmo[g.equippedWeapon],mx-mr,my+mr+d(17),11,Color.WHITE,true);
            // Pause control is outside the minimap hit area, never layered over it.
            action(c,right-d(18),top+d(18),d(15),10,false);
            if(pauseMenuOpen){float pw=Math.min(w-d(36),d(330)),ph=d(226),pl=(w-pw)/2f,pt=(h-ph)/2f;panel(c,pl,pt,pl+pw,pt+ph,12,0xf018222e);stroke(0xffe8bd61,2);c.drawRoundRect(pl,pt,pl+pw,pt+ph,d(12),d(12),paint);paint.setStyle(Paint.Style.FILL);text(c,"BATTLE ROYALE",pl+d(22),pt+d(36),20,Color.WHITE,true);panel(c,pl+d(18),pt+d(83),pl+pw-d(18),pt+d(132),7,0xffb58a38);text(c,"RESUME",pl+d(34),pt+d(114),15,Color.WHITE,true);panel(c,pl+d(18),pt+d(145),pl+pw-d(18),pt+d(194),7,0xff344454);text(c,"MAP",pl+d(34),pt+d(176),15,Color.WHITE,true);return;}
            // Left movement stick.
            float jx=d(100),jy=h-d(105),jr=d(54);
            fill(c,0x55303b49);c.drawCircle(jx,jy,jr,paint);stroke(0x99e8f0f7,1.5f);c.drawCircle(jx,jy,jr,paint);
            for(int i=0;i<4;i++){double a=Math.PI*.5*i;float ax=jx+(float)Math.cos(a)*d(43),ay=jy+(float)Math.sin(a)*d(43);Path tri=new Path();tri.moveTo(ax,ay);tri.lineTo(ax+(float)Math.cos(a+2.5)*d(7),ay+(float)Math.sin(a+2.5)*d(7));tri.lineTo(ax+(float)Math.cos(a-2.5)*d(7),ay+(float)Math.sin(a-2.5)*d(7));tri.close();fill(c,0xffdce5ed);c.drawPath(tri,paint);}
            fill(c,0x99e8edf2);c.drawCircle(jx+moveX*d(25),jy-moveY*d(25),d(21),paint);stroke(0xfff8fbff,1);c.drawCircle(jx+moveX*d(25),jy-moveY*d(25),d(21),paint);
            // Sprint and crouch controls.
            action(c,d(205),h-d(205),d(27),2,sprintHeld);
            action(c,d(205),h-d(132),d(27),4,crouchHeld);
            action(c,d(205),h-d(65),d(27),3,slidePointer!=-1);
            // Combat controls are spaced vertically to avoid overlap.
            action(c,right-d(154),h-d(205),d(35),0,aimHeld);
            action(c,right-d(55),h-d(205),d(39),1,fireHeld);
            action(c,right-d(55),h-d(294),d(31),3,false);
            action(c,right-d(154),h-d(294),d(26),5,false);
            action(c,right-d(252),h-d(108),d(25),6,false);
            action(c,right-d(154),h-d(108),d(25),8,false);
            action(c,right-d(55),h-d(108),d(27),9,false);
            // Fortnite-like five-slot weapon bar anchored to the bottom safe area.
            float slot=d(54),gap=d(5),total=5*slot+4*gap,start=(w-total)/2f,slotY=h-d(67);
            for(int i=0;i<5;i++){
                quickX[i]=start+i*(slot+gap);boolean selected=i==g.activeQuickbarSlot;
                int weapon=i<g.quickbarWeapons.length?g.quickbarWeapons[i]:-1;
                panel(c,quickX[i]-d(1),slotY-d(1),quickX[i]+slot+d(1),slotY+slot+d(1),d(5),selected?0xfff5c84b:0xb51a222c);
                panel(c,quickX[i]+d(2),slotY+d(2),quickX[i]+slot-d(2),slotY+slot-d(2),d(4),selected?0xff426a87:0xe52c3947);
                stroke(selected?0xffffe18a:0xff9aa9b8,selected?2.2f:1);c.drawRoundRect(quickX[i]+d(2),slotY+d(2),quickX[i]+slot-d(2),slotY+slot-d(2),d(4),d(4),paint);paint.setStyle(Paint.Style.FILL);
                if(weapon>=0)weaponIcon(c,weapon,quickX[i]+slot/2,slotY+slot*.53f,slot*.84f);else icon(c,7,quickX[i]+slot/2,slotY+slot*.53f,slot*.55f);
                text(c,String.valueOf(i+1),quickX[i]+d(5),slotY+d(12),9,Color.WHITE,true);
            }
        }
        private void clearPointer(int id){
            if(movePointer==id){movePointer=-1;moveX=moveY=0;renderer.game.sx=renderer.game.sy=0;}
            if(cameraPointer==id)cameraPointer=-1;
            if(firePointer==id){firePointer=-1;fireHeld=false;renderer.game.weaponFireHeld=false;}
            if(aimPointer==id){aimPointer=-1;aimHeld=false;renderer.game.weaponAimHeld=false;}
            if(sprintPointer==id){sprintPointer=-1;sprintHeld=false;renderer.game.sprint=false;}
            if(crouchPointer==id){crouchPointer=-1;crouchHeld=false;renderer.game.crouching=false;}
            if(slidePointer==id){slidePointer=-1;renderer.game.crouching=false;renderer.game.sprint=false;}
            if(jumpPointer==id)jumpPointer=-1;if(reloadPointer==id)reloadPointer=-1;invalidate();
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            if(renderer==null)return false;int a=e.getActionMasked(),index=e.getActionIndex(),id=e.getPointerId(index);float x=e.getX(index),y=e.getY(index);
            if(a==MotionEvent.ACTION_DOWN||a==MotionEvent.ACTION_POINTER_DOWN){
                float left=d(16),right=getWidth()-d(16);
                if(pauseMenuOpen){float pw=Math.min(getWidth()-d(36),d(330)),ph=d(226),pl=(getWidth()-pw)/2f,pt=(getHeight()-ph)/2f;if(x>=pl&&x<=pl+pw&&y>=pt+d(83)&&y<=pt+d(132)){pauseMenuOpen=false;renderer.game.paused=false;invalidate();return true;}if(x>=pl&&x<=pl+pw&&y>=pt+d(145)&&y<=pt+d(194)){pauseMenuOpen=false;invalidate();showMapDialog(true);return true;}return true;}
                if(x>getWidth()-d(48)&&y<d(50)){pauseMenuOpen=true;renderer.game.paused=true;invalidate();return true;}
                float mr=Math.min(d(76),Math.min(getWidth()*.105f,getHeight()*.14f)),mx=right-mr-d(10),my=d(14)+mr+d(8);if(x>=mx-mr&&x<=mx+mr&&y>=my-mr&&y<=my+mr){showMapDialog(false);return true;}
                float jx=d(100),jy=getHeight()-d(105);if(x<d(170)&&y>getHeight()-d(190)){movePointer=id;moveX=Math.max(-1,Math.min(1,(x-jx)/d(48)));moveY=Math.max(-1,Math.min(1,(jy-y)/d(48)));renderer.game.sx=moveX;renderer.game.sy=moveY;invalidate();return true;}
                Game g=renderer.game;
                if(inside(x,y,d(205),getHeight()-d(205),d(33))){sprintPointer=id;sprintHeld=true;g.sprint=true;invalidate();return true;}
                if(inside(x,y,d(205),getHeight()-d(132),d(33))){crouchPointer=id;crouchHeld=true;g.crouching=true;invalidate();return true;}
                if(inside(x,y,d(205),getHeight()-d(65),d(33))){slidePointer=id;g.sprint=true;g.crouching=true;g.slideSeconds=Math.max(g.slideSeconds,.45f);invalidate();return true;}
                if(inside(x,y,right-d(55),getHeight()-d(200),d(42))){firePointer=id;fireHeld=true;g.weaponFireHeld=true;invalidate();return true;}
                if(inside(x,y,right-d(154),getHeight()-d(200),d(39))){aimPointer=id;aimHeld=true;g.weaponAimHeld=true;invalidate();return true;}
                if(inside(x,y,right-d(55),getHeight()-d(292),d(33))){jumpPointer=id;g.jumpRequested=true;invalidate();return true;}
                if(inside(x,y,right-d(154),getHeight()-d(285),d(29))){reloadPointer=id;g.reloadRequested=true;invalidate();return true;}
                if(inside(x,y,right-d(252),getHeight()-d(105),d(30))){g.punchRequested=true;invalidate();return true;}
                if(inside(x,y,right-d(154),getHeight()-d(105),d(30))){g.lootInteractionRequested=true;invalidate();return true;}
                if(inside(x,y,right-d(55),getHeight()-d(105),d(32))){renderer.toggleCar();invalidate();return true;}
                float slot=d(42),gap=d(4),total=5*slot+4*gap,start=(getWidth()-total)/2f,sy=getHeight()-d(54);
                if(y>=sy&&y<=sy+slot&&x>=start&&x<=start+total){int chosen=Math.max(0,Math.min(4,(int)((x-start)/(slot+gap))));if(g.selectQuickbarSlot(chosen))saveWeaponState();invalidate();return true;}
                if(x>getWidth()*.42f&&y<getHeight()*.82f){cameraPointer=id;cameraLastX=x;cameraLastY=y;return true;}return true;
            }
            if(a==MotionEvent.ACTION_MOVE){for(int p=0;p<e.getPointerCount();p++)if(e.getPointerId(p)==cameraPointer){float cx=e.getX(p),cy=e.getY(p);renderer.addLook(cx-cameraLastX,cy-cameraLastY);cameraLastX=cx;cameraLastY=cy;}for(int p=0;p<e.getPointerCount();p++)if(e.getPointerId(p)==movePointer){float jx=d(100),jy=getHeight()-d(105);moveX=Math.max(-1,Math.min(1,(e.getX(p)-jx)/d(48)));moveY=Math.max(-1,Math.min(1,(jy-e.getY(p))/d(48)));renderer.game.sx=moveX;renderer.game.sy=moveY;invalidate();}return true;}
            if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_POINTER_UP||a==MotionEvent.ACTION_CANCEL){if(a==MotionEvent.ACTION_CANCEL){if(movePointer!=-1)clearPointer(movePointer);if(cameraPointer!=-1)clearPointer(cameraPointer);if(firePointer!=-1)clearPointer(firePointer);if(aimPointer!=-1)clearPointer(aimPointer);if(sprintPointer!=-1)clearPointer(sprintPointer);if(crouchPointer!=-1)clearPointer(crouchPointer);if(slidePointer!=-1)clearPointer(slidePointer);}else clearPointer(id);return true;}return true;
        }
    }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        renderer = new CityRenderer(this, this);
        SharedPreferences audioPrefs = getSharedPreferences("adam_world_audio", MODE_PRIVATE);
        renderer.setAudioEnabled(audioPrefs.getBoolean("enabled", true));
        renderer.setAudioVolumes(audioPrefs.getInt("master", 82)/100f, audioPrefs.getInt("engine", 90)/100f,
                audioPrefs.getInt("ambience", 55)/100f, audioPrefs.getInt("effects", 85)/100f);
        loadWeaponState();
        glView = new CityGLView(this, renderer);
        glView.setFocusable(true);
        glView.setFocusableInTouchMode(true);
        root.addView(glView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        glView.requestFocus();
        weaponWheelView = new WeaponWheelView(this);
        weaponWheelView.setGame(renderer.game);
        glView.setWeaponWheelView(weaponWheelView);
        ActivityManager activityManager = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
        int defaultGraphicsQuality = activityManager != null && activityManager.isLowRamDevice() ? 2 : 1;
        renderer.setGraphicsQuality(getSharedPreferences("adam_world_settings", MODE_PRIVATE).getInt("graphics_quality",defaultGraphicsQuality));
        weaponWheelView.setListener(id -> {
            weaponWheelOpen = false;
            runOnGl(() -> {
                renderer.game.equipWeapon(id);
                runOnUiThread(this::saveWeaponState);
            });
        });
        FrameLayout.LayoutParams wheelParams = new FrameLayout.LayoutParams(dp(320), dp(320), Gravity.CENTER);
        root.addView(weaponWheelView, wheelParams);

        // The old Android Button/Joystick/health/weapon/minimap HUD is intentionally not created.
        aimReticle = new AimReticleView(this);
        FrameLayout.LayoutParams reticleParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT,Gravity.FILL);
        root.addView(aimReticle,reticleParams);

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

        battleRoyaleHud = new BattleRoyaleHudView(this);
        root.addView(battleRoyaleHud,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        
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

    @Override protected void onResume() {
        super.onResume();
        if (renderer != null) renderer.resumeAudio();
        glView.onResume();
        UpdateManager.resumePendingInstall(this);
    }

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
    @Override protected void onPause() {
        // Never leave held touch/gamepad actions latched when Android interrupts the activity.
        padL2 = padR2 = padCameraModifier = false;
        padHatX = padHatY = 0f;
        weaponWheelOpen = false;
        if (glView != null) glView.setWeaponWheelOpen(false);
        if (glView != null) glView.clearDigitalInputs();
        keyUp = keyDown = keyLeft = keyRight = false;
        if (renderer != null) {
            renderer.pauseAudio();
            saveWeaponState();
            renderer.game.sx = renderer.game.sy = 0f;
            renderer.game.cameraZoomInput = 0f;
            renderer.game.cameraLookOffset = 0f;
            renderer.setGamepadLook(0f, 0f);
            renderer.game.sprint = false;
            renderer.game.crouching = false;
            renderer.game.slideSeconds = 0f;
            renderer.game.jogging = false;
            renderer.game.stealthMode = false;
            renderer.game.guardRequested = false;
            renderer.game.jumpRequested = false;
            renderer.game.punchRequested = false;
            renderer.game.kickRequested = false;
            renderer.game.attackRequested = false;
            renderer.game.dodgeRequested = false;
            renderer.game.weaponAimHeld = false;
            renderer.game.weaponFireHeld = false;
            renderer.game.weaponFireRequested = false;
            renderer.game.buildRequested = false;
            renderer.game.editRequested = false;
            renderer.game.cycleBuildTypeRequested = false;
            renderer.game.reloadRequested = false;
            renderer.game.handbrake = false;
            renderer.game.brakeRequested = false;
            renderer.game.liftInput = 0f;
            renderer.game.rudderInput = 0f;
            renderer.game.throttleInput = 0f;
            renderer.game.weaponFireHeld = false;
            renderer.game.weaponAimHeld = false;
            renderer.game.brakeRequested = false;
        }
        glView.onPause();
        super.onPause();
    }
    @Override protected void onDestroy() { if (renderer != null) renderer.shutdown(); super.onDestroy(); }

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
        runOnUiThread(() -> {
            if (battleRoyaleHud != null) battleRoyaleHud.invalidate();
            if (aimReticle != null && renderer != null) {
                aimReticle.setAiming(renderer.game.weaponAimHeld);
                aimReticle.invalidate();
            }
        });
    }

    @Override public void onToast(final String s) {
        runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show(); } });
    }
}
