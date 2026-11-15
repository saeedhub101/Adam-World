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
    private TextView loading, hud;
    private EditText query;
    private Button timeBtn;
    private boolean keyUp, keyDown, keyLeft, keyRight;
    private AlertDialog pauseDialog;
    private boolean padL2, padR2, padCameraModifier;
    private float padHatX, padHatY;
    private TextView healthHud, weaponHud;
    private MiniMapView miniMap;
    private AimReticleView aimReticle;
    private WeaponWheelView weaponWheelView;
    private boolean weaponWheelOpen;
    private boolean mapOpen;
    private AlertDialog mapDialog;
    private FullMapView fullMapView;
    private long lastHudRefresh;
    private final Button[] quickbarButtons=new Button[5];
    private View touchActionPad;
    private LinearLayout touchCombatRow, touchFlightRow, touchGroundVehicleRow;
    private View touchActionScroll;
    private FrameLayout battleHudOverlay;
    private java.util.ArrayList<Button> battleHudButtons;
    private FrameLayout.LayoutParams touchActionScrollParams;
    private boolean tapFireMode, touchHudLeft, hudEditMode;
    private View touchMoveJoystick;
    private boolean touchSteerLeftHeld, touchSteerRightHeld, touchVehicleGasHeld, touchVehicleBrakeHeld;
    private Button touchVehicleButton, touchVehicleTypeButton, touchSprintButton, touchDodgeButton;
    private Button flightSchoolButton, returnCityButton, touchGearButton, touchFlightUpButton, touchFlightDownButton, touchBrakeButton;
    private LinearLayout touchFlightUtilityRow;


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
    private void refreshTouchVehicleSteering() {
        if (renderer == null) return;
        float value = (touchSteerRightHeld ? 1f : 0f) - (touchSteerLeftHeld ? 1f : 0f);
        runOnGl(() -> renderer.game.vehicleSteeringInput = value);
    }
    private void refreshTouchVehicleThrottle() {
        if (renderer == null) return;
        float value = (touchVehicleGasHeld ? 1f : 0f) - (touchVehicleBrakeHeld ? 1f : 0f);
        runOnGl(() -> renderer.game.vehicleForwardInput = value);
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
    private final class CameraLookSurface extends View {
        private float lastX,lastY;
        private boolean tracking;
        CameraLookSurface(Context c){super(c);setBackgroundColor(Color.TRANSPARENT);setClickable(true);}
        @Override public boolean onTouchEvent(MotionEvent e){
            float x=e.getX(),y=e.getY();
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    if(x<getWidth()*0.48f || y>getHeight()*0.78f)return false;
                    if(touchActionScroll!=null&&touchActionScroll.getVisibility()==View.VISIBLE){
                        int[] pos=new int[2];touchActionScroll.getLocationOnScreen(pos);
                        float rawX=e.getRawX(),rawY=e.getRawY();
                        if(rawX>=pos[0]&&rawX<=pos[0]+touchActionScroll.getWidth()
                                &&rawY>=pos[1]&&rawY<=pos[1]+touchActionScroll.getHeight())return false;
                    }
                    if(touchMoveJoystick!=null&&touchMoveJoystick.getVisibility()==View.VISIBLE){
                        int[] pos=new int[2];touchMoveJoystick.getLocationOnScreen(pos);
                        float rawX=e.getRawX(),rawY=e.getRawY();
                        if(rawX>=pos[0]&&rawX<=pos[0]+touchMoveJoystick.getWidth()
                                &&rawY>=pos[1]&&rawY<=pos[1]+touchMoveJoystick.getHeight())return false;
                    }
                    // The camera gesture surface sits above the HUD in the view stack.
                    // Pass touches through whenever they land on a visible action button.
                    if(battleHudOverlay!=null&&battleHudOverlay.getVisibility()==View.VISIBLE
                            &&battleHudButtons!=null){
                        float rawX=e.getRawX(),rawY=e.getRawY();
                        for(Button control:battleHudButtons){
                            if(control==null||control.getVisibility()!=View.VISIBLE)continue;
                            int[] pos=new int[2];control.getLocationOnScreen(pos);
                            if(rawX>=pos[0]&&rawX<=pos[0]+control.getWidth()
                                    &&rawY>=pos[1]&&rawY<=pos[1]+control.getHeight())return false;
                        }
                    }
                    tracking=true;lastX=x;lastY=y;return true;
                case MotionEvent.ACTION_MOVE:
                    if(!tracking)return false;
                    float dx=x-lastX,dy=y-lastY;lastX=x;lastY=y;
                    if(renderer!=null)renderer.addLook(dx,dy);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    tracking=false;return true;
            }
            return tracking;
        }
    }

    private final class MiniMapView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        MiniMapView(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w=getWidth(), h=getHeight(), cx=w/2f, cy=h/2f, rad=Math.min(w,h)*.47f;
            p.setColor(0xd91a2028); c.drawCircle(cx,cy,rad,p);
            c.save(); android.graphics.Path clip=new android.graphics.Path(); clip.addCircle(cx,cy,rad,android.graphics.Path.Direction.CW); c.clipPath(clip);
            p.setColor(0xffaeb9c4); p.setStrokeWidth(dp(2.2f)); p.setStrokeCap(Paint.Cap.ROUND); p.setStyle(Paint.Style.STROKE);
            float playerX=renderer.game.driving?renderer.game.cx:renderer.game.px;
            float playerZ=renderer.game.driving?renderer.game.cz:renderer.game.pz;
            float mapScale=rad/260f;
            float[] roads=renderer.miniMapRoads();
            for(int i=0;i+3<roads.length;i+=4){
                float x1=cx+(roads[i]-playerX)*mapScale, y1=cy+(roads[i+1]-playerZ)*mapScale;
                float x2=cx+(roads[i+2]-playerX)*mapScale, y2=cy+(roads[i+3]-playerZ)*mapScale;
                c.drawLine(x1,y1,x2,y2,p);
            }
            p.setColor(0xffd4d9df); p.setStrokeWidth(dp(1.4f)); c.drawLine(cx,cy-rad*.72f,cx,cy-rad*.9f,p); p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE); p.setTextSize(dp(10)); c.drawText("N",cx-dp(3),cy-rad*.76f,p);
            c.restore();
            p.setColor(0xff38d978); android.graphics.Path arrow=new android.graphics.Path();
            arrow.moveTo(cx,cy-dp(12)); arrow.lineTo(cx-dp(8),cy+dp(8)); arrow.lineTo(cx,cy+dp(4)); arrow.lineTo(cx+dp(8),cy+dp(8)); arrow.close();
            c.save(); c.rotate((float)Math.toDegrees(renderer.game.driving?renderer.game.cyaw:renderer.game.pyaw),cx,cy); c.drawPath(arrow,p); c.restore();
            p.setColor(Color.WHITE); p.setTextSize(dp(9)); c.drawText("MAP",cx-dp(11),h-dp(7),p);
        }
    }

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

    /** Single contextual Battle Royale layout. Coordinates are normalized and clamped to the safe screen. */
    private void layoutBattleHudControls(FrameLayout panel, java.util.ArrayList<Button> controls) {
        int w=panel.getWidth(), h=panel.getHeight();
        if(w<=0 || h<=0) return;
        boolean driving=renderer!=null && renderer.game.driving;
        boolean aircraft=driving && (renderer.game.vehicleType==ProceduralVehicleModels.HELICOPTER
                || renderer.game.vehicleType==ProceduralVehicleModels.AIRPLANE);
        boolean build=renderer!=null && renderer.game.buildMode;
        for(Button button:controls){
            String label=String.valueOf(button.getContentDescription());
            float x=.88f,y=.70f; int size=48; boolean visible=true;
            if(label.startsWith("خانة ")){
                int slot=0; try{slot=Integer.parseInt(label.substring(5))-1;}catch(Exception ignored){}
                x=.25f+slot*.095f; y=.945f; size=38;
                visible=!driving;
            } else if(label.contains("إطلاق")){x=.925f;y=.775f;size=70;visible=!driving&&!build;}
            else if(label.contains("تصويب")){x=.77f;y=.855f;size=54;visible=!driving&&!build;}
            else if(label.contains("قفز")){x=.925f;y=.575f;size=54;visible=!driving&&!build;}
            else if(label.contains("انحناء")){x=.82f;y=.68f;size=46;visible=!driving&&!build;}
            else if(label.contains("تلقيم")){x=.90f;y=.455f;size=42;visible=!driving&&!build;}
            else if(label.equals("ركض")){x=.19f;y=.69f;size=46;visible=!driving&&!build;}
            else if(label.contains("جمع الموارد")){x=.70f;y=.65f;size=46;visible=!driving&&!build;}
            else if(label.contains("التقاط")){x=.72f;y=.46f;size=44;visible=!driving&&!build;}
            else if(label.contains("علاج")){x=.73f;y=.945f;size=38;visible=!driving&&!build;}
            else if(label.contains("درع")){x=.82f;y=.945f;size=38;visible=!driving&&!build;}
            else if(label.equals("بناء")){x=.72f;y=.82f;size=46;visible=!driving;}
            else if(label.equals("قطعة البناء")){x=.82f;y=.82f;size=46;visible=!driving&&build;}
            else if(label.equals("تأكيد البناء")){x=.92f;y=.82f;size=50;visible=!driving&&build;}
            else if(label.contains("دخول / خروج")){x=.92f;y=.945f;size=44;visible=true;}
            else if(label.equals("تسارع")){x=.92f;y=.72f;size=60;visible=driving&&!aircraft;}
            else if(label.equals("فرامل")){x=.81f;y=.72f;size=50;visible=driving&&!aircraft;}
            else if(label.equals("زامور")){x=.72f;y=.83f;size=42;visible=driving&&!aircraft;}
            else if(label.equals("زيادة الدفع")){x=.92f;y=.72f;size=54;visible=aircraft;}
            else if(label.equals("تقليل الدفع")){x=.81f;y=.72f;size=48;visible=aircraft;}
            else if(label.equals("رفع المقدمة")){x=.92f;y=.55f;size=52;visible=aircraft;}
            else if(label.equals("خفض المقدمة")){x=.81f;y=.55f;size=48;visible=aircraft;}
            else if(label.equals("مكابح")){x=.72f;y=.84f;size=44;visible=aircraft&&renderer.game.vehicleType==ProceduralVehicleModels.AIRPLANE;}
            else if(label.equals("العجلات")){x=.82f;y=.84f;size=44;visible=aircraft&&renderer.game.vehicleType==ProceduralVehicleModels.AIRPLANE;}
            if(!visible){button.setVisibility(View.GONE);continue;}
            button.setVisibility(View.VISIBLE);
            int px=dp(size),cx=Math.round(w*x),cy=Math.round(h*y);
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(px,px,Gravity.TOP|Gravity.START);
            lp.leftMargin=Math.max(dp(4),Math.min(Math.max(dp(4),w-px-dp(4)),cx-px/2));
            lp.topMargin=Math.max(dp(4),Math.min(Math.max(dp(4),h-px-dp(4)),cy-px/2));
            button.setLayoutParams(lp);
            button.setElevation(dp(label.contains("إطلاق")?12:7));
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

    private void showHudButtonEditor(final View target) {
        final String key=String.valueOf(target.getTag());
        final SharedPreferences prefs=getSharedPreferences("adam_world_hud",MODE_PRIVATE);
        final SeekBar size=new SeekBar(this),opacity=new SeekBar(this);
        size.setMax(80);size.setProgress(Math.max(0,Math.min(80,Math.round((target.getScaleX()-0.7f)*100f))));
        opacity.setMax(70);opacity.setProgress(Math.max(0,Math.min(70,Math.round((target.getAlpha()-0.3f)*100f))));
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(18),dp(8),dp(18),dp(8));
        TextView sizeLabel=new TextView(this);sizeLabel.setText("حجم الزر");panel.addView(sizeLabel);panel.addView(size);
        TextView opacityLabel=new TextView(this);opacityLabel.setText("شفافية الزر");panel.addView(opacityLabel);panel.addView(opacity);
        final float oldScale=target.getScaleX(),oldAlpha=target.getAlpha();
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int value,boolean user){float s=0.7f+value/100f;target.setScaleX(s);target.setScaleY(s);}
            public void onStartTrackingTouch(SeekBar bar){} public void onStopTrackingTouch(SeekBar bar){}
        });
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int value,boolean user){target.setAlpha(0.3f+value/100f);}
            public void onStartTrackingTouch(SeekBar bar){} public void onStopTrackingTouch(SeekBar bar){}
        });
        new AlertDialog.Builder(this).setTitle("تخصيص: "+((target instanceof Button)?((Button)target).getText():"زر التحكم"))
                .setView(panel).setPositiveButton("حفظ",(d,w)->prefs.edit()
                        .putFloat(key+"_scale",target.getScaleX()).putFloat(key+"_alpha",target.getAlpha()).apply())
                .setNegativeButton("إلغاء",(d,w)->{target.setScaleX(oldScale);target.setScaleY(oldScale);target.setAlpha(oldAlpha);}).show();
    }

    private String touchControlIcon(String label) {
        if (label.contains("قفز")) return "↟";
        if (label.contains("لكم")) return "✊";
        if (label.contains("ركل")) return "🦶";
        if (label.contains("حراسة") || label.contains("دفاع")) return "🛡";
        if (label.contains("ركض") || label.contains("جري")) return "⚡";
        if (label.contains("مراوغة") || label.contains("انزلاق")) return "➤";
        if (label.contains("تصويب") || label.contains("هدف")) return "⌖";
        if (label.contains("إطلاق") || label.contains("نار")) return "●";
        if (label.contains("مركبة") || label.contains("دخول") || label.contains("خروج")) return "🚙";
        if (label.contains("مكابح") || label.contains("فرامل")) return "▣";
        if (label.contains("تسارع")) return "▲";
        if (label.contains("صعود") || label.contains("رفع")) return "⬆";
        if (label.contains("هبوط") || label.contains("خفض")) return "⬇";
        if (label.contains("تلقيم")) return "↻";
        if (label.contains("سلاح")) return "⚔";
        if (label.contains("التقاط") || label.contains("تبديل")) return "＋";
        if (label.contains("علاج")) return "✚";
        if (label.contains("درع")) return "⬡";
        if (label.contains("الحقيبة")) return "▤";
        if (label.contains("خانة 1")) return "1";
        if (label.contains("خانة 2")) return "2";
        if (label.contains("خانة 3")) return "3";
        if (label.contains("خانة 4")) return "4";
        if (label.contains("خانة 5")) return "5";
        if (label.equals("◀") || label.equals("▶")) return label;
        if (label.contains("زامور")) return "♫";
        return "•";
    }

    private Button touchAction(String label, final Runnable press, final Runnable release) {
        Button b=btn(label);
        String icon=touchControlIcon(label);
        b.setText(icon);
        b.setTextSize(label.contains("إطلاق")?30:22);
        b.setContentDescription(label);
        b.setTextColor(Color.WHITE);
        android.graphics.drawable.GradientDrawable face=new android.graphics.drawable.GradientDrawable();
        face.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        face.setColor(label.contains("إطلاق")?0xD9B83A32:0xA9233448);
        face.setStroke(dp(1.5f),label.contains("إطلاق")?0xFFFFD7C9:0xB8D6E8F5);
        b.setBackground(face);
        b.setPadding(dp(2),0,dp(2),0);
        b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));
        b.setClickable(true);b.setFocusable(false);
        final String key="hud_"+Integer.toHexString(label.hashCode());b.setTag(key);
        SharedPreferences hp=getSharedPreferences("adam_world_hud",MODE_PRIVATE);
        b.setScaleX(hp.getFloat(key+"_scale",1f));b.setScaleY(hp.getFloat(key+"_scale",1f));
        b.setAlpha(hp.getFloat(key+"_alpha",1f));b.setTranslationX(hp.getFloat(key+"_x",0f));b.setTranslationY(hp.getFloat(key+"_y",0f));
        b.setOnTouchListener(new View.OnTouchListener(){
            private int activePointerId=MotionEvent.INVALID_POINTER_ID;
            private boolean held,moved;private float downX,downY,startX,startY;
            private void finishPress(View view){if(!held)return;held=false;activePointerId=MotionEvent.INVALID_POINTER_ID;if(release!=null)release.run();view.setAlpha(getSharedPreferences("adam_world_hud",MODE_PRIVATE).getFloat(key+"_alpha",1f));}
            @Override public boolean onTouch(View v,MotionEvent e){
                int action=e.getActionMasked();
                if(hudEditMode){
                    if(action==MotionEvent.ACTION_DOWN){downX=e.getRawX();downY=e.getRawY();startX=v.getTranslationX();startY=v.getTranslationY();moved=false;return true;}
                    if(action==MotionEvent.ACTION_MOVE){float dx=e.getRawX()-downX,dy=e.getRawY()-downY;if(Math.abs(dx)+Math.abs(dy)>5f)moved=true;if(moved){v.setTranslationX(startX+dx);v.setTranslationY(startY+dy);}return true;}
                    if(action==MotionEvent.ACTION_UP){if(moved)getSharedPreferences("adam_world_hud",MODE_PRIVATE).edit().putFloat(key+"_x",v.getTranslationX()).putFloat(key+"_y",v.getTranslationY()).apply();else showHudButtonEditor(v);return true;}
                    return true;
                }
                if(action==MotionEvent.ACTION_DOWN){activePointerId=e.getPointerId(e.getActionIndex());held=true;if(press!=null)press.run();v.setAlpha(0.72f);return true;}
                if(action==MotionEvent.ACTION_POINTER_UP&&e.getPointerId(e.getActionIndex())==activePointerId){finishPress(v);return true;}
                if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){finishPress(v);return true;}
                if(action==MotionEvent.ACTION_MOVE&&held&&e.findPointerIndex(activePointerId)<0)finishPress(v);
                return true;
            }
        });
        return b;
    }

    private void makeHudElementEditable(final View view,String key){
        final SharedPreferences prefs=getSharedPreferences("adam_world_hud",MODE_PRIVATE);
        view.setTag(key);
        view.setScaleX(prefs.getFloat(key+"_scale",1f));view.setScaleY(prefs.getFloat(key+"_scale",1f));
        view.setAlpha(prefs.getFloat(key+"_alpha",1f));
        view.setTranslationX(prefs.getFloat(key+"_x",0f));view.setTranslationY(prefs.getFloat(key+"_y",0f));
        view.setClickable(true);
        view.setOnTouchListener(new View.OnTouchListener(){
            float downX,downY,startX,startY;boolean moved;
            @Override public boolean onTouch(View v,MotionEvent e){
                if(!hudEditMode)return false;
                int action=e.getActionMasked();
                if(action==MotionEvent.ACTION_DOWN){
                    downX=e.getRawX();downY=e.getRawY();startX=v.getTranslationX();startY=v.getTranslationY();moved=false;return true;
                }
                if(action==MotionEvent.ACTION_MOVE){
                    float dx=e.getRawX()-downX,dy=e.getRawY()-downY;
                    if(Math.abs(dx)+Math.abs(dy)>5f)moved=true;
                    if(moved){v.setTranslationX(startX+dx);v.setTranslationY(startY+dy);}return true;
                }
                if(action==MotionEvent.ACTION_UP){
                    if(moved)prefs.edit().putFloat(key+"_x",v.getTranslationX()).putFloat(key+"_y",v.getTranslationY()).apply();
                    else showHudButtonEditor(v);
                    return true;
                }
                return true;
            }
        });
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
        makeHudElementEditable(b,"admin_"+Integer.toHexString(t.hashCode()));
        return b;
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
        Button garage = btn("المركبات");
        garage.setOnClickListener(v -> showVehicleSelector());
        chips.addView(garage);
        flightSchoolButton = btn("المطارات");
        flightSchoolButton.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("اختر المطار الحقيقي")
                .setItems(new String[]{"مطار عمان المدني — ماركا","مطار الملكة علياء الدولي"},(dialog,which)->{
                    if(which==0)runOnGl(()->renderer.enterAirport(31.9726,35.9916));
                    else runOnGl(()->renderer.enterAirport(31.7226,35.9932));
                }).show());
        chips.addView(flightSchoolButton);
        returnCityButton = btn("عودة للمدينة");
        returnCityButton.setVisibility(View.GONE);
        returnCityButton.setOnClickListener(v -> runOnGl(() -> renderer.leaveFlightSchool()));
        chips.addView(returnCityButton);
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
        // City selection and search are available from Pause / Settings, never over the driving view.

        // Fortnite-style Battle Royale HUD: one joystick, one camera zone and one contextual action layer.
        JoystickView js = new JoystickView(this);
        touchMoveJoystick = js;
        js.setListener(new JoystickView.Listener() {
            @Override public void onMove(float x, float y) {
                Game g = renderer.game;
                if (g.driving) {
                    g.vehicleSteeringInput = x;
                    g.vehicleForwardInput = y;
                    g.sx = g.sy = 0f;
                } else {
                    g.sx = x; g.sy = y;
                    g.vehicleSteeringInput = g.vehicleForwardInput = 0f;
                }
            }
        });
        FrameLayout.LayoutParams jp = new FrameLayout.LayoutParams(dp(156), dp(156), Gravity.BOTTOM | Gravity.START);
        jp.setMargins(dp(24), 0, 0, dp(28));
        root.addView(js, jp);

        tapFireMode = getSharedPreferences("adam_world_controls", MODE_PRIVATE).getBoolean("tap_fire", false);
        touchHudLeft = false;
        battleHudOverlay = new FrameLayout(this);
        battleHudOverlay.setClipChildren(false);
        battleHudOverlay.setClipToPadding(false);
        battleHudOverlay.setBackgroundColor(Color.TRANSPARENT);
        battleHudButtons = new java.util.ArrayList<>();
        touchActionPad = battleHudOverlay;
        touchActionScroll = null;
        touchCombatRow = null;
        touchGroundVehicleRow = null;
        touchFlightRow = null;
        touchFlightUtilityRow = null;

        final java.util.ArrayList<Button> controls = battleHudButtons;
        Button fire = touchAction("إطلاق", () -> runOnGl(() -> {
            if (tapFireMode) renderer.game.weaponFireRequested = true;
            else renderer.game.weaponFireHeld = true;
        }), () -> { if (!tapFireMode) runOnGl(() -> renderer.game.weaponFireHeld = false); });
        fire.setText("●"); fire.setTextSize(30);
        controls.add(fire);

        Button aim = touchAction("تصويب", () -> runOnGl(() -> renderer.game.weaponAimHeld = true),
                () -> runOnGl(() -> renderer.game.weaponAimHeld = false));
        aim.setText("◎"); controls.add(aim);

        Button jump = touchAction("قفز", () -> runOnGl(() -> { if (!renderer.game.driving) renderer.game.jumpRequested = true; }), null);
        jump.setText("↑"); controls.add(jump);

        Button crouch = touchAction("انحناء", () -> runOnGl(() -> { if (!renderer.game.driving) renderer.game.crouching = true; }),
                () -> runOnGl(() -> renderer.game.crouching = false));
        crouch.setText("↓"); controls.add(crouch);

        Button reload = touchAction("تلقيم", () -> runOnGl(() -> renderer.game.reloadRequested = true), null);
        reload.setText("↻"); controls.add(reload);

        touchSprintButton = touchAction("ركض", () -> runOnGl(() -> renderer.game.sprint = true),
                () -> runOnGl(() -> renderer.game.sprint = false));
        touchSprintButton.setText("⚡"); controls.add(touchSprintButton);

        Button harvest = touchAction("جمع الموارد", () -> runOnGl(() -> renderer.game.harvestRequested = true), null);
        harvest.setText("⛏"); controls.add(harvest);

        Button pickup = touchAction("التقاط", () -> runOnGl(() -> renderer.game.lootInteractionRequested = true), null);
        pickup.setText("⇧"); controls.add(pickup);

        for (int slot = 0; slot < 5; slot++) {
            final int quickSlot = slot;
            Button quick = touchAction("خانة " + (slot + 1), () -> runOnGl(() -> {
                if (renderer.game.selectQuickbarSlot(quickSlot)) runOnUiThread(this::saveWeaponState);
            }), null);
            quick.setText(slot == 0 ? "1" : String.valueOf(slot + 1));
            quickbarButtons[slot] = quick;
            controls.add(quick);
        }

        Button medkit = touchAction("علاج", () -> runOnGl(() -> renderer.game.useMedkitRequested = true), null);
        medkit.setText("+"); controls.add(medkit);
        Button shield = touchAction("درع", () -> runOnGl(() -> renderer.game.useShieldPotionRequested = true), null);
        shield.setText("◇"); controls.add(shield);

        Button buildMode = touchAction("بناء", () -> runOnGl(() -> {
            renderer.game.buildMode = !renderer.game.buildMode;
            renderer.game.buildEditMode = false;
        }), null);
        buildMode.setText("▦"); controls.add(buildMode);
        Button buildPiece = touchAction("قطعة البناء", () -> runOnGl(() -> renderer.game.cycleBuildTypeRequested = true), null);
        buildPiece.setText("▱"); controls.add(buildPiece);
        Button buildPlace = touchAction("تأكيد البناء", () -> runOnGl(() -> renderer.game.buildRequested = true), null);
        buildPlace.setText("✓"); controls.add(buildPlace);

        touchVehicleButton = touchAction("دخول / خروج", () -> runOnGl(() -> renderer.toggleCar()), null);
        touchVehicleButton.setText("🚗"); controls.add(touchVehicleButton);
        Button gas = touchAction("تسارع", () -> { touchVehicleGasHeld = true; refreshTouchVehicleThrottle(); },
                () -> { touchVehicleGasHeld = false; refreshTouchVehicleThrottle(); });
        gas.setText("▲"); controls.add(gas);
        Button brake = touchAction("فرامل", () -> { touchVehicleBrakeHeld = true; refreshTouchVehicleThrottle(); },
                () -> { touchVehicleBrakeHeld = false; refreshTouchVehicleThrottle(); });
        brake.setText("▼"); controls.add(brake);
        Button horn = touchAction("زامور", () -> renderer.playHorn(), null);
        horn.setText("♫"); controls.add(horn);

        Button throttleUp = touchAction("زيادة الدفع", () -> runOnGl(() -> renderer.game.throttleInput = 1f),
                () -> runOnGl(() -> renderer.game.throttleInput = 0f));
        throttleUp.setText("▲"); controls.add(throttleUp);
        Button throttleDown = touchAction("تقليل الدفع", () -> runOnGl(() -> renderer.game.throttleInput = -1f),
                () -> runOnGl(() -> renderer.game.throttleInput = 0f));
        throttleDown.setText("▼"); controls.add(throttleDown);
        touchFlightUpButton = touchAction("رفع المقدمة", () -> runOnGl(() -> renderer.game.liftInput = 1f),
                () -> runOnGl(() -> renderer.game.liftInput = 0f));
        touchFlightUpButton.setText("↑"); controls.add(touchFlightUpButton);
        touchFlightDownButton = touchAction("خفض المقدمة", () -> runOnGl(() -> renderer.game.liftInput = -1f),
                () -> runOnGl(() -> renderer.game.liftInput = 0f));
        touchFlightDownButton.setText("↓"); controls.add(touchFlightDownButton);
        touchBrakeButton = touchAction("مكابح", () -> runOnGl(() -> renderer.game.brakeRequested = true),
                () -> runOnGl(() -> renderer.game.brakeRequested = false));
        touchBrakeButton.setText("■"); controls.add(touchBrakeButton);
        touchGearButton = touchAction("العجلات", () -> runOnGl(() -> renderer.toggleAircraftGear()), null);
        touchGearButton.setText("⚙"); controls.add(touchGearButton);

        battleHudOverlay.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) ->
                layoutBattleHudControls(battleHudOverlay, controls));
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            js.setVisibility(View.GONE);
            battleHudOverlay.setVisibility(View.GONE);
        }

        hud = new TextView(this);
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(13);
        hud.setShadowLayer(2, 0, 1, Color.BLACK);
        hud.setSingleLine(false);
        hud.setPadding(dp(9),dp(5),dp(9),dp(5));
        hud.setBackgroundColor(0x990d141c);
        hud.setVisibility(View.GONE);
        makeHudElementEditable(hud,"hud_status_panel");
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        hp.setMargins(0, 0, dp(24), dp(190));
        root.addView(hud, hp);

        weaponHud = new TextView(this);
        weaponHud.setTextColor(0xfff4f7fb);
        weaponHud.setTextSize(13);
        weaponHud.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));
        weaponHud.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        weaponHud.setTextDirection(View.TEXT_DIRECTION_RTL);
        weaponHud.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
        weaponHud.setIncludeFontPadding(false);
        weaponHud.setLineSpacing(dp(3), 1.0f);
        weaponHud.setShadowLayer(1.5f, 0, 1, 0x99000000);
        weaponHud.setMinWidth(dp(154));
        weaponHud.setPadding(dp(14),dp(10),dp(14),dp(10));
        android.graphics.drawable.GradientDrawable weaponCard = new android.graphics.drawable.GradientDrawable();
        weaponCard.setColor(0xe615202d);
        weaponCard.setCornerRadius(dp(12));
        weaponCard.setStroke(dp(1), 0xff53677d);
        weaponHud.setBackground(weaponCard);
        weaponHud.setElevation(dp(8));
        weaponHud.setText("مسدس\n12   /   48");
        makeHudElementEditable(weaponHud,"hud_weapon_panel");
        FrameLayout.LayoutParams weaponParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,Gravity.TOP|Gravity.END);
        weaponParams.setMargins(0,dp(170),dp(16),0);
        root.addView(weaponHud,weaponParams);

        healthHud = new TextView(this);
        healthHud.setTextColor(0xff65e58b);
        healthHud.setTextSize(16);
        healthHud.setShadowLayer(4,0,0,Color.BLACK);
        healthHud.setText("♥ 100 / 100");
        makeHudElementEditable(healthHud,"hud_health_panel");
        FrameLayout.LayoutParams healthParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        healthParams.setMargins(dp(18), 0, 0, dp(214));
        root.addView(healthHud, healthParams);

        miniMap = new MiniMapView(this);
        miniMap.setOnClickListener(v -> showMapDialog(false));
        makeHudElementEditable(miniMap,"hud_minimap");
        FrameLayout.LayoutParams miniParams = new FrameLayout.LayoutParams(dp(142), dp(142), Gravity.TOP | Gravity.END);
        miniParams.setMargins(0, dp(18), dp(14), 0);
        root.addView(miniMap, miniParams);

        CameraLookSurface cameraLookSurface=new CameraLookSurface(this);
        root.addView(cameraLookSurface,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        aimReticle = new AimReticleView(this);
        FrameLayout.LayoutParams reticleParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT,Gravity.FILL);
        root.addView(aimReticle,reticleParams);

        Button settings = btn("⚙");
        settings.setOnClickListener(v -> showGameSettings());
        FrameLayout.LayoutParams settingsParams = new FrameLayout.LayoutParams(dp(48),dp(44),Gravity.TOP | Gravity.START);
        settingsParams.setMargins(dp(8),dp(8),0,0);
        root.addView(settings,settingsParams);

        TextView credit = new TextView(this);
        credit.setText("© مساهمو OpenStreetMap · الارتفاعات: Mapzen/AWS Terrain Tiles");
        credit.setTextColor(0xc0ffffff);
        credit.setTextSize(10);
        credit.setVisibility(View.GONE);
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
        root.addView(battleHudOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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

    private boolean isAircraftDriven() {
        return renderer != null && renderer.game.driving
                && (renderer.game.vehicleType == ProceduralVehicleModels.HELICOPTER
                || renderer.game.vehicleType == ProceduralVehicleModels.AIRPLANE);
    }

    private void updateTouchActionVisibility() {
        if (renderer == null) return;
        boolean flightSchool = renderer.isFlightSchoolMode();
        if (flightSchoolButton != null) flightSchoolButton.setVisibility(flightSchool ? View.GONE : View.VISIBLE);
        if (returnCityButton != null) returnCityButton.setVisibility(flightSchool ? View.VISIBLE : View.GONE);
        boolean touch = getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        if (touchMoveJoystick != null) touchMoveJoystick.setVisibility(touch ? View.VISIBLE : View.GONE);
        if (battleHudOverlay != null) {
            battleHudOverlay.setVisibility(touch ? View.VISIBLE : View.GONE);
            if (touch && battleHudButtons != null) layoutBattleHudControls(battleHudOverlay, battleHudButtons);
        }
        boolean driving = renderer.game.driving;
        if (touchVehicleButton != null) touchVehicleButton.setText(driving ? "خروج" : "🚗");
        if (touchGearButton != null) touchGearButton.setVisibility(driving
                && renderer.game.vehicleType == ProceduralVehicleModels.AIRPLANE ? View.VISIBLE : View.GONE);
    }

    @Override public void onHud(final String s) {
        runOnUiThread(new Runnable() { @Override public void run() {
            String lootHint = renderer == null ? "" : renderer.game.nearbyLootHint();
            String displayHud = lootHint.isEmpty() ? s : s + "\n" + lootHint;
            if (!displayHud.equals(hud.getText().toString())) hud.setText(displayHud);
            updateTouchActionVisibility();
            if (System.currentTimeMillis()-lastHudRefresh>250 && renderer!=null) {
                lastHudRefresh=System.currentTimeMillis();
                if(battleHudOverlay!=null && battleHudButtons!=null) layoutBattleHudControls(battleHudOverlay,battleHudButtons);
                int hp=renderer.game.health;
                healthHud.setText("♥ "+hp+"     🛡 "+renderer.game.shield);
                Game game=renderer.game;
                int weaponRarity=WeaponSystem.RARITY[game.equippedWeapon];
                String weaponName=WeaponSystem.NAME[game.equippedWeapon]+" · "+WeaponSystem.RARITY_LABEL[weaponRarity];
                String ammo=weaponName+"\n"+game.magazineAmmo+"   /   "+game.reserveAmmo[game.equippedWeapon];
                if(weaponHud!=null&&!ammo.equals(weaponHud.getText().toString())){
                    android.text.SpannableStringBuilder styled=new android.text.SpannableStringBuilder(ammo);
                    int split=weaponName.length();
                    styled.setSpan(new android.text.style.ForegroundColorSpan(0xffffc857),0,split,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    styled.setSpan(new android.text.style.RelativeSizeSpan(0.78f),0,split,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    styled.setSpan(new android.text.style.ForegroundColorSpan(0xfff4f7fb),split+1,styled.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    styled.setSpan(new android.text.style.RelativeSizeSpan(1.22f),split+1,styled.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    weaponHud.setText(styled);
                }
                for(int slot=0;slot<quickbarButtons.length;slot++){
                    String label=(slot==game.activeQuickbarSlot?"● ":"")+(slot+1);
                    if(quickbarButtons[slot]!=null&&!label.equals(quickbarButtons[slot].getText().toString()))quickbarButtons[slot].setText(label);
                }
                if(aimReticle!=null){aimReticle.setAiming(game.weaponAimHeld);aimReticle.invalidate();}
                healthHud.setTextColor(hp>60?0xff65e58b:hp>25?0xffffc857:0xffff5555);
                miniMap.invalidate();
            }
        }});
    }

    @Override public void onToast(final String s) {
        runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show(); } });
    }
}
