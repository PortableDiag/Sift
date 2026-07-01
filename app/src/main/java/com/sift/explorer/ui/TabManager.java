package com.sift.explorer.ui;

import java.util.ArrayList;
import java.util.List;

/** Process-wide registry of open tabs, survives configuration changes. */
public class TabManager {

    private static final TabManager INSTANCE = new TabManager();
    public static TabManager get() { return INSTANCE; }

    private final List<Tab> tabs = new ArrayList<>();

    public List<Tab> tabs() { return tabs; }
    public int count() { return tabs.size(); }
    public Tab at(int i) { return tabs.get(i); }

    public Tab byId(int id) {
        for (Tab t : tabs) if (t.id == id) return t;
        return null;
    }

    public int indexOf(int id) {
        for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).id == id) return i;
        return -1;
    }

    public Tab add(Tab t) { tabs.add(t); return t; }

    public void add(int index, Tab t) { tabs.add(index, t); }

    public void remove(int index) { if (index >= 0 && index < tabs.size()) tabs.remove(index); }

    public boolean isEmpty() { return tabs.isEmpty(); }
}
