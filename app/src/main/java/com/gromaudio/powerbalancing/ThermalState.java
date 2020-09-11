package com.gromaudio.powerbalancing;

import androidx.annotation.ColorRes;
import androidx.annotation.StringRes;

enum ThermalState {
    NORMAL(R.string.thermal_state_normal, R.color.green),
    WARNING(R.string.thermal_state_warning, R.color.yellow),
    SHUTDOWN(R.string.thermal_state_shutdown, R.color.red);

    @StringRes
    final private int mTitle;

    @ColorRes
    final private int mColor;

    ThermalState(@StringRes int title, @ColorRes int color) {
        mTitle = title;
        mColor = color;
    }

    public int getTitle() {
        return mTitle;
    }

    public int getColor() {
        return mColor;
    }
}