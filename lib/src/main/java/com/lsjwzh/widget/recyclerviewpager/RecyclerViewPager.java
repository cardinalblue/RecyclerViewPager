package com.lsjwzh.widget.recyclerviewpager;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.LinearSnapHelper;
import android.support.v7.widget.OrientationHelper;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SnapHelper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewConfiguration;

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

    private int mPageSize = 1000;   //hardcode default and adjust in MyCollagesPreviewActivity.

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

    public int getPageWidth() {
        return mPageSize;
    }

    public void setPageWidth(int pageSize) {
        mPageSize = pageSize;
        requestLayout();
    }

    public RecyclerViewPagerAdapter getWrapperAdapter() {
        return mViewPagerAdapter;
    }

    @Override
    public boolean fling(int velocityX, int velocityY) {
        boolean flinging = super.fling((int) (velocityX * mFlingFactor), (int) (velocityY * mFlingFactor));

        LinearLayoutManager linearLayoutManager = (LinearLayoutManager) getLayoutManager();

        //these four variables identify the views you see on screen.
        int lastVisibleView = linearLayoutManager.findLastVisibleItemPosition();
        int firstVisibleView = linearLayoutManager.findFirstVisibleItemPosition();
        View firstView = linearLayoutManager.findViewByPosition(firstVisibleView);
        View lastView = linearLayoutManager.findViewByPosition(lastVisibleView);

        //these variables get the distance you need to scroll in order to center your views.
        //note the subtle difference in how right and left margins are calculated, as well as
        //the resulting scroll distances.
        int leftMargin = (this.getWidth() - lastView.getWidth()) / 2;
        int rightMargin = (this.getWidth() - firstView.getWidth()) / 2 + firstView.getWidth();
        int leftEdge = lastView.getLeft();
        int rightEdge = firstView.getRight();
        int scrollDistanceLeft = leftEdge - leftMargin;
        int scrollDistanceRight = rightMargin - rightEdge;

        //if(user swipes from right to the left).
        if(velocityX > 0){
            smoothScrollBy(scrollDistanceLeft, 0);  //offset to center
        } else {
            smoothScrollBy(-scrollDistanceRight, 0);//offset to center
        }

        //notify listener to update position
        if (mOnPageChangedListeners != null) {
            for (OnPageChangedListener onPageChangedListener : mOnPageChangedListeners) {
                if (onPageChangedListener != null && velocityX > 0) {       //swipes from right to the left
                    onPageChangedListener.OnPageChanged(firstVisibleView, lastVisibleView);
                }else if(onPageChangedListener != null && velocityX < 0){   //swipes from left to the right
                    onPageChangedListener.OnPageChanged(lastVisibleView, firstVisibleView);
                }
            }
        }
        if (DEBUG) {
            Log.d("@", "velocityX:" + velocityX);
            Log.d("@", "velocityY:" + velocityY);
        }
        return flinging;
    }

    @Override
    public void scrollToPosition(int position) {
        if (DEBUG) {
            Log.d("@", "scrollToPosition:" + position);
        }
        mPositionBeforeScroll = getCurrentPosition();
        mSmoothScrollTargetPosition = position;
        super.scrollToPosition(position);

        //Offset the childItem to RecyclerView's center
        //cause scrollToPosition(...) won't trigger fling(...) to allocate to center.
        if(position != 0 || position != mViewPagerAdapter.getItemCount() - 1){
            int padding = (getWidth() - mPageSize)/2;
            scrollBy(-padding, 0);
        }
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

    public interface OnPageChangedListener {
        void OnPageChanged(int oldPosition, int newPosition);
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
