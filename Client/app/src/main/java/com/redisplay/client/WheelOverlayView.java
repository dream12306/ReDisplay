package com.redisplay.client;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class WheelOverlayView extends View {
    private static final int PAGE_MAIN = 0;
    private static final int PAGE_RECORDING = 1;
    private static final int PAGE_MACRO_LIBRARY = 2;
    private static final int MACROS_PER_PAGE = 14;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final float centerRadius;
    private final float innerRadius;
    private final float outerRadius;
    private float centerX;
    private float centerY;
    private int selectedIndex = -1;
    private int page = PAGE_MAIN;
    private List<NetworkServer.WheelSlot> mainSlots = Collections.emptyList();
    private List<NetworkServer.WheelSlot> macroItems = Collections.emptyList();
    private int macroPage = 0;
    private final List<NetworkServer.WheelSlot> recordingSlots;

    public WheelOverlayView(Context context) {
        super(context);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        density = metrics.density;
        centerRadius = dp(34);
        innerRadius = dp(94);
        outerRadius = dp(158);
        fillPaint.setStyle(Paint.Style.FILL);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.2f));
        borderPaint.setColor(Color.argb(160, 255, 255, 255));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dp(12));
        textPaint.setFakeBoldText(true);
        recordingSlots = buildRecordingSlots();
        setVisibility(GONE);
    }

    public void setConfig(NetworkServer.WheelConfig config) {
        if (config != null && config.slots != null && config.slots.size() == 16) {
            mainSlots = new ArrayList<>(config.slots);
            invalidate();
        }
    }

    public void setMacroCatalog(NetworkServer.MacroCatalog catalog) {
        macroItems = catalog == null || catalog.items == null
                ? Collections.emptyList()
                : new ArrayList<>(catalog.items);
        macroPage = 0;
        invalidate();
    }

    public void showAt(float requestedX, float requestedY) {
        float margin = outerRadius + dp(8);
        centerX = clamp(requestedX, margin, Math.max(margin, getWidth() - margin));
        centerY = clamp(requestedY, margin, Math.max(margin, getHeight() - margin));
        page = PAGE_MAIN;
        selectedIndex = -1;
        setVisibility(VISIBLE);
        bringToFront();
        invalidate();
    }

    public void dismiss() {
        selectedIndex = -1;
        setVisibility(GONE);
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    public boolean hasSelection() {
        return selectedIndex >= 0;
    }

    public void updateSelection(float x, float y) {
        float dx = x - centerX;
        float dy = y - centerY;
        int next = WheelGeometry.selectionIndex(
                dx, dy, centerRadius, innerRadius, outerRadius
        );
        if (next != selectedIndex) {
            selectedIndex = next;
            invalidate();
        }
    }

    public NetworkServer.WheelSlot commitSelection() {
        List<NetworkServer.WheelSlot> slots = activeSlots();
        if (selectedIndex < 0 || selectedIndex >= slots.size()) return null;
        NetworkServer.WheelSlot slot = slots.get(selectedIndex);
        if (slot.action == NetworkServer.INPUT_ACTION_RECORDING_ZONE) {
            page = PAGE_RECORDING;
            selectedIndex = -1;
            invalidate();
            return null;
        }
        if (slot.action == NetworkServer.INPUT_ACTION_MACRO_LIBRARY) {
            page = PAGE_MACRO_LIBRARY;
            macroPage = 0;
            selectedIndex = -1;
            invalidate();
            return null;
        }
        if (slot.action == NetworkServer.INPUT_ACTION_MACRO_PAGE_PREVIOUS) {
            if (macroPage > 0) macroPage--;
            else page = PAGE_MAIN;
            selectedIndex = -1;
            invalidate();
            return null;
        }
        if (slot.action == NetworkServer.INPUT_ACTION_MACRO_PAGE_NEXT) {
            macroPage++;
            selectedIndex = -1;
            invalidate();
            return null;
        }
        if (slot.action == NetworkServer.INPUT_ACTION_BACK) {
            page = PAGE_MAIN;
            selectedIndex = -1;
            invalidate();
            return null;
        }
        dismiss();
        return slot;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getVisibility() != VISIBLE) return;
        canvas.drawColor(Color.argb(72, 0, 0, 0));
        List<NetworkServer.WheelSlot> slots = activeSlots();
        for (int ring = 0; ring < 2; ring++) {
            float minRadius = ring == 0 ? centerRadius : innerRadius;
            float maxRadius = ring == 0 ? innerRadius : outerRadius;
            for (int sector = 0; sector < 8; sector++) {
                int index = ring * 8 + sector;
                drawSector(canvas, minRadius, maxRadius, sector, index == selectedIndex);
                if (index < slots.size()) drawLabel(canvas, slots.get(index).label, minRadius, maxRadius, sector);
            }
        }

        fillPaint.setColor(page == PAGE_RECORDING
                ? Color.argb(235, 150, 35, 45)
                : Color.argb(235, 30, 36, 48));
        canvas.drawCircle(centerX, centerY, centerRadius - dp(2), fillPaint);
        borderPaint.setColor(Color.argb(190, 255, 255, 255));
        canvas.drawCircle(centerX, centerY, centerRadius - dp(2), borderPaint);
        textPaint.setTextSize(dp(12));
        String centerLabel = page == PAGE_RECORDING ? "REC"
                : page == PAGE_MACRO_LIBRARY ? "宏库" : "轮盘";
        canvas.drawText(centerLabel, centerX,
                centerY - (textPaint.ascent() + textPaint.descent()) / 2, textPaint);
    }

    private void drawSector(Canvas canvas, float minRadius, float maxRadius, int sector, boolean selected) {
        float centerAngle = sector * 45.0f - 90.0f;
        float startAngle = centerAngle - 21.75f;
        float sweep = 43.5f;
        RectF outer = new RectF(centerX - maxRadius, centerY - maxRadius,
                centerX + maxRadius, centerY + maxRadius);
        RectF inner = new RectF(centerX - minRadius, centerY - minRadius,
                centerX + minRadius, centerY + minRadius);
        Path path = new Path();
        double startRadians = Math.toRadians(startAngle);
        path.moveTo(centerX + (float) Math.cos(startRadians) * maxRadius,
                centerY + (float) Math.sin(startRadians) * maxRadius);
        path.arcTo(outer, startAngle, sweep);
        path.arcTo(inner, startAngle + sweep, -sweep);
        path.close();
        fillPaint.setColor(selected
                ? Color.argb(245, 48, 143, 255)
                : Color.argb(225, 28, 34, 46));
        canvas.drawPath(path, fillPaint);
        borderPaint.setColor(Color.argb(selected ? 245 : 120, 255, 255, 255));
        canvas.drawPath(path, borderPaint);
    }

    private void drawLabel(Canvas canvas, String rawLabel, float minRadius, float maxRadius, int sector) {
        String label = rawLabel == null || rawLabel.isEmpty() ? "—" : rawLabel;
        if (label.length() > 10) label = label.substring(0, 9) + "…";
        float radius = (minRadius + maxRadius) * 0.5f;
        double angle = Math.toRadians(sector * 45.0 - 90.0);
        float x = centerX + (float) Math.cos(angle) * radius;
        float y = centerY + (float) Math.sin(angle) * radius;
        textPaint.setTextSize(dp(label.length() > 6 ? 9 : 11));
        canvas.drawText(label, x, y - (textPaint.ascent() + textPaint.descent()) / 2, textPaint);
    }

    private List<NetworkServer.WheelSlot> activeSlots() {
        if (page == PAGE_RECORDING) return recordingSlots;
        if (page == PAGE_MACRO_LIBRARY) return buildMacroPage();
        return mainSlots;
    }

    private List<NetworkServer.WheelSlot> buildMacroPage() {
        ArrayList<NetworkServer.WheelSlot> slots = new ArrayList<>(16);
        slots.add(slot(
                macroPage == 0 ? NetworkServer.INPUT_ACTION_BACK
                        : NetworkServer.INPUT_ACTION_MACRO_PAGE_PREVIOUS,
                macroPage == 0 ? "返回" : "上一页"
        ));
        int start = macroPage * MACROS_PER_PAGE;
        for (int offset = 0; offset < MACROS_PER_PAGE; offset++) {
            int index = start + offset;
            slots.add(index < macroItems.size()
                    ? macroItems.get(index)
                    : slot(NetworkServer.INPUT_ACTION_NONE, "空"));
        }
        boolean hasNext = start + MACROS_PER_PAGE < macroItems.size();
        slots.add(slot(
                hasNext ? NetworkServer.INPUT_ACTION_MACRO_PAGE_NEXT
                        : NetworkServer.INPUT_ACTION_BACK,
                hasNext ? "下一页" : "返回"
        ));
        return slots;
    }

    private List<NetworkServer.WheelSlot> buildRecordingSlots() {
        ArrayList<NetworkServer.WheelSlot> slots = new ArrayList<>(16);
        slots.add(slot(NetworkServer.INPUT_ACTION_RECORD_START, "开始录制"));
        slots.add(slot(NetworkServer.INPUT_ACTION_RECORD_STOP, "结束并保存"));
        slots.add(slot(NetworkServer.INPUT_ACTION_RECORD_CANCEL, "取消录制"));
        slots.add(slot(NetworkServer.INPUT_ACTION_SAVE_LAST_STROKE, "保存笔迹"));
        slots.add(slot(NetworkServer.INPUT_ACTION_ENTER_MOUSE_MODE, "鼠标模式"));
        slots.add(slot(NetworkServer.INPUT_ACTION_BACK, "返回"));
        slots.add(slot(NetworkServer.INPUT_ACTION_REPLAY_LATEST, "最近录制"));
        slots.add(slot(NetworkServer.INPUT_ACTION_REPLAY_LAST_STROKE, "重复笔迹"));
        slots.add(slot(NetworkServer.INPUT_ACTION_EXIT_MOUSE_MODE, "退出鼠标"));
        slots.add(slot(NetworkServer.INPUT_ACTION_RECORD_STOP, "停止"));
        slots.add(slot(NetworkServer.INPUT_ACTION_RECORD_CANCEL, "放弃"));
        slots.add(slot(NetworkServer.INPUT_ACTION_BACK, "主轮盘"));
        slots.add(slot(NetworkServer.INPUT_ACTION_NONE, "空"));
        slots.add(slot(NetworkServer.INPUT_ACTION_NONE, "空"));
        slots.add(slot(NetworkServer.INPUT_ACTION_NONE, "空"));
        slots.add(slot(NetworkServer.INPUT_ACTION_NONE, "空"));
        return slots;
    }

    private static NetworkServer.WheelSlot slot(int action, String label) {
        return new NetworkServer.WheelSlot(action, 0, 0, 0, label);
    }

    private float dp(float value) {
        return value * density;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

