/*
 * Copyright (C) 2014-2017 SlimRoms Project
 * Author: Lars Greiss - email: kufikugel@googlemail.com
 * Copyright (C) 2017 ABC rom
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.systemui.slimrecent;

import android.content.ContentResolver;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.LruCache;

import java.util.ArrayList;

/**
 * This class is our LRU cache controller. It holds
 * the app icons.
 *
 * BroadcastReceiver takes care of the situation if the user updated
 * or removed and installed again the app and the icon may have changed.
 */
public class CacheController {

    private final static String TAG = "RecentCacheController";

    public interface EvictionCallback {
        public void onEntryEvicted(String key);
    }

    /**
     * Singleton.
     */
    private static CacheController sInstance;

    /**
     * Memory Cache.
     */
    protected LruCache<String, Drawable> mMemoryCache;

    private Context mContext;
    private final EvictionCallback mEvictionCallback;
    private int mMaxMemory;

    // Array list of all current keys.
    private final ArrayList<String> mKeys = new ArrayList<String>();

    /**
     * Get the instance.
     */
    public static CacheController getInstance(Context context,
            EvictionCallback evictionCallback) {
        if (sInstance != null) {
            return sInstance;
        } else {
            return sInstance = new CacheController(context, evictionCallback);
        }
    }

    /**
     * Listen for package change or added broadcast.
     */
    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_PACKAGE_CHANGED.equals(action)
                    || Intent.ACTION_PACKAGE_ADDED.equals(action)
                    || Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                // Get the package name from the intent.
                Uri uri = intent.getData();
                final String packageName = uri != null ? uri.getSchemeSpecificPart() : null;
                if (packageName == null) {
                    return;
                }

                // Check if icons from the searched package are present.
                // If yes remove them.
                final ArrayList<String> keysToRemove = new ArrayList<String>();
                for (String key : mKeys) {
                    if (key.toLowerCase().contains(packageName.toLowerCase())) {
                        keysToRemove.add(key);
                    }
                }
                for (String key : keysToRemove) {
                    removeBitmapFromMemCache(key);
                    if (mEvictionCallback != null) {
                        mEvictionCallback.onEntryEvicted(key);
                    }
                }
                if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                    mayBeRemoveFavoriteEntry(packageName);
                }
            }
        }
    };

    /**
     * Remove favorite if current app was uninstalled.
     */
    private void mayBeRemoveFavoriteEntry(String packageName) {
        ContentResolver resolver = mContext.getContentResolver();
        final String favorites = Settings.System.getStringForUser(
                    resolver, Settings.System.RECENT_PANEL_FAVORITES,
                    UserHandle.USER_CURRENT);
        String entryToSave = "";

        if (favorites == null || favorites != null && favorites.isEmpty()) {
            return;
        }
        for (String favorite : favorites.split("\\|")) {
            if (favorite.toLowerCase().contains(packageName.toLowerCase())) {
                continue;
            }
            entryToSave += favorite + "|";
        }
        if (!entryToSave.isEmpty()) {
            entryToSave = entryToSave.substring(0, entryToSave.length() - 1);
        }

        Settings.System.putStringForUser(
                resolver, Settings.System.RECENT_PANEL_FAVORITES,
                entryToSave,
                UserHandle.USER_CURRENT);
    }

    /**
     * Constructor.
     * Defines the LRU cache size and setup the broadcast receiver.
     */
    private CacheController(Context context, EvictionCallback evictionCallback) {
        mContext = context;
        mEvictionCallback = evictionCallback;

        // Get max available VM memory, exceeding this amount will throw an
        // OutOfMemory exception. Stored in kilobytes as LruCache takes an
        // int in its constructor.
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);

        int cacheSize = maxMemory / 4;
        mMaxMemory = maxMemory;

        if (mMemoryCache == null) {
            mMemoryCache = new LruCache<String, Drawable>(cacheSize) {
                @Override
                protected int sizeOf(String key, Drawable bitmap) {
                    if (bitmap instanceof BitmapDrawable){
                        return ((BitmapDrawable)bitmap).getBitmap().getByteCount() / 1024;
                    } else {
                        return 1;
                    }
                }

                @Override
                protected void entryRemoved(boolean evicted, String key,
                        Drawable oldBitmap, Drawable newBitmap) {
                }
            };
        }

        // Receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiver(mBroadcastReceiver, filter);
    }

    /**
     * Add the bitmap to the LRU cache.
     */
    protected void addBitmapToMemoryCache(String key, Drawable bitmap) {
        if (key != null && bitmap != null) {
            if (key.startsWith(RecentPanelView.TASK_PACKAGE_IDENTIFIER)) {
                mKeys.add(key);
            }
            mMemoryCache.put(key, bitmap);
        }
    }

    /**
     * Get the bitmap from the LRU cache.
     */
    protected Drawable getBitmapFromMemCache(String key) {
        if (key == null) {
            return null;
        }
        return mMemoryCache.get(key);
    }

    /**
     * Remove a bitmap from the LRU cache.
     */
    protected Drawable removeBitmapFromMemCache(String key) {
        if (key == null) {
            return null;
        }
        if (key.startsWith(RecentPanelView.TASK_PACKAGE_IDENTIFIER)) {
            mKeys.remove(key);
        }
        return mMemoryCache.remove(key);
    }

    /**
     * Wether to clear the whole cache
     */
    public void clearCache() {
        mMemoryCache.evictAll();
    }

    /** Trims the cache to a specific size */
    final void trimToSize(int cacheSize) {
        mMemoryCache.trimToSize(cacheSize);
    }

    public int getMaxMemory() {
        return mMaxMemory;
    }
}
