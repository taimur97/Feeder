package com.nononsenseapps.feeder.model;

import android.app.IntentService;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.util.Log;

import com.nononsenseapps.feeder.db.FeedItemSQL;
import com.nononsenseapps.feeder.db.FeedSQL;
import com.nononsenseapps.feeder.db.RssContentProvider;
import com.nononsenseapps.feeder.db.Util;
import com.nononsenseapps.feeder.model.apis.BackendAPIClient;
import com.shirwa.simplistic_rss.RssFeed;
import com.shirwa.simplistic_rss.RssItem;
import com.shirwa.simplistic_rss.RssReader;
import com.squareup.picasso.Picasso;

import org.joda.time.DateTime;

import java.util.List;

import retrofit.RetrofitError;

/**
 * Synchronizes RSS feeds.
 */
public class RssSyncService extends IntentService {

    private static final String TAG = "RssSyncService";
    private static final String ACTION_PUT_FEED = "PUTFEED";
    private static final String ACTION_DELETE_FEED = "DELETEFEED";

    public RssSyncService() {
        super("RssSyncService");
    }

    /**
     * Starts this service to perform action Foo with the given parameters. If
     * the service is already performing a task this action will be queued.
     *
     * @see IntentService
     */
    public static void syncFeeds(Context context) {
        Intent intent = new Intent(context, RssSyncService.class);
        context.startService(intent);
    }

    public static void uploadFeed(Context context, long id, String title,
            String link, String tag) {
        Intent intent = new Intent(context, RssSyncService.class);
        intent.setAction(ACTION_PUT_FEED);
        intent.putExtra("id", id);
        intent.putExtra("title", title);
        intent.putExtra("link", link);
        intent.putExtra("tag", tag);
        context.startService(intent);
    }

    public static void deleteFeed(Context context, String link) {
        Intent intent = new Intent(context, RssSyncService.class);
        intent.setAction(ACTION_DELETE_FEED);
        intent.putExtra("link", link);
        context.startService(intent);
    }


    @Override
    protected void onHandleIntent(Intent intent) {
        if (ACTION_PUT_FEED.equals(intent.getAction())) {
            putFeed(intent.getLongExtra("id", -1),
                    intent.getStringExtra("title"),
                    intent.getStringExtra("link"),
                    intent.getStringExtra("tag"));
        } else if (ACTION_DELETE_FEED.equals(intent.getAction())) {
            deleteFeed(intent.getStringExtra("link"));
        } else {
            //syncAll();
            syncAllRetro();
        }
    }

    protected void putFeed(final long id, final String title, final String link,
            final String tag) {
        final String token = SyncHelper.getAuthToken(this);
        if (token != null) {
            BackendAPIClient.BackendAPI api =
                    BackendAPIClient.GetBackendAPI(token);
            BackendAPIClient.FeedMessage f = new BackendAPIClient.FeedMessage();
            f.title = title;
            f.link = link;
            if (tag != null && !tag.isEmpty()) {
                f.tag = tag;
            }

            try {
                api.putFeed(f);
            } catch (RetrofitError e) {
                Log.e(TAG, "put error: " + e.getMessage());
//                Toast.makeText(getApplicationContext(),
//                        "Put failed: " + e.getMessage(), Toast.LENGTH_SHORT)
//                        .show();
                // Store for later
                // PendingNetworkSQL.storePut(this, id, link);
            }
        }
    }

    protected void deleteFeed(final String link) {
        final String token = SyncHelper.getAuthToken(this);
        if (token != null) {
            BackendAPIClient.BackendAPI api =
                    BackendAPIClient.GetBackendAPI(token);
//            BackendAPIClient.FeedsRequest f =
//                    new BackendAPIClient.FeedsRequest(link);

            try {
                api.deleteFeed(link);
            } catch (RetrofitError e) {
                Log.e(TAG, "put error: " + e.getMessage());
//                Toast.makeText(getApplicationContext(),
//                        "Put failed: " + e.getMessage(), Toast.LENGTH_SHORT)
//                        .show();
                // Store for later unless 404
                // TODO
                // PendingNetworkSQL.storeDelete(this, -1, link);
            }
        }
    }

    protected void syncAll() {
        // Iterate over all feeds
        Cursor cursor = getContentResolver()
                .query(FeedSQL.URI_FEEDS, FeedSQL.FIELDS, null, null, null);

        try {
            while (cursor.moveToNext()) {
                syncFeed(new FeedSQL(cursor));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    protected void syncFeed(final FeedSQL feedSQL) {
        final DateTime latestPubDate = getLatestPubDate(feedSQL);

        Log.d("JONAS", "Syncing feed " + feedSQL.title + " with " +
                       latestPubDate);

        ContentResolver resolver = getContentResolver();
        // Prevent notifications to be called until we're done
        RssContentProvider.setShouldNotify(false);
        try {
            RssFeed feed = new RssReader(feedSQL.url).getFeed();
            // Process each feed item
            for (RssItem item : feed.getRssItems()) {
                Log.d("JONAS", "Looping item " + item.getTitle());
                // Only care about new items
                if (latestPubDate != null && item.getPubDate() != null &&
                    item.getPubDate().isBefore(latestPubDate)) {
                    Log.d("JONAS", "Was too old, " +
                                   "moving on: " +
                                   item.getPubDate().toString());
                    continue;
                }
                // if no PubDate, fill in current time
                if (item.getPubDate() == null) {
                    item.setPubDate(DateTime.now());
                }
                // Save to database
                //FeedItemSQL itemSQL = getUniqueSQLItem(item, feedSQL);
                FeedItemSQL itemSQL = null;
                // Set new values. Make sure some are not null
                itemSQL.title = item.getTitle();
                if (itemSQL.title == null) {
                    itemSQL.title = "";
                }
                itemSQL.description = item.getCleanDescription();
                if (itemSQL.description == null) {
                    itemSQL.description = "";
                }
                itemSQL.plaintitle = item.getPlainTitle();
                if (itemSQL.plaintitle == null) {
                    itemSQL.plaintitle = "";
                }
                itemSQL.plainsnippet = item.getSnippet();
                if (itemSQL.plainsnippet == null) {
                    itemSQL.plainsnippet = "";
                }
                itemSQL.imageurl = item.getImageUrl();
                // TODO pre-cache ALL images
                if (itemSQL.imageurl != null && !itemSQL.imageurl.isEmpty()) {
                    Log.d("JONAS", "Pre-fetching " + itemSQL.imageurl);
                    Picasso.with(this).load(itemSQL.imageurl).fetch();
                }
                itemSQL.link = item.getLink();
                //itemSQL.author = item.getAuthor();
                itemSQL.setPubDate(item.getPubDate());
                // Always need these
                itemSQL.feed_id = feedSQL.id;
                itemSQL.tag = feedSQL.tag;
                // Save it
                Log.d("JONAS", "Saving the item: " + itemSQL.title);
                Util.SaveOrUpdate(resolver, itemSQL);
            }
            // Mark as success
            //db.setTransactionSuccessful();
        } catch (Exception e) {
            Log.e(TAG, "" + e.getMessage());
        } finally {
            // Enable notifications again
            RssContentProvider.setShouldNotify(true);
            // And notify what we changed
            RssContentProvider.notifyAllUris(this);
        }
    }

    /**
     * Return the latest pubdate of the selected feed
     *
     * @param feedSQL
     * @return latest DateTime or Null if no dates present
     */
    protected DateTime getLatestPubDate(final FeedSQL feedSQL) {
        DateTime latest = null;

        Cursor c = getContentResolver().query(FeedItemSQL.URI_FEED_ITEMS,
                Util.ToStringArray(FeedItemSQL.COL_PUBDATE),
                FeedItemSQL.COL_FEED + " " +
                "IS ?", Util.LongsToStringArray(feedSQL.id), null);

        try {
            while (c.moveToNext()) {
                if (!c.isNull(0)) {
                    DateTime date = DateTime.parse(c.getString(0));
                    if (latest == null) {
                        latest = date;
                    } else if (date.isAfter(latest)) {
                        latest = date;
                    }
                }
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }

        return latest;
    }

    /**
     * Looks and sees if an existing item exists, otherwise,
     * just creates a new item.
     *
     * @param item
     * @return a FeedItemSQL
     */
    private FeedItemSQL getUniqueSQLItem(final BackendAPIClient.FeedItem item,
            final FeedSQL feedSQL) {
        FeedItemSQL result = null;
        Cursor c = getContentResolver()
                .query(FeedItemSQL.URI_FEED_ITEMS, FeedItemSQL.FIELDS,
                        FeedItemSQL.COL_LINK + " IS ? AND " +
                        FeedItemSQL.COL_FEED + " IS ?",
                        Util.ToStringArray(item.link,
                                Long.toString(feedSQL.id)), null);

        try {
            if (c.moveToNext()) {
                Log.d("JONAS", "Found existing feeditem");
                result = new FeedItemSQL(c);
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }

        if (result == null) {
            Log.d("JONAS", "Creating new feeditem");
            result = new FeedItemSQL();
        }

        return result;
    }

    /**
     * Looks and sees if an existing item exists, otherwise,
     * just creates a new item.
     * It then fills in the values from feed and saves it.
     *
     * @param feed
     * @return a FeedSQL with a valid id
     */
    private FeedSQL getUniqueSQLFeed(final BackendAPIClient.Feed feed) {
        FeedSQL result = null;
        Cursor c = getContentResolver()
                .query(FeedSQL.URI_FEEDS, FeedSQL.FIELDS,
                        FeedSQL.COL_URL + " IS ?",
                        Util.ToStringArray(feed.link), null);

        try {
            if (c.moveToNext()) {
                result = new FeedSQL(c);
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }

        if (result == null) {
            result = new FeedSQL();
        }

        // Fill in values
        result.title = feed.title;
        result.tag = feed.tag;
        result.timestamp = feed.timestamp;
        result.url = feed.link;
        // Save
        result.id = Util.SaveOrUpdate(getContentResolver(), result);

        return result;
    }

    protected void syncAllRetro() {
        final String token = SyncHelper.getAuthToken(this);
        if (token == null) {
            Log.e(TAG, "No token exists! Aborting sync...");
            return;
        }

        BackendAPIClient.BackendAPI api = BackendAPIClient.GetBackendAPI(token);
        try {
            //BackendAPIClient.FeedsRequest request =
            //        new BackendAPIClient.FeedsRequest();
            // fetch timestamp from database, set on request so we only
            // download items we haven't seen
            //request.min_timestamp = RssContentProvider.GetLatestTimestamp
           //        (this);
            Log.d(TAG, "Using min_timestamp: " + RssContentProvider.GetLatestTimestamp(this));

            List<BackendAPIClient.Feed> feeds = api.getFeeds(RssContentProvider.GetLatestTimestamp(this));

            if (feeds == null) {
                Log.d(TAG, "Feeds was null");
            } else {
                Log.d(TAG, "Number of feeds to sync: " + feeds.size());
                for (BackendAPIClient.Feed feed : feeds) {
                    Log.d(TAG, "ftitle " + feed.title);
                    Log.d(TAG, "fdesc " + feed.description);
                    Log.d(TAG, "ftimestamp " + feed.timestamp);
                    // Sync feed
                    syncFeedRetro(feed);
                }
            }
        } catch (RetrofitError e) {
            Log.e(TAG, "" + e.getMessage());
        }
    }

    protected void syncFeedRetro(final BackendAPIClient.Feed feed) {
        ContentResolver resolver = getContentResolver();
        // Prevent notifications to be called until we're done
        RssContentProvider.setShouldNotify(false);
        try {
            FeedSQL feedSQL = getUniqueSQLFeed(feed);

            // Handle items
            if (feed.items == null) {
                Log.d(TAG, "Items in feed was null");
                // Done
                return;
            }
            Log.d(TAG, "Number of items in feed: " + feed.items.size());
            for (BackendAPIClient.FeedItem item : feed.items) {
                Log.d(TAG, "ititle " + item.title);
                Log.d(TAG, "isnippet " + item.snippet);

                // Save to database
                FeedItemSQL itemSQL = getUniqueSQLItem(item, feedSQL);
                // Set new values. Make sure some are not null
                itemSQL.title = item.title;
                if (itemSQL.title == null) {
                    itemSQL.title = "";
                }
                itemSQL.description = item.description;
                if (itemSQL.description == null) {
                    itemSQL.description = "";
                }
                itemSQL.plaintitle = item.title_stripped;
                if (itemSQL.plaintitle == null) {
                    itemSQL.plaintitle = "";
                }
                itemSQL.plainsnippet = item.snippet;
                if (itemSQL.plainsnippet == null) {
                    itemSQL.plainsnippet = "";
                }
                itemSQL.imageurl = item.image;
//                if (item.image != null) {
 //                   itemSQL.imageurl = item.images.get(0);
//                    // TODO pre-cache ALL images
//                    if (itemSQL.imageurl != null && !itemSQL.imageurl.isEmpty()) {
//                        Log.d("JONAS", "Pre-fetching " + itemSQL.imageurl);
//                        Picasso.with(this).load(itemSQL.imageurl).fetch();
//                    }
 //               }
                itemSQL.link = item.link;
                itemSQL.enclosurelink = item.enclosure;
                itemSQL.author = item.author;
                try {
                    Log.d("JONASTIME", "" + item.published);
                    itemSQL.setPubDate(item.published);
                } catch (Exception e) {
                    // server should deal with timestamps and convert them,
                    // but some formats may slip through.
                    Log.d(TAG, "published error: " + e.getMessage());
                    itemSQL.setPubDate(DateTime.now());
                }
                // Always need these
                itemSQL.feed_id = feedSQL.id;
                itemSQL.feedtitle = feedSQL.title;
                itemSQL.tag = feedSQL.tag;
                // Save it
                Log.d("JONAS", "Saving the item: " + itemSQL.title);
                Util.SaveOrUpdate(resolver, itemSQL);
            }

        } catch (Exception e) {
            Log.e(TAG, "" + e.getMessage());
        } finally {
            // Enable notifications again
            RssContentProvider.setShouldNotify(true);
            // And notify what we changed
            RssContentProvider.notifyAllUris(this);
        }
    }
}
