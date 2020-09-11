package com.gromaudio.powerbalancing;

import androidx.annotation.RawRes;

enum Speed {
    SLOW(20, R.raw.rabbit_anim_l, R.raw.rabbit_anim_r),
    AVERAGE(40, R.raw.horse_anim_l, R.raw.horse_anim_r),
    FAST(60, R.raw.cheetah_anim_l, R.raw.cheetah_anim_r);

    final int mThreshold;

    @RawRes
    final int mLeft;

    @RawRes
    final int mRight;

    Speed(int threshold, @RawRes int left, @RawRes int right) {
        mThreshold = threshold;
        mLeft = left;
        mRight = right;
    }

    public int getLeft() {
        return mLeft;
    }

    public int getRight() {
        return mRight;
    }

    public int getThreshold() {
        return mThreshold;
    }
}