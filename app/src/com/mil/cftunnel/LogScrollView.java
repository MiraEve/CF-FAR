package com.mil.cftunnel;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ScrollView;

/** A nested log viewport that owns vertical gestures while the finger is inside it. */
public class LogScrollView extends ScrollView {
    public LogScrollView(Context context) { super(context); init(); }
    public LogScrollView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public LogScrollView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        setFillViewport(false);
        setVerticalScrollBarEnabled(true);
        setSmoothScrollingEnabled(true);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            // Must be requested on DOWN: waiting until MOVE is too late because the
            // outer ScrollView gets the first chance to intercept that MOVE.
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        boolean intercepted = super.onInterceptTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return intercepted;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        boolean handled = super.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return handled;
    }
}
