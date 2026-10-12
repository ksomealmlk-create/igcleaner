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

public class CleanerService extends AccessibilityService {
    static CleanerService instance;
    static volatile boolean running = false;

    final Handler h = new Handler(Looper.getMainLooper());
    int misses = 0, longTries = 0;
    int screenW, screenH;

    // الكلمات اللي يضغطها (القائمة + التأكيد + نافذة "موافق")
    static final String[] UNSEND = {"Unsend", "إلغاء الإرسال", "إلغاء إرسال", "Unsend message", "موافق", "OK", "حذف لديك", "Delete for you", "Remove for you"};

    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try { step(); } catch (Exception ignored) {}
            h.postDelayed(this, 500); // التأخير بين كل خطوة
        }
    };

    static void start() { start(5000); }

    static void start(long delay) {
        CleanerService s = instance;
        s.misses = 0;
        s.longTries = 0;
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

    void step() {
        if (android.os.SystemClock.uptimeMillis() < gestureUntil) return; // الضغطة المطولة لسا شغالة
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (!"com.instagram.android".equals(String.valueOf(root.getPackageName()))) return;

        // نافذة التفاعلات انفتحت بالغلط: سكّرها
        if (findByText(root, new String[]{"التفاعلات", "Reactions"}) != null) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            return;
        }

        // 1) زر Unsend / موافق ظاهر؟ اضغطه
        AccessibilityNodeInfo u = findByText(root, UNSEND);
        if (u != null) {
            clickNode(u);
            longTries = 0;
            misses = 0;
            return;
        }

        // 2) دور على آخر رسالة إلك
        AccessibilityNodeInfo msg = null;
        if (longTries < 3) msg = findOwnMessage(root);
        if (msg != null) {
            Rect r = new Rect();
            msg.getBoundsInScreen(r);
            longPress(r.centerX(), r.centerY());
            longTries++;
            return;
        }

        // 3) ما لقينا شي: انزل للرسايل القديمة
        longTries = 0;
        AccessibilityNodeInfo sc = findScrollable(root);
        if (sc != null) sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        misses++;
        if (misses > 8) {
            running = false;
            Toast.makeText(this, "خلص أو ما لقيت رسائل", Toast.LENGTH_LONG).show();
        }
    }

    AccessibilityNodeInfo best;
    int bestBottom;

    AccessibilityNodeInfo findOwnMessage(AccessibilityNodeInfo root) {
        best = null;
        bestBottom = -1;
        scanOwn(root);
        return best;
    }

    void scanOwn(AccessibilityNodeInfo n) {
        if (n == null) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
        boolean isEdit = "android.widget.EditText".contentEquals(String.valueOf(n.getClassName()));
        boolean skip = false;
        if (t != null) {
            String ts = t.toString();
            boolean noLetters = true;
            for (int k = 0; k < ts.length(); k++) {
                if (Character.isLetterOrDigit(ts.charAt(k))) { noLetters = false; break; }
            }
            // شارة التفاعل (إيموجي صغير تحت الرسالة) مو رسالة
            if (ts.contains("تفاعل") || ts.toLowerCase().contains("reaction")) skip = true;
            if (noLetters && r.height() < screenW * 0.05) skip = true;
        }
        // الواجهة عربية (RTL): رسائلك على اليسار
        if (!skip && t != null && t.length() > 0 && !isEdit && n.getChildCount() == 0
                && r.left <= screenW * 0.15 && r.right < screenW * 0.85
                && r.top > 250 && r.bottom < screenH - 300
                && r.bottom > bestBottom) {
            best = n;
            bestBottom = r.bottom;
        }
        for (int i = 0; i < n.getChildCount(); i++) scanOwn(n.getChild(i));
    }

    AccessibilityNodeInfo findByText(AccessibilityNodeInfo root, String[] words) {
        java.util.List<AccessibilityWindowInfo> ws = getWindows();
        if (ws != null) {
            for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                if (!"com.instagram.android".equals(String.valueOf(r.getPackageName()))) continue;
                AccessibilityNodeInfo f = findIn(r, words);
                if (f != null) return f;
            }
        }
        return findIn(root, words);
    }

    boolean same(CharSequence c, String[] words) {
        if (c == null) return false;
        String s = c.toString().trim();
        for (String w : words) if (s.equalsIgnoreCase(w)) return true;
        // "حذف لديك" أو "حذف لديك (١)" أو أي رقم (نشيل رموز الاتجاه المخفية)
        String clean = s.replaceAll("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069\\u00a0]", "").trim();
        if (clean.length() <= 30
                && (clean.contains("حذف لديك") || clean.toLowerCase().contains("delete for you"))) return true;
        return false;
    }

    AccessibilityNodeInfo findIn(AccessibilityNodeInfo n, String[] words) {
        if (n == null) return null;
        if (same(n.getText(), words) || same(n.getContentDescription(), words)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = findIn(n.getChild(i), words);
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

    long gestureUntil = 0;

    void longPress(int x, int y) {
        gestureUntil = android.os.SystemClock.uptimeMillis() + 600;
        gesture(x, y, 550);
    }

    void gesture(int x, int y, long dur) {
        Path p = new Path();
        p.moveTo(x, y);
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
