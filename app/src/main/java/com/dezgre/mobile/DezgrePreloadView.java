package com.dezgre.mobile;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

final class DezgrePreloadView extends FrameLayout {
    private static final int BG = Color.rgb(247, 247, 248);

    DezgrePreloadView(Context context) {
        super(context);
        setBackgroundColor(BG);
        setClickable(true);

        ShimmerSkeleton skeleton = new ShimmerSkeleton(context);
        addView(skeleton, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        ImageView logo = new ImageView(context);
        logo.setImageResource(R.drawable.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LayoutParams logoParams = new LayoutParams(dp(76), dp(76));
        logoParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        logoParams.topMargin = dp(86);
        addView(logo, logoParams);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ShimmerSkeleton extends View {
        private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix shimmerMatrix = new Matrix();
        private ValueAnimator animator;
        private float shimmerProgress = -1f;
        private LinearGradient gradient;

        ShimmerSkeleton(Context context) {
            super(context);
            basePaint.setColor(Color.rgb(232, 233, 236));
            setLayerType(LAYER_TYPE_HARDWARE, null);
            startAnimation();
        }

        private void startAnimation() {
            animator = ValueAnimator.ofFloat(-1f, 1f);
            animator.setDuration(1400L);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(animation -> {
                shimmerProgress = (float) animation.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            gradient = new LinearGradient(
                    0, 0, Math.max(1, w / 2f), 0,
                    new int[]{
                            Color.argb(0, 255, 255, 255),
                            Color.argb(120, 255, 255, 255),
                            Color.argb(0, 255, 255, 255)
                    },
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP
            );
            shimmerPaint.setShader(gradient);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int top = dp(196);
            int side = dp(22);
            int content = Math.max(0, width - side * 2);
            float radius = dp(16);

            drawRounded(canvas, side, top, side + content, top + dp(24), dp(10));
            top += dp(44);
            drawCard(canvas, side, top, content, dp(122), radius);
            top += dp(142);
            drawCard(canvas, side, top, content, dp(96), radius);
            top += dp(116);
            drawCard(canvas, side, top, content, dp(96), radius);

            if (gradient != null) {
                float travel = width + width * 0.65f;
                shimmerMatrix.setTranslate(shimmerProgress * travel, 0f);
                gradient.setLocalMatrix(shimmerMatrix);
                drawRoundedShimmer(canvas, side, dp(196), side + content, dp(220), dp(10));
                drawCardShimmer(canvas, side, dp(240), content, dp(122), radius);
                drawCardShimmer(canvas, side, dp(382), content, dp(96), radius);
                drawCardShimmer(canvas, side, dp(498), content, dp(96), radius);
            }
        }

        private void drawCard(Canvas canvas, int left, int top, int width, int height, float radius) {
            canvas.drawRoundRect(left, top, left + width, top + height, radius, radius, basePaint);
        }

        private void drawRounded(Canvas canvas, int left, int top, int right, int bottom, float radius) {
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, basePaint);
        }

        private void drawCardShimmer(Canvas canvas, int left, int top, int width, int height, float radius) {
            canvas.drawRoundRect(left, top, left + width, top + height, radius, radius, shimmerPaint);
        }

        private void drawRoundedShimmer(Canvas canvas, int left, int top, int right, int bottom, float radius) {
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, shimmerPaint);
        }

        private int dp(int value) {
            return Math.round(value * getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onDetachedFromWindow() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            super.onDetachedFromWindow();
        }
    }
}
