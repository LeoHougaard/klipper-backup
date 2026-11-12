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
  // Neutral surfaces; accent is reserved for selection and primary actions.
  static final int BG = 0xff101113,
      SURFACE = 0xff1b1c1f,
      RAISED = 0xff2b2d31,
      LINE = 0xff41444a,
      TEXT = 0xfff2f3f4,
      MUTED = 0xffaeb2b9,
      ACCENT = 0xffc4f5e1, // Mint 12; one accent ink/fill throughout
      ACCENT_BG = 0xff243b33,
      ACCENT_LINE = 0xff526d60,
      DANGER = 0xffff9592, // Red 11
      DANGER_BG = 0xff3b1219, // Red 3
      DANGER_LINE = 0xff8c333a, // Red 7
      AMBER = 0xffffe7b3, // Amber 12
      DISABLED = 0xff63706b; // Sage 9
  static final int RIPPLE = 0x23c4f5e1;
  static final int CONTROL_RADIUS = 6,
      CARD_INSET = 12,
      CARD_RADIUS = 0,
      SHEET_INSET = 16,
      SHEET_RADIUS = 8;
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
    v.setFontFeatureSettings("tnum");
    return v;
  }

  TextView label(String value) {
    TextView v = text(value, 12, MUTED);
    v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return v;
  }

  TextView heading(String value) {
    TextView v = text(value, 20, TEXT);
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
    GradientDrawable d = shape(color, CONTROL_RADIUS);
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
    lp.setMargins(0, dp(10), 0, dp(10));
    parent.addView(v, lp);
  }

  Button button(String title, String icon, int style, Runnable click) {
    Button v = new QuietButton(context);
    v.setAllCaps(false);
    v.setText(title);
    v.setTextSize(14);
    v.setIncludeFontPadding(false);
    v.setFontFeatureSettings("tnum");
    v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    v.setGravity(Gravity.CENTER);
    v.setMinWidth(0);
    v.setMinimumWidth(0);
    v.setMinHeight(dp(48));
    v.setMinimumHeight(dp(48));
    v.setStateListAnimator(null);
    v.setElevation(0);
    pad(v, 12, 8);
    int ink = style == 1 ? BG : style == 2 ? DANGER : style == 3 ? ACCENT : TEXT;
    v.setTextColor(ink(ink));
    v.setBackground(buttonBackground(style));
    if (icon != null) {
      Icon d = new Icon(context, icon, ink);
      d.setBounds(0, 0, dp(24), dp(24));
      v.setCompoundDrawables(d, null, null, null);
      v.setCompoundDrawablePadding(dp(8));
    }
    v.setOnClickListener(view -> click.run());
    return v;
  }

  ColorStateList ink(int color) {
    return new ColorStateList(
        new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
        new int[] {DISABLED, color});
  }

  GradientDrawable controlShape(int fill, int border) {
    GradientDrawable d = shape(fill, CONTROL_RADIUS);
    if (border != 0) d.setStroke(dp(1), border);
    return d;
  }

  Drawable buttonBackground(int style) {
    int fill =
        style == 1 ? ACCENT : style == 2 ? DANGER_BG : style == 3 ? ACCENT_BG : style == 4 ? SURFACE : RAISED;
    int border =
        style == 2 ? DANGER_LINE : 0;
    StateListDrawable content = new StateListDrawable();
    content.addState(new int[] {-android.R.attr.state_enabled}, controlShape(SURFACE, 0));
    content.addState(new int[] {android.R.attr.state_focused}, controlShape(fill, ACCENT));
    content.addState(new int[] {}, controlShape(fill, border));
    return new RippleDrawable(
        ColorStateList.valueOf(RIPPLE), content, shape(Color.WHITE, CONTROL_RADIUS));
  }

  void weighted(LinearLayout parent, View v, int height) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height < 0 ? height : dp(height), 1);
    // Internal gutters only: the outer edge must follow its card's 12 dp inset.
    if (parent.getChildCount() > 0) lp.leftMargin = dp(8);
    parent.addView(v, lp);
  }

  LinearLayout panel() {
    LinearLayout v = col();
    pad(v, CARD_INSET, CARD_INSET);
    return v;
  }

  View navigation(String title, String icon, boolean selected, Runnable action) {
    LinearLayout item = col();
    item.setGravity(Gravity.CENTER);
    item.setMinimumHeight(dp(56));
    item.setMinimumWidth(dp(48));
    GradientDrawable cue = shape(ACCENT, 2);
    LayerDrawable selectedBackground =
        new LayerDrawable(new Drawable[] {shape(RAISED, CONTROL_RADIUS), cue});
    selectedBackground.setLayerSize(1, dp(3), dp(24));
    selectedBackground.setLayerGravity(1, Gravity.LEFT | Gravity.CENTER_VERTICAL);
    item.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(RIPPLE),
            selected ? selectedBackground : shape(Color.TRANSPARENT, CONTROL_RADIUS),
            shape(Color.WHITE, CONTROL_RADIUS)));
    ImageView image = new ImageView(context);
    image.setDuplicateParentStateEnabled(true);
    image.setImageDrawable(new Icon(context, icon, selected ? ACCENT : TEXT));
    item.addView(image, new LinearLayout.LayoutParams(dp(28), dp(28)));
    TextView label = text(title, 11, selected ? ACCENT : MUTED);
    label.setTextColor(ink(selected ? ACCENT : MUTED));
    label.setDuplicateParentStateEnabled(true);
    label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    label.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(20));
    lp.topMargin = dp(3);
    item.addView(label, lp);
    item.setContentDescription("Open " + title);
    item.setSelected(selected);
    item.setOnClickListener(v -> action.run());
    return item;
  }

  Button quietButton(String title, String icon, Runnable action) {
    Button button = button(title, icon, 0, action);
    button.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE),
        shape(Color.TRANSPARENT, CONTROL_RADIUS), shape(Color.WHITE, CONTROL_RADIUS)));
    return button;
  }

  View motionPad(Button up, Button right, Button down, Button left, Button home) {
    FrameLayout wheel = new FrameLayout(context) {
      final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
      View touchTarget;
      float touchX, touchY;
      { setWillNotDraw(false); }
      @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight()), x = getWidth()/2f, y = getHeight()/2f;
        float radius = size/2f - dp(1);
        RectF bounds = new RectF(x-radius, y-radius, x+radius, y+radius);
        for (int i = 0; i < 4; i++) {
          paint.setColor(getChildAt(i).isPressed() ? LINE : RAISED);
          canvas.drawArc(bounds, -134 + i*90, 88, true, paint);
        }
        paint.setColor(BG);
        canvas.drawCircle(x, y, size*.19f, paint);
      }
      @Override public boolean onInterceptTouchEvent(MotionEvent event) { return true; }
      @Override public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX()-getWidth()/2f, y = event.getY()-getHeight()/2f;
        float size = Math.min(getWidth(), getHeight()), distance = (float)Math.hypot(x,y);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
          if (distance > size/2f) return false;
          double angle = Math.toDegrees(Math.atan2(y,x));
          int index = distance < size*.19f ? 4 : angle < -45 && angle >= -135 ? 0
              : angle >= -45 && angle < 45 ? 1 : angle >= 45 && angle < 135 ? 2 : 3;
          touchTarget = getChildAt(index);
          touchX = event.getX(); touchY = event.getY();
          if (touchTarget.isEnabled()) touchTarget.setPressed(true);
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
          if (touchTarget != null && Math.hypot(event.getX()-touchX,event.getY()-touchY) > dp(24)) {
            touchTarget.setPressed(false); touchTarget = null;
          }
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
          if (touchTarget != null) {
            boolean click = touchTarget.isPressed() && event.getActionMasked() == MotionEvent.ACTION_UP;
            touchTarget.setPressed(false);
            if (click) touchTarget.performClick();
            touchTarget = null;
          }
        }
        invalidate();
        return true;
      }
      @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int size = Math.min(getWidth(), getHeight()), cx = getWidth()/2, cy = getHeight()/2;
        int target = Math.max(dp(48), (int)(size*.32f)), reach = (int)(size*.32f);
        int[][] centers = {{cx,cy-reach},{cx+reach,cy},{cx,cy+reach},{cx-reach,cy},{cx,cy}};
        for(int i=0;i<5;i++) {
          View child=getChildAt(i);
          child.layout(centers[i][0]-target/2,centers[i][1]-target/2,
              centers[i][0]+target/2,centers[i][1]+target/2);
        }
      }
    };
    for (Button button : new Button[] {up, right, down, left, home}) {
      pad(button, 2, 2);
      button.setTextSize(12);
      button.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE),
          shape(Color.TRANSPARENT, 100), shape(Color.WHITE, 100)));
      wheel.addView(button, new FrameLayout.LayoutParams(dp(64), dp(64)));
    }
    return wheel;
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

    @Override protected void onDraw(Canvas canvas) {
      Drawable[] icons = getCompoundDrawables();
      Drawable icon = icons[0] != null ? icons[0] : icons[1];
      if (icon == null || getLineCount() > 1) {
        super.onDraw(canvas);
        return;
      }
      // TextView centers its text independently from a left compound drawable.
      // Center the actual combined mark and label, including icon-only controls.
      Paint paint = getPaint();
      int oldColor = paint.getColor();
      paint.setColor(getCurrentTextColor());
      CharSequence text = getText();
      float iw = icon.getBounds().width(), ih = icon.getBounds().height();
      float gap = text.length() == 0 ? 0 : getCompoundDrawablePadding();
      float available = getWidth()-getPaddingLeft()-getPaddingRight();
      CharSequence label = TextUtils.ellipsize(text, getPaint(),
          Math.max(0, icons[0] != null ? available-iw-gap : available), TextUtils.TruncateAt.END);
      float tw = paint.measureText(label.toString());
      float th = text.length() == 0 ? 0 : paint.descent()-paint.ascent();
      float centerX = getPaddingLeft()+available/2f;
      float centerY = (getPaddingTop()+getHeight()-getPaddingBottom())/2f;
      float x, y, tx, baseline;
      if (icons[0] != null) {
        x = centerX-(iw+gap+tw)/2f;
        y = centerY-ih/2f;
        tx = x+iw+gap;
        baseline = centerY-(paint.ascent()+paint.descent())/2f;
      } else {
        x = centerX-iw/2f;
        y = centerY-(ih+gap+th)/2f;
        tx = centerX-tw/2f;
        baseline = y+ih+gap-paint.ascent();
      }
      canvas.save();
      canvas.translate(x-icon.getBounds().left, y-icon.getBounds().top);
      icon.draw(canvas);
      canvas.restore();
      if (label.length() > 0) canvas.drawText(label.toString(), tx, baseline, paint);
      paint.setColor(oldColor);
    }
  }

  static void selection(Button button, boolean selected, PanelUi ui) {
    if (Boolean.valueOf(selected).equals(button.getTag())) return;
    button.setTag(selected);
    button.setSelected(selected);
    int ink = selected ? ACCENT : MUTED;
    button.setTextColor(ui.ink(ink));
    button.setBackground(ui.buttonBackground(selected ? 3 : 4));
    for (Drawable drawable : button.getCompoundDrawables()) {
      if (drawable instanceof Icon) ((Icon) drawable).setInk(ink);
    }
  }

  /** Pinned Material Symbols; Android owns path rendering and state tinting. */
  static final class Icon extends Drawable {
    final Drawable vector;
    final int intrinsicSize;
    int baseColor;

    Icon(Context context, String name, int color) {
      int resource = context.getResources().getIdentifier("panel_" + name, "drawable", context.getPackageName());
      if (resource == 0) throw new IllegalArgumentException("Missing panel icon: " + name);
      vector = context.getDrawable(resource).mutate();
      intrinsicSize = Math.round(24 * context.getResources().getDisplayMetrics().density);
      baseColor = color;
      vector.setTint(color);
    }

    @Override public boolean isStateful() { return true; }
    @Override protected boolean onStateChange(int[] states) {
      boolean enabled = states.length == 0;
      for (int state : states) if (state == android.R.attr.state_enabled) enabled = true;
      vector.setTint(enabled ? baseColor : DISABLED);
      invalidateSelf();
      return true;
    }
    void setInk(int color) { baseColor = color; onStateChange(getState()); }
    @Override protected void onBoundsChange(Rect bounds) { vector.setBounds(bounds); }
    @Override public void draw(Canvas canvas) { vector.draw(canvas); }
    @Override public void setAlpha(int alpha) { vector.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { vector.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return intrinsicSize; }
    @Override public int getIntrinsicHeight() { return intrinsicSize; }
  }
}
