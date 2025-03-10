package co.tinode.tindroid;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.IconMarginSpan;
import android.text.style.StyleSpan;
import android.util.Base64;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.app.ActivityCompat;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import co.tinode.tindroid.db.BaseDb;
import co.tinode.tindroid.db.MessageDb;
import co.tinode.tindroid.db.StoredMessage;
import co.tinode.tindroid.format.CopyFormatter;
import co.tinode.tindroid.format.FullFormatter;
import co.tinode.tindroid.format.QuoteFormatter;
import co.tinode.tindroid.format.ThumbnailTransformer;
import co.tinode.tindroid.media.VxCard;
import co.tinode.tinodesdk.ComTopic;
import co.tinode.tinodesdk.MeTopic;
import co.tinode.tinodesdk.PromisedReply;
import co.tinode.tinodesdk.Storage;
import co.tinode.tinodesdk.Tinode;
import co.tinode.tinodesdk.Topic;
import co.tinode.tinodesdk.model.Drafty;
import co.tinode.tinodesdk.model.MsgRange;
import co.tinode.tinodesdk.model.ServerMessage;
import co.tinode.tinodesdk.model.Subscription;

/**
 * Handle display of a conversation
 */
public class MessagesAdapter extends RecyclerView.Adapter<MessagesAdapter.ViewHolder> {
    private static final String TAG = "MessagesAdapter";

    private static final int MESSAGES_TO_LOAD = 20;

    private static final int MESSAGES_QUERY_ID = 200;

    private static final String HARD_RESET = "hard_reset";
    private static final int REFRESH_NONE = 0;
    private static final int REFRESH_SOFT = 1;
    private static final int REFRESH_HARD = 2;

    // Bits defining message bubble variations
    // _TIP_ == "single", i.e. has a bubble tip.
    // _SIMPLE_ == no bubble tip, just a rounded rectangle.
    // _DATE == the date bubble is visible.
    private static final int VIEWTYPE_SIDE_CENTER = 0b000001;
    private static final int VIEWTYPE_SIDE_LEFT   = 0b000010;
    private static final int VIEWTYPE_SIDE_RIGHT  = 0b000100;
    private static final int VIEWTYPE_TIP         = 0b001000;
    private static final int VIEWTYPE_AVATAR      = 0b010000;
    private static final int VIEWTYPE_DATE        = 0b100000;
    private static final int VIEWTYPE_INVALID     = 0b000000;

    // Duration of a message bubble animation in ms.
    private static final int MESSAGE_BUBBLE_ANIMATION_SHORT = 150;
    private static final int MESSAGE_BUBBLE_ANIMATION_LONG = 600;

    // Storage Permissions
    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static final String[] PERMISSIONS_STORAGE = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    private final MessageActivity mActivity;
    private final ActionMode.Callback mSelectionModeCallback;
    private final SwipeRefreshLayout mRefresher;
    private final MessageLoaderCallbacks mMessageLoaderCallback;
    private ActionMode mSelectionMode;
    private RecyclerView mRecyclerView;
    private Cursor mCursor;
    private String mTopicName = null;
    private SparseBooleanArray mSelectedItems = null;
    private int mPagesToLoad;

    private AudioManager mAudioManager = null;
    private MediaPlayer mAudioPlayer = null;
    private int mPlayingAudioSeq = -1;
    private FullFormatter.AudioControlCallback mAudioControlCallback = null;

    MessagesAdapter(MessageActivity context, SwipeRefreshLayout refresher) {
        super();

        mActivity = context;
        setHasStableIds(true);

        mRefresher = refresher;
        mPagesToLoad = 1;

        mMessageLoaderCallback = new MessageLoaderCallbacks();

        mSelectionModeCallback = new ActionMode.Callback() {
            @Override
            public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
                if (mSelectedItems == null) {
                    mSelectedItems = new SparseBooleanArray();
                }
                int selected = mSelectedItems.size();
                menu.findItem(R.id.action_reply).setVisible(selected <= 1);
                menu.findItem(R.id.action_forward).setVisible(selected <= 1);
                return true;
            }

            @SuppressLint("NotifyDataSetChanged")
            @Override
            public void onDestroyActionMode(ActionMode actionMode) {
                SparseBooleanArray arr = mSelectedItems;
                mSelectedItems = null;
                if (arr.size() < 6) {
                    for (int i = 0; i < arr.size(); i++) {
                        notifyItemChanged(arr.keyAt(i));
                    }
                } else {
                    notifyDataSetChanged();
                }
            }

            @Override
            public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
                mActivity.getMenuInflater().inflate(R.menu.menu_message_selected, menu);
                menu.findItem(R.id.action_delete).setVisible(!ComTopic.isChannel(mTopicName));
                return true;
            }

            @Override
            public boolean onActionItemClicked(ActionMode actionMode, MenuItem menuItem) {
                // Don't convert to switch: Android does not like it.
                int id = menuItem.getItemId();
                if (id == R.id.action_delete) {
                    sendDeleteMessages(getSelectedArray());
                    return true;
                } else if (id == R.id.action_copy) {
                    copyMessageText(getSelectedArray());
                    return true;
                } else if (id == R.id.action_send_now) {
                    // FIXME: implement resending now.
                    Log.d(TAG, "Try re-sending selected item");
                    return true;
                } else if (id == R.id.action_reply) {
                    int[] selected = getSelectedArray();
                    if (selected != null) {
                        showReplyPreview(selected[0]);
                    }
                    return true;
                } else if (id == R.id.action_forward) {
                    int[] selected = getSelectedArray();
                    if (selected != null) {
                        showMessageForwardSelector(selected[0]);
                    }
                    return true;
                }

                return false;
            }
        };

        verifyStoragePermissions();
    }

    // Generates formatted content:
    //  - "( ! ) invalid content"
    //  - "( <) processing ..."
    //  - "( ! ) failed"
    private static Spanned serviceContentSpanned(Context ctx, int iconId, int messageId) {
        SpannableString span = new SpannableString(ctx.getString(messageId));
        span.setSpan(new StyleSpan(Typeface.ITALIC), 0, span.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new ForegroundColorSpan(Color.rgb(0x75, 0x75, 0x75)),
                0, span.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Drawable icon = AppCompatResources.getDrawable(ctx, iconId);
        span.setSpan(new IconMarginSpan(UiUtils.bitmapFromDrawable(icon), 24),
                0, span.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return span;
    }

    private static StoredMessage getMessage(Cursor cur, int position, int previewLength) {
        if (cur.moveToPosition(position)) {
            return StoredMessage.readMessage(cur, previewLength);
        }
        return null;
    }

    private static int findInCursor(Cursor cur, int seq) {
        int low = 0;
        int high = cur.getCount() - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            StoredMessage m = getMessage(cur, mid, 0); // previewLength == 0 means no content is needed.
            if (m == null) {
                return -mid;
            }

            // Messages are sorted in descending order by seq.
            int cmp = -m.seq + seq;
            if (cmp < 0)
                low = mid + 1;
            else if (cmp > 0)
                high = mid - 1;
            else
                return mid; // key found
        }
        return -(low + 1);  // key not found
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);

        mRecyclerView = recyclerView;
    }

    private int[] getSelectedArray() {
        if (mSelectedItems == null || mSelectedItems.size() == 0) {
            return null;
        }

        int[] items = new int[mSelectedItems.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = mSelectedItems.keyAt(i);
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private void copyMessageText(int[] positions) {
        if (positions == null || positions.length == 0) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        final Topic topic = Cache.getTinode().getTopic(mTopicName);
        if (topic == null) {
            return;
        }

        // The list is inverted, so iterating messages in inverse order as well.
        for (int i = positions.length - 1; i >= 0; i--) {
            int pos = positions[i];
            StoredMessage msg = getMessage(pos);
            if (msg != null) {
                if (msg.from != null) {
                    Subscription<VxCard, ?> sub = (Subscription<VxCard, ?>) topic.getSubscription(msg.from);
                    sb.append("\n[");
                    sb.append((sub != null && sub.pub != null) ? sub.pub.fn : msg.from);
                    sb.append("]: ");
                }
                if (msg.content != null) {
                    sb.append(msg.content.format(new CopyFormatter(mActivity)));
                }
                sb.append("; ").append(UiUtils.shortDate(msg.ts));
            }
            toggleSelectionAt(pos);
            notifyItemChanged(pos);
        }

        updateSelectionMode();

        if (sb.length() > 1) {
            // Delete unnecessary CR in the beginning.
            sb.deleteCharAt(0);
            String text = sb.toString();

            ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("message text", text));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void sendDeleteMessages(final int[] positions) {
        if (positions == null || positions.length == 0) {
            return;
        }

        final Topic topic = Cache.getTinode().getTopic(mTopicName);
        final Storage store = BaseDb.getInstance().getStore();
        if (topic != null) {
            ArrayList<Integer> toDelete = new ArrayList<>();
            int i = 0;
            int discarded = 0;
            while (i < positions.length) {
                int pos = positions[i++];
                StoredMessage msg = getMessage(pos);
                if (msg != null) {
                    if (msg.status == BaseDb.Status.SYNCED) {
                        toDelete.add(msg.seq);
                    } else {
                        store.msgDiscard(topic, msg.getDbId());
                        discarded++;
                    }
                }
            }

            if (!toDelete.isEmpty()) {
                topic.delMessages(MsgRange.listToRanges(toDelete), true)
                        .thenApply(new PromisedReply.SuccessListener<ServerMessage>() {
                            @Override
                            public PromisedReply<ServerMessage> onSuccess(ServerMessage result) {
                                runLoader(false);
                                mActivity.runOnUiThread(() -> updateSelectionMode());
                                return null;
                            }
                        }, new UiUtils.ToastFailureListener(mActivity));
            } else if (discarded > 0) {
                runLoader(false);
                updateSelectionMode();
            }
        }
    }

    private String messageFrom(StoredMessage msg) {
        Tinode tinode =  Cache.getTinode();
        String uname = null;
        if (tinode.isMe(msg.from)) {
            MeTopic<VxCard> me = tinode.getMeTopic();
            if (me != null) {
                VxCard pub = me.getPub();
                uname = pub != null ? pub.fn : null;
            }
        } else {
            @SuppressWarnings("unchecked") final ComTopic<VxCard> topic = (ComTopic<VxCard>) tinode.getTopic(mTopicName);
            if (topic != null) {
                if (topic.isChannel() || topic.isP2PType()) {
                    VxCard pub = topic.getPub();
                    uname = pub != null ? pub.fn : null;
                } else {
                    final Subscription<VxCard, ?> sub = topic.getSubscription(msg.from);
                    uname = (sub != null && sub.pub != null) ? sub.pub.fn : null;
                }
            }
        }
        if (TextUtils.isEmpty(uname)) {
            uname = mActivity.getString(R.string.unknown);
        }
        return uname;
    }

    private void showReplyPreview(int pos) {
        StoredMessage msg = getMessage(pos);
        if (msg != null && msg.status == BaseDb.Status.SYNCED) {
            toggleSelectionAt(pos);
            notifyItemChanged(pos);
            updateSelectionMode();
            ThumbnailTransformer tr = new ThumbnailTransformer();
            Drafty replyContent = msg.content.replyContent(UiUtils.QUOTED_REPLY_LENGTH, 1).transform(tr);
            tr.completionPromise().thenApply(new PromisedReply.SuccessListener<Void>() {
                @Override
                public PromisedReply<Void> onSuccess(Void result) {
                    mActivity.runOnUiThread(() -> {
                        Drafty reply = Drafty.quote(messageFrom(msg), msg.from, replyContent);
                        mActivity.showReply(reply, msg.seq);
                    });
                    return null;
                }
            });
        } else {
            Toast.makeText(mActivity, R.string.cannot_reply, Toast.LENGTH_SHORT).show();
        }
    }

    private void showMessageForwardSelector(int pos) {
        StoredMessage msg = getMessage(pos);
        if (msg != null) { // No need to check message status, OK to forward failed message.
            toggleSelectionAt(pos);
            notifyItemChanged(pos);
            updateSelectionMode();

            Bundle args = new Bundle();
            String uname = "➦ " + messageFrom(msg);
            String from = msg.from != null ? msg.from : mTopicName;
            args.putSerializable(ForwardToFragment.CONTENT_TO_FORWARD, msg.content.forwardedContent());
            args.putSerializable(ForwardToFragment.FORWARDING_FROM_USER, Drafty.mention(uname, from));
            args.putString(ForwardToFragment.FORWARDING_FROM_TOPIC, mTopicName);
            ForwardToFragment fragment = new ForwardToFragment();
            fragment.setArguments(args);
            fragment.show(mActivity.getSupportFragmentManager(), MessageActivity.FRAGMENT_FORWARD_TO);
        }
    }

    private static int packViewType(int side, boolean tip, boolean avatar, boolean date) {
        int type = side;
        if (tip) {
            type |= VIEWTYPE_TIP;
        }
        if (avatar) {
            type |= VIEWTYPE_AVATAR;
        }
        if (date) {
            type |= VIEWTYPE_DATE;
        }
        return type;
    }

    @Override
    public int getItemViewType(int position) {
        int itemType = VIEWTYPE_INVALID;
        StoredMessage m = getMessage(position);

        if (m != null) {
            if (m.delId > 0) {
                itemType = packViewType(VIEWTYPE_SIDE_CENTER, false, false, false);
            } else {
                long nextFrom = -2;
                Date nextDate = null;
                if (position > 0) {
                    StoredMessage m2 = getMessage(position - 1);
                    if (m2 != null) {
                        nextFrom = m2.userId;
                        nextDate = m2.ts;
                    }
                }
                Date prevDate = null;
                if (position < getItemCount() - 1) {
                    StoredMessage m2 = getMessage(position + 1);
                    if (m2 != null) {
                        prevDate = m2.ts;
                    }
                }
                itemType = packViewType(m.isMine() ? VIEWTYPE_SIDE_RIGHT : VIEWTYPE_SIDE_LEFT,
                        m.userId != nextFrom || !UiUtils.isSameDate(nextDate, m.ts),
                        Topic.isGrpType(mTopicName) && !ComTopic.isChannel(mTopicName),
                        !UiUtils.isSameDate(prevDate, m.ts));
            }
        }

        return itemType;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Create a new message bubble view.

        int layoutId = -1;
        if ((viewType & VIEWTYPE_SIDE_CENTER) != 0) {
            layoutId = R.layout.meta_message;
        } else if ((viewType & VIEWTYPE_SIDE_LEFT) != 0) {
            if ((viewType & VIEWTYPE_AVATAR) != 0 && (viewType & VIEWTYPE_TIP) != 0) {
                layoutId = R.layout.message_left_single_avatar;
            } else if ((viewType & VIEWTYPE_TIP) != 0) {
                layoutId = R.layout.message_left_single;
            } else if ((viewType & VIEWTYPE_AVATAR) != 0) {
                layoutId = R.layout.message_left_avatar;
            } else {
                layoutId = R.layout.message_left;
            }
        } else if ((viewType & VIEWTYPE_SIDE_RIGHT) != 0) {
            if ((viewType & VIEWTYPE_TIP) != 0) {
                layoutId = R.layout.message_right_single;
            } else {
                layoutId = R.layout.message_right;
            }
        }

        View v = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
        if (v != null) {
            View dateBubble = v.findViewById(R.id.dateDivider);
            if (dateBubble != null) {
                dateBubble.setVisibility((viewType & VIEWTYPE_DATE) != 0 ? View.VISIBLE : View.GONE);
            }
        }

        return new ViewHolder(v, viewType);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, int position, @NonNull List<Object> payload) {
        if (!payload.isEmpty()) {
            Float progress = (Float) payload.get(0);
            holder.mProgressBar.setProgress((int) (progress * 100));
            return;
        }

        onBindViewHolder(holder, position);
    }

    @SuppressLint("ClickableViewAccessibility")
    @SuppressWarnings("unchecked")
    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, int position) {
        final ComTopic<VxCard> topic = (ComTopic<VxCard>) Cache.getTinode().getTopic(mTopicName);
        final StoredMessage m = getMessage(position);

        if (topic == null || m == null) {
            return;
        }

        holder.seqId = m.seq;

        if (holder.mIcon != null) {
            // Meta bubble in the center of the screen
            holder.mIcon.setVisibility(View.VISIBLE);
            holder.mText.setText(R.string.content_deleted);
            return;
        }

        final long msgId = m.getDbId();

        boolean hasAttachment = m.content != null && m.content.getEntReferences() != null;
        boolean uploadingAttachment = hasAttachment && m.isPending();
        boolean uploadFailed = hasAttachment && (m.status == BaseDb.Status.FAILED);

        if (m.content != null) {
            // Disable clicker while message is processed.
            FullFormatter formatter = new FullFormatter(holder.mText, uploadingAttachment ? null : new SpanClicker(m.seq));
            formatter.setQuoteFormatter(new QuoteFormatter(holder.mText, holder.mText.getTextSize()));
            Spanned text = m.content.format(formatter);
            if (TextUtils.isEmpty(text)) {
                if (m.status == BaseDb.Status.DRAFT || m.status == BaseDb.Status.QUEUED || m.status == BaseDb.Status.SENDING) {
                    text = serviceContentSpanned(mActivity, R.drawable.ic_schedule_gray, R.string.processing);
                } else if (m.status == BaseDb.Status.FAILED) {
                    text = serviceContentSpanned(mActivity, R.drawable.ic_error_gray, R.string.failed);
                } else {
                    text = serviceContentSpanned(mActivity, R.drawable.ic_warning_gray, R.string.invalid_content);
                }
            }
            holder.mText.setText(text);
        }

        if (m.content != null && m.content.hasEntities(Arrays.asList("AU", "BN", "LN", "MN", "HT", "IM", "EX"))) {
            // Some spans are clickable.
            holder.mText.setOnTouchListener((v, ev) -> {
                holder.mGestureDetector.onTouchEvent(ev);
                return false;
            });
            holder.mText.setMovementMethod(LinkMovementMethod.getInstance());
            holder.mText.setLinksClickable(true);
            holder.mText.setFocusable(true);
            holder.mText.setClickable(true);
        } else {
            holder.mText.setOnTouchListener(null);
            holder.mText.setMovementMethod(null);
            holder.mText.setLinksClickable(false);
            holder.mText.setFocusable(false);
            holder.mText.setClickable(false);
            holder.mText.setAutoLinkMask(0);
        }

        if (hasAttachment && holder.mProgressContainer != null) {
            if (uploadingAttachment) {
                // Hide the word 'canceled'.
                holder.mProgressResult.setVisibility(View.GONE);
                // Show progress bar.
                holder.mProgress.setVisibility(View.VISIBLE);
                holder.mProgressContainer.setVisibility(View.VISIBLE);
                holder.mCancelProgress.setOnClickListener(v -> {
                    cancelUpload(msgId);
                    holder.mProgress.setVisibility(View.GONE);
                    holder.mProgressResult.setVisibility(View.VISIBLE);
                });
            } else if (uploadFailed) {
                // Show the word 'canceled'.
                holder.mProgressResult.setVisibility(View.VISIBLE);
                // Hide progress bar.
                holder.mProgress.setVisibility(View.GONE);
                holder.mProgressContainer.setVisibility(View.VISIBLE);
                holder.mCancelProgress.setOnClickListener(null);
            } else {
                // Hide the entire progress bar component.
                holder.mProgressContainer.setVisibility(View.GONE);
                holder.mCancelProgress.setOnClickListener(null);
            }
        }

        if (holder.mSelected != null) {
            if (mSelectedItems != null && mSelectedItems.get(position)) {
                holder.mSelected.setVisibility(View.VISIBLE);
            } else {
                holder.mSelected.setVisibility(View.GONE);
            }
        }

        if (holder.mAvatar != null || holder.mUserName != null) {
            Subscription<VxCard, ?> sub = topic.getSubscription(m.from);
            if (sub != null) {
                if (holder.mAvatar != null) {
                    UiUtils.setAvatar(holder.mAvatar, sub.pub, sub.user, false);
                }

                if (holder.mUserName != null && sub.pub != null) {
                    holder.mUserName.setText(sub.pub.fn);
                }
            } else {
                if (holder.mAvatar != null) {
                    holder.mAvatar.setImageResource(R.drawable.ic_person_circle);
                }
                if (holder.mUserName != null) {
                    Spannable span = new SpannableString(mActivity.getString(R.string.user_not_found));
                    span.setSpan(new StyleSpan(Typeface.ITALIC), 0, span.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    holder.mUserName.setText(span);
                }
            }
        }

        if (m.ts != null) {
            Context context = holder.itemView.getContext();
            if (holder.mDateDivider.getVisibility() == View.VISIBLE && m.ts != null) {
                // DateUtils.getRelativeTimeSpanString is stupid: it assumes local time zone for
                // calculations and is frequently off by one day, including "TOMORROW".
                long now = System.currentTimeMillis();
                long offset = TimeZone.getDefault().getOffset(now);
                CharSequence date = DateUtils.getRelativeTimeSpanString(
                        m.ts.getTime() + offset,
                        System.currentTimeMillis() + offset,
                        DateUtils.DAY_IN_MILLIS,
                        DateUtils.FORMAT_NUMERIC_DATE | DateUtils.FORMAT_SHOW_YEAR).toString().toUpperCase();
                holder.mDateDivider.setText(date);
            }
            if (holder.mMeta != null) {
                holder.mMeta.setText(UiUtils.timeOnly(context, m.ts));
            }
        }

        if (holder.mDeliveredIcon != null) {
            if ((holder.mViewType & VIEWTYPE_SIDE_RIGHT) != 0) {
                UiUtils.setMessageStatusIcon(holder.mDeliveredIcon, m.status.value,
                        topic.msgReadCount(m.seq), topic.msgRecvCount(m.seq));
            }
        }

        holder.itemView.setOnLongClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();

            if (mSelectedItems == null) {
                mSelectionMode = mActivity.startSupportActionMode(mSelectionModeCallback);
            }

            toggleSelectionAt(pos);
            notifyItemChanged(pos);
            updateSelectionMode();

            return true;
        });
        holder.itemView.setOnClickListener(v -> {
            if (mSelectedItems != null) {
                int pos = holder.getBindingAdapterPosition();
                toggleSelectionAt(pos);
                notifyItemChanged(pos);
                updateSelectionMode();
            } else {
                animateMessageBubble(holder, m.isMine(), true);
                int replySeq = -1;
                try {
                    replySeq = Integer.parseInt(m.getStringHeader("reply"));
                } catch (NumberFormatException ignored) {
                }
                if (replySeq != -1) {
                    // A reply message was clicked. Scroll original into view and animate.
                    final int pos = findInCursor(mCursor, replySeq);
                    if (pos >= 0) {
                        StoredMessage mm = getMessage(pos);
                        if (mm != null) {
                            LinearLayoutManager lm = (LinearLayoutManager) mRecyclerView.getLayoutManager();
                            if (lm != null &&
                                    pos >= lm.findFirstCompletelyVisibleItemPosition() &&
                                    pos <= lm.findLastCompletelyVisibleItemPosition()) {
                                // Completely visible, animate now.
                                animateMessageBubble(
                                        (ViewHolder) mRecyclerView.findViewHolderForAdapterPosition(pos),
                                        mm.isMine(), false);
                            } else {
                                // Scroll then animate.
                                mRecyclerView.clearOnScrollListeners();
                                mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                                    @Override
                                    public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                                        super.onScrollStateChanged(recyclerView, newState);
                                        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                                            recyclerView.removeOnScrollListener(this);
                                            animateMessageBubble(
                                                    (ViewHolder) mRecyclerView.findViewHolderForAdapterPosition(pos),
                                                    mm.isMine(), false);
                                        }
                                    }
                                });
                                mRecyclerView.smoothScrollToPosition(pos);
                            }
                        }
                    }
                }
            }
        });
    }

    @Override
    public void onViewRecycled(final @NonNull ViewHolder vh) {
        // Stop playing audio and release mAudioPlayer.
        if (vh.seqId > 0) {
            if (mAudioPlayer != null) {
                mAudioPlayer.stop();
                mAudioPlayer.release();
                mAudioPlayer = null;
                mPlayingAudioSeq = -1;
                vh.seqId = -1;
            }
        }
    }

    private void animateMessageBubble(final ViewHolder vh, boolean isMine, boolean light) {
        if (vh == null) {
            return;
        }
        int from = vh.mMessageBubble.getResources().getColor(isMine ?
                R.color.colorMessageBubbleMine : R.color.colorMessageBubbleOther, null);
        int to = vh.mMessageBubble.getResources().getColor(isMine ?
                (light ? R.color.colorMessageBubbleMineFlashingLight : R.color.colorMessageBubbleMineFlashing) :
                (light ? R.color.colorMessageBubbleOtherFlashingLight: R.color.colorMessageBubbleOtherFlashing),
                null);
        ValueAnimator colorAnimation = ValueAnimator.ofArgb(from, to, from);
        colorAnimation.setDuration(light ? MESSAGE_BUBBLE_ANIMATION_SHORT : MESSAGE_BUBBLE_ANIMATION_LONG);
        colorAnimation.addUpdateListener(animator ->
                vh.mMessageBubble.setBackgroundTintList(ColorStateList.valueOf((int) animator.getAnimatedValue()))
        );
        colorAnimation.start();
    }

    // Must match position-to-item of getItemId.
    private StoredMessage getMessage(int position) {
        if (mCursor != null && !mCursor.isClosed()) {
            return getMessage(mCursor, position, -1);
        }
        return null;
    }

    @Override
    // Must match position-to-item of getMessage.
    public long getItemId(int position) {
        if (mCursor != null && !mCursor.isClosed() && mCursor.moveToPosition(position)) {
            return MessageDb.getLocalId(mCursor);
        }
        return View.NO_ID;
    }

    int findItemPositionById(long itemId, int first, int last) {
        if (mCursor == null || mCursor.isClosed()) {
            return -1;
        }

        for (int i = first; i <= last; i++) {
            if (mCursor.moveToPosition(i)) {
                if (MessageDb.getLocalId(mCursor) == itemId) {
                    return i;
                }
            }
        }
        return -1;
    }

    @Override
    public int getItemCount() {
        return mCursor != null ? mCursor.getCount() : 0;
    }

    private void toggleSelectionAt(int pos) {
        if (mSelectedItems.get(pos)) {
            mSelectedItems.delete(pos);
        } else {
            mSelectedItems.put(pos, true);
        }
    }

    private void updateSelectionMode() {
        if (mSelectionMode != null) {
            int selected = mSelectedItems.size();
            if (selected == 0) {
                mSelectionMode.finish();
                mSelectionMode = null;
            } else {
                mSelectionMode.setTitle(String.valueOf(selected));
                Menu menu = mSelectionMode.getMenu();
                menu.findItem(R.id.action_reply).setVisible(selected == 1);
                menu.findItem(R.id.action_forward).setVisible(selected == 1);
            }
        }
    }

    void resetContent(@Nullable final String topicName) {
        if (topicName == null) {
            boolean hard = mTopicName != null;
            mTopicName = null;
            swapCursor(null, hard ? REFRESH_HARD : REFRESH_NONE);
        } else {
            boolean hard = !topicName.equals(mTopicName);
            mTopicName = topicName;
            runLoader(hard);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void swapCursor(final Cursor cursor, final int refresh) {
        if (mCursor != null && mCursor == cursor) {
            return;
        }

        // Clear selection
        if (mSelectionMode != null) {
            mSelectionMode.finish();
            mSelectionMode = null;
        }

        Cursor oldCursor = mCursor;
        mCursor = cursor;
        if (oldCursor != null) {
            oldCursor.close();
        }

        if (refresh != REFRESH_NONE) {
            mActivity.runOnUiThread(() -> {
                int position = -1;
                if (cursor != null) {
                    LinearLayoutManager lm = (LinearLayoutManager) mRecyclerView.getLayoutManager();
                    if (lm != null) {
                        position = lm.findFirstVisibleItemPosition();
                    }
                }
                mRefresher.setRefreshing(false);
                if (refresh == REFRESH_HARD) {
                    mRecyclerView.setAdapter(MessagesAdapter.this);
                } else {
                    notifyDataSetChanged();
                }
                if (cursor != null) {
                    if (position == 0) {
                        mRecyclerView.scrollToPosition(0);
                    }
                }
            });
        }
    }

    /**
     * Checks if the app has permission to write to device storage
     * <p>
     * If the app does not has permission then the user will be prompted to grant permission.
     */
    private boolean verifyStoragePermissions() {
        // Check if we have write permission
        if (!UiUtils.isPermissionGranted(mActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            // We don't have permission so prompt the user
            Log.i(TAG, "No permission to write to storage");
            ActivityCompat.requestPermissions(mActivity, PERMISSIONS_STORAGE, REQUEST_EXTERNAL_STORAGE);
            return false;
        }
        return true;
    }

    // Run loader on UI thread
    private void runLoader(final boolean hard) {
        mActivity.runOnUiThread(() -> {
            final LoaderManager lm = LoaderManager.getInstance(mActivity);
            final Loader<Cursor> loader = lm.getLoader(MESSAGES_QUERY_ID);
            Bundle args = new Bundle();
            args.putBoolean(HARD_RESET, hard);
            if (loader != null && !loader.isReset()) {
                lm.restartLoader(MESSAGES_QUERY_ID, args, mMessageLoaderCallback);
            } else {
                lm.initLoader(MESSAGES_QUERY_ID, args, mMessageLoaderCallback);
            }
        });
    }

    boolean loadNextPage() {
        if (getItemCount() == mPagesToLoad * MESSAGES_TO_LOAD) {
            mPagesToLoad++;
            runLoader(false);
            return true;
        }

        return false;
    }

    private void cancelUpload(long msgId) {
        Storage store = BaseDb.getInstance().getStore();
        final Topic topic = Cache.getTinode().getTopic(mTopicName);
        if (store != null && topic != null) {
            store.msgFailed(topic, msgId);
            // Invalidate cached data.
            runLoader(false);
        }

        final String uniqueID = Long.toString(msgId);

        WorkManager wm = WorkManager.getInstance(mActivity);
        WorkInfo.State state = null;
        try {
            List<WorkInfo> lwi = wm.getWorkInfosForUniqueWork(uniqueID).get();
            if (!lwi.isEmpty()) {
                WorkInfo wi = lwi.get(0);
                state = wi.getState();
            }
        } catch (CancellationException | ExecutionException | InterruptedException ignored) {
        }

        if (state == null || !state.isFinished()) {
            wm.cancelUniqueWork(uniqueID);
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final int mViewType;
        final ImageView mIcon;
        final ImageView mAvatar;
        final View mMessageBubble;
        final AppCompatImageView mDeliveredIcon;
        final TextView mDateDivider;
        final TextView mText;
        final TextView mMeta;
        final TextView mUserName;
        final View mSelected;
        final View mRippleOverlay;
        final View mProgressContainer;
        final ProgressBar mProgressBar;
        final AppCompatImageButton mCancelProgress;
        final View mProgress;
        final View mProgressResult;
        final GestureDetector mGestureDetector;
        int seqId = 0;

        ViewHolder(View itemView, int viewType) {
            super(itemView);

            mViewType = viewType;
            mIcon = itemView.findViewById(R.id.icon);
            mAvatar = itemView.findViewById(R.id.avatar);
            mMessageBubble = itemView.findViewById(R.id.messageBubble);
            mDeliveredIcon = itemView.findViewById(R.id.messageViewedIcon);
            mDateDivider = itemView.findViewById(R.id.dateDivider);
            mText = itemView.findViewById(R.id.messageText);
            mMeta = itemView.findViewById(R.id.messageMeta);
            mUserName = itemView.findViewById(R.id.userName);
            mSelected = itemView.findViewById(R.id.selected);
            mRippleOverlay = itemView.findViewById(R.id.overlay);
            mProgressContainer = itemView.findViewById(R.id.progressContainer);
            mProgress = itemView.findViewById(R.id.progressPanel);
            mProgressBar = itemView.findViewById(R.id.attachmentProgressBar);
            mCancelProgress = itemView.findViewById(R.id.attachmentProgressCancel);
            mProgressResult = itemView.findViewById(R.id.progressResult);
            mGestureDetector = new GestureDetector(itemView.getContext(),
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public void onLongPress(MotionEvent ev) {
                            itemView.performLongClick();
                        }

                        @Override
                        public boolean onSingleTapConfirmed(MotionEvent ev) {
                            itemView.performClick();
                            return super.onSingleTapConfirmed(ev);
                        }

                        @Override
                        public void onShowPress(MotionEvent ev) {
                            if (mRippleOverlay != null) {
                                mRippleOverlay.setPressed(true);
                                mRippleOverlay.postDelayed(() -> mRippleOverlay.setPressed(false), 250);
                            }
                        }
                        @Override
                        public boolean onDown(MotionEvent ev) {
                            // Convert click coordinates in itemView to TexView.
                            int[] item = new int[2];
                            int[] text = new int[2];
                            itemView.getLocationOnScreen(item);
                            mText.getLocationOnScreen(text);

                            int x = (int) ev.getX();
                            int y = (int) ev.getY();

                            // Make click position available to spannable.
                            mText.setTag(R.id.click_coordinates, new Point(x, y));
                            return super.onDown(ev);
                        }
                    });
        }
    }

    private class MessageLoaderCallbacks implements LoaderManager.LoaderCallbacks<Cursor> {
        private boolean mHardReset;

        @NonNull
        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            if (id == MESSAGES_QUERY_ID) {
                if (args != null) {
                    mHardReset = args.getBoolean(HARD_RESET, false);
                }
                return new MessageDb.Loader(mActivity, mTopicName, mPagesToLoad, MESSAGES_TO_LOAD);
            }

            throw new IllegalArgumentException("Unknown loader id " + id);
        }

        @Override
        public void onLoadFinished(@NonNull Loader<Cursor> loader,
                                   Cursor cursor) {
            if (loader.getId() == MESSAGES_QUERY_ID) {
                swapCursor(cursor, mHardReset ? REFRESH_HARD : REFRESH_SOFT);
            }
        }

        @Override
        public void onLoaderReset(@NonNull Loader<Cursor> loader) {
            if (loader.getId() == MESSAGES_QUERY_ID) {
                swapCursor(null, mHardReset ? REFRESH_HARD : REFRESH_SOFT);
            }
        }
    }

    class SpanClicker implements FullFormatter.ClickListener {
        private final int mSeqId;

        SpanClicker(int seq) {
            mSeqId = seq;
        }

        @Override
        public void onClick(String type, Map<String, Object> data, Object params) {
            if (mSelectedItems != null) {
                return;
            }

            switch (type) {
                case "AU":
                    // Audio play/pause.
                    if (data != null) {
                        try {
                            FullFormatter.AudioClickAction aca = (FullFormatter.AudioClickAction) params;
                            if (aca.action == FullFormatter.AudioClickAction.Action.PLAY) {
                                String url = getPayloadUrl(data);
                                if (url != null) {
                                    if (mAudioPlayer == null || mPlayingAudioSeq != mSeqId) {
                                        initAudioPlayer(mSeqId, url, aca.control);
                                    }
                                    mAudioPlayer.start();
                                }
                            } else if (aca.action == FullFormatter.AudioClickAction.Action.PAUSE) {
                                if (mAudioPlayer != null) {
                                    mAudioPlayer.pause();
                                }
                            } else if (aca.seekTo != null) {
                                if (mAudioPlayer == null || mPlayingAudioSeq != mSeqId) {
                                    String url = getPayloadUrl(data);
                                    if (url != null) {
                                        initAudioPlayer(mSeqId, url, aca.control);
                                    }
                                }
                                if (mAudioPlayer != null) {
                                    long duration = mAudioPlayer.getDuration();
                                    if (duration > 0) {
                                        mAudioPlayer.seekTo((int) (aca.seekTo * duration));
                                    } else {
                                        Log.i(TAG, "Audio has no duration");
                                    }
                                }
                            }
                        } catch (IOException | ClassCastException ignored) {
                            Toast.makeText(mActivity, R.string.unable_to_play_audio, Toast.LENGTH_SHORT).show();
                        }
                    }
                    break;

                case "LN":
                    // Click on an URL
                    try {
                        if (data != null) {
                            URL url = new URL(Cache.getTinode().getBaseUrl(), (String) data.get("url"));
                            String scheme = url.getProtocol();
                            if (!scheme.equals("http") && !scheme.equals("https")) {
                                // As a security measure refuse to follow URLs with non-http(s) protocols.
                                break;
                            }
                            mActivity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())));
                        }
                    } catch (ClassCastException | MalformedURLException | NullPointerException ignored) {
                    }
                    break;

                case "IM":
                    // Image
                    Bundle args = null;
                    if (data != null) {
                        Object val;
                        if ((val = data.get("ref")) instanceof String) {
                            URL url = Cache.getTinode().toAbsoluteURL((String) val);
                            // URL is null when the image is not sent yet.
                            if (url != null) {
                                args = new Bundle();
                                args.putParcelable(AttachmentHandler.ARG_REMOTE_URI, Uri.parse(url.toString()));
                            }
                        }

                        if (args == null && (val = data.get("val")) != null) {
                            byte[] bytes = val instanceof String ?
                                    Base64.decode((String) val, Base64.DEFAULT) :
                                    val instanceof byte[] ? (byte[]) val : null;
                            if (bytes != null) {
                                args = new Bundle();
                                args.putByteArray(AttachmentHandler.ARG_SRC_BYTES, bytes);
                            }
                        }

                        if (args != null) {
                            try {
                                args.putString(AttachmentHandler.ARG_MIME_TYPE, (String) data.get("mime"));
                                args.putString(AttachmentHandler.ARG_FILE_NAME, (String) data.get("name"));
                                //noinspection ConstantConditions
                                args.putInt(AttachmentHandler.ARG_IMAGE_WIDTH, (int) data.get("width"));
                                //noinspection ConstantConditions
                                args.putInt(AttachmentHandler.ARG_IMAGE_HEIGHT, (int) data.get("height"));
                            } catch (NullPointerException | ClassCastException ex) {
                                Log.i(TAG, "Invalid type of image parameters", ex);
                            }
                        }
                    }

                    if (args != null) {
                        mActivity.showFragment(MessageActivity.FRAGMENT_VIEW_IMAGE, args, true);
                    } else {
                        Toast.makeText(mActivity, R.string.broken_image, Toast.LENGTH_SHORT).show();
                    }

                    break;

                case "EX":
                    // Attachment
                    if (verifyStoragePermissions()) {
                        String fname = null;
                        String mimeType = null;
                        try {
                            fname = (String) data.get("name");
                            mimeType = (String) data.get("mime");
                        } catch (ClassCastException ignored) {
                        }

                        // Try to extract file name from reference.
                        if (TextUtils.isEmpty(fname)) {
                            Object ref = data.get("ref");
                            if (ref instanceof String) {
                                try {
                                    URL url = new URL((String) ref);
                                    fname = url.getFile();
                                } catch (MalformedURLException ignored) {
                                }
                            }
                        }

                        if (TextUtils.isEmpty(fname)) {
                            fname = mActivity.getString(R.string.default_attachment_name);
                        }

                        AttachmentHandler.enqueueDownloadAttachment(mActivity, data, fname, mimeType);
                    } else {
                        Toast.makeText(mActivity, R.string.failed_to_save_download, Toast.LENGTH_SHORT).show();
                    }
                    break;

                case "BN":
                    // Button
                    if (data != null) {
                        try {
                            String actionType = (String) data.get("act");
                            String actionValue = (String) data.get("val");
                            String name = (String) data.get("name");
                            // StoredMessage msg = getMessage(mPosition);
                            if ("pub".equals(actionType)) {
                                Drafty newMsg = new Drafty((String) data.get("title"));
                                Map<String, Object> json = new HashMap<>();
                                // {"seq":6,"resp":{"yes":1}}
                                if (!TextUtils.isEmpty(name)) {
                                    Map<String, Object> resp = new HashMap<>();
                                    // noinspection
                                    resp.put(name, TextUtils.isEmpty(actionValue) ? 1 : actionValue);
                                    json.put("resp", resp);
                                }

                                json.put("seq", "" + mSeqId);
                                newMsg.attachJSON(json);
                                mActivity.sendMessage(newMsg, -1);

                            } else if ("url".equals(actionType)) {
                                URL url = new URL(Cache.getTinode().getBaseUrl(), (String) data.get("ref"));
                                String scheme = url.getProtocol();
                                if (!scheme.equals("http") && !scheme.equals("https")) {
                                    // As a security measure refuse to follow URLs with non-http(s) protocols.
                                    break;
                                }
                                Uri uri = Uri.parse(url.toString());
                                Uri.Builder builder = uri.buildUpon();
                                if (!TextUtils.isEmpty(name)) {
                                    builder = builder.appendQueryParameter(name,
                                            TextUtils.isEmpty(actionValue) ? "1" : actionValue);
                                }
                                builder = builder
                                        .appendQueryParameter("seq", "" + mSeqId)
                                        .appendQueryParameter("uid", Cache.getTinode().getMyId());
                                mActivity.startActivity(new Intent(Intent.ACTION_VIEW, builder.build()));
                            }
                        } catch (ClassCastException | MalformedURLException | NullPointerException ignored) {
                        }
                    }
                    break;
            }
        }

        private String getPayloadUrl(Map<String, Object> data) {
            Object val;
            String url = null;
            if ((val = data.get("ref")) instanceof String) {
                url = (String) val;
            } else if ((val = data.get("val")) instanceof String) {
                String mime = (String) data.get("mime");
                url = "data:" + mime + ";base64," + val;
            }

            return url;
        }

        private void initAudioPlayer(int seq, String sourceUrl,
                                     FullFormatter.AudioControlCallback control) throws IOException {
            mPlayingAudioSeq = seq;
            if (mAudioPlayer != null) {
                mAudioPlayer.reset();
                mAudioPlayer.release();
                if (mAudioControlCallback != null) {
                    mAudioControlCallback.reset();
                }
            }

            if (mAudioManager == null) {
                mAudioManager = (AudioManager) mActivity.getSystemService(Activity.AUDIO_SERVICE);
                mAudioManager.setMode(AudioManager.MODE_IN_CALL);
                mAudioManager.setSpeakerphoneOn(true);
            }

            mAudioControlCallback = control;
            mAudioPlayer = new MediaPlayer();
            mAudioPlayer.setOnCompletionListener(mp -> {
                if (mAudioControlCallback != null) {
                    mAudioControlCallback.reset();
                }
            });
            mAudioPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL).build());
            mAudioPlayer.setDataSource(sourceUrl);
            mAudioPlayer.prepare();
        }
    }
}
