package dev.leo.voronpanel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import java.util.Locale;
import org.json.JSONArray;

/** Read-only measured mesh. Row zero is the front of the bed, drawn at the bottom. */
final class BedMeshView extends View {
  private static final int LOW = PanelUi.MUTED, ZERO = PanelUi.ACCENT, HIGH = PanelUi.AMBER;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF cell = new RectF();
  private final float density;
  private double[][] values;
  private double minimum, maximum, average, scale;

  BedMeshView(Context context, JSONArray matrix) {
    super(context);
    density = getResources().getDisplayMetrics().density;
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    setBackgroundColor(PanelUi.SURFACE);
    if (!readMatrix(matrix)) {
      values = null;
      setContentDescription(
          "Bed heightmap unavailable. A complete finite grid of 2 to 100 rows and columns is"
              + " required.");
      return;
    }
    StringBuilder description = new StringBuilder("Measured bed heightmap, ");
    description
        .append(values[0].length)
        .append(" columns by ")
        .append(values.length)
        .append(" rows. Rear, Y maximum, is at the top. Front, Y minimum, is at the bottom. ")
        .append(
            "X minimum is left and X maximum is right. All heights are in millimeters. Minimum ")
        .append(minimum)
        .append(", maximum ")
        .append(maximum)
        .append(", range ")
        .append(maximum - minimum)
        .append(", average ")
        .append(average)
        .append(
            ". Colors are relative to the mesh average: gray lower, mint near average, amber"
                + " higher. ")
        .append("The color scale extends at least 0.10 millimeters either side of the average. ")
        .append("Measured rows, listed front to rear; each row runs from X minimum to X maximum: ");
    for (int y = 0; y < values.length; y++) {
      description.append("Row ").append(y + 1).append(": ");
      for (int x = 0; x < values[y].length; x++) {
        if (x > 0) description.append(", ");
        description.append(values[y][x]);
      }
      description.append(". ");
    }
    setContentDescription(description.toString());
  }

  private boolean readMatrix(JSONArray matrix) {
    if (matrix == null || matrix.length() < 2 || matrix.length() > 100) return false;
    JSONArray first = matrix.optJSONArray(0);
    if (first == null || first.length() < 2 || first.length() > 100) return false;
    int columns = first.length();
    values = new double[matrix.length()][columns];
    minimum = Double.POSITIVE_INFINITY;
    maximum = Double.NEGATIVE_INFINITY;
    double sum = 0;
    for (int y = 0; y < values.length; y++) {
      JSONArray row = matrix.optJSONArray(y);
      if (row == null || row.length() != columns) return false;
      for (int x = 0; x < columns; x++) {
        Object raw = row.opt(x);
        if (!(raw instanceof Number)) return false;
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value)) return false;
        values[y][x] = value;
        minimum = Math.min(minimum, value);
        maximum = Math.max(maximum, value);
        sum += value;
      }
    }
    average = sum / (values.length * columns);
    scale = Math.max(0.10, Math.max(Math.abs(minimum - average), Math.abs(maximum - average)));
    return Double.isFinite(average) && Double.isFinite(scale) && Double.isFinite(maximum - minimum);
  }

  String summary() {
    return values == null
        ? "No active mesh"
        : String.format(
            Locale.US, "Range %.3f mm\nMin %+.3f · Max %+.3f", maximum - minimum, minimum, maximum);
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    setMeasuredDimension(
        resolveSize(Math.round(dp(440)), widthSpec), resolveSize(Math.round(dp(280)), heightSpec));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    paint.setTextSize(dp(12));
    paint.setColor(PanelUi.MUTED);
    paint.setTextAlign(Paint.Align.CENTER);
    if (values == null) {
      canvas.drawText(
          "No measured heightmap available",
          getWidth() / 2f,
          getHeight() / 2f - (paint.ascent() + paint.descent()) / 2f,
          paint);
      return;
    }
    float left = dp(8), right = getWidth() - dp(8), top = dp(28), bottom = getHeight() - dp(32);
    if (right <= left || bottom <= top) return;
    canvas.drawText("Rear · Y max", getWidth() / 2f, dp(17), paint);
    paint.setTextAlign(Paint.Align.LEFT);
    paint.setTextSize(dp(11));
    canvas.drawText(String.format(Locale.US, "Color scale ±%.2f mm", scale), left, dp(17), paint);
    paint.setTextAlign(Paint.Align.RIGHT);
    canvas.drawText("Gray lower · amber higher", right, dp(17), paint);
    paint.setTextAlign(Paint.Align.CENTER);
    paint.setTextSize(dp(12));
    canvas.drawText("Front · Y min", getWidth() / 2f, getHeight() - dp(10), paint);
    paint.setTextAlign(Paint.Align.LEFT);
    canvas.drawText("X min", left, getHeight() - dp(10), paint);
    paint.setTextAlign(Paint.Align.RIGHT);
    canvas.drawText("X max", right, getHeight() - dp(10), paint);
    float cellWidth = (right - left) / values[0].length;
    float cellHeight = (bottom - top) / values.length;
    float gap = Math.min(dp(1), Math.min(cellWidth, cellHeight) * .12f);
    boolean numbers = cellWidth >= dp(54) && cellHeight >= dp(32);
    paint.setTextAlign(Paint.Align.CENTER);
    paint.setTextSize(dp(12));
    for (int y = 0; y < values.length; y++) {
      for (int x = 0; x < values[y].length; x++) {
        float cx = left + x * cellWidth;
        float cy = bottom - (y + 1) * cellHeight;
        cell.set(cx + gap / 2, cy + gap / 2, cx + cellWidth - gap / 2, cy + cellHeight - gap / 2);
        double deviation = (values[y][x] - average) / scale;
        paint.setColor(mix(ZERO, deviation < 0 ? LOW : HIGH, Math.min(1, Math.abs(deviation))));
        canvas.drawRect(cell, paint);
        if (numbers) {
          String label = String.format(Locale.US, "%+.3f", values[y][x]);
          if (paint.measureText(label) <= cellWidth - dp(8)) {
            paint.setColor(PanelUi.BG);
            canvas.drawText(
                label,
                cell.centerX(),
                cell.centerY() - (paint.ascent() + paint.descent()) / 2f,
                paint);
          }
        }
      }
    }
  }

  private float dp(float value) {
    return value * density;
  }

  private static int mix(int from, int to, double amount) {
    return Color.rgb(
        (int) Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
        (int) Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
        (int) Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
  }
}
