/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.am;

import static com.android.server.am.ActivityManagerService.TAG;
import static com.android.server.am.ActivityRecord.HOME_ACTIVITY_TYPE;
import static com.android.server.am.ActivityRecord.APPLICATION_ACTIVITY_TYPE;
import static com.android.server.am.ActivityRecord.RECENTS_ACTIVITY_TYPE;
import static com.android.server.am.ActivityStackSupervisor.DEBUG_ADD_REMOVE;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.TaskThumbnail;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.service.voice.IVoiceInteractionSession;
import android.util.Slog;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.util.XmlUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;

final class TaskRecord {
    private static final String ATTR_TASKID = "task_id";
    private static final String TAG_INTENT = "intent";
    private static final String TAG_AFFINITYINTENT = "affinity_intent";
    private static final String ATTR_REALACTIVITY = "real_activity";
    private static final String ATTR_ORIGACTIVITY = "orig_activity";
    private static final String TAG_ACTIVITY = "activity";
    private static final String ATTR_AFFINITY = "affinity";
    private static final String ATTR_ROOTHASRESET = "root_has_reset";
    private static final String ATTR_ASKEDCOMPATMODE = "asked_compat_mode";
    private static final String ATTR_USERID = "user_id";
    private static final String ATTR_TASKTYPE = "task_type";
    private static final String ATTR_FIRSTACTIVETIME = "first_active_time";
    private static final String ATTR_LASTACTIVETIME = "last_active_time";
    private static final String ATTR_LASTDESCRIPTION = "last_description";
    private static final String ATTR_LASTTIMEMOVED = "last_time_moved";
    private static final String ATTR_NEVERRELINQUISH = "never_relinquish_identity";
    private static final String ATTR_TASKDESCRIPTIONLABEL = "task_description_label";
    private static final String ATTR_TASKDESCRIPTIONCOLOR = "task_description_color";
    private static final String LAST_ACTIVITY_ICON_SUFFIX = "_last_activity_icon_";

    private static final String TASK_THUMBNAIL_SUFFIX = "_task_thumbnail";

    final int taskId;       // Unique identifier for this task.
    String affinity;        // The affinity name for this task, or null.
    final IVoiceInteractionSession voiceSession;    // Voice interaction session driving task
    final IVoiceInteractor voiceInteractor;         // Associated interactor to provide to app
    Intent intent;          // The original intent that started the task.
    Intent affinityIntent;  // Intent of affinity-moved activity that started this task.
    ComponentName origActivity; // The non-alias activity component of the intent.
    ComponentName realActivity; // The actual activity component that started the task.
    long firstActiveTime;   // First time this task was active.
    long lastActiveTime;    // Last time this task was active, including sleep.
    boolean rootWasReset;   // True if the intent at the root of the task had
                            // the FLAG_ACTIVITY_RESET_TASK_IF_NEEDED flag.
    boolean askedCompatMode;// Have asked the user about compat mode for this task.
    boolean hasBeenVisible; // Set if any activities in the task have been visible to the user.

    String stringName;      // caching of toString() result.
    int userId;             // user for which this task was created
    int creatorUid;         // The app uid that originally created the task

    int numFullscreen;      // Number of fullscreen activities.

    // This represents the last resolved activity values for this task
    // NOTE: This value needs to be persisted with each task
    ActivityManager.TaskDescription lastTaskDescription =
            new ActivityManager.TaskDescription();

    /** List of all activities in the task arranged in history order */
    final ArrayList<ActivityRecord> mActivities;

    /** Current stack */
    ActivityStack stack;

    /** Takes on same set of values as ActivityRecord.mActivityType */
    int taskType;

    /** Takes on same value as first root activity */
    boolean isPersistable = false;
    int maxRecents;

    /** Only used for persistable tasks, otherwise 0. The last time this task was moved. Used for
     * determining the order when restoring. Sign indicates whether last task movement was to front
     * (positive) or back (negative). Absolute value indicates time. */
    long mLastTimeMoved = System.currentTimeMillis();

    /** True if persistable, has changed, and has not yet been persisted */
    boolean needsPersisting = false;

    /** Indication of what to run next when task exits. Use ActivityRecord types.
     * ActivityRecord.APPLICATION_ACTIVITY_TYPE indicates to resume the task below this one in the
     * task stack. */
    private int mTaskToReturnTo = APPLICATION_ACTIVITY_TYPE;

    /** If original intent did not allow relinquishing task identity, save that information */
    boolean mNeverRelinquishIdentity = true;

    // Used in the unique case where we are clearing the task in order to reuse it. In that case we
    // do not want to delete the stack when the task goes empty.
    boolean mReuseTask = false;

    private Bitmap mLastThumbnail; // Last thumbnail captured for this item.
    private final File mLastThumbnailFile; // File containing last thubmnail.
    private final String mFilename;
    CharSequence lastDescription; // Last description captured for this item.

    final ActivityManagerService mService;

    TaskRecord(ActivityManagerService service, int _taskId, ActivityInfo info, Intent _intent,
            IVoiceInteractionSession _voiceSession, IVoiceInteractor _voiceInteractor) {
        mService = service;
        mFilename = String.valueOf(_taskId) + TASK_THUMBNAIL_SUFFIX + TaskPersister.IMAGE_EXTENSION;
        mLastThumbnailFile = new File(TaskPersister.sImagesDir, mFilename);
        taskId = _taskId;
        voiceSession = _voiceSession;
        voiceInteractor = _voiceInteractor;
        mActivities = new ArrayList<ActivityRecord>();
        setIntent(_intent, info);
    }

    TaskRecord(ActivityManagerService service, int _taskId, Intent _intent, Intent _affinityIntent,
            String _affinity, ComponentName _realActivity, ComponentName _origActivity,
            boolean _rootWasReset, boolean _askedCompatMode, int _taskType, int _userId,
            String _lastDescription, ArrayList<ActivityRecord> activities, long _firstActiveTime,
            long _lastActiveTime, long lastTimeMoved, boolean neverRelinquishIdentity,
            ActivityManager.TaskDescription _lastTaskDescription) {
        mService = service;
        mFilename = String.valueOf(_taskId) + TASK_THUMBNAIL_SUFFIX + TaskPersister.IMAGE_EXTENSION;
        mLastThumbnailFile = new File(TaskPersister.sImagesDir, mFilename);
        taskId = _taskId;
        intent = _intent;
        affinityIntent = _affinityIntent;
        affinity = _affinity;
        voiceSession = null;
        voiceInteractor = null;
        realActivity = _realActivity;
        origActivity = _origActivity;
        rootWasReset = _rootWasReset;
        askedCompatMode = _askedCompatMode;
        taskType = _taskType;
        mTaskToReturnTo = HOME_ACTIVITY_TYPE;
        userId = _userId;
        firstActiveTime = _firstActiveTime;
        lastActiveTime = _lastActiveTime;
        lastDescription = _lastDescription;
        mActivities = activities;
        mLastTimeMoved = lastTimeMoved;
        mNeverRelinquishIdentity = neverRelinquishIdentity;
        lastTaskDescription = _lastTaskDescription;
    }

    void touchActiveTime() {
        lastActiveTime = android.os.SystemClock.elapsedRealtime();
        if (firstActiveTime == 0) {
            firstActiveTime = lastActiveTime;
        }
    }

    long getInactiveDuration() {
        return android.os.SystemClock.elapsedRealtime() - lastActiveTime;
    }

    void setIntent(Intent _intent, ActivityInfo info) {
        if (intent == null) {
            mNeverRelinquishIdentity =
                    (info.flags & ActivityInfo.FLAG_RELINQUISH_TASK_IDENTITY) == 0;
        } else if (mNeverRelinquishIdentity) {
            return;
        }

        affinity = info.taskAffinity;
        stringName = null;

        if (info.targetActivity == null) {
            if (_intent != null) {
                // If this Intent has a selector, we want to clear it for the
                // recent task since it is not relevant if the user later wants
                // to re-launch the app.
                if (_intent.getSelector() != null || _intent.getSourceBounds() != null) {
                    _intent = new Intent(_intent);
                    _intent.setSelector(null);
                    _intent.setSourceBounds(null);
                }
            }
            if (ActivityManagerService.DEBUG_TASKS) Slog.v(ActivityManagerService.TAG,
                    "Setting Intent of " + this + " to " + _intent);
            intent = _intent;
            realActivity = _intent != null ? _intent.getComponent() : null;
            origActivity = null;
        } else {
            ComponentName targetComponent = new ComponentName(
                    info.packageName, info.targetActivity);
            if (_intent != null) {
                Intent targetIntent = new Intent(_intent);
                targetIntent.setComponent(targetComponent);
                targetIntent.setSelector(null);
                targetIntent.setSourceBounds(null);
                if (ActivityManagerService.DEBUG_TASKS) Slog.v(ActivityManagerService.TAG,
                        "Setting Intent of " + this + " to target " + targetIntent);
                intent = targetIntent;
                realActivity = targetComponent;
                origActivity = _intent.getComponent();
            } else {
                intent = null;
                realActivity = targetComponent;
                origActivity = new ComponentName(info.packageName, info.name);
            }
        }

        if (intent != null &&
                (intent.getFlags()&Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
            // Once we are set to an Intent with this flag, we count this
            // task as having a true root activity.
            rootWasReset = true;
        }

        userId = UserHandle.getUserId(info.applicationInfo.uid);
        creatorUid = info.applicationInfo.uid;
        if ((info.flags & ActivityInfo.FLAG_AUTO_REMOVE_FROM_RECENTS) != 0) {
            intent.addFlags(Intent.FLAG_ACTIVITY_AUTO_REMOVE_FROM_RECENTS);
        }
    }

    void setTaskToReturnTo(int taskToReturnTo) {
        mTaskToReturnTo = taskToReturnTo;
    }

    int getTaskToReturnTo() {
        return mTaskToReturnTo;
    }

    void setLastThumbnail(Bitmap thumbnail) {
        mLastThumbnail = thumbnail;
        if (thumbnail == null) {
            if (mLastThumbnailFile != null) {
                mLastThumbnailFile.delete();
            }
        } else {
            mService.mTaskPersister.saveImage(thumbnail, mFilename);
        }
    }

    void getLastThumbnail(TaskThumbnail thumbs) {
        thumbs.mainThumbnail = mLastThumbnail;
        thumbs.thumbnailFileDescriptor = null;
        if (mLastThumbnailFile.exists()) {
            try {
                thumbs.thumbnailFileDescriptor = ParcelFileDescriptor.open(mLastThumbnailFile,
                        ParcelFileDescriptor.MODE_READ_ONLY);
            } catch (IOException e) {
            }
        }
    }

    void freeLastThumbnail() {
        mLastThumbnail = null;
    }

    void disposeThumbnail() {
        mLastThumbnail = null;
        lastDescription = null;
    }

    /** Returns the intent for the root activity for this task */
    Intent getBaseIntent() {
        return intent != null ? intent : affinityIntent;
    }

    /** Returns the first non-finishing activity from the root. */
    ActivityRecord getRootActivity() {
        for (int i = 0; i < mActivities.size(); i++) {
            final ActivityRecord r = mActivities.get(i);
            if (r.finishing) {
                continue;
            }
            return r;
        }
        return null;
    }

    ActivityRecord getTopActivity() {
        for (int i = mActivities.size() - 1; i >= 0; --i) {
            final ActivityRecord r = mActivities.get(i);
            if (r.finishing) {
                continue;
            }
            return r;
        }
        return null;
    }

    ActivityRecord topRunningActivityLocked(ActivityRecord notTop) {
        for (int activityNdx = mActivities.size() - 1; activityNdx >= 0; --activityNdx) {
            ActivityRecord r = mActivities.get(activityNdx);
            if (!r.finishing && r != notTop && stack.okToShowLocked(r)) {
                return r;
            }
        }
        return null;
    }

    /** Call after activity movement or finish to make sure that frontOfTask is set correctly */
    final void setFrontOfTask() {
        boolean foundFront = false;
        final int numActivities = mActivities.size();
        for (int activityNdx = 0; activityNdx < numActivities; ++activityNdx) {
            final ActivityRecord r = mActivities.get(activityNdx);
            if (foundFront || r.finishing) {
                r.frontOfTask = false;
            } else {
                r.frontOfTask = true;
                // Set frontOfTask false for every following activity.
                foundFront = true;
            }
        }
        if (!foundFront && numActivities > 0) {
            // All activities of this task are finishing. As we ought to have a frontOfTask
            // activity, make the bottom activity front.
            mActivities.get(0).frontOfTask = true;
        }
    }

    /**
     * Reorder the history stack so that the passed activity is brought to the front.
     */
    final void moveActivityToFrontLocked(ActivityRecord newTop) {
        if (DEBUG_ADD_REMOVE) Slog.i(TAG, "Removing and adding activity " + newTop
            + " to stack at top", new RuntimeException("here").fillInStackTrace());

        mActivities.remove(newTop);
        mActivities.add(newTop);
        updateEffectiveIntent();

        setFrontOfTask();
    }

    void addActivityAtBottom(ActivityRecord r) {
        addActivityAtIndex(0, r);
    }

    void addActivityToTop(ActivityRecord r) {
        addActivityAtIndex(mActivities.size(), r);
    }

    void addActivityAtIndex(int index, ActivityRecord r) {
        // Remove r first, and if it wasn't already in the list and it's fullscreen, count it.
        if (!mActivities.remove(r) && r.fullscreen) {
            // Was not previously in list.
            numFullscreen++;
        }
        // Only set this based on the first activity
        if (mActivities.isEmpty()) {
            taskType = r.mActivityType;
            isPersistable = r.isPersistable();
            // Clamp to [1, 100].
            maxRecents = Math.min(Math.max(r.info.maxRecents, 1), 100);
        } else {
            // Otherwise make all added activities match this one.
            r.mActivityType = taskType;
        }
        mActivities.add(index, r);
        updateEffectiveIntent();
        if (r.isPersistable()) {
            mService.notifyTaskPersisterLocked(this, false);
        }
    }

    /** @return true if this was the last activity in the task */
    boolean removeActivity(ActivityRecord r) {
        if (mActivities.remove(r) && r.fullscreen) {
            // Was previously in list.
            numFullscreen--;
        }
        if (r.isPersistable()) {
            mService.notifyTaskPersisterLocked(this, false);
        }
        if (mActivities.isEmpty()) {
            return !mReuseTask;
        }
        updateEffectiveIntent();
        return false;
    }

    boolean autoRemoveFromRecents() {
        // We will automatically remove the task either if it has explicitly asked for
        // this, or it is empty and has never contained an activity that got shown to
        // the user.
        return (intent != null &&
                (intent.getFlags() & Intent.FLAG_ACTIVITY_AUTO_REMOVE_FROM_RECENTS) != 0) ||
                (mActivities.isEmpty() && !hasBeenVisible);
    }

    /**
     * Completely remove all activities associated with an existing
     * task starting at a specified index.
     */
    final void performClearTaskAtIndexLocked(int activityNdx) {
        int numActivities = mActivities.size();
        for ( ; activityNdx < numActivities; ++activityNdx) {
            final ActivityRecord r = mActivities.get(activityNdx);
            if (r.finishing) {
                continue;
            }
            if (stack == null) {
                // Task was restored from persistent storage.
                r.takeFromHistory();
                mActivities.remove(activityNdx);
                --activityNdx;
                --numActivities;
            } else if (stack.finishActivityLocked(r, Activity.RESULT_CANCELED, null, "clear",
                    false)) {
                --activityNdx;
                --numActivities;
            }
        }
    }

    /**
     * Completely remove all activities associated with an existing task.
     */
    final void performClearTaskLocked() {
        mReuseTask = true;
        performClearTaskAtIndexLocked(0);
        mReuseTask = false;
    }

    /**
     * Perform clear operation as requested by
     * {@link Intent#FLAG_ACTIVITY_CLEAR_TOP}: search from the top of the
     * stack to the given task, then look for
     * an instance of that activity in the stack and, if found, finish all
     * activities on top of it and return the instance.
     *
     * @param newR Description of the new activity being started.
     * @return Returns the old activity that should be continued to be used,
     * or null if none was found.
     */
    final ActivityRecord performClearTaskLocked(ActivityRecord newR, int launchFlags) {
        int numActivities = mActivities.size();
        for (int activityNdx = numActivities - 1; activityNdx >= 0; --activityNdx) {
            ActivityRecord r = mActivities.get(activityNdx);
            if (r.finishing) {
                continue;
            }
            if (r.realActivity.equals(newR.realActivity)) {
                // Here it is!  Now finish everything in front...
                final ActivityRecord ret = r;

                for (++activityNdx; activityNdx < numActivities; ++activityNdx) {
                    r = mActivities.get(activityNdx);
                    if (r.finishing) {
                        continue;
                    }
                    ActivityOptions opts = r.takeOptionsLocked();
                    if (opts != null) {
                        ret.updateOptionsLocked(opts);
                    }
                    if (stack.finishActivityLocked(r, Activity.RESULT_CANCELED, null, "clear",
                            false)) {
                        --activityNdx;
                        --numActivities;
                    }
                }

                // Finally, if this is a normal launch mode (that is, not
                // expecting onNewIntent()), then we will finish the current
                // instance of the activity so a new fresh one can be started.
                if (ret.launchMode == ActivityInfo.LAUNCH_MULTIPLE
                        && (launchFlags & Intent.FLAG_ACTIVITY_SINGLE_TOP) == 0) {
                    if (!ret.finishing) {
                        stack.finishActivityLocked(ret, Activity.RESULT_CANCELED, null,
                                "clear", false);
                        return null;
                    }
                }

                return ret;
            }
        }

        return null;
    }

    public TaskThumbnail getTaskThumbnailLocked() {
        if (stack != null) {
            final ActivityRecord resumedActivity = stack.mResumedActivity;
            if (resumedActivity != null && resumedActivity.task == this) {
                final Bitmap thumbnail = stack.screenshotActivities(resumedActivity);
                setLastThumbnail(thumbnail);
            }
        }
        final TaskThumbnail taskThumbnail = new TaskThumbnail();
        getLastThumbnail(taskThumbnail);
        return taskThumbnail;
    }

    public void removeTaskActivitiesLocked() {
        // Just remove the entire task.
        performClearTaskAtIndexLocked(0);
    }

    boolean isHomeTask() {
        return taskType == HOME_ACTIVITY_TYPE;
    }

    boolean isApplicationTask() {
        return taskType == APPLICATION_ACTIVITY_TYPE;
    }

    boolean isOverHomeStack() {
        return mTaskToReturnTo == HOME_ACTIVITY_TYPE || mTaskToReturnTo == RECENTS_ACTIVITY_TYPE;
    }

    /**
     * Find the activity in the history stack within the given task.  Returns
     * the index within the history at which it's found, or < 0 if not found.
     */
    final ActivityRecord findActivityInHistoryLocked(ActivityRecord r) {
        final ComponentName realActivity = r.realActivity;
        for (int activityNdx = mActivities.size() - 1; activityNdx >= 0; --activityNdx) {
            ActivityRecord candidate = mActivities.get(activityNdx);
            if (candidate.finishing) {
                continue;
            }
            if (candidate.realActivity.equals(realActivity)) {
                return candidate;
            }
        }
        return null;
    }

    /** Updates the last task description values. */
    void updateTaskDescription() {
        // Traverse upwards looking for any break between main task activities and
        // utility activities.
        int activityNdx;
        final int numActivities = mActivities.size();
        final boolean relinquish = numActivities == 0 ? false :
                (mActivities.get(0).info.flags & ActivityInfo.FLAG_RELINQUISH_TASK_IDENTITY) != 0;
        for (activityNdx = Math.min(numActivities, 1); activityNdx < numActivities;
                ++activityNdx) {
            final ActivityRecord r = mActivities.get(activityNdx);
            if (relinquish && (r.info.flags & ActivityInfo.FLAG_RELINQUISH_TASK_IDENTITY) == 0) {
                // This will be the top activity for determining taskDescription. Pre-inc to
                // overcome initial decrement below.
                ++activityNdx;
                break;
            }
            if (r.intent != null &&
                    (r.intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET) != 0) {
                break;
            }
        }
        if (activityNdx > 0) {
            // Traverse downwards starting below break looking for set label, icon.
            // Note that if there are activities in the task but none of them set the
            // recent activity values, then we do not fall back to the last set
            // values in the TaskRecord.
            String label = null;
            Bitmap icon = null;
            int colorPrimary = 0;
            for (--activityNdx; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = mActivities.get(activityNdx);
                if (r.taskDescription != null) {
                    if (label == null) {
                        label = r.taskDescription.getLabel();
                    }
                    if (icon == null) {
                        icon = r.taskDescription.getIcon();
                    }
                    if (colorPrimary == 0) {
                        colorPrimary = r.taskDescription.getPrimaryColor();

                    }
                }
            }
            lastTaskDescription = new ActivityManager.TaskDescription(label, icon, colorPrimary);
        }
    }

    int findEffectiveRootIndex() {
        int activityNdx;
        final int topActivityNdx = mActivities.size() - 1;
        for (activityNdx = 0; activityNdx < topActivityNdx; ++activityNdx) {
            final ActivityRecord r = mActivities.get(activityNdx);
            if (r.finishing) {
                continue;
            }
            if ((r.info.flags & ActivityInfo.FLAG_RELINQUISH_TASK_IDENTITY) == 0) {
                break;
            }
        }
        return activityNdx;
    }

    void updateEffectiveIntent() {
        final int effectiveRootIndex = findEffectiveRootIndex();
        final ActivityRecord r = mActivities.get(effectiveRootIndex);
        setIntent(r.intent, r.info);
    }

    void saveTaskDescription(ActivityManager.TaskDescription taskDescription,
            String iconFilename, XmlSerializer out) throws IOException {
        if (taskDescription != null) {
            final String label = taskDescription.getLabel();
            if (label != null) {
                out.attribute(null, ATTR_TASKDESCRIPTIONLABEL, label);
            }
            final int colorPrimary = taskDescription.getPrimaryColor();
            if (colorPrimary != 0) {
                out.attribute(null, ATTR_TASKDESCRIPTIONCOLOR, Integer.toHexString(colorPrimary));
            }
            final Bitmap icon = taskDescription.getIcon();
            if (icon != null) {
                mService.mTaskPersister.saveImage(icon, iconFilename);
            }
        }
    }

    static boolean readTaskDescriptionAttribute(ActivityManager.TaskDescription taskDescription,
            String attrName, String attrValue) {
        if (ATTR_TASKDESCRIPTIONLABEL.equals(attrName)) {
            taskDescription.setLabel(attrValue);
        } else if (ATTR_TASKDESCRIPTIONCOLOR.equals(attrName)) {
            taskDescription.setPrimaryColor((int) Long.parseLong(attrValue, 16));
        } else {
            return false;
        }
        return true;
    }

    void saveToXml(XmlSerializer out) throws IOException, XmlPullParserException {
        Slog.i(TAG, "Saving task=" + this);

        out.attribute(null, ATTR_TASKID, String.valueOf(taskId));
        if (realActivity != null) {
            out.attribute(null, ATTR_REALACTIVITY, realActivity.flattenToShortString());
        }
        if (origActivity != null) {
            out.attribute(null, ATTR_ORIGACTIVITY, origActivity.flattenToShortString());
        }
        if (affinity != null) {
            out.attribute(null, ATTR_AFFINITY, affinity);
        }
        out.attribute(null, ATTR_ROOTHASRESET, String.valueOf(rootWasReset));
        out.attribute(null, ATTR_ASKEDCOMPATMODE, String.valueOf(askedCompatMode));
        out.attribute(null, ATTR_USERID, String.valueOf(userId));
        out.attribute(null, ATTR_TASKTYPE, String.valueOf(taskType));
        out.attribute(null, ATTR_FIRSTACTIVETIME, String.valueOf(firstActiveTime));
        out.attribute(null, ATTR_LASTACTIVETIME, String.valueOf(lastActiveTime));
        out.attribute(null, ATTR_LASTTIMEMOVED, String.valueOf(mLastTimeMoved));
        out.attribute(null, ATTR_NEVERRELINQUISH, String.valueOf(mNeverRelinquishIdentity));
        if (lastDescription != null) {
            out.attribute(null, ATTR_LASTDESCRIPTION, lastDescription.toString());
        }

        if (lastTaskDescription != null) {
            saveTaskDescription(lastTaskDescription, String.valueOf(taskId) +
                    LAST_ACTIVITY_ICON_SUFFIX + lastActiveTime, out);
        }

        if (affinityIntent != null) {
            out.startTag(null, TAG_AFFINITYINTENT);
            affinityIntent.saveToXml(out);
            out.endTag(null, TAG_AFFINITYINTENT);
        }

        out.startTag(null, TAG_INTENT);
        intent.saveToXml(out);
        out.endTag(null, TAG_INTENT);

        final ArrayList<ActivityRecord> activities = mActivities;
        final int numActivities = activities.size();
        for (int activityNdx = 0; activityNdx < numActivities; ++activityNdx) {
            final ActivityRecord r = activities.get(activityNdx);
            if (r.info.persistableMode == ActivityInfo.PERSIST_ROOT_ONLY || !r.isPersistable() ||
                    ((r.intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET) != 0) &&
                            activityNdx > 0) {
                // Stop at first non-persistable or first break in task (CLEAR_WHEN_TASK_RESET).
                break;
            }
            out.startTag(null, TAG_ACTIVITY);
            r.saveToXml(out);
            out.endTag(null, TAG_ACTIVITY);
        }
    }

    static TaskRecord restoreFromXml(XmlPullParser in, ActivityStackSupervisor stackSupervisor)
            throws IOException, XmlPullParserException {
        Intent intent = null;
        Intent affinityIntent = null;
        ArrayList<ActivityRecord> activities = new ArrayList<ActivityRecord>();
        ComponentName realActivity = null;
        ComponentName origActivity = null;
        String affinity = null;
        boolean rootHasReset = false;
        boolean askedCompatMode = false;
        int taskType = ActivityRecord.APPLICATION_ACTIVITY_TYPE;
        int userId = 0;
        String lastDescription = null;
        long firstActiveTime = -1;
        long lastActiveTime = -1;
        long lastTimeOnTop = 0;
        boolean neverRelinquishIdentity = true;
        int taskId = -1;
        final int outerDepth = in.getDepth();
        ActivityManager.TaskDescription taskDescription = new ActivityManager.TaskDescription();

        for (int attrNdx = in.getAttributeCount() - 1; attrNdx >= 0; --attrNdx) {
            final String attrName = in.getAttributeName(attrNdx);
            final String attrValue = in.getAttributeValue(attrNdx);
            if (TaskPersister.DEBUG) Slog.d(TaskPersister.TAG, "TaskRecord: attribute name=" +
                    attrName + " value=" + attrValue);
            if (ATTR_TASKID.equals(attrName)) {
                taskId = Integer.valueOf(attrValue);
            } else if (ATTR_REALACTIVITY.equals(attrName)) {
                realActivity = ComponentName.unflattenFromString(attrValue);
            } else if (ATTR_ORIGACTIVITY.equals(attrName)) {
                origActivity = ComponentName.unflattenFromString(attrValue);
            } else if (ATTR_AFFINITY.equals(attrName)) {
                affinity = attrValue;
            } else if (ATTR_ROOTHASRESET.equals(attrName)) {
                rootHasReset = Boolean.valueOf(attrValue);
            } else if (ATTR_ASKEDCOMPATMODE.equals(attrName)) {
                askedCompatMode = Boolean.valueOf(attrValue);
            } else if (ATTR_USERID.equals(attrName)) {
                userId = Integer.valueOf(attrValue);
            } else if (ATTR_TASKTYPE.equals(attrName)) {
                taskType = Integer.valueOf(attrValue);
            } else if (ATTR_FIRSTACTIVETIME.equals(attrName)) {
                firstActiveTime = Long.valueOf(attrValue);
            } else if (ATTR_LASTACTIVETIME.equals(attrName)) {
                lastActiveTime = Long.valueOf(attrValue);
            } else if (ATTR_LASTDESCRIPTION.equals(attrName)) {
                lastDescription = attrValue;
            } else if (ATTR_LASTTIMEMOVED.equals(attrName)) {
                lastTimeOnTop = Long.valueOf(attrValue);
            } else if (ATTR_NEVERRELINQUISH.equals(attrName)) {
                neverRelinquishIdentity = Boolean.valueOf(attrValue);
            } else if (readTaskDescriptionAttribute(taskDescription, attrName, attrValue)) {
                // Completed in TaskPersister.readTaskDescriptionAttribute()
            } else {
                Slog.w(TAG, "TaskRecord: Unknown attribute=" + attrName);
            }
        }

        int event;
        while (((event = in.next()) != XmlPullParser.END_DOCUMENT) &&
                (event != XmlPullParser.END_TAG || in.getDepth() < outerDepth)) {
            if (event == XmlPullParser.START_TAG) {
                final String name = in.getName();
                if (TaskPersister.DEBUG) Slog.d(TaskPersister.TAG, "TaskRecord: START_TAG name=" +
                        name);
                if (TAG_AFFINITYINTENT.equals(name)) {
                    affinityIntent = Intent.restoreFromXml(in);
                } else if (TAG_INTENT.equals(name)) {
                    intent = Intent.restoreFromXml(in);
                } else if (TAG_ACTIVITY.equals(name)) {
                    ActivityRecord activity =
                            ActivityRecord.restoreFromXml(in, taskId, stackSupervisor);
                    if (TaskPersister.DEBUG) Slog.d(TaskPersister.TAG, "TaskRecord: activity=" +
                            activity);
                    if (activity != null) {
                        activities.add(activity);
                    }
                } else {
                    Slog.e(TAG, "restoreTask: Unexpected name=" + name);
                    XmlUtils.skipCurrentTag(in);
                }
            }
        }

        if (lastActiveTime >= 0) {
            taskDescription.setIcon(TaskPersister.restoreImage(String.valueOf(taskId) +
                    LAST_ACTIVITY_ICON_SUFFIX + lastActiveTime + TaskPersister.IMAGE_EXTENSION));
        }

        final TaskRecord task = new TaskRecord(stackSupervisor.mService, taskId, intent,
                affinityIntent, affinity, realActivity, origActivity, rootHasReset,
                askedCompatMode, taskType, userId, lastDescription, activities, firstActiveTime,
                lastActiveTime, lastTimeOnTop, neverRelinquishIdentity, taskDescription);

        for (int activityNdx = activities.size() - 1; activityNdx >=0; --activityNdx) {
            activities.get(activityNdx).task = task;
        }

        Slog.i(TAG, "Restored task=" + task);
        return task;
    }

    void dump(PrintWriter pw, String prefix) {
        if (rootWasReset || userId != 0 || numFullscreen != 0) {
            pw.print(prefix); pw.print(" rootWasReset="); pw.print(rootWasReset);
                    pw.print(" userId="); pw.print(userId);
                    pw.print(" taskType="); pw.print(taskType);
                    pw.print(" numFullscreen="); pw.print(numFullscreen);
                    pw.print(" mTaskToReturnTo="); pw.println(mTaskToReturnTo);
        }
        if (affinity != null) {
            pw.print(prefix); pw.print("affinity="); pw.println(affinity);
        }
        if (voiceSession != null || voiceInteractor != null) {
            pw.print(prefix); pw.print("VOICE: session=0x");
            pw.print(Integer.toHexString(System.identityHashCode(voiceSession)));
            pw.print(" interactor=0x");
            pw.println(Integer.toHexString(System.identityHashCode(voiceInteractor)));
        }
        if (intent != null) {
            StringBuilder sb = new StringBuilder(128);
            sb.append(prefix); sb.append("intent={");
            intent.toShortString(sb, false, true, false, true);
            sb.append('}');
            pw.println(sb.toString());
        }
        if (affinityIntent != null) {
            StringBuilder sb = new StringBuilder(128);
            sb.append(prefix); sb.append("affinityIntent={");
            affinityIntent.toShortString(sb, false, true, false, true);
            sb.append('}');
            pw.println(sb.toString());
        }
        if (origActivity != null) {
            pw.print(prefix); pw.print("origActivity=");
            pw.println(origActivity.flattenToShortString());
        }
        if (realActivity != null) {
            pw.print(prefix); pw.print("realActivity=");
            pw.println(realActivity.flattenToShortString());
        }
        pw.print(prefix); pw.print("Activities="); pw.println(mActivities);
        if (!askedCompatMode) {
            pw.print(prefix); pw.print("askedCompatMode="); pw.println(askedCompatMode);
        }
        pw.print(prefix); pw.print("lastThumbnail="); pw.print(mLastThumbnail);
                pw.print(" lastThumbnailFile="); pw.print(mLastThumbnailFile);
                pw.print(" lastDescription="); pw.println(lastDescription);
        pw.print(prefix); pw.print("hasBeenVisible="); pw.print(hasBeenVisible);
                pw.print(" firstActiveTime="); pw.print(lastActiveTime);
                pw.print(" lastActiveTime="); pw.print(lastActiveTime);
                pw.print(" (inactive for ");
                pw.print((getInactiveDuration()/1000)); pw.println("s)");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        if (stringName != null) {
            sb.append(stringName);
            sb.append(" U=");
            sb.append(userId);
            sb.append(" sz=");
            sb.append(mActivities.size());
            sb.append('}');
            return sb.toString();
        }
        sb.append("TaskRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" #");
        sb.append(taskId);
        if (affinity != null) {
            sb.append(" A=");
            sb.append(affinity);
        } else if (intent != null) {
            sb.append(" I=");
            sb.append(intent.getComponent().flattenToShortString());
        } else if (affinityIntent != null) {
            sb.append(" aI=");
            sb.append(affinityIntent.getComponent().flattenToShortString());
        } else {
            sb.append(" ??");
        }
        stringName = sb.toString();
        return toString();
    }
}