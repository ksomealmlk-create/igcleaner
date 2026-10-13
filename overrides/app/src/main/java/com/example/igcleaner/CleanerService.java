package com.example.igcleaner;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;

public class CleanerService extends AccessibilityService {
    static CleanerService instance;
    static volatile boolean running = false;

    final Handler h = new Handler(Looper.getMainLooper());
    int misses = 0;
    int screenW, screenH;
    long nextDelay = 2000;
    boolean needSmallScroll = false;
    String lastSig = "";
    ArrayList<String> pendingKeys = null;
    final HashMap<String, Integer> fails = new HashMap<>();
    final ArrayList<AccessibilityNodeInfo> cands = new ArrayList<>();

    static final String[] EXACT = {"Unsend", "Unsend message", "إلغاء الإرسال", "إلغاء إرسال",
            "موافق", "OK", "Delete", "حذف"};
    static final String[] PART = {"unsend", "إلغاء الإرسال", "إلغاء إرسال", "الغاء الارسال", "الغاء ارسال"};

    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            nextDelay = 2000;
            try { step(); } catch (Exception ignored) {}
            h.postDelayed(this, nextDelay);
        }
    };

    static void start() { start(5000); }

    static void start(long delay) {
        CleanerService s = instance;
        s.misses = 0;
        s.fails.clear();
        s.pendingKeys = null;
        s.needSmallScroll = false;
        s.lastSig = "";
        running = true;
        s.h.removeCallbacks(s.tick);
        s.h.postDelayed(s.tick, delay);
    }

    @Override protected void onServiceConnected() {
        instance = this;
        DisplayMetrics m = getResources().getDisplayMetrics();
        screenW = m.widthPixels;
        screenH = m.heightPixels;
        addFab();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}

    @Override public boolean onUnbind(android.content.Intent i) {
        instance = null;
        running = false;
        removeFab();
        return super.onUnbind(i);
    }

    void registerFail() {
        if (pendingKeys == null) return;
        for (String k : pendingKeys) {
            Integer c = fails.get(k);
            fails.put(k, c == null ? 1 : c + 1);
        }
        pendingKeys = null;
    }

    void step() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (!"com.instagram.android".equals(String.valueOf(root.getPackageName()))) return;

        // نافذة التفاعلات انفتحت بالغلط: سكّرها
        if (findByText(root, new String[]{"التفاعلات", "Reactions"}, null) != null) {
            registerFail();
            performGlobalAction(GLOBAL_ACTION_BACK);
            return;
        }

        // 1) زر Unsend / موافق / حذف ظاهر؟ اضغطه
        AccessibilityNodeInfo u = findByText(root, EXACT, PART);
        if (u != null) {
            clickNode(u);
            pendingKeys = null;
            needSmallScroll = true;
            misses = 0;
            nextDelay = 1200;
            return;
        }

        // ضغطنا على رسالة وما طلعت قائمة: سجّل فشل
        registerFail();

        // 2) بعد كل حذف: اصعد شوية
        if (needSmallScroll) {
            needSmallScroll = false;
            swipeDown(0.40f, 0.55f, 250);
            nextDelay = 900;
            return;
        }

        // 3) دور على آخر رسالة (أي نوع)
        cands.clear();
        scanOwn(root);
        StringBuilder sig = new StringBuilder();
        AccessibilityNodeInfo low = null;
        Rect lowR = null;
        for (AccessibilityNodeInfo c : cands) {
            Rect r = new Rect();
            c.getBoundsInScreen(r);
            String k = key(c, r);
            sig.append(k).append('@').append(r.top).append(';');
            Integer f = fails.get(k);
            if ((f == null || f < 2) && (lowR == null || r.bottom > lowR.bottom)) {
                low = c;
                lowR = r;
            }
        }

        if (low != null) {
            // اختار أعرض عنصر بنفس سطر آخر رسالة (الفقاعة، مو أيقونة الخطأ)
            AccessibilityNodeInfo pick = low;
            Rect pr = lowR;
            for (AccessibilityNodeInfo c : cands) {
                Rect r = new Rect();
                c.getBoundsInScreen(r);
                Integer f = fails.get(key(c, r));
                if (f != null && f >= 2) continue;
                if (r.centerY() >= lowR.top && r.centerY() <= lowR.bottom && r.width() > pr.width()) {
                    pick = c;
                    pr = r;
                }
            }
            String pickKey = key(pick, pr);
            pendingKeys = new ArrayList<>();
            pendingKeys.add(pickKey);
            Integer f = fails.get(pickKey);
            int x = (f == null || f == 0) ? pr.centerX() : pr.left + (int) (pr.width() * 0.2f);
            longPress(x, pr.centerY());
            misses = 0;
            return;
        }

        // 4) ما بقى شي بهاي الصفحة: قلبة سريعة للرسايل القديمة
        String s = sig.toString();
        if (s.equals(lastSig)) misses++; else misses = 0;
        lastSig = s;
        AccessibilityNodeInfo sc = findScrollable(root);
        if (sc != null) sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        else swipeDown(0.25f, 0.75f, 200);
        nextDelay = misses > 0 ? 1200 : 600;
        if (misses >= 5) {
            running = false;
            Toast.makeText(this, "خلص أو ما لقيت رسائل", Toast.LENGTH_LONG).show();
        }
    }

    String key(AccessibilityNodeInfo n, Rect r) {
        return n.getClassName() + "|" + n.getText() + "|" + n.getContentDescription()
                + "|" + r.width() + "x" + r.height();
    }

    void scanOwn(AccessibilityNodeInfo n) {
        if (n == null) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        String cls = String.valueOf(n.getClassName());
        boolean ok = n.isVisibleToUser() && !cls.contains("EditText");
        CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
        if (ok && t != null) {
            String ts = t.toString();
            String lo = ts.toLowerCase();
            boolean noLetters = true;
            for (int k = 0; k < ts.length(); k++) {
                if (Character.isLetterOrDigit(ts.charAt(k))) { noLetters = false; break; }
            }
            if (ts.contains("تفاعل") || lo.contains("reaction")) ok = false;
            if (noLetters && r.height() < screenW * 0.035) ok = false;
        }
        boolean eligible = n.getChildCount() == 0 || n.isLongClickable();
        // الواجهة عربية (RTL): رسائلك على اليسار
        if (ok && eligible
                && r.left <= screenW * 0.30 && r.right < screenW * 0.85
                && r.width() >= screenW * 0.02 && r.height() >= screenW * 0.02
                && r.top > 250 && r.bottom < screenH - 300) {
            cands.add(n);
        }
        for (int i = 0; i < n.getChildCount(); i++) scanOwn(n.getChild(i));
    }

    boolean match(CharSequence c, String[] exact, String[] part) {
        if (c == null) return false;
        String s = c.toString().trim();
        if (s.length() == 0) return false;
        for (String w : exact) if (s.equalsIgnoreCase(w)) return true;
        if (part != null && s.length() <= 30) {
            String lo = s.toLowerCase();
            for (String w : part) if (lo.contains(w.toLowerCase())) return true;
        }
        return false;
    }

    AccessibilityNodeInfo findByText(AccessibilityNodeInfo root, String[] exact, String[] part) {
        java.util.List<AccessibilityWindowInfo> ws = getWindows();
        if (ws != null) {
            for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                if (!"com.instagram.android".equals(String.valueOf(r.getPackageName()))) continue;
                AccessibilityNodeInfo f = findIn(r, exact, part);
                if (f != null) return f;
            }
        }
        return findIn(root, exact, part);
    }

    AccessibilityNodeInfo findIn(AccessibilityNodeInfo n, String[] exact, String[] part) {
        if (n == null) return null;
        if (match(n.getText(), exact, part) || match(n.getContentDescription(), exact, part)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = findIn(n.getChild(i), exact, part);
            if (f != null) return f;
        }
        return null;
    }

    AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isScrollable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findScrollable(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    void clickNode(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n;
        while (p != null && !p.isClickable()) p = p.getParent();
        if (p != null && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        gesture(r.centerX(), r.centerY(), 50);
    }

    void longPress(int x, int y) { gesture(x, y, 700); }

    void gesture(int x, int y, long dur) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, dur));
        dispatchGesture(b.build(), null, null);
    }

    // سحبة للأسفل = الرسايل تصعد (القديمة تبين)
    void swipeDown(float from, float to, long dur) {
        Path p = new Path();
        p.moveTo(screenW / 2f, screenH * from);
        p.lineTo(screenW / 2f, screenH * to);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, dur));
        dispatchGesture(b.build(), null, null);
    }

    // ---------- تسجيل الشاشة (للتشخيص) ----------
    static void dump() {
        final CleanerService s = instance;
        s.h.postDelayed(new Runnable() {
            @Override public void run() { s.doDump(); }
        }, 5000);
    }

    void doDump() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Toast.makeText(this, "ما شفت الشاشة", Toast.LENGTH_LONG).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("screen ").append(screenW).append("x").append(screenH).append("\n");
        walk(root, 0, sb);
        android.content.ClipboardManager cm =
            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("dump", sb.toString()));
        Toast.makeText(this, "انسخت! الصقها بالدردشة", Toast.LENGTH_LONG).show();
    }

    void walk(AccessibilityNodeInfo n, int depth, StringBuilder sb) {
        if (n == null) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        for (int i = 0; i < depth; i++) sb.append(" ");
        sb.append(n.getClassName())
          .append(" | ").append(n.getText())
          .append(" | ").append(n.getContentDescription())
          .append(" | ").append(r.left).append(",").append(r.top)
          .append(",").append(r.right).append(",").append(r.bottom)
          .append(" | click=").append(n.isClickable())
          .append("\n");
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), depth + 1, sb);
    }

    // ---------- الزر العائم ----------
    WindowManager wm;
    TextView fab;
    WindowManager.LayoutParams fp;

    final Runnable fabTick = new Runnable() {
        @Override public void run() {
            if (fab == null) return;
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            if (running) {
                fab.setText("■");
                bg.setColor(0xCCC62828);
            } else {
                fab.setText("▶");
                bg.setColor(0xCC2E7D32);
            }
            fab.setBackground(bg);
            h.postDelayed(this, 500);
        }
    };

    void toggle() {
        if (running) running = false;
        else start(500);
    }

    void addFab() {
        if (fab != null) return;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        fab = new TextView(this);
        fab.setTextColor(Color.WHITE);
        fab.setTextSize(22);
        fab.setGravity(Gravity.CENTER);
        int size = (int) (56 * getResources().getDisplayMetrics().density);
        fp = new WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        fp.gravity = Gravity.TOP | Gravity.START;
        fp.x = 20;
        fp.y = 400;
        fab.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy;
            float tx, ty;
            boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = fp.x; sy = fp.y;
                        tx = e.getRawX(); ty = e.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - tx;
                        float dy = e.getRawY() - ty;
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true;
                        if (moved) {
                            fp.x = sx + (int) dx;
                            fp.y = sy + (int) dy;
                            wm.updateViewLayout(fab, fp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) toggle();
                        return true;
                }
                return false;
            }
        });
        wm.addView(fab, fp);
        fabTick.run();
    }

    void removeFab() {
        h.removeCallbacks(fabTick);
        if (fab != null && wm != null) {
            try { wm.removeView(fab); } catch (Exception ignored) {}
        }
        fab = null;
    }
}
