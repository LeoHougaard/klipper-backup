package dev.leo.voronpanel;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

/** Small native component system for the landscape printer console. */
final class PanelUi {
  static final int BG = 0xff101416,
      SURFACE = 0xff191f22,
      RAISED = 0xff242c30,
      LINE = 0xff303a3f,
      TEXT = 0xffe7edeb,
      MUTED = 0xffa0afaf,
      ACCENT = 0xff9dc9b5,
      ACCENT_BG = 0xff263c34,
      DANGER = 0xffe6a5a0,
      DANGER_BG = 0xff3b2929,
      AMBER = 0xffdfc397;
  final Context context;

  PanelUi(Context c) {
    context = c;
  }

  int dp(float n) {
    return Math.round(n * context.getResources().getDisplayMetrics().density);
  }

  LinearLayout col() {
    LinearLayout v = new LinearLayout(context);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  LinearLayout row() {
    LinearLayout v = new LinearLayout(context);
    v.setOrientation(LinearLayout.HORIZONTAL);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  TextView text(String value, int size, int color) {
    TextView v = new QuietText(context);
    v.setText(value);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setGravity(Gravity.CENTER_VERTICAL);
    v.setIncludeFontPadding(false);
    return v;
  }

  TextView label(String value) {
    TextView v = text(value, 12, MUTED);
    v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return v;
  }

  TextView heading(String value) {
    TextView v = text(value, 22, TEXT);
    v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return v;
  }

  GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  GradientDrawable outline(int color) {
    GradientDrawable d = shape(color, 10);
    d.setStroke(dp(1), LINE);
    return d;
  }

  void pad(View v, int x, int y) {
    v.setPadding(dp(x), dp(y), dp(x), dp(y));
  }

  void gap(LinearLayout parent, int n) {
    View v = new View(context);
    parent.addView(v, new LinearLayout.LayoutParams(dp(n), dp(n)));
  }

  void rule(LinearLayout parent) {
    View v = new View(context);
    v.setBackgroundColor(LINE);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
    lp.setMargins(0, dp(12), 0, dp(12));
    parent.addView(v, lp);
  }

  Button button(String title, String icon, int style, Runnable click) {
    Button v = new QuietButton(context);
    v.setAllCaps(false);
    v.setText(title);
    v.setTextSize(14);
    v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    v.setGravity(Gravity.CENTER);
    v.setMinWidth(0);
    v.setMinimumWidth(0);
    v.setMinHeight(dp(48));
    v.setMinimumHeight(dp(48));
    v.setStateListAnimator(null);
    v.setElevation(0);
    pad(v, 12, 8);
    int fill = style == 1 ? ACCENT : style == 2 ? DANGER_BG : style == 3 ? ACCENT_BG : RAISED;
    int ink = style == 1 ? BG : style == 2 ? DANGER : style == 3 ? ACCENT : TEXT;
    v.setTextColor(
        new ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {0xff6c797b, ink}));
    StateListDrawable states = new StateListDrawable();
    states.addState(new int[] {-android.R.attr.state_enabled}, shape(0xff1c2427, 10));
    states.addState(
        new int[] {},
        new RippleDrawable(ColorStateList.valueOf(0x22ffffff), shape(fill, 10), null));
    v.setBackground(states);
    if (icon != null) {
      Icon d = new Icon(icon, ink);
      d.setBounds(0, 0, dp(19), dp(19));
      v.setCompoundDrawables(d, null, null, null);
      v.setCompoundDrawablePadding(dp(8));
    }
    v.setOnClickListener(view -> click.run());
    return v;
  }

  void weighted(LinearLayout parent, View v, int height) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(height), 1);
    lp.setMargins(dp(3), 0, dp(3), 0);
    parent.addView(v, lp);
  }

  LinearLayout panel() {
    LinearLayout v = col();
    v.setBackground(shape(SURFACE, 12));
    pad(v, 16, 14);
    return v;
  }

  View navigation(String title, String icon, boolean selected, Runnable action) {
    LinearLayout item = col();
    item.setGravity(Gravity.CENTER);
    item.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(0x22ffffff), shape(selected ? ACCENT_BG : BG, 10), null));
    ImageView image = new ImageView(context);
    image.setImageDrawable(new Icon(icon, selected ? ACCENT : MUTED));
    item.addView(image, new LinearLayout.LayoutParams(dp(22), dp(22)));
    TextView label = text(title, 11, selected ? ACCENT : MUTED);
    label.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(20));
    lp.topMargin = dp(4);
    item.addView(label, lp);
    item.setContentDescription("Open " + title);
    item.setSelected(selected);
    item.setOnClickListener(v -> action.run());
    return item;
  }

  // Polling must not invalidate an unchanged accessibility tree or repaint idle text.
  static final class QuietText extends TextView {
    QuietText(Context context) {
      super(context);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
      if (!TextUtils.equals(getText(), text)) super.setText(text, type);
    }
  }

  static final class QuietButton extends Button {
    QuietButton(Context context) {
      super(context);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
      if (!TextUtils.equals(getText(), text)) super.setText(text, type);
    }
  }

  static void selection(Button button, boolean selected, PanelUi ui) {
    if (Boolean.valueOf(selected).equals(button.getTag())) return;
    button.setTag(selected);
    button.setSelected(selected);
    button.setTextColor(selected ? ACCENT : MUTED);
    button.setBackground(ui.shape(selected ? ACCENT_BG : RAISED, 8));
  }

  static final class Icon extends Drawable {
    final String name;
    final int baseColor;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    Icon(String name, int color) {
      this.name = name;
      this.baseColor = color;
      paint.setColor(color);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(1.6f);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public boolean isStateful() {
      return true;
    }

    @Override
    protected boolean onStateChange(int[] states) {
      boolean enabled = false;
      for (int state : states) if (state == android.R.attr.state_enabled) enabled = true;
      paint.setColor(enabled ? baseColor : 0xff6c797b);
      invalidateSelf();
      return true;
    }

    void path(Canvas c, float... points) {
      Path p = new Path();
      p.moveTo(points[0], points[1]);
      for (int i = 2; i < points.length; i += 2) p.lineTo(points[i], points[i + 1]);
      c.drawPath(p, paint);
    }

    @Override
    public void draw(Canvas canvas) {
      canvas.save();
      Rect r = getBounds();
      canvas.translate(r.left, r.top);
      canvas.scale(r.width() / 24f, r.height() / 24f);
      switch (name) {
        case "home":
          path(canvas, 3, 10, 12, 3, 21, 10, 21, 21, 15, 21, 15, 14, 9, 14, 9, 21, 3, 21, 3, 10);
          break;
        case "move":
          path(canvas, 12, 2, 12, 22);
          path(canvas, 2, 12, 22, 12);
          path(canvas, 8, 6, 12, 2, 16, 6);
          path(canvas, 8, 18, 12, 22, 16, 18);
          path(canvas, 6, 8, 2, 12, 6, 16);
          path(canvas, 18, 8, 22, 12, 18, 16);
          break;
        case "files":
          path(canvas, 6, 3, 15, 3, 20, 8, 20, 21, 6, 21, 6, 3);
          path(canvas, 14, 3, 14, 9, 20, 9);
          path(canvas, 9, 13, 16, 13);
          path(canvas, 9, 17, 15, 17);
          break;
        case "camera":
          canvas.drawRoundRect(3, 6, 21, 20, 3, 3, paint);
          canvas.drawCircle(12, 13, 4, paint);
          path(canvas, 7, 6, 9, 3, 15, 3, 17, 6);
          break;
        case "more":
          for (int x = 5; x <= 19; x += 7) canvas.drawCircle(x, 12, 1, paint);
          break;
        case "heat":
          path(canvas, 9, 15, 9, 5);
          canvas.drawArc(9, 2, 15, 8, 180, 180, false, paint);
          path(canvas, 15, 5, 15, 15);
          canvas.drawArc(6, 12, 18, 24, -35, 250, false, paint);
          path(canvas, 12, 8, 12, 18);
          break;
        case "bed":
          path(canvas, 3, 15, 21, 15, 19, 21, 5, 21, 3, 15);
          for (int x = 7; x <= 17; x += 5) {
            Path p = new Path();
            p.moveTo(x, 11);
            p.cubicTo(x - 3, 8, x + 3, 6, x, 3);
            canvas.drawPath(p, paint);
          }
          break;
        case "filament":
          canvas.drawCircle(12, 12, 9, paint);
          canvas.drawCircle(12, 12, 3, paint);
          path(canvas, 6, 6, 9, 9);
          path(canvas, 15, 15, 18, 18);
          path(canvas, 18, 6, 15, 9);
          path(canvas, 6, 18, 9, 15);
          break;
        case "calibrate":
          path(canvas, 3, 18, 21, 18);
          path(canvas, 6, 21, 18, 21);
          path(canvas, 12, 3, 12, 13, 8, 9);
          path(canvas, 12, 13, 16, 9);
          break;
        case "wifi":
          canvas.drawArc(2, 4, 22, 22, 220, 100, false, paint);
          canvas.drawArc(6, 9, 18, 21, 220, 100, false, paint);
          canvas.drawCircle(12, 19, 1, paint);
          break;
        case "stop":
          path(canvas, 8, 3, 16, 3, 21, 8, 21, 16, 16, 21, 8, 21, 3, 16, 3, 8, 8, 3);
          path(canvas, 12, 7, 12, 13);
          canvas.drawCircle(12, 17, .5f, paint);
          break;
        case "edit":
          path(canvas, 5, 16, 16, 5, 20, 9, 9, 20, 4, 21, 5, 16);
          path(canvas, 13, 8, 17, 12);
          break;
        case "play":
          path(canvas, 8, 4, 20, 12, 8, 20, 8, 4);
          break;
        case "pause":
          path(canvas, 8, 4, 8, 20);
          path(canvas, 16, 4, 16, 20);
          break;
        case "check":
          path(canvas, 4, 12, 10, 18, 20, 6);
          break;
        case "back":
          path(canvas, 14, 5, 7, 12, 14, 19);
          break;
        case "arrow":
          path(canvas, 9, 5, 16, 12, 9, 19);
          break;
        case "refresh":
          canvas.drawArc(4, 4, 20, 20, 45, 290, false, paint);
          path(canvas, 16, 3, 20, 8, 14, 8);
          break;
        case "light":
          canvas.drawCircle(12, 9, 5, paint);
          path(canvas, 9, 13, 9, 18, 15, 18, 15, 13);
          path(canvas, 10, 21, 14, 21);
          break;
        default:
          canvas.drawCircle(12, 12, 8, paint);
      }
      canvas.restore();
    }

    @Override
    public void setAlpha(int a) {
      paint.setAlpha(a);
    }

    @Override
    public void setColorFilter(ColorFilter f) {
      paint.setColorFilter(f);
    }

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }
}
