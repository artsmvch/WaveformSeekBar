package com.frolo.waveformseekbar;

import android.animation.ValueAnimator;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;


/**
 * A WaveformSeekBar is for the same as {@link android.widget.SeekBar}.
 * The only difference is that the WaveformSeekBar displays a waveform represented by an array of ints.
 * That array of ints is set via {@link WaveformSeekBar#setWaveform(int[], boolean)}
 * and {@link WaveformSeekBar#setWaveform(int[], boolean)} methods.
 * The progress is set in percentage via {@link WaveformSeekBar#setProgressInPercentage(float)} method.
 * The tracking of the progress is listened using {@link WaveformSeekBar.Callback}.
 *
 * The structure of the waveform on the UI be like:
 * [paddingLeft] [edgeWaveGap] [wave] [waveGap] [wave] ... [wave] [waveGap] [wave] [edgeWaveGap] [paddingRight].
 */
public class WaveformSeekBar extends View {

    private static final String LOG_TAG = WaveformSeekBar.class.getSimpleName();
    private static final boolean DEBUG = BuildConfig.DEBUG;

    private static final float DEFAULT_VIEW_WIDTH_IN_DP = 280f;
    private static final float DEFAULT_VIEW_HEIGHT_IN_DP = 72f;
    private static final float DEFAULT_EDGE_WAVE_GAP_WIDTH_IN_DP = 2f;

    private static final float MAX_WIDTH_NO_LIMIT = -1f;

    private static final int ANIM_DURATION = 200;

    public enum WaveCornerType { NONE, AUTO, EXACTLY }

    /**
     * Creates a waveform based on the default implementation.
     * @param waves to create waveform from
     * @return waveform instance
     */
    public static Waveform createWaveform(int[] waves) {
        return new WaveformImpl(waves);
    }

    private static float clampPercentage(float percent) {
        if (percent < 0) return 0f;
        if (percent > 1) return 1;
        return percent;
    }

    private static int calculateDiscretePosition(int count, float percent) {
        if (percent < 0.001f) {
            return -1;
        }
        if (percent > 0.999f) {
            return count - 1;
        }
        // Edge wave areas (the leftmost and the rightmost) have
        // only half the percentage width of a normal wave.
        float normalWavePercent = 1f / (count - 1);
        float missingEdgeWavePercent = normalWavePercent / 2f;
        float actualPercent = (percent + missingEdgeWavePercent) / (1 + missingEdgeWavePercent * 2f);
        return (int) (count * actualPercent);
    }

    /**
     * Calculates the intermediate waveform between <code>start</code> and <code>end</code> waveforms.
     * <code>factor</code> defines the position between the start and end waves. Normally, the factor
     * should be from 0 to 1. If the factor is 0, then the resulting waveform is equal to the start one.
     * If the factor is 1, then the resulting waveform is equal to the end one.
     * @param start the start waveform
     * @param end the end waveform
     * @param factor factor
     * @return blended waveform
     */
    private static Waveform blendWaveforms(Waveform start, Waveform end, float factor) {
        int waveCount = end.getWaveCount();
        if (waveCount != start.getWaveCount()) {
            return end;
        }
        float normalizer = ((float) end.getMaxWave()) / start.getMaxWave();
        int[] waves = new int[waveCount];
        for (int i = 0; i < waveCount; i++) {
            float normalizedStartWave = start.getWaveAt(i) * normalizer;
            waves[i] = (int) (normalizedStartWave + (end.getWaveAt(i) - normalizedStartWave) * factor);
        }
        return createWaveform(waves);
    }

    /**
     * The normal color of the waves that are not in progress.
     */
    private int mWaveBackgroundColor;

    /**
     * The color of the waves that are in progress.
     */
    private int mWaveProgressColor;

    /**
     * The preferred gap between waves. Is set by the client.
     */
    private float mPreferredWaveGap;

    /**
     * The actual gap between waves.
     */
    private float mWaveGap;

    /**
     * The edge gap between edge waves and lateral content borders.
     */
    private float mEdgeWaveGap;

    /**
     * The max width of waves. Is set by the client.
     */
    private float mWaveMaxWidth = MAX_WIDTH_NO_LIMIT;

    /**
     * The actual width of waves.
     */
    private float mWaveWidth;

    /**
     * Corner type of wave rectangles. Is set by the client.
     */
    private WaveCornerType mWaveCornerType;

    /**
     * The preferred corner radius of wave rectangles. Is set by the client.
     */
    private float mPreferredWaveCornerRadius;

    /**
     * The actual corner radius of wave rectangles.
     */
    private float mWaveCornerRadius;

    /**
     * Paint for drawing waves that are not in progress.
     */
    private final Paint mWaveBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * Paint for drawing waves that are in progress.
     */
    private final Paint mWaveProgressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * Temporary rectangle. Used for drawing.
     */
    private RectF mTempRect;

    /**
     * Current waveform change animation.
     */
    private ValueAnimator mWaveAnim = null;

    /**
     * Waveform change animation duration.
     */
    private final long mWaveAnimDur;

    /**
     * Waveform change animation interpolator.
     */
    private final Interpolator mWaveAnimInterpolator = new AccelerateDecelerateInterpolator();

    /**
     * The current animated factor of waveform change. Possible value in the range 0..1.
     */
    private float mWaveAnimFactor = 1f;

    /**
     * The update listener for waveform change animation.
     */
    private final ValueAnimator.AnimatorUpdateListener mWaveAnimUpdateListener =
            new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    mWaveAnimFactor = (float) animation.getAnimatedValue();
                    invalidate();
                }
            };

    /**
     * Indicates if the user is currently controlling the progress by touching.
     */
    private boolean mIsTracking = false;

    /**
     * The current waveform. Is set by the user.
     */
    private Waveform mWaveform;

    /**
     * The previous waveform. Can be an intermediate waveform if the change animation
     * of waveforms has not been completed earlier.
     */
    private Waveform mPrevWaveform;

    /**
     * Current progress as a percentage. It can be less than 0 if the user was tracking progress outside the left wave,
     * and it can be greater than 1 if the user was tracking progress outside the right wave.
     */
    private float mProgressPercentPosition = 0.0f;

    /**
     * Current progress as a whole value.
     */
    private int mDiscreteProgressPosition = -1;

    /**
     * Callback for this view.
     */
    private Callback mCallback;

    public WaveformSeekBar(Context context) {
        this(context, null);
    }

    public WaveformSeekBar(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.waveformSeekBarStyle);
    }

    public WaveformSeekBar(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, R.style.Base_AppTheme_WaveformSeekBar);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public WaveformSeekBar(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final TypedArray a = context.getTheme()
                .obtainStyledAttributes(attrs, R.styleable.WaveformSeekBar, defStyleAttr, defStyleRes);
        mWaveBackgroundColor = a.getColor(R.styleable.WaveformSeekBar_waveBackgroundColor, Color.LTGRAY);
        mWaveProgressColor = a.getColor(R.styleable.WaveformSeekBar_waveProgressColor, Color.GRAY);
        mPreferredWaveGap = a.getDimension(R.styleable.WaveformSeekBar_waveGap, 0f);
        mWaveMaxWidth = a.getDimension(R.styleable.WaveformSeekBar_waveMaxWidth, MAX_WIDTH_NO_LIMIT);
        mPreferredWaveCornerRadius = a.getDimension(R.styleable.WaveformSeekBar_waveCornerRadius, 0f);
        mWaveCornerType = WaveCornerType.AUTO;
        mWaveAnimDur = a.getInt(R.styleable.WaveformSeekBar_waveAnimDuration, ANIM_DURATION);
        a.recycle();

        mWaveBackgroundPaint.setColor(mWaveBackgroundColor);
        mWaveProgressPaint.setColor(mWaveProgressColor);
    }

    private RectF getTempRect() {
        if (mTempRect == null) {
            mTempRect = new RectF();
        }
        return mTempRect;
    }

    private float dpToPx(float dp){
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return dp * ((float) metrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT);
    }

    private int getWaveCount() {
        return mWaveform != null ? mWaveform.getWaveCount() : 0;
    }

    private float getPercentageForX(float x) {
        float contentWidth = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
        float wavesContentWidth = contentWidth - mEdgeWaveGap * 2f;
        float relativeX = x - getPaddingLeft() - mEdgeWaveGap;
        return relativeX / wavesContentWidth;
    }

    public void setCallback(Callback callback) {
        this.mCallback = callback;
    }

    /**
     * Sets the progress in percentage to {@code percent} value.
     * @param percent new progress value in percentage
     */
    public void setProgressInPercentage(float percent) {
        setProgressInPercentageInternal(percent, false);
    }

    private void setProgressInPercentageInternal(float percent, boolean fromUser) {
        mProgressPercentPosition = percent;
        int discretePosition = calculateDiscretePosition(getWaveCount(), percent);
        if (mDiscreteProgressPosition != discretePosition) {
            mDiscreteProgressPosition = discretePosition;
            invalidate();
        }
        if (mCallback != null) {
            mCallback.onProgressChanged(this, clampPercentage(percent), fromUser);
        }
    }

    /**
     * Sets {@code waves} to display.
     * @param waves new waveform data
     */
    public void setWaveform(int[] waves) {
        setWaveform(createWaveform(waves));
    }

    /**
     * Sets {@code waves} to display and then animates it.
     * @param waves new waveform data
     * @param animate if waveform appearance needs to be animated
     */
    public void setWaveform(int[] waves, boolean animate) {
        setWaveform(createWaveform(waves), animate);
    }

    /**
     * Sets {@code waveform} to display.
     * @param waveform new waveform data
     */
    public void setWaveform(Waveform waveform) {
        setWaveform(waveform, true);
    }

    /**
     * Setups {@code waveform} to display and then animates it.
     * @param waveform new waveform data
     * @param animate if waveform appearance needs to be animated
     */
    public void setWaveform(Waveform waveform, boolean animate) {
        Waveform currWaveform = mWaveform;
        if (currWaveform != null) {
            Waveform prevWaveform = mPrevWaveform;
            // First, checking if there is a running waveform animation
            if (prevWaveform != null
                    && prevWaveform.getWaveCount() == currWaveform.getWaveCount()
                    && mWaveAnim != null && mWaveAnim.isRunning()) {
                // Creating an intermediate waveform according to the current animation factor
                float factor = (float) mWaveAnim.getAnimatedValue();
                mPrevWaveform = blendWaveforms(prevWaveform, currWaveform, factor);
            } else {
                mPrevWaveform = currWaveform;
            }
        } else {
            mPrevWaveform = null;
        }

        this.mWaveform = waveform;

        if (mWaveAnim != null) {
            mWaveAnim.cancel();
            mWaveAnim = null;
        }

        calculateWaveDimensions(getWaveCount());

        // Need to re-calculate the discrete progress position
        mDiscreteProgressPosition = calculateDiscretePosition(getWaveCount(), mProgressPercentPosition);

        invalidate();

        if (animate) {
            // Animating the change of waves
            ValueAnimator newWaveAnim = ValueAnimator.ofFloat(0.0f, 1.0f);
            newWaveAnim.setDuration(mWaveAnimDur);
            newWaveAnim.setInterpolator(mWaveAnimInterpolator);
            newWaveAnim.addUpdateListener(mWaveAnimUpdateListener);
            newWaveAnim.start();

            mWaveAnim = newWaveAnim;
        }
    }

    /**
     * Sets the wave background color.
     * @param color background color
     */
    public void setWaveBackgroundColor(int color) {
        this.mWaveBackgroundColor = color;
        mWaveBackgroundPaint.setColor(mWaveProgressColor);
        invalidate();
    }

    /**
     * Sets the wave progress color.
     * @param color progress color
     */
    public void setWaveProgressColor(int color) {
        this.mWaveProgressColor = color;
        mWaveProgressPaint.setColor(mWaveProgressColor);
        invalidate();
    }

    /**
     * Sets the gap between waves in pixels. NOTE: this is only a preferred value
     * and the final gap may be different, for example, in cases where the wave width is too small,
     * which may cause the wave to be almost invisible.
     * @param gap the gap between waves in pixels
     */
    public void setWaveGap(int gap) {
        this.mPreferredWaveGap = gap;
        calculateWaveDimensions(getWaveCount());
        invalidate();
    }

    /**
     * Sets the wave rectangle corner type.
     * @param type type of wave corners
     */
    public void setWaveCornerType(WaveCornerType type) {
        this.mWaveCornerType = type;
        calculateWaveCornerRadius();
        invalidate();
    }

    /**
     * Sets the wave rectangle corner radius in pixels.
     * NOTE: this takes effect only if the wave corner type is set to {@link WaveCornerType#EXACTLY}.
     * @param radius corner radius in pixels
     */
    public void setWaveCornerRadius(int radius) {
        this.mPreferredWaveCornerRadius = radius;
        calculateWaveCornerRadius();
        invalidate();
    }

    /**
     * Calculates wave dimensions in the context of the current measured width and height and {@code waveCount} value.
     * This includes Wave width, Wave gap and Wave corner radius.
     * @param waveCount wave count for which to calculate dimensions
     */
    private void calculateWaveDimensions(int waveCount) {
        if (waveCount <= 0) {
            mWaveWidth = 0f;
            mWaveGap = 0f;
            return;
        }

        final int contentWidth = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
        if (contentWidth <= 0) {
            // it's probably not measured yet
            mWaveWidth = 0f;
            mWaveGap = 0f;
        }

        float edgeWaveGapCandidate = Math.min(dpToPx(DEFAULT_EDGE_WAVE_GAP_WIDTH_IN_DP), contentWidth / 40f);
        int edgeWaveGapCount = 2;

        int waveGapCount = waveCount - 1;
        float waveGapCandidate = mPreferredWaveGap;
        float totalGap;
        if (waveGapCandidate > 0) {
            totalGap = waveGapCandidate * waveGapCount + edgeWaveGapCandidate * edgeWaveGapCount;
        } else {
            totalGap = 0;
        }

        float remainingSpace = contentWidth - totalGap;
        if (remainingSpace < 0f) {
            remainingSpace = 0f;
        }
        float waveWidthCandidate = remainingSpace / waveCount;

        // Calculating wave width
        if (mWaveMaxWidth > 0 && waveWidthCandidate > mWaveMaxWidth) {
            waveWidthCandidate = mWaveMaxWidth;
        }
        if (waveWidthCandidate <= 0f) {
            // Wave should be at least 1 pixel wide, otherwise it will be invisible
            waveWidthCandidate = Math.min(1f, ((float) contentWidth) / waveCount);
        }

        // Calculating wave gaps
        if (waveGapCount > 0) {
            waveGapCandidate = (contentWidth
                    - (waveWidthCandidate * waveCount)
                    - (edgeWaveGapCandidate * edgeWaveGapCount)) / waveGapCount;
            if (waveGapCandidate < 0f) {
                waveGapCandidate = 0f;
            }
        } else {
            waveGapCandidate = 0f;
            edgeWaveGapCandidate = (contentWidth - (waveWidthCandidate * waveCount)) / 2f;
        }

        mWaveWidth = waveWidthCandidate;
        mWaveGap = waveGapCandidate;
        mEdgeWaveGap = edgeWaveGapCandidate;

        calculateWaveCornerRadius();
    }

    private void calculateWaveCornerRadius() {
        switch (mWaveCornerType) {
            case NONE:
                mWaveCornerRadius = 0f;
                break;
            case AUTO:
                mWaveCornerRadius = mWaveWidth / 2f;
                break;
            case EXACTLY:
                mWaveCornerRadius = Math.min(mPreferredWaveCornerRadius, mWaveWidth / 2f);
                break;
        }
    }

    /**
     * Returns the progress position in percent.
     * @return the progress position in percent
     */
    public float getProgressPercent() {
        return clampPercentage(mProgressPercentPosition);
    }

    @Override
    protected int getSuggestedMinimumWidth() {
        return (int) dpToPx(DEFAULT_VIEW_WIDTH_IN_DP);
    }

    @Override
    protected int getSuggestedMinimumHeight() {
        return (int) dpToPx(DEFAULT_VIEW_HEIGHT_IN_DP);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mWaveAnimFactor = 1f;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mWaveAnim != null) {
            mWaveAnim.cancel();
            mWaveAnim = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            calculateWaveDimensions(getWaveCount());
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final float x = event.getX();
        final float y = event.getY();

        if (!mIsTracking) {
            // Checking if the event occurs over the content area.
            // It is allowed to start tracking only if the initial touch is occurred in the content area.
            if (x < getPaddingLeft() || x > getMeasuredWidth() - getPaddingRight()
                    || y < getPaddingTop() || y > getMeasuredHeight() - getPaddingBottom()) {
                if (DEBUG) Log.d(LOG_TAG, "Motion event is not in the content area");
                return super.onTouchEvent(event);
            }
        }

        if (event.getAction() == MotionEvent.ACTION_UP
                || event.getAction() == MotionEvent.ACTION_CANCEL) {
            mIsTracking = false;

            if (mCallback != null) {
                mCallback.onStopTrackingTouch(this);
            }

            ViewParent parent = getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(false);
            }

            if (DEBUG) Log.d(LOG_TAG, "Stopped tracking");

            return false;
        }

        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            mIsTracking = true;

            if (mCallback != null) {
                mCallback.onStartTrackingTouch(this);
            }

            ViewParent parent = getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(true);
            }

            if (DEBUG) Log.d(LOG_TAG, "Started tracking");
        }

        // Finding the touched wave position
        float percent = getPercentageForX(x);
        setProgressInPercentageInternal(percent, true);

        if (DEBUG) Log.d(LOG_TAG, "Tracked to percent: " + percent);

        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final Waveform waveform = mWaveform;
        if (waveform == null || waveform.getWaveCount() <= 0) {
            return;
        }

        final int maxWave = waveform.getMaxWave();

        final int contentHeight = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();
        final int leftPadding = getPaddingLeft();
        final float waveCenterY = getPaddingTop() + contentHeight / 2f;
        final float waveHalfOfWidth = mWaveWidth / 2f;

        for (int i = 0; i < waveform.getWaveCount(); i++) {
            final float waveHeight;
            if (mPrevWaveform != null && mWaveAnimFactor < 1f) {
                int prevWave = mPrevWaveform.getWaveAt(i);
                int prevMaxWave = mPrevWaveform.getMaxWave();
                float prevWaveHeight = contentHeight * ((float) prevWave / prevMaxWave);

                int targetWave = waveform.getWaveAt(i);
                int targetMaxWave = waveform.getMaxWave();
                float targetWaveHeight = contentHeight * ((float) targetWave / targetMaxWave);

                waveHeight = prevWaveHeight + (targetWaveHeight - prevWaveHeight) * mWaveAnimFactor;
            } else {
                int targetWave = waveform.getWaveAt(i);
                waveHeight = contentHeight * ((float) targetWave / maxWave) * mWaveAnimFactor;
            }
            float waveHalfOfHeight = waveHeight / 2;
            float waveCenterX = i * (mWaveWidth + mWaveGap) + waveHalfOfWidth + leftPadding + mEdgeWaveGap;

            final Paint paint;
            if (i <= mDiscreteProgressPosition) {
                paint = mWaveProgressPaint;
            } else {
                paint = mWaveBackgroundPaint;
            }

            final float left = waveCenterX - waveHalfOfWidth;
            final float top = waveCenterY - waveHalfOfHeight;
            final float right = waveCenterX + waveHalfOfWidth;
            final float bottom = waveCenterY + waveHalfOfHeight;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                canvas.drawRoundRect(left, top, right, bottom, mWaveCornerRadius, mWaveCornerRadius, paint);
            } else {
                final RectF rect = getTempRect();
                rect.set(left, top, right, bottom);
                canvas.drawRoundRect(rect, mWaveCornerRadius, mWaveCornerRadius, paint);
            }
        }
    }

    public interface Waveform {
        int getWaveCount();

        int getWaveAt(int index);

        int getMaxWave();
    }

    /**
     * Default Waveform implementation.
     */
    private static class WaveformImpl implements Waveform {

        final int waveCount;
        final int[] waves;
        // Lazily calculated
        Integer maxWave = null;

        WaveformImpl(int[] waves) {
            this.waves = waves;
            this.waveCount = waves != null ? waves.length : 0;
        }

        private int calculateMaxWave() {
            if (waves == null || waves.length <= 0) {
                return 0;
            }

            int maxWave = waves[0];
            for (int i = 1; i < waves.length; i++) {
                int wave = waves[i];
                if (wave > maxWave) {
                    maxWave = wave;
                }
            }
            return maxWave;
        }

        @Override
        public int getWaveCount() {
            return waveCount;
        }

        @Override
        public int getWaveAt(int index) {
            return waves[index];
        }

        @Override
        public int getMaxWave() {
            if (maxWave == null) {
                maxWave = calculateMaxWave();
            }
            return maxWave;
        }
    }

    public interface Callback {
        void onProgressChanged(WaveformSeekBar seekBar, float percent, boolean fromUser);

        void onStartTrackingTouch(WaveformSeekBar seekBar);

        void onStopTrackingTouch(WaveformSeekBar seekBar);
    }
}
