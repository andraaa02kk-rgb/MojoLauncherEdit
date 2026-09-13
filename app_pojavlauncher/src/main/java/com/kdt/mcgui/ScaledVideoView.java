package com.kdt.mcgui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.VideoView;

/**
 * A VideoView that scales its content to always fill its bounds, similarly to
 * ImageView's "centerCrop" scale type, instead of the default VideoView behavior
 * which letterboxes the video to fit inside its bounds.
 *
 * Must be placed inside a parent that clips its children (the default for most
 * ViewGroups, including FrameLayout) so the overflowing part of the video gets cropped.
 *
 * Usage: call setVideoSize(width, height) once the video's real dimensions are known
 * (typically from MediaPlayer.OnPreparedListener), then this view will grow past its
 * layout bounds on one axis to cover the whole area without distorting the aspect ratio.
 */
public class ScaledVideoView extends VideoView {
    private int mVideoWidth = 0;
    private int mVideoHeight = 0;

    public ScaledVideoView(Context context) {
        super(context);
    }

    public ScaledVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ScaledVideoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** Provide the real pixel size of the video, usually from MediaPlayer.getVideoWidth/Height() */
    public void setVideoSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        mVideoWidth = width;
        mVideoHeight = height;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int parentWidth = MeasureSpec.getSize(widthMeasureSpec);
        int parentHeight = MeasureSpec.getSize(heightMeasureSpec);

        if (mVideoWidth <= 0 || mVideoHeight <= 0 || parentWidth <= 0 || parentHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        float viewRatio = (float) parentWidth / parentHeight;
        float videoRatio = (float) mVideoWidth / mVideoHeight;

        int finalWidth;
        int finalHeight;
        if (videoRatio > viewRatio) {
            // Video is proportionally wider than the container: match height, crop the sides
            finalHeight = parentHeight;
            finalWidth = Math.round(parentHeight * videoRatio);
        } else {
            // Video is proportionally taller than the container: match width, crop top/bottom
            finalWidth = parentWidth;
            finalHeight = Math.round(parentWidth / videoRatio);
        }

        setMeasuredDimension(finalWidth, finalHeight);
    }
}
