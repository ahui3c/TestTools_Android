package tw.chehu.testtools;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

final class ScreenPatternView extends View {
    private static final String[] NAMES = {
            "全黑／漏光與亮點", "全白／暗點與均勻度", "紅色", "綠色", "藍色", "青色",
            "洋紅", "黃色", "5% 灰階", "10% 灰階", "25% 灰階", "50% 灰階",
            "75% 灰階", "水平灰階漸層", "RGB 漸層", "標準色條", "棋盤格", "像素銳利度"
    };
    private final Paint paint = new Paint();
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int index;

    ScreenPatternView(Context context) {
        super(context);
        paint.setAntiAlias(false);
        labelPaint.setTextSize(Ui.dp(context, 12));
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    void next() { index = (index + 1) % NAMES.length; invalidate(); }
    void previous() { index = (index - 1 + NAMES.length) % NAMES.length; invalidate(); }

    @SuppressLint("DrawAllocation")
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        paint.setShader(null);
        if (index <= 7) {
            int[] colors = {Color.BLACK, Color.WHITE, Color.RED, Color.GREEN, Color.BLUE,
                    Color.CYAN, Color.MAGENTA, Color.YELLOW};
            canvas.drawColor(colors[index]);
        } else if (index <= 12) {
            int[] grays = {13, 26, 64, 128, 191};
            int value = grays[index - 8];
            canvas.drawColor(Color.rgb(value, value, value));
        } else if (index == 13) {
            paint.setShader(new LinearGradient(0, 0, width, 0, Color.BLACK, Color.WHITE,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
        } else if (index == 14) {
            int band = Math.max(1, height / 3);
            int[] colors = {Color.RED, Color.GREEN, Color.BLUE};
            for (int row = 0; row < 3; row++) {
                paint.setShader(new LinearGradient(0, 0, width, 0, Color.BLACK, colors[row],
                        Shader.TileMode.CLAMP));
                canvas.drawRect(0, row * band, width, row == 2 ? height : (row + 1) * band, paint);
            }
        } else if (index == 15) {
            int[] bars = {Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN,
                    Color.MAGENTA, Color.RED, Color.BLUE, Color.BLACK};
            for (int column = 0; column < bars.length; column++) {
                paint.setColor(bars[column]);
                float left = width * column / (float) bars.length;
                float right = width * (column + 1) / (float) bars.length;
                canvas.drawRect(left, 0, right, height, paint);
            }
        } else if (index == 16) {
            int cell = Math.max(4, Ui.dp(getContext(), 18));
            for (int y = 0; y < height; y += cell) {
                for (int x = 0; x < width; x += cell) {
                    paint.setColor(((x / cell) + (y / cell)) % 2 == 0 ? Color.WHITE : Color.BLACK);
                    canvas.drawRect(x, y, Math.min(width, x + cell), Math.min(height, y + cell), paint);
                }
            }
        } else {
            canvas.drawColor(Color.WHITE);
            paint.setColor(Color.BLACK);
            int third = height / 3;
            for (int x = 0; x < width; x += 2) canvas.drawRect(x, 0, x + 1, third, paint);
            for (int y = third; y < third * 2; y += 2) canvas.drawRect(0, y, width, y + 1, paint);
            for (int y = third * 2; y < height; y += 4) {
                for (int x = 0; x < width; x += 4) {
                    if (((x / 4) + (y / 4)) % 2 == 0) canvas.drawRect(x, y, x + 2, y + 2, paint);
                }
            }
        }
        drawLabel(canvas);
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void drawLabel(Canvas canvas) {
        String label = (index + 1) + "/" + NAMES.length + "  " + NAMES[index];
        float padding = Ui.dp(getContext(), 8);
        float textWidth = labelPaint.measureText(label);
        float height = Ui.dp(getContext(), 28);
        float left = Ui.dp(getContext(), 8);
        float top = Ui.dp(getContext(), 8);
        paint.setShader(null);
        paint.setColor(0x99000000);
        canvas.drawRoundRect(new RectF(left, top, left + textWidth + padding * 2, top + height),
                Ui.dp(getContext(), 7), Ui.dp(getContext(), 7), paint);
        labelPaint.setColor(0xFFD1D5DB);
        canvas.drawText(label, left + padding, top + Ui.dp(getContext(), 19), labelPaint);
    }
}
