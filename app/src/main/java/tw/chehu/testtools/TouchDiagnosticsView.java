package tw.chehu.testtools;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@SuppressLint("ViewConstructor")
final class TouchDiagnosticsView extends View {
    private static final int COLUMNS = 12;
    private static final int ROWS = 24;
    private final boolean[][] touched = new boolean[COLUMNS][ROWS];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, float[]> pointers = new HashMap<>();
    private final GestureDetector gestures;
    private final Runnable finish;
    private int touchedCells;
    private int maxPointers;

    TouchDiagnosticsView(Context context, Runnable finish) {
        super(context);
        this.finish = finish;
        setBackgroundColor(Ui.color("#F8FAFC"));
        textPaint.setTextSize(Ui.dp(context, 13));
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onDoubleTap(MotionEvent event) {
                if (event.getY() <= getHeight() * 0.25f) {
                    if (event.getX() < getWidth() / 2f) clear();
                    else finish.run();
                    return true;
                }
                return false;
            }
        });
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        gestures.onTouchEvent(event);
        pointers.clear();
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            for (int index = 0; index < event.getPointerCount(); index++) {
                int id = event.getPointerId(index);
                float x = event.getX(index);
                float y = event.getY(index);
                pointers.put(id, new float[]{x, y});
                mark(x, y);
            }
            maxPointers = Math.max(maxPointers, event.getPointerCount());
        }
        invalidate();
        if (action == MotionEvent.ACTION_UP) performClick();
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cellWidth = getWidth() / (float) COLUMNS;
        float cellHeight = getHeight() / (float) ROWS;
        for (int column = 0; column < COLUMNS; column++) {
            for (int row = 0; row < ROWS; row++) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(touched[column][row] ? Ui.color("#BFDBFE") : Color.WHITE);
                float left = column * cellWidth;
                float top = row * cellHeight;
                canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1f);
                paint.setColor(Ui.color("#CBD5E1"));
                canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        for (Map.Entry<Integer, float[]> pointer : pointers.entrySet()) {
            float[] point = pointer.getValue();
            paint.setColor(pointerColor(pointer.getKey()));
            canvas.drawCircle(point[0], point[1], Ui.dp(getContext(), 22), paint);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(String.valueOf(pointer.getKey() + 1), point[0],
                    point[1] + Ui.dp(getContext(), 5), textPaint);
        }
        drawStatus(canvas);
    }

    private void drawStatus(Canvas canvas) {
        float coverage = touchedCells * 100f / (COLUMNS * ROWS);
        String status = String.format(Locale.TAIWAN, "目前 %d 點｜最高 %d 點｜覆蓋 %.0f%%",
                pointers.size(), maxPointers, coverage);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCC0F172A);
        canvas.drawRoundRect(Ui.dp(getContext(), 8), Ui.dp(getContext(), 8),
                getWidth() - Ui.dp(getContext(), 8), Ui.dp(getContext(), 42),
                Ui.dp(getContext(), 9), Ui.dp(getContext(), 9), paint);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(status, getWidth() / 2f, Ui.dp(getContext(), 30), textPaint);
        String help = "雙擊左上清除｜雙擊右上返回";
        paint.setColor(0xCC0F172A);
        float top = getHeight() - Ui.dp(getContext(), 38);
        canvas.drawRect(0, top, getWidth(), getHeight(), paint);
        canvas.drawText(help, getWidth() / 2f, getHeight() - Ui.dp(getContext(), 14), textPaint);
    }

    private void mark(float x, float y) {
        int column = Math.max(0, Math.min(COLUMNS - 1, (int) (x * COLUMNS / getWidth())));
        int row = Math.max(0, Math.min(ROWS - 1, (int) (y * ROWS / getHeight())));
        if (!touched[column][row]) {
            touched[column][row] = true;
            touchedCells++;
        }
    }

    private void clear() {
        for (int column = 0; column < COLUMNS; column++) {
            for (int row = 0; row < ROWS; row++) touched[column][row] = false;
        }
        touchedCells = 0;
        maxPointers = 0;
        invalidate();
    }

    private int pointerColor(int id) {
        int[] colors = {0xFF2563EB, 0xFF16A34A, 0xFFDC2626, 0xFF9333EA,
                0xFFEA580C, 0xFF0891B2, 0xFFDB2777, 0xFF4F46E5, 0xFF65A30D, 0xFF0F766E};
        return colors[Math.abs(id) % colors.length];
    }
}
