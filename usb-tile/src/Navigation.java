package cn.local.usbtile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

public class Navigation extends AccessibilityService {
    static final String MUSIC = "com.netease.cloudmusic";
    static Navigation instance;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private boolean readOnly;
    private final Runnable readVisibleSwitch = this::observeSwitch;
    private long started, pressed, unknownSince;
    private boolean before, running, inSettings;
    private int backs, scrolls;
    private long returnStarted;
    private String lastReturnPage;
    private String confirmedStatus;
    @Override protected void onServiceConnected() {
        instance = this;
        android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            | android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        timer.post(readVisibleSwitch);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!running && MUSIC.contentEquals(String.valueOf(event.getPackageName()))
                && !timer.hasCallbacks(readVisibleSwitch))
            timer.postDelayed(readVisibleSwitch, 150);
    }
    private void observeSwitch() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (running || root == null || !MUSIC.contentEquals(String.valueOf(root.getPackageName()))) return;
        for (AccessibilityNodeInfo label : root.findAccessibilityNodeInfosByText("独占")) {
            if (!label.isVisibleToUser() || !matches(label.getText(), "独占usb")) continue;
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            collect(root, nodes);
            AccessibilityNodeInfo toggle = usbSwitch(nodes, label);
            if (toggle != null) UsbTile.remember(this, toggle.isChecked());
            return;
        }
    }
    @Override public void onInterrupt() { stop(); }
    @Override public void onDestroy() {
        stop(); instance = null; super.onDestroy();
    }

    boolean begin(boolean queryOnly) {
        // An explicit tile click supersedes an in-flight read-only query.
        if (running && readOnly && !queryOnly) stop();
        if (running) return false;
        running = true;
        readOnly = queryOnly;
        inSettings = false;
        unknownSince = 0;
        pressed = 0;
        returnStarted = 0;
        lastReturnPage = "";
        backs = scrolls = 0;
        started = SystemClock.elapsedRealtime();
        later(550);
        return true;
    }
    private void stop() { running = false; timer.removeCallbacksAndMessages(null); }
    void finish(String message) {
        stop();
        android.util.Log.i("CloudUsbTile", message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
    private void later(int ms) { timer.postDelayed(this::step, ms); }

    private static void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        if (node.isVisibleToUser()) result.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), result);
    }
    private static boolean matches(CharSequence value, String label) {
        return value != null && label.equalsIgnoreCase(value.toString().trim());
    }
    private static AccessibilityNodeInfo find(List<AccessibilityNodeInfo> nodes, String label) {
        for (AccessibilityNodeInfo node : nodes)
            if (matches(node.getText(), label) || matches(node.getContentDescription(), label)) return node;
        return null;
    }
    private static Rect bounds(AccessibilityNodeInfo node) {
        Rect result = new Rect();
        node.getBoundsInScreen(result);
        return result;
    }
    // The RN settings screen exposes the title and Switch as separate siblings.
    private static AccessibilityNodeInfo usbSwitch(List<AccessibilityNodeInfo> nodes, AccessibilityNodeInfo label) {
        Rect title = bounds(label);
        AccessibilityNodeInfo found = null;
        for (AccessibilityNodeInfo node : nodes) {
            if (!"android.widget.Switch".contentEquals(node.getClassName())) continue;
            Rect box = bounds(node);
            if (box.left < title.centerX() || box.top >= title.bottom || box.bottom <= title.top) continue;
            if (found != null) return null;
            found = node;
        }
        return found;
    }
    private boolean gesture(int x, int y, int endY) {
        Path path = new Path();
        path.moveTo(x, y);
        if (endY != y) path.lineTo(x, endY);
        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, endY == y ? 65 : 420);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }
    private boolean tap(AccessibilityNodeInfo node) {
        Rect box = bounds(node);
        return !box.isEmpty() && gesture(box.centerX(), box.centerY(), box.centerY());
    }
    private AccessibilityNodeInfo homeTab(List<AccessibilityNodeInfo> nodes, Rect area) {
        AccessibilityNodeInfo home = find(nodes, "首页");
        return home != null && bounds(home).centerY() > area.bottom - area.height()/6 ? home : null;
    }
    private void returnToMain(List<AccessibilityNodeInfo> nodes, Rect area) {
        String page = "";
        if (find(nodes, "抽屉菜单") != null) {
            if (find(nodes, "设置") == null) {
                finish(confirmedStatus);
                return;
            }
            page = "侧边菜单";
        } else if (homeTab(nodes, area) != null) {
            if (!"首页".equals(lastReturnPage) && tap(homeTab(nodes, area))) lastReturnPage = "首页";
        } else {
            for (String title : new String[] {"更多播放设置", "播放与下载", "设置"}) {
                AccessibilityNodeInfo header = find(nodes, title);
                if (header != null && bounds(header).centerY() < area.top + area.height()/8) {
                    page = title;
                    break;
                }
            }
        }
        // Wait for a new, recognized page before sending another Back.
        if (!page.isEmpty() && !page.equals(lastReturnPage) && performGlobalAction(GLOBAL_ACTION_BACK))
            lastReturnPage = page;
        later(450);
    }
    private void step() {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        if (returnStarted != 0 && now - returnStarted > 10000) {
            finish(confirmedStatus + "，请手动返回首页"); return;
        }
        if (returnStarted == 0 && now - started > 30000) {
            finish(readOnly ? "未读到网易云 USB 独占状态" : "切换超时，请检查网易云页面");
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !MUSIC.contentEquals(String.valueOf(root.getPackageName()))) {
            for (android.view.accessibility.AccessibilityWindowInfo window : getWindows()) {
                AccessibilityNodeInfo candidate = window.getRoot();
                if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
                    && (window.isActive() || window.isFocused()) && candidate != null
                    && MUSIC.contentEquals(String.valueOf(candidate.getPackageName()))) {
                    root = candidate;
                    break;
                }
            }
        }
        if (root == null || !MUSIC.contentEquals(String.valueOf(root.getPackageName()))) {
            // A USB authorization dialog may temporarily cover the switch.
            later(350); return;
        }
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collect(root, nodes);
        if (returnStarted != 0) {
            returnToMain(nodes, bounds(root));
            return;
        }
        AccessibilityNodeInfo label = find(nodes, "独占usb");
        AccessibilityNodeInfo toggle = label == null ? null : usbSwitch(nodes, label);
        if (toggle != null) UsbTile.remember(this, toggle.isChecked());
        if (readOnly && toggle != null) {
            confirmedStatus = toggle.isChecked() ? "网易云 USB 独占已开启" : "网易云 USB 独占已关闭";
            returnStarted = now;
            returnToMain(nodes, bounds(root));
            return;
        }
        if (pressed != 0) {
            if (toggle != null && toggle.isChecked() != before) {
                confirmedStatus = toggle.isChecked() ? "网易云 USB 独占已开启" : "网易云 USB 独占已关闭";
                returnStarted = now;
                returnToMain(nodes, bounds(root));
            }
            else if (now - pressed > 2200) finish("开关未改变，请查看网易云提示并确认 DAC 已连接");
            else later(250);
            return;
        }
        if (label != null) {
            if (toggle == null) { finish("未能识别独占 USB 开关"); return; }
            before = toggle.isChecked();
            if (!tap(toggle)) { finish("未能点击独占 USB 开关"); return; }
            pressed = now;
            android.util.Log.i("CloudUsbTile", "已点击独占 USB");
            later(300); return;
        }
        AccessibilityNodeInfo playback = find(nodes, "播放与下载");
        AccessibilityNodeInfo more = find(nodes, "更多播放设置");
        AccessibilityNodeInfo settings = find(nodes, "设置");
        AccessibilityNodeInfo menu = find(nodes, "抽屉菜单");
        if (playback != null || more != null) inSettings = true;
        Rect area = bounds(root);
        // Header is centered. A left-aligned entry opens the page of the same name.
        if (more != null && bounds(more).centerY() > area.top + area.height()/8) tap(more);
        else if (playback != null && bounds(playback).centerY() > area.top + area.height()/8) tap(playback);
        else if (playback != null && scrolls++ < 5) {
            boolean reverse = find(nodes, "下载音质") != null && find(nodes, "后台播放保护") == null;
            int top = area.top + area.height()*2/5, bottom = area.top + area.height()*3/4;
            gesture(area.centerX(), reverse ? top : bottom, reverse ? bottom : top);
        }
        else if (settings != null && (menu != null || bounds(settings).centerY() > area.centerY())) tap(settings);
        else if (menu != null) tap(menu);
        else if (!inSettings && homeTab(nodes, area) != null) tap(homeTab(nodes, area));
        else {
            // RN briefly exposes just a toolbar during navigation. Give it time to render.
            if (unknownSince == 0) unknownSince = now;
            if (!inSettings && now - unknownSince > 3000 && backs < 3) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                unknownSince = now;
                backs++;
            }
            later(400);
            return;
        }
        android.util.Log.d("CloudUsbTile", "导航 menu=" + (menu != null) + " settings=" +
            (settings != null) + " playback=" + (playback != null) + " more=" + (more != null) + " scrolls=" + scrolls);
        unknownSince = 0;
        later(1000);
    }
}
