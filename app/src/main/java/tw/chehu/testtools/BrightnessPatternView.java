package tw.chehu.testtools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import java.util.List;

final class BrightnessPatternView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Integer> percentages;
    private final boolean circle;
    private int index;

    BrightnessPatternView(Context context, List<Integer> percentages, boolean circle, int initialPercentage) {
        super(context);
        this.percentages = percentages;
        this.circle = circle;
        int savedIndex = percentages.indexOf(initialPercentage);
        if (savedIndex >= 0) index = savedIndex;
        paint.setColor(Color.WHITE);
        labelPaint.setColor(Color.rgb(110, 110, 110));
        labelPaint.setTextSize(Ui.dp(context, 12));
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        labelBackgroundPaint.setColor(Color.argb(170, 0, 0, 0));
        setBackgroundColor(Color.BLACK);
    }

    void next() {
        index = (index + 1) % percentages.size();
        invalidate();
    }

    void previous() {
        index = (index - 1 + percentages.size()) % percentages.size();
        invalidate();
    }

    int currentPercentage() {
        return percentages.get(index);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        double targetArea = width * height * percentages.get(index) / 100.0;
        float cx = width / 2f;
        float cy = height / 2f;

        if (circle) {
            float radius = solveRadius(width, height, targetArea);
            canvas.drawCircle(cx, cy, radius, paint);
        } else {
            float side = solveSquareSide(width, height, targetArea);
            canvas.drawRect(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f, paint);
        }
        String label = percentages.get(index) + "%";
        float padding = Ui.dp(getContext(), 7);
        float right = width - Ui.dp(getContext(), 14);
        float baseline = height - Ui.dp(getContext(), 18);
        float textWidth = labelPaint.measureText(label);
        float textHeight = labelPaint.descent() - labelPaint.ascent();
        RectF labelBackground = new RectF(
                right - textWidth - padding * 2,
                baseline + labelPaint.ascent() - padding,
                right + padding,
                baseline + labelPaint.descent() + padding);
        canvas.drawRoundRect(labelBackground, padding, padding, labelBackgroundPaint);
        canvas.drawText(label, right, baseline, labelPaint);
    }

    private float solveSquareSide(float width, float height, double target) {
        float low = 0f;
        float high = Math.max(width, height);
        for (int i = 0; i < 40; i++) {
            float middle = (low + high) / 2f;
            double visible = Math.min(width, middle) * Math.min(height, middle);
            if (visible < target) low = middle; else high = middle;
        }
        return high;
    }

    private float solveRadius(float width, float height, double target) {
        float low = 0f;
        float high = (float) Math.hypot(width / 2.0, height / 2.0);
        for (int i = 0; i < 34; i++) {
            float middle = (low + high) / 2f;
            if (visibleCircleArea(width, height, middle) < target) low = middle; else high = middle;
        }
        return high;
    }

    private double visibleCircleArea(float width, float height, float radius) {
        double halfW = width / 2.0;
        double halfH = height / 2.0;
        int slices = 600;
        double step = halfW / slices;
        double quadrant = 0;
        for (int i = 0; i < slices; i++) {
            double x = (i + 0.5) * step;
            double y = x >= radius ? 0 : Math.sqrt(radius * radius - x * x);
            quadrant += Math.min(halfH, y) * step;
        }
        return quadrant * 4.0;
    }
}
