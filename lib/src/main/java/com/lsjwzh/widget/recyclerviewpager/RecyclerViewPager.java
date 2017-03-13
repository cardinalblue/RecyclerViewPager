package com.lsjwzh.widget.recyclerviewpager;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.PointF;
import android.os.Build;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.LinearSmoothScroller;
import android.support.v7.widget.LinearSnapHelper;
import android.support.v7.widget.OrientationHelper;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SnapHelper;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerViewPager
 *
 * @author Green
 */
public class RecyclerViewPager extends RecyclerView {
    public static final boolean DEBUG = BuildConfig.DEBUG;

    private RecyclerViewPagerAdapter<?> mViewPagerAdapter;
    private float mTriggerOffset = 0.25f;
    private float mFlingFactor = 0.15f;
    private float mMillisecondsPerInch = 25f;
    private float mTouchSpan;
    private List<OnPageChangedListener> mOnPageChangedListeners;
    private int mSmoothScrollTargetPosition = -1;
    private int mPositionBeforeScroll = -1;

    private boolean mSinglePageFling;
    boolean isInertia; // inertia slide state
    float minSlideDistance;
    PointF touchStartPoint;

    boolean mNeedAdjust;
    int mFisrtLeftWhenDragging;
    int mFirstTopWhenDragging;
    View mCurView;
    int mMaxLeftWhenDragging = Integer.MIN_VALUE;
    int mMinLeftWhenDragging = Integer.MAX_VALUE;
    int mMaxTopWhenDragging = Integer.MIN_VALUE;
    int mMinTopWhenDragging = Integer.MAX_VALUE;
    private int mPositionOnTouchDown = -1;
    private boolean mHasCalledOnPageChanged = true;
    private boolean reverseLayout = false;
    private float mLastY;

    private int mChildWidth = 1050;

    public RecyclerViewPager(Context context) {
        this(context, null);
    }

    public RecyclerViewPager(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecyclerViewPager(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initAttrs(context, attrs, defStyle);
        setNestedScrollingEnabled(false);
        ViewConfiguration viewConfiguration = ViewConfiguration.get(context);
        minSlideDistance = viewConfiguration.getScaledTouchSlop();

        SnapHelper snapHelper = new FixLinearSnapHelper();
        snapHelper.attachToRecyclerView(this);
    }

    private void initAttrs(Context context, AttributeSet attrs, int defStyle) {
        final TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.RecyclerViewPager, defStyle,
                0);
        mFlingFactor = a.getFloat(R.styleable.RecyclerViewPager_rvp_flingFactor, 0.15f);
        mTriggerOffset = a.getFloat(R.styleable.RecyclerViewPager_rvp_triggerOffset, 0.25f);
        mSinglePageFling = a.getBoolean(R.styleable.RecyclerViewPager_rvp_singlePageFling, mSinglePageFling);
        isInertia = a.getBoolean(R.styleable.RecyclerViewPager_rvp_inertia, false);
        mMillisecondsPerInch = a.getFloat(R.styleable.RecyclerViewPager_rvp_millisecondsPerInch, 25f);
        a.recycle();
    }

    public void setFlingFactor(float flingFactor) {
        mFlingFactor = flingFactor;
    }

    public float getFlingFactor() {
        return mFlingFactor;
    }

    public void setTriggerOffset(float triggerOffset) {
        mTriggerOffset = triggerOffset;
    }

    public float getTriggerOffset() {
        return mTriggerOffset;
    }

    public void setSinglePageFling(boolean singlePageFling) {
        mSinglePageFling = singlePageFling;
    }

    public boolean isSinglePageFling() {
        return mSinglePageFling;
    }

    public boolean isInertia() {
        return isInertia;
    }

    public void setInertia(boolean inertia) {
        isInertia = inertia;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        try {
            Field fLayoutState = state.getClass().getDeclaredField("mLayoutState");
            fLayoutState.setAccessible(true);
            Object layoutState = fLayoutState.get(state);
            Field fAnchorOffset = layoutState.getClass().getDeclaredField("mAnchorOffset");
            Field fAnchorPosition = layoutState.getClass().getDeclaredField("mAnchorPosition");
            fAnchorPosition.setAccessible(true);
            fAnchorOffset.setAccessible(true);
            if (fAnchorOffset.getInt(layoutState) > 0) {
                fAnchorPosition.set(layoutState, fAnchorPosition.getInt(layoutState) - 1);
            } else if (fAnchorOffset.getInt(layoutState) < 0) {
                fAnchorPosition.set(layoutState, fAnchorPosition.getInt(layoutState) + 1);
            }
            fAnchorOffset.setInt(layoutState, 0);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        super.onRestoreInstanceState(state);
    }

    @Override
    public void setAdapter(Adapter adapter) {
        mViewPagerAdapter = ensureRecyclerViewPagerAdapter(adapter);
        super.setAdapter(mViewPagerAdapter);
    }

    @Override
    public void swapAdapter(Adapter adapter, boolean removeAndRecycleExistingViews) {
        mViewPagerAdapter = ensureRecyclerViewPagerAdapter(adapter);
        super.swapAdapter(mViewPagerAdapter, removeAndRecycleExistingViews);
    }

    @Override
    public Adapter getAdapter() {
        if (mViewPagerAdapter != null) {
            return mViewPagerAdapter.mAdapter;
        }
        return null;
    }

    public int getPageSize() {
        return mChildWidth;
    }

    public RecyclerViewPagerAdapter getWrapperAdapter() {
        return mViewPagerAdapter;
    }

    @Override
    public void setLayoutManager(LayoutManager layout) {
        super.setLayoutManager(layout);

        if (layout instanceof LinearLayoutManager) {
            reverseLayout = ((LinearLayoutManager) layout).getReverseLayout();
        }
    }

    @Override
    public boolean fling(int velocityX, int velocityY) {
        boolean flinging = super.fling((int) (velocityX * mFlingFactor), (int) (velocityY * mFlingFactor));
        if (flinging) {
            LinearLayoutManager linearLayoutManager = (LinearLayoutManager) getLayoutManager();

            //these four variables identify the views you see on screen.
            int lastVisibleView = linearLayoutManager.findLastVisibleItemPosition();
            int firstVisibleView = linearLayoutManager.findFirstVisibleItemPosition();
            View firstView = linearLayoutManager.findViewByPosition(firstVisibleView);
            View lastView = linearLayoutManager.findViewByPosition(lastVisibleView);

//these variables get the distance you need to scroll in order to center your views.
//my views have variable sizes, so I need to calculate side margins separately.
//note the subtle difference in how right and left margins are calculated, as well as
//the resulting scroll distances.
            int leftMargin = (this.getWidth() - lastView.getWidth()) / 2;
            int rightMargin = (this.getWidth() - firstView.getWidth()) / 2 + firstView.getWidth();
            int leftEdge = lastView.getLeft();
            int rightEdge = firstView.getRight();
            int scrollDistanceLeft = leftEdge - leftMargin;
            int scrollDistanceRight = rightMargin - rightEdge;

//if(user swipes to the left)
            if(velocityX > 0) smoothScrollBy(scrollDistanceLeft, 0);
            else smoothScrollBy(-scrollDistanceRight, 0);

            if (mOnPageChangedListeners != null) {
                for (OnPageChangedListener onPageChangedListener : mOnPageChangedListeners) {
                    if (onPageChangedListener != null && velocityX > 0) {
                        onPageChangedListener.OnPageChanged(firstVisibleView, lastVisibleView);
                    }else if(onPageChangedListener != null && velocityX < 0){
                        onPageChangedListener.OnPageChanged(lastVisibleView, firstVisibleView);
                    }
                }
            }
            return true;


/*
            if (getLayoutManager().canScrollHorizontally()) {
                adjustPositionX(velocityX);
            } else {
                adjustPositionY(velocityY);
            }
*/

        }

        if (DEBUG) {
            Log.d("@", "velocityX:" + velocityX);
            Log.d("@", "velocityY:" + velocityY);
        }
        return flinging;
    }

    @Override
    public void smoothScrollToPosition(int position) {
        if (DEBUG) {
            Log.d("@", "smoothScrollToPosition:" + position);
        }

        if (mPositionBeforeScroll < 0) {
            mPositionBeforeScroll = getCurrentPosition();
        }
        mSmoothScrollTargetPosition = position;
        if (getLayoutManager() != null && getLayoutManager() instanceof LinearLayoutManager) {
            // exclude item decoration
            LinearSmoothScroller linearSmoothScroller =
                    new LinearSmoothScroller(getContext()) {
                        @Override
                        public PointF computeScrollVectorForPosition(int targetPosition) {
                            if (getLayoutManager() == null) {
                                return null;
                            }
                            return ((LinearLayoutManager) getLayoutManager())
                                    .computeScrollVectorForPosition(targetPosition);
                        }

                        @Override
                        protected void onTargetFound(View targetView, RecyclerView.State state, Action action) {
                            if (getLayoutManager() == null) {
                                return;
                            }
                            int dx = calculateDxToMakeVisible(targetView,
                                    getHorizontalSnapPreference());
                            int dy = calculateDyToMakeVisible(targetView,
                                    getVerticalSnapPreference());
                            if (dx > 0) {
                                dx = dx - getLayoutManager()
                                        .getLeftDecorationWidth(targetView);
                            } else {
                                dx = dx + getLayoutManager()
                                        .getRightDecorationWidth(targetView);
                            }
                            if (dy > 0) {
                                dy = dy - getLayoutManager()
                                        .getTopDecorationHeight(targetView);
                            } else {
                                dy = dy + getLayoutManager()
                                        .getBottomDecorationHeight(targetView);
                            }
                            final int distance = (int) Math.sqrt(dx * dx + dy * dy);
                            final int time = calculateTimeForDeceleration(distance);
                            if (time > 0) {
                                action.update(-dx, -dy, time, mDecelerateInterpolator);
                            }
                        }

                        @Override
                        protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
                            return mMillisecondsPerInch / displayMetrics.densityDpi;
                        }
                    };
            linearSmoothScroller.setTargetPosition(position);
            if (position == RecyclerView.NO_POSITION) {
                return;
            }
            getLayoutManager().startSmoothScroll(linearSmoothScroller);
        } else {
            super.smoothScrollToPosition(position);
        }
    }

    @Override
    public void scrollToPosition(int position) {
        if (DEBUG) {
            Log.d("@", "scrollToPosition:" + position);
        }
        mPositionBeforeScroll = getCurrentPosition();
        mSmoothScrollTargetPosition = position;
        super.scrollToPosition(position);
        if(position != 0 || position != mViewPagerAdapter.getItemCount() - 1){
            int padding = (getWidth() - mChildWidth)/2;
            scrollBy(-padding, 0);
        }
        getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @SuppressWarnings("deprecation")
            @Override
            public void onGlobalLayout() {
                if (Build.VERSION.SDK_INT < 16) {
                    getViewTreeObserver().removeGlobalOnLayoutListener(this);
                } else {
                    getViewTreeObserver().removeOnGlobalLayoutListener(this);
                }

                if (mSmoothScrollTargetPosition >= 0 && mSmoothScrollTargetPosition < getItemCount()) {
                    if (mOnPageChangedListeners != null) {
                        for (OnPageChangedListener onPageChangedListener : mOnPageChangedListeners) {
                            if (onPageChangedListener != null) {
                                onPageChangedListener.OnPageChanged(mPositionBeforeScroll, getCurrentPosition());
                            }
                        }
                    }
                }
            }
        });
    }

    private int getItemCount() {
        return mViewPagerAdapter == null ? 0 : mViewPagerAdapter.getItemCount();
    }

    /**
     * get item position in center of viewpager
     */
    public int getCurrentPosition() {
        int curPosition;
        if (getLayoutManager().canScrollHorizontally()) {
            curPosition = ViewUtils.getCenterXChildPosition(this);
        } else {
            curPosition = ViewUtils.getCenterYChildPosition(this);
        }
        if (curPosition < 0) {
            curPosition = mSmoothScrollTargetPosition;
        }
        return curPosition;
    }

    /***
     * adjust position before Touch event complete and fling action start.
     */
    protected void adjustPositionX(int velocityX) {
        if (reverseLayout) velocityX *= -1;

        int childCount = getChildCount();
        if (childCount > 0) {
            int curPosition = ViewUtils.getCenterXChildPosition(this);
            int childWidth = getPageSize();
            int flingCount = getFlingCount(velocityX, childWidth);
            int targetPosition = curPosition + flingCount;
            if (mSinglePageFling) {
                flingCount = Math.max(-1, Math.min(1, flingCount));
                targetPosition = flingCount == 0 ? curPosition : mPositionOnTouchDown + flingCount;
                if (DEBUG) {
                    Log.d("@", "flingCount:" + flingCount);
                    Log.d("@", "original targetPosition:" + targetPosition);
                }
            }
            targetPosition = Math.max(targetPosition, 0);
            targetPosition = Math.min(targetPosition, getItemCount() - 1);
            if (targetPosition == curPosition
                    && (!mSinglePageFling || mPositionOnTouchDown == curPosition)) {
                View centerXChild = ViewUtils.getCenterXChild(this);
                if (centerXChild != null) {
                    if (mTouchSpan > centerXChild.getWidth() * mTriggerOffset * mTriggerOffset && targetPosition != 0) {
                        if (!reverseLayout) targetPosition--;
                        else targetPosition++;
                    } else if (mTouchSpan < centerXChild.getWidth() * -mTriggerOffset && targetPosition != getItemCount() - 1) {
                        if (!reverseLayout) targetPosition++;
                        else targetPosition--;
                    }
                }
            }
            if (DEBUG) {
                Log.d("@", "mTouchSpan:" + mTouchSpan);
                Log.d("@", "adjustPositionX:" + targetPosition);
            }
//            smoothScrollToPosition(safeTargetPosition(targetPosition, getItemCount()));
            //padding
            int totalWidth = this.getWidth();
            int viewWidth = getPageSize();
            int padding = (totalWidth - viewWidth) / 2;

            if(mTouchSpan < 0) {    //from left to right
                smoothScrollBy(childWidth + (int)mTouchSpan, 0);
            }else{  //from right to left
                smoothScrollBy( (int)mTouchSpan - childWidth, 0);
            }
        }
    }

    public void addOnPageChangedListener(OnPageChangedListener listener) {
        if (mOnPageChangedListeners == null) {
            mOnPageChangedListeners = new ArrayList<>();
        }
        mOnPageChangedListeners.add(listener);
    }


    @SuppressWarnings("unchecked")
    @NonNull
    protected RecyclerViewPagerAdapter ensureRecyclerViewPagerAdapter(Adapter adapter) {
        return (adapter instanceof RecyclerViewPagerAdapter)
                ? (RecyclerViewPagerAdapter) adapter
                : new RecyclerViewPagerAdapter(this, adapter);

    }

    private int getFlingCount(int velocity, int cellSize) {
        if (velocity == 0) {
            return 0;
        }
        int sign = velocity > 0 ? 1 : -1;
        return (int) (sign * Math.ceil((velocity * sign * mFlingFactor / cellSize)
                - mTriggerOffset));
    }

    private int safeTargetPosition(int position, int count) {
        if (position < 0) {
            return 0;
        }
        if (position >= count) {
            return count - 1;
        }
        return position;
    }

    public interface OnPageChangedListener {
        void OnPageChanged(int oldPosition, int newPosition);
    }


    public float getlLastY() {
        return mLastY;
    }

    class FixLinearSnapHelper extends LinearSnapHelper {

        private OrientationHelper mVerticalHelper;

        private OrientationHelper mHorizontalHelper;

        private RecyclerView mRecyclerView;

        @Override
        public int[] calculateDistanceToFinalSnap(@NonNull RecyclerView.LayoutManager layoutManager,
                                                  @NonNull View targetView) {
            int[] out = new int[2];

            if (layoutManager.canScrollHorizontally()) {
                out[0] = distanceToCenter(targetView, getHorizontalHelper(layoutManager));
            } else {
                out[0] = 0;
            }

            if (layoutManager.canScrollVertically()) {
                out[1] = distanceToCenter(targetView, getVerticalHelper(layoutManager));
            } else {
                out[1] = 0;
            }
            return out;
        }

        @Override
        public void attachToRecyclerView(@Nullable RecyclerView recyclerView) throws IllegalStateException {
            this.mRecyclerView = recyclerView;
            super.attachToRecyclerView(recyclerView);
        }

        private int distanceToCenter(View targetView, OrientationHelper helper) {
            //if is scroll to the edge (first item or last item) then return 0 to avoid scroll.
            if ((helper.getDecoratedStart(targetView) == 0 && mRecyclerView.getChildAdapterPosition(targetView) == 0)
                    || (helper.getDecoratedEnd(targetView) == helper.getEndAfterPadding()
                    && mRecyclerView.getChildAdapterPosition(targetView) == mRecyclerView.getAdapter().getItemCount() - 1) )
                return 0;

            int viewCenter = helper.getDecoratedStart(targetView) + (helper.getDecoratedEnd(targetView) - helper.getDecoratedStart(targetView))/2;
            int correctCenter = (helper.getEndAfterPadding() - helper.getStartAfterPadding())/2;
            return viewCenter - correctCenter;
        }

        private OrientationHelper getVerticalHelper(RecyclerView.LayoutManager layoutManager) {
            if (mVerticalHelper == null) {
                mVerticalHelper = OrientationHelper.createVerticalHelper(layoutManager);
            }
            return mVerticalHelper;
        }

        private OrientationHelper getHorizontalHelper(RecyclerView.LayoutManager layoutManager) {
            if (mHorizontalHelper == null) {
                mHorizontalHelper = OrientationHelper.createHorizontalHelper(layoutManager);
            }
            return mHorizontalHelper;
        }

    }
}
