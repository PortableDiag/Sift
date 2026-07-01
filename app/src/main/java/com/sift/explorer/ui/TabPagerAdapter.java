package com.sift.explorer.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/** Pages = real tabs, plus a trailing phantom "new tab" home page. */
public class TabPagerAdapter extends FragmentStateAdapter {

    public static final long HOME_ID = -99L;

    private final TabManager tabs = TabManager.get();

    public TabPagerAdapter(@NonNull FragmentActivity activity) {
        super(activity);
    }

    public boolean isHomePosition(int position) {
        return position >= tabs.count();
    }

    @Override public int getItemCount() {
        return tabs.count() + 1; // + phantom home
    }

    @Override public long getItemId(int position) {
        return isHomePosition(position) ? HOME_ID : tabs.at(position).id;
    }

    @Override public boolean containsItem(long itemId) {
        if (itemId == HOME_ID) return true;
        return tabs.byId((int) itemId) != null;
    }

    @NonNull @Override public Fragment createFragment(int position) {
        if (isHomePosition(position)) return new HomeFragment();
        return BrowserFragment.newInstance(tabs.at(position).id);
    }
}
