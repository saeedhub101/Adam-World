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
    private LinearLayout touchActionPad, touchCombatRow, touchFlightRow;
    private Button touchVehicleButton, touchVehicleTypeButton, touchSprintButton, touchDodgeButton;
    private Button flightSchoolButton, returnCityButton, touchGearButton, touchFlightUpButton, touchFlightDownButton, touchBrakeButton;
    private LinearLayout touchFlightUtilityRow;


    private int dp(int v) { return dp((float) v); }
    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private void updateRemoteMovement() {
        if (renderer == null) return;
        renderer.game.sx = (keyRight ? 1f : 0f) - (keyLeft ? 1f : 0f);
        renderer.game.sy = (keyUp ? 1f : 0f) - (keyDown ? 1f : 0f);
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
                .setMessage("على الهاتف: العصا اليسرى للحركة، واسحب على مساحة اللعب لتحريك الكاميرا، واستخدم أزرار القفز واللكم والركل والصد والركض والمراوغة. زر المركبة للاستدعاء والدخول والخروج، ومن قائمة المركبات اختر الهليكوبتر أو الطائرة. في مطار التدريب: زد الدفع حتى سرعة الإقلاع، ارفع مقدمة الطائرة، ثم اخفضها تدريجياً للهبوط. استخدم المكابح والعجلات؛ والهليكوبتر تتحكم بارتفاعها من أزرار الصعود والهبوط. زر العودة يعيدك إلى موقعك السابق. على التلفاز (توزيع قريب من GTA): العصا اليسرى للحركة أو التوجيه، واليمنى للكاميرا. اضغط مطولاً LB/L1 لعجلة السلاح، واختر بالعصا اليمنى أو الأسهم ثم اترك الزر للتأكيد. LT/L2 للتصويب أو الفرامل، RT/R2 للإطلاق أو التسارع، A للقفز أو فرامل اليد، B للكم أو النظر للخلف (وفي الطائرة لفتح/طي العجلات)، X للتلقيم أو البوق، Y للدخول والخروج، RB/R1 للصد أو فرامل اليد، L3 للركض، R3 للمراوغة، الأسهم أعلى/أسفل للزووم، والأسهم يمين/يسار لتبديل السلاح، وView للخريطة وMenu للإيقاف.")
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

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        final int rawCode = event.getKeyCode();
        final int code = normalizeGamepadKeyCode(rawCode);
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final boolean pressed = down && event.getRepeatCount() == 0;
        if (renderer == null) return super.dispatchKeyEvent(event);
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
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (weaponWheelOpen && pressed) {
                weaponWheelView.moveSelection(code == KeyEvent.KEYCODE_DPAD_UP ? 1 : -1);
                return true;
            }
            renderer.game.cameraZoomInput = down ? (code == KeyEvent.KEYCODE_DPAD_UP ? 1f : -1f) : 0f;
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (pressed) {
                int direction = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                if (weaponWheelOpen) weaponWheelView.moveSelection(direction);
                else if (!renderer.game.driving) cycleEquippedWeapon(direction);
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
        if (code == KeyEvent.KEYCODE_BUTTON_A) {
            if (renderer.game.driving) renderer.game.handbrake = down;
            else if (pressed) runOnGl(() -> renderer.game.jumpRequested = true);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_B) {
            if (renderer.game.driving && renderer.game.vehicleType == ProceduralVehicleModels.AIRPLANE && pressed)
                runOnGl(() -> renderer.toggleAircraftGear());
            else if (renderer.game.driving) renderer.game.cameraLookOffset = down ? (float)Math.PI : 0f;
            else if (pressed) runOnGl(() -> renderer.game.punchRequested = true);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_X) {
            if (renderer.game.driving) { if (pressed) playHorn(); }
            else if (pressed) runOnGl(() -> renderer.game.reloadRequested = true);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_Y) {
            if (pressed) runOnGl(() -> renderer.toggleCar());
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_L1) {
            if (!renderer.game.driving) {
                if (down && !weaponWheelOpen) {
                    weaponWheelOpen = true;
                    glView.setWeaponWheelOpen(true);
                    runOnUiThread(() -> weaponWheelView.showWheel());
                } else if (!down && weaponWheelOpen) {
                    weaponWheelOpen = false;
                    glView.setWeaponWheelOpen(false);
                    final int chosen = weaponWheelView.selectedWeapon();
                    runOnGl(() -> {
                        renderer.game.equipWeapon(chosen);
                        runOnUiThread(this::saveWeaponState);
                    });
                    runOnUiThread(() -> weaponWheelView.hideWheel());
                }
            } else renderer.game.cameraLookOffset = down ? (float)Math.PI : 0f;
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_R1) {
            if (renderer.game.driving) renderer.game.handbrake = down;
            else renderer.game.guardRequested = down;
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_L2 || code == KeyEvent.KEYCODE_BUTTON_R2) {
            padL2 = code == KeyEvent.KEYCODE_BUTTON_L2 ? down : padL2;
            padR2 = code == KeyEvent.KEYCODE_BUTTON_R2 ? down : padR2;
            if (renderer.game.driving) {
                if (renderer.game.vehicleType == ProceduralVehicleModels.AIRPLANE) {
                    renderer.game.brakeRequested = padL2;
                    renderer.game.throttleInput = padR2 ? 1f : 0f;
                } else if (renderer.game.vehicleType == ProceduralVehicleModels.HELICOPTER)
                    renderer.game.throttleInput = (padR2 ? 1f : 0f) - (padL2 ? 1f : 0f);
                else renderer.game.sy = (padR2 ? 1f : 0f) - (padL2 ? 1f : 0f);
            } else {
                renderer.game.weaponAimHeld = padL2;
                renderer.game.weaponFireHeld = padR2;
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_THUMBL) { renderer.game.sprint = down; return true; }
        if (code == KeyEvent.KEYCODE_BUTTON_THUMBR) {
            if (renderer.game.driving) renderer.game.cameraLookOffset = down ? (float)Math.PI : 0f;
            else if (pressed) runOnGl(() -> renderer.game.dodgeRequested = true);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (pressed) showMapDialog(false);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_START || code == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (pressed) showPauseMenu();
            return true;
        }
        if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (pressed) runOnGl(() -> renderer.toggleCar());
            return true;
        }
        return super.dispatchKeyEvent(event);
    }


    // Android TV / Bluetooth / USB gamepads: left stick moves, right stick looks.
    // While LB is held, the right stick selects a weapon instead of rotating the camera.
    @Override public boolean onGenericMotionEvent(android.view.MotionEvent event) {
        final int source = event.getSource();
        if (renderer == null || event.getActionMasked() != android.view.MotionEvent.ACTION_MOVE
                || (source & android.view.InputDevice.SOURCE_CLASS_JOYSTICK) == 0) {
            return super.onGenericMotionEvent(event);
        }
        android.view.InputDevice device = event.getDevice();
        float lx = event.getAxisValue(android.view.MotionEvent.AXIS_X);
        float ly = -event.getAxisValue(android.view.MotionEvent.AXIS_Y); // stick-up is negative on Android
        float rx = event.getAxisValue(android.view.MotionEvent.AXIS_RX);
        float ry = event.getAxisValue(android.view.MotionEvent.AXIS_RY);
        final boolean hasRxRy = device != null && (hasMotionRange(device, android.view.MotionEvent.AXIS_RX, source)
                || hasMotionRange(device, android.view.MotionEvent.AXIS_RY, source));
        if (device != null && !hasRxRy
                && hasMotionRange(device, android.view.MotionEvent.AXIS_Z, source)
                && hasMotionRange(device, android.view.MotionEvent.AXIS_RZ, source)) {
            rx = event.getAxisValue(android.view.MotionEvent.AXIS_Z);
            ry = event.getAxisValue(android.view.MotionEvent.AXIS_RZ);
        }
        final float dead = 0.16f;
        lx = applyStickDeadZone(lx, dead);
        ly = applyStickDeadZone(ly, dead);
        rx = applyStickDeadZone(rx, dead);
        ry = applyStickDeadZone(ry, dead);
        renderer.game.sx = lx;
        renderer.game.sy = ly;
        int leftTriggerAxis = device == null ? -1 : firstAvailableAxis(device, source,
                android.view.MotionEvent.AXIS_LTRIGGER, android.view.MotionEvent.AXIS_BRAKE);
        int rightTriggerAxis = device == null ? -1 : firstAvailableAxis(device, source,
                android.view.MotionEvent.AXIS_RTRIGGER, android.view.MotionEvent.AXIS_GAS);
        boolean hasLeftTrigger = leftTriggerAxis >= 0;
        boolean hasRightTrigger = rightTriggerAxis >= 0;
        float leftTrigger = hasLeftTrigger ? readTriggerAxis(event, device, leftTriggerAxis, source) : 0f;
        float rightTrigger = hasRightTrigger ? readTriggerAxis(event, device, rightTriggerAxis, source) : 0f;
        if (hasLeftTrigger) padL2 = leftTrigger > 0.18f;
        if (hasRightTrigger) padR2 = rightTrigger > 0.18f;
        if (hasLeftTrigger || hasRightTrigger || padL2 || padR2) {
            if (renderer.game.driving) {
                if (renderer.game.vehicleType == ProceduralVehicleModels.AIRPLANE) {
                    if (hasLeftTrigger) renderer.game.brakeRequested = padL2;
                    if (hasRightTrigger) renderer.game.throttleInput = rightTrigger;
                } else if (renderer.game.vehicleType == ProceduralVehicleModels.HELICOPTER) {
                    renderer.game.throttleInput = (hasRightTrigger ? rightTrigger : (padR2 ? 1f : 0f))
                            - (hasLeftTrigger ? leftTrigger : (padL2 ? 1f : 0f));
                } else {
                    float triggerDrive = (hasRightTrigger ? rightTrigger : (padR2 ? 1f : 0f))
                            - (hasLeftTrigger ? leftTrigger : (padL2 ? 1f : 0f));
                    renderer.game.sy = Math.abs(triggerDrive) > 0.05f ? triggerDrive : ly;
                }
            } else {
                renderer.game.weaponAimHeld = padL2;
                renderer.game.weaponFireHeld = padR2;
            }
        }
        if (device != null && (hasMotionRange(device, android.view.MotionEvent.AXIS_HAT_X, source)
                || hasMotionRange(device, android.view.MotionEvent.AXIS_HAT_Y, source))) {
            float hx = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X);
            float hy = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y);
            handleHatDpad(hx, hy);
        }
        if (weaponWheelOpen && weaponWheelView != null) {
            if (Math.abs(rx) > 0.01f || Math.abs(ry) > 0.01f) weaponWheelView.selectDirection(rx, ry);
            renderer.setGamepadLook(0f, 0f);
        } else renderer.setGamepadLook(rx, ry);
        return true;
    }

    private void handleHatDpad(float x, float y) {
        float nx = Math.abs(x) > 0.5f ? Math.signum(x) : 0f;
        float ny = Math.abs(y) > 0.5f ? Math.signum(y) : 0f;
        if (mapOpen && fullMapView != null) {
            renderer.game.cameraZoomInput = 0f;
            if (nx != padHatX && nx != 0f) fullMapView.panBy(nx < 0f ? dp(72) : -dp(72), 0f);
            if (ny != padHatY && ny != 0f) fullMapView.panBy(0f, ny < 0f ? dp(72) : -dp(72));
        } else if (weaponWheelOpen && weaponWheelView != null) {
            renderer.game.cameraZoomInput = 0f;
            if (nx != padHatX && nx != 0f) weaponWheelView.moveSelection(nx > 0f ? 1 : -1);
            if (ny != padHatY && ny != 0f) weaponWheelView.moveSelection(ny < 0f ? 1 : -1);
        } else {
            renderer.game.cameraZoomInput = ny == 0f ? 0f : (ny < 0f ? 1f : -1f);
            if (nx != padHatX && nx != 0f && !renderer.game.driving)
                cycleEquippedWeapon(nx > 0f ? 1 : -1);
        }
        padHatX = nx; padHatY = ny;
    }

    private static boolean hasMotionRange(android.view.InputDevice device, int axis, int source) {
        return device != null && device.getMotionRange(axis, source) != null;
    }

    private static int firstAvailableAxis(android.view.InputDevice device, int source, int first, int second) {
        if (hasMotionRange(device, first, source)) return first;
        if (hasMotionRange(device, second, source)) return second;
        return -1;
    }

    private static float readTriggerAxis(android.view.MotionEvent event, android.view.InputDevice device, int axis, int source) {
        android.view.InputDevice.MotionRange range = device.getMotionRange(axis, source);
        if (range == null) return 0f;
        float span = range.getMax() - range.getMin();
        if (span <= 0.0001f) return 0f;
        float value = (event.getAxisValue(axis) - range.getMin()) / span;
        return Math.max(0f, Math.min(1f, value));
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

    private final class AimReticleView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean aiming;
        AimReticleView(Context c){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);setClickable(false);}
        void setAiming(boolean value){aiming=value;}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            float cx=getWidth()/2f,cy=getHeight()/2f;
            paint.setColor(aiming?0xfffff1c2:0xddefffff);paint.setStrokeWidth(dp(1.5f));paint.setStyle(Paint.Style.STROKE);
            float r=aiming?dp(8):dp(5),gap=aiming?dp(5):dp(3),len=aiming?dp(8):dp(5);
            c.drawCircle(cx,cy,r,paint);
            c.drawLine(cx-len-gap,cy,cx-gap,cy,paint);c.drawLine(cx+gap,cy,cx+len+gap,cy,paint);
            c.drawLine(cx,cy-len-gap,cx,cy-gap,paint);c.drawLine(cx,cy+gap,cx,cy+len+gap,paint);
            paint.setStyle(Paint.Style.FILL);c.drawCircle(cx,cy,dp(1.2f),paint);
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

    private void loadWeaponState(){
        SharedPreferences p=getSharedPreferences("adam_world_weapons",MODE_PRIVATE);Game g=renderer.game;g.cash=p.getInt("cash",g.cash);
        for(int i=0;i<WeaponSystem.NAME.length;i++){g.ownedWeapons[i]=p.getBoolean("owned_"+i,g.ownedWeapons[i]);g.reserveAmmo[i]=p.getInt("reserve_"+i,g.reserveAmmo[i]);g.weaponMagazines[i]=p.getInt("magazine_"+i,g.weaponMagazines[i]);}
        int selected=p.getInt("equipped",0);if(selected<0||selected>=g.ownedWeapons.length||!g.ownedWeapons[selected])selected=0;g.equippedWeapon=selected;g.magazineAmmo=p.contains("magazine_"+selected)?g.weaponMagazines[selected]:WeaponSystem.MAGAZINE[selected];
    }
    private void saveWeaponState(){if(renderer==null)return;Game g=renderer.game;g.weaponMagazines[g.equippedWeapon]=g.magazineAmmo;SharedPreferences.Editor e=getSharedPreferences("adam_world_weapons",MODE_PRIVATE).edit().putInt("cash",g.cash).putInt("equipped",g.equippedWeapon);for(int i=0;i<WeaponSystem.NAME.length;i++)e.putBoolean("owned_"+i,g.ownedWeapons[i]).putInt("reserve_"+i,g.reserveAmmo[i]).putInt("magazine_"+i,g.weaponMagazines[i]);e.apply();}

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

    private Button touchAction(String label, final Runnable press, final Runnable release) {
        Button b = btn(label);
        b.setTextSize(12);
        b.setMinHeight(dp(46));
        b.setBackgroundColor(0xB91B2634);
        b.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    if (press != null) press.run();
                    v.setAlpha(0.72f);
                    return true;
                }
                if (e.getActionMasked() == MotionEvent.ACTION_UP
                        || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    if (release != null) release.run();
                    v.setAlpha(1f);
                    return true;
                }
                return true;
            }
        });
        return b;
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
        SharedPreferences audioPrefs = getSharedPreferences("adam_world_audio", MODE_PRIVATE);
        renderer.setAudioEnabled(audioPrefs.getBoolean("enabled", true));
        renderer.setAudioVolumes(audioPrefs.getInt("master", 82)/100f, audioPrefs.getInt("engine", 90)/100f,
                audioPrefs.getInt("ambience", 55)/100f, audioPrefs.getInt("effects", 85)/100f);
        loadWeaponState();
        glView = new CityGLView(this, renderer);
        root.addView(glView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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

        // العصا
        JoystickView js = new JoystickView(this);
        js.setListener(new JoystickView.Listener() {
            @Override public void onMove(float x, float y) { renderer.game.sx = x; renderer.game.sy = y; }
        });
        FrameLayout.LayoutParams jp = new FrameLayout.LayoutParams(dp(150), dp(150), Gravity.BOTTOM | Gravity.START);
        jp.setMargins(dp(20), 0, 0, dp(30));
        root.addView(js, jp);

        // Mobile action pad: actions reuse the exact Game requests used by the gamepad.
        LinearLayout actionPad = new LinearLayout(this);
        actionPad.setOrientation(LinearLayout.VERTICAL);
        actionPad.setGravity(Gravity.CENTER);
        actionPad.setPadding(dp(4), dp(4), dp(4), dp(4));
        actionPad.setBackgroundColor(0x30000000);
        FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(
                dp(174), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        actionParams.setMargins(0, 0, dp(10), dp(22));
        touchActionPad = actionPad;
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            root.addView(actionPad, actionParams);
        } else {
            actionPad.setVisibility(View.GONE);
        }

        LinearLayout combatControls = new LinearLayout(this);
        combatControls.setOrientation(LinearLayout.VERTICAL);
        LinearLayout combatRow = new LinearLayout(this);
        combatRow.setGravity(Gravity.CENTER);
        Button jumpTouch = touchAction("قفز", () -> runOnGl(() -> {
            if (!renderer.game.driving) renderer.game.jumpRequested = true;
        }), null);
        Button punchTouch = touchAction("لكم", () -> runOnGl(() -> {
            if (!renderer.game.driving) renderer.game.punchRequested = true;
        }), null);
        combatRow.addView(jumpTouch, new LinearLayout.LayoutParams(0, dp(46), 1f));
        combatRow.addView(punchTouch, new LinearLayout.LayoutParams(0, dp(46), 1f));
        combatControls.addView(combatRow);

        LinearLayout combatRow2 = new LinearLayout(this);
        combatRow2.setGravity(Gravity.CENTER);
        Button kickTouch = touchAction("ركل", () -> runOnGl(() -> {
            if (!renderer.game.driving) renderer.game.kickRequested = true;
        }), null);
        Button guardTouch = touchAction("صدّ", () -> runOnGl(() -> renderer.game.guardRequested = true),
                () -> runOnGl(() -> renderer.game.guardRequested = false));
        combatRow2.addView(kickTouch, new LinearLayout.LayoutParams(0, dp(46), 1f));
        combatRow2.addView(guardTouch, new LinearLayout.LayoutParams(0, dp(46), 1f));
        combatControls.addView(combatRow2);

        LinearLayout runRow = new LinearLayout(this);
        runRow.setGravity(Gravity.CENTER);
        touchSprintButton = touchAction("ركض", () -> runOnGl(() -> renderer.game.sprint = true),
                () -> runOnGl(() -> renderer.game.sprint = false));
        touchDodgeButton = touchAction("مراوغة", () -> runOnGl(() -> {
            if (!renderer.game.driving) renderer.game.dodgeRequested = true;
        }), null);
        runRow.addView(touchSprintButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        runRow.addView(touchDodgeButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        combatControls.addView(runRow);
        LinearLayout weaponRow=new LinearLayout(this); weaponRow.setGravity(Gravity.CENTER);
        Button aimTouch=touchAction("تصويب",()->runOnGl(()->renderer.game.weaponAimHeld=true),()->runOnGl(()->renderer.game.weaponAimHeld=false));
        Button fireTouch=touchAction("إطلاق",()->runOnGl(()->renderer.game.weaponFireHeld=true),()->runOnGl(()->renderer.game.weaponFireHeld=false));
        weaponRow.addView(aimTouch,new LinearLayout.LayoutParams(0,dp(46),1f)); weaponRow.addView(fireTouch,new LinearLayout.LayoutParams(0,dp(46),1f)); combatControls.addView(weaponRow);
        LinearLayout weaponRow2=new LinearLayout(this); weaponRow2.setGravity(Gravity.CENTER);
        weaponRow2.addView(touchAction("تلقيم",()->runOnGl(()->renderer.game.reloadRequested=true),null),new LinearLayout.LayoutParams(0,dp(44),1f));
        weaponRow2.addView(touchAction("عجلة السلاح",()->{weaponWheelOpen=true;weaponWheelView.showWheel();},null),new LinearLayout.LayoutParams(0,dp(44),1f));
        weaponRow2.addView(touchAction("متجر الأسلحة",()->showWeaponShop(),null),new LinearLayout.LayoutParams(0,dp(44),1.35f)); combatControls.addView(weaponRow2);
        touchCombatRow = combatControls;
        actionPad.addView(combatControls);

        LinearLayout vehicleRow = new LinearLayout(this);
        vehicleRow.setGravity(Gravity.CENTER);
        Button vehicleTouch = touchAction("دخول / خروج", () -> runOnGl(() -> renderer.toggleCar()), null);
        Button vehicleTypeTouch = touchAction("اختيار مركبة", () -> showVehicleSelector(), null);
        touchVehicleButton = vehicleTouch;
        touchVehicleTypeButton = vehicleTypeTouch;
        vehicleRow.addView(vehicleTouch, new LinearLayout.LayoutParams(0, dp(46), 1.45f));
        vehicleRow.addView(vehicleTypeTouch, new LinearLayout.LayoutParams(0, dp(46), 1f));
        actionPad.addView(vehicleRow);

        LinearLayout flightRow = new LinearLayout(this);
        flightRow.setOrientation(LinearLayout.VERTICAL);
        flightRow.setGravity(Gravity.CENTER);
        LinearLayout flightThrottleRow = new LinearLayout(this);
        flightThrottleRow.setGravity(Gravity.CENTER);
        Button throttleUpTouch = touchAction("زيادة الدفع ▲", () -> runOnGl(() -> {
            if (isAircraftDriven()) renderer.game.throttleInput = 1f;
        }), () -> runOnGl(() -> renderer.game.throttleInput = 0f));
        Button throttleDownTouch = touchAction("تقليل الدفع ▼", () -> runOnGl(() -> {
            if (isAircraftDriven()) renderer.game.throttleInput = -1f;
        }), () -> runOnGl(() -> renderer.game.throttleInput = 0f));
        flightThrottleRow.addView(throttleUpTouch, new LinearLayout.LayoutParams(0, dp(46), 1f));
        flightThrottleRow.addView(throttleDownTouch, new LinearLayout.LayoutParams(0, dp(46), 1f));
        flightRow.addView(flightThrottleRow);
        LinearLayout flightPitchRow = new LinearLayout(this);
        flightPitchRow.setGravity(Gravity.CENTER);
        touchFlightUpButton = touchAction("رفع المقدمة ▲", () -> runOnGl(() -> {
            if (isAircraftDriven()) renderer.game.liftInput = 1f;
        }), () -> runOnGl(() -> renderer.game.liftInput = 0f));
        touchFlightDownButton = touchAction("خفض المقدمة ▼", () -> runOnGl(() -> {
            if (isAircraftDriven()) renderer.game.liftInput = -1f;
        }), () -> runOnGl(() -> renderer.game.liftInput = 0f));
        flightPitchRow.addView(touchFlightUpButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        flightPitchRow.addView(touchFlightDownButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        flightRow.addView(flightPitchRow);
        touchFlightUtilityRow = new LinearLayout(this);
        touchFlightUtilityRow.setGravity(Gravity.CENTER);
        touchBrakeButton = touchAction("مكابح", () -> runOnGl(() -> renderer.game.brakeRequested = true),
                () -> runOnGl(() -> renderer.game.brakeRequested = false));
        touchGearButton = btn("العجلات: مفتوحة");
        touchGearButton.setTextSize(12);
        touchGearButton.setOnClickListener(v -> runOnGl(() -> renderer.toggleAircraftGear()));
        touchFlightUtilityRow.addView(touchBrakeButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        touchFlightUtilityRow.addView(touchGearButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        flightRow.addView(touchFlightUtilityRow);
        actionPad.addView(flightRow);
        touchFlightRow = flightRow;

        hud = new TextView(this);
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(13);
        hud.setShadowLayer(2, 0, 1, Color.BLACK);
        hud.setSingleLine(false);
        hud.setPadding(dp(9),dp(5),dp(9),dp(5));
        hud.setBackgroundColor(0x990d141c);
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
        FrameLayout.LayoutParams weaponParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,Gravity.TOP|Gravity.END);
        weaponParams.setMargins(0,dp(112),dp(18),0);
        root.addView(weaponHud,weaponParams);

        healthHud = new TextView(this);
        healthHud.setTextColor(0xff65e58b);
        healthHud.setTextSize(16);
        healthHud.setShadowLayer(4,0,0,Color.BLACK);
        healthHud.setText("♥ 100 / 100");
        FrameLayout.LayoutParams healthParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        healthParams.setMargins(0, dp(62), dp(18), 0);
        root.addView(healthHud, healthParams);

        miniMap = new MiniMapView(this);
        miniMap.setOnClickListener(v -> showMapDialog(false));
        FrameLayout.LayoutParams miniParams = new FrameLayout.LayoutParams(dp(142), dp(142), Gravity.TOP | Gravity.START);
        miniParams.setMargins(dp(14), dp(18), 0, 0);
        root.addView(miniMap, miniParams);

        aimReticle = new AimReticleView(this);
        FrameLayout.LayoutParams reticleParams = new FrameLayout.LayoutParams(dp(42),dp(42),Gravity.CENTER);
        root.addView(aimReticle,reticleParams);

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
        keyUp = keyDown = keyLeft = keyRight = false;
        if (renderer != null) {
            renderer.pauseAudio();
            saveWeaponState();
            renderer.game.sx = renderer.game.sy = 0f;
            renderer.game.cameraZoomInput = 0f;
            renderer.game.cameraLookOffset = 0f;
            renderer.setGamepadLook(0f, 0f);
            renderer.game.sprint = false;
            renderer.game.guardRequested = false;
            renderer.game.brakeRequested = false;
            renderer.game.liftInput = 0f;
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
        if (touchActionPad == null || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return;
        boolean driving = renderer.game.driving;
        boolean aircraft = driving && (renderer.game.vehicleType == ProceduralVehicleModels.HELICOPTER
                || renderer.game.vehicleType == ProceduralVehicleModels.AIRPLANE);
        if (touchCombatRow != null) {
            for (int i = 0; i < touchCombatRow.getChildCount(); i++) {
                View child = touchCombatRow.getChildAt(i);
                if (child instanceof LinearLayout) {
                    for (int j = 0; j < ((LinearLayout) child).getChildCount(); j++) {
                        ((LinearLayout) child).getChildAt(j).setVisibility(driving ? View.GONE : View.VISIBLE);
                    }
                }
            }
        }
        if (touchSprintButton != null) touchSprintButton.setVisibility(driving ? View.GONE : View.VISIBLE);
        if (touchDodgeButton != null) touchDodgeButton.setVisibility(driving ? View.GONE : View.VISIBLE);
        if (touchVehicleButton != null) touchVehicleButton.setText(driving ? "خروج من المركبة" : "استدعاء / دخول");
        if (touchVehicleTypeButton != null) touchVehicleTypeButton.setVisibility(driving ? View.GONE : View.VISIBLE);
        if (touchFlightRow != null) touchFlightRow.setVisibility(aircraft ? View.VISIBLE : View.GONE);
        boolean airplane = aircraft && renderer.game.vehicleType == ProceduralVehicleModels.AIRPLANE;
        if (touchGearButton != null) {
            touchGearButton.setVisibility(airplane ? View.VISIBLE : View.GONE);
            touchGearButton.setText(renderer.game.landingGearDown ? "العجلات: مفتوحة" : "العجلات: مطوية");
        }
        if (touchBrakeButton != null) touchBrakeButton.setVisibility(airplane ? View.VISIBLE : View.GONE);
        if (touchFlightUtilityRow != null) touchFlightUtilityRow.setVisibility(airplane ? View.VISIBLE : View.GONE);
        if (touchFlightUpButton != null) touchFlightUpButton.setText(airplane ? "رفع المقدمة ▲" : "صعود ▲");
        if (touchFlightDownButton != null) touchFlightDownButton.setText(airplane ? "خفض المقدمة ▼" : "هبوط ▼");
        if (touchActionPad != null) touchActionPad.setVisibility(View.VISIBLE);
    }

    @Override public void onHud(final String s) {
        runOnUiThread(new Runnable() { @Override public void run() {
            if (!s.equals(hud.getText().toString())) hud.setText(s);
            updateTouchActionVisibility();
            if (System.currentTimeMillis()-lastHudRefresh>250 && renderer!=null) {
                lastHudRefresh=System.currentTimeMillis();
                int hp=renderer.game.health;
                healthHud.setText("♥ "+hp+" / 100     ⚡ "+(int)renderer.game.stamina+" / 100"+(renderer.game.dead?"  — متوفى | عودة تلقائية قريباً":""));
                Game game=renderer.game;
                String weaponName=WeaponSystem.NAME[game.equippedWeapon];
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
