/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.hdmi;

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.hdmi.HdmiCecDeviceInfo;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiHotplugEvent;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.hdmi.HdmiTvClient;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.hdmi.IHdmiControlService;
import android.hardware.hdmi.IHdmiDeviceEventListener;
import android.hardware.hdmi.IHdmiHotplugEventListener;
import android.hardware.hdmi.IHdmiInputChangeListener;
import android.hardware.hdmi.IHdmiSystemAudioModeChangeListener;
import android.hardware.hdmi.IHdmiVendorCommandListener;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;
import com.android.server.hdmi.HdmiCecController.AllocateAddressCallback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Provides a service for sending and processing HDMI control messages,
 * HDMI-CEC and MHL control command, and providing the information on both standard.
 */
public final class HdmiControlService extends SystemService {
    private static final String TAG = "HdmiControlService";

    static final String PERMISSION = "android.permission.HDMI_CEC";

    /**
     * Interface to report send result.
     */
    interface SendMessageCallback {
        /**
         * Called when {@link HdmiControlService#sendCecCommand} is completed.
         *
         * @param error result of send request.
         * @see {@link #SEND_RESULT_SUCCESS}
         * @see {@link #SEND_RESULT_NAK}
         * @see {@link #SEND_RESULT_FAILURE}
         */
        void onSendCompleted(int error);
    }

    /**
     * Interface to get a list of available logical devices.
     */
    interface DevicePollingCallback {
        /**
         * Called when device polling is finished.
         *
         * @param ackedAddress a list of logical addresses of available devices
         */
        void onPollingFinished(List<Integer> ackedAddress);
    }

    private class PowerStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_OFF:
                    if (isPowerOnOrTransient()) {
                        onStandby();
                    }
                    break;
                case Intent.ACTION_SCREEN_ON:
                    if (isPowerStandbyOrTransient()) {
                        onWakeUp();
                    }
                    break;
            }
        }
    }

    // A thread to handle synchronous IO of CEC and MHL control service.
    // Since all of CEC and MHL HAL interfaces processed in short time (< 200ms)
    // and sparse call it shares a thread to handle IO operations.
    private final HandlerThread mIoThread = new HandlerThread("Hdmi Control Io Thread");

    // Used to synchronize the access to the service.
    private final Object mLock = new Object();

    // Type of logical devices hosted in the system. Stored in the unmodifiable list.
    private final List<Integer> mLocalDevices;

    // List of listeners registered by callers that want to get notified of
    // hotplug events.
    @GuardedBy("mLock")
    private final ArrayList<IHdmiHotplugEventListener> mHotplugEventListeners = new ArrayList<>();

    // List of records for hotplug event listener to handle the the caller killed in action.
    @GuardedBy("mLock")
    private final ArrayList<HotplugEventListenerRecord> mHotplugEventListenerRecords =
            new ArrayList<>();

    // List of listeners registered by callers that want to get notified of
    // device status events.
    @GuardedBy("mLock")
    private final ArrayList<IHdmiDeviceEventListener> mDeviceEventListeners = new ArrayList<>();

    // List of records for device event listener to handle the the caller killed in action.
    @GuardedBy("mLock")
    private final ArrayList<DeviceEventListenerRecord> mDeviceEventListenerRecords =
            new ArrayList<>();

    // List of records for vendor command listener to handle the the caller killed in action.
    @GuardedBy("mLock")
    private final ArrayList<VendorCommandListenerRecord> mVendorCommandListenerRecords =
            new ArrayList<>();

    @GuardedBy("mLock")
    private IHdmiInputChangeListener mInputChangeListener;

    @GuardedBy("mLock")
    private InputChangeListenerRecord mInputChangeListenerRecord;

    // Set to true while HDMI control is enabled. If set to false, HDMI-CEC/MHL protocol
    // handling will be disabled and no request will be handled.
    @GuardedBy("mLock")
    private boolean mHdmiControlEnabled;

    // Set to true while the service is in normal mode. While set to false, no input change is
    // allowed. Used for situations where input change can confuse users such as channel auto-scan,
    // system upgrade, etc., a.k.a. "prohibit mode".
    @GuardedBy("mLock")
    private boolean mProhibitMode;

    // List of listeners registered by callers that want to get notified of
    // system audio mode changes.
    private final ArrayList<IHdmiSystemAudioModeChangeListener>
            mSystemAudioModeChangeListeners = new ArrayList<>();
    // List of records for system audio mode change to handle the the caller killed in action.
    private final ArrayList<SystemAudioModeChangeListenerRecord>
            mSystemAudioModeChangeListenerRecords = new ArrayList<>();

    // Handler used to run a task in service thread.
    private final Handler mHandler = new Handler();

    @Nullable
    private HdmiCecController mCecController;

    @Nullable
    private HdmiMhlController mMhlController;

    // HDMI port information. Stored in the unmodifiable list to keep the static information
    // from being modified.
    private List<HdmiPortInfo> mPortInfo;

    private final PowerStateReceiver mPowerStateReceiver = new PowerStateReceiver();

    @ServiceThreadOnly
    private int mPowerStatus = HdmiControlManager.POWER_STATUS_STANDBY;

    @ServiceThreadOnly
    private boolean mStandbyMessageReceived = false;

    public HdmiControlService(Context context) {
        super(context);
        mLocalDevices = HdmiUtils.asImmutableList(getContext().getResources().getIntArray(
                com.android.internal.R.array.config_hdmiCecLogicalDeviceType));
    }

    @Override
    public void onStart() {
        mIoThread.start();
        mPowerStatus = HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON;
        mCecController = HdmiCecController.create(this);

        if (mCecController != null) {
            // TODO: Remove this as soon as OEM's HAL implementation is corrected.
            mCecController.setOption(HdmiTvClient.OPTION_CEC_ENABLE,
                    HdmiTvClient.ENABLED);

            mCecController.setOption(HdmiTvClient.OPTION_CEC_SERVICE_CONTROL,
                    HdmiTvClient.ENABLED);
            initializeLocalDevices(mLocalDevices);
        } else {
            Slog.i(TAG, "Device does not support HDMI-CEC.");
        }

        mMhlController = HdmiMhlController.create(this);
        if (mMhlController == null) {
            Slog.i(TAG, "Device does not support MHL-control.");
        }
        mPortInfo = initPortInfo();
        publishBinderService(Context.HDMI_CONTROL_SERVICE, new BinderService());

        // Register broadcast receiver for power state change.
        if (mCecController != null || mMhlController != null) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            getContext().registerReceiver(mPowerStateReceiver, filter);
        }

        // TODO: Read the preference for SystemAudioMode and initialize mSystemAudioMode and
        // start to monitor the preference value and invoke SystemAudioActionFromTv if needed.
        mHdmiControlEnabled = true;
        // TODO: Get control flag from persistent storage
        mProhibitMode = false;
    }

    @ServiceThreadOnly
    private void initializeLocalDevices(final List<Integer> deviceTypes) {
        assertRunOnServiceThread();
        // A container for [Logical Address, Local device info].
        final SparseArray<HdmiCecLocalDevice> devices = new SparseArray<>();
        final SparseIntArray finished = new SparseIntArray();
        mCecController.clearLogicalAddress();
        for (int type : deviceTypes) {
            final HdmiCecLocalDevice localDevice = HdmiCecLocalDevice.create(this, type);
            localDevice.init();
            mCecController.allocateLogicalAddress(type,
                    localDevice.getPreferredAddress(), new AllocateAddressCallback() {
                @Override
                public void onAllocated(int deviceType, int logicalAddress) {
                    if (logicalAddress == Constants.ADDR_UNREGISTERED) {
                        Slog.e(TAG, "Failed to allocate address:[device_type:" + deviceType + "]");
                    } else {
                        HdmiCecDeviceInfo deviceInfo = createDeviceInfo(logicalAddress, deviceType);
                        localDevice.setDeviceInfo(deviceInfo);
                        mCecController.addLocalDevice(deviceType, localDevice);
                        mCecController.addLogicalAddress(logicalAddress);
                        devices.append(logicalAddress, localDevice);
                    }
                    finished.append(deviceType, logicalAddress);

                    // Address allocation completed for all devices. Notify each device.
                    if (deviceTypes.size() == finished.size()) {
                        if (mPowerStatus == HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON) {
                            mPowerStatus = HdmiControlManager.POWER_STATUS_ON;
                        }
                        notifyAddressAllocated(devices);
                    }
                }
            });
        }
    }

    @ServiceThreadOnly
    private void notifyAddressAllocated(SparseArray<HdmiCecLocalDevice> devices) {
        assertRunOnServiceThread();
        for (int i = 0; i < devices.size(); ++i) {
            int address = devices.keyAt(i);
            HdmiCecLocalDevice device = devices.valueAt(i);
            device.handleAddressAllocated(address);
        }
    }

    // Initialize HDMI port information. Combine the information from CEC and MHL HAL and
    // keep them in one place.
    @ServiceThreadOnly
    private List<HdmiPortInfo> initPortInfo() {
        assertRunOnServiceThread();
        HdmiPortInfo[] cecPortInfo = null;

        // CEC HAL provides majority of the info while MHL does only MHL support flag for
        // each port. Return empty array if CEC HAL didn't provide the info.
        if (mCecController != null) {
            cecPortInfo = mCecController.getPortInfos();
        }
        if (cecPortInfo == null) {
            return Collections.emptyList();
        }

        HdmiPortInfo[] mhlPortInfo = new HdmiPortInfo[0];
        if (mMhlController != null) {
            // TODO: Implement plumbing logic to get MHL port information.
            // mhlPortInfo = mMhlController.getPortInfos();
        }

        // Use the id (port number) to find the matched info between CEC and MHL to combine them
        // into one. Leave the field `mhlSupported` to false if matched MHL entry is not found.
        ArrayList<HdmiPortInfo> result = new ArrayList<>(cecPortInfo.length);
        for (int i = 0; i < cecPortInfo.length; ++i) {
            HdmiPortInfo cec = cecPortInfo[i];
            int id = cec.getId();
            boolean mhlInfoFound = false;
            for (HdmiPortInfo mhl : mhlPortInfo) {
                if (id == mhl.getId()) {
                    result.add(new HdmiPortInfo(id, cec.getType(), cec.getAddress(),
                            cec.isCecSupported(), mhl.isMhlSupported(), cec.isArcSupported()));
                    mhlInfoFound = true;
                    break;
                }
            }
            if (!mhlInfoFound) {
                result.add(cec);
            }
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * Returns HDMI port information for the given port id.
     *
     * @param portId HDMI port id
     * @return {@link HdmiPortInfo} for the given port
     */
    HdmiPortInfo getPortInfo(int portId) {
        // mPortInfo is an unmodifiable list and the only reference to its inner list.
        // No lock is necessary.
        for (HdmiPortInfo info : mPortInfo) {
            if (portId == info.getId()) {
                return info;
            }
        }
        return null;
    }

    /**
     * Returns the routing path (physical address) of the HDMI port for the given
     * port id.
     */
    int portIdToPath(int portId) {
        HdmiPortInfo portInfo = getPortInfo(portId);
        if (portInfo == null) {
            Slog.e(TAG, "Cannot find the port info: " + portId);
            return Constants.INVALID_PHYSICAL_ADDRESS;
        }
        return portInfo.getAddress();
    }

    /**
     * Returns the id of HDMI port located at the top of the hierarchy of
     * the specified routing path. For the routing path 0x1220 (1.2.2.0), for instance,
     * the port id to be returned is the ID associated with the port address
     * 0x1000 (1.0.0.0) which is the topmost path of the given routing path.
     */
    int pathToPortId(int path) {
        int portAddress = path & Constants.ROUTING_PATH_TOP_MASK;
        for (HdmiPortInfo info : mPortInfo) {
            if (portAddress == info.getAddress()) {
                return info.getId();
            }
        }
        return Constants.INVALID_PORT_ID;
    }

    /**
     * Returns {@link Looper} for IO operation.
     *
     * <p>Declared as package-private.
     */
    Looper getIoLooper() {
        return mIoThread.getLooper();
    }

    /**
     * Returns {@link Looper} of main thread. Use this {@link Looper} instance
     * for tasks that are running on main service thread.
     *
     * <p>Declared as package-private.
     */
    Looper getServiceLooper() {
        return mHandler.getLooper();
    }

    /**
     * Returns physical address of the device.
     */
    int getPhysicalAddress() {
        return mCecController.getPhysicalAddress();
    }

    /**
     * Returns vendor id of CEC service.
     */
    int getVendorId() {
        return mCecController.getVendorId();
    }

    @ServiceThreadOnly
    HdmiCecDeviceInfo getDeviceInfo(int logicalAddress) {
        assertRunOnServiceThread();
        HdmiCecLocalDeviceTv tv = tv();
        if (tv == null) {
            return null;
        }
        return tv.getDeviceInfo(logicalAddress);
    }

    /**
     * Returns version of CEC.
     */
    int getCecVersion() {
        return mCecController.getVersion();
    }

    /**
     * Whether a device of the specified physical address is connected to ARC enabled port.
     */
    boolean isConnectedToArcPort(int physicalAddress) {
        for (HdmiPortInfo portInfo : mPortInfo) {
            if (hasSameTopPort(portInfo.getAddress(), physicalAddress)
                    && portInfo.isArcSupported()) {
                return true;
            }
        }
        return false;
    }

    void runOnServiceThread(Runnable runnable) {
        mHandler.post(runnable);
    }

    void runOnServiceThreadAtFrontOfQueue(Runnable runnable) {
        mHandler.postAtFrontOfQueue(runnable);
    }

    private void assertRunOnServiceThread() {
        if (Looper.myLooper() != mHandler.getLooper()) {
            throw new IllegalStateException("Should run on service thread.");
        }
    }

    /**
     * Transmit a CEC command to CEC bus.
     *
     * @param command CEC command to send out
     * @param callback interface used to the result of send command
     */
    @ServiceThreadOnly
    void sendCecCommand(HdmiCecMessage command, @Nullable SendMessageCallback callback) {
        assertRunOnServiceThread();
        mCecController.sendCommand(command, callback);
    }

    @ServiceThreadOnly
    void sendCecCommand(HdmiCecMessage command) {
        assertRunOnServiceThread();
        mCecController.sendCommand(command, null);
    }

    @ServiceThreadOnly
    boolean handleCecCommand(HdmiCecMessage message) {
        assertRunOnServiceThread();
        return dispatchMessageToLocalDevice(message);
    }

    void setAudioReturnChannel(boolean enabled) {
        mCecController.setAudioReturnChannel(enabled);
    }

    @ServiceThreadOnly
    private boolean dispatchMessageToLocalDevice(HdmiCecMessage message) {
        assertRunOnServiceThread();
        for (HdmiCecLocalDevice device : mCecController.getLocalDeviceList()) {
            if (device.dispatchMessage(message)
                    && message.getDestination() != Constants.ADDR_BROADCAST) {
                return true;
            }
        }

        if (message.getDestination() != Constants.ADDR_BROADCAST) {
            Slog.w(TAG, "Unhandled cec command:" + message);
        }
        return false;
    }

    /**
     * Called when a new hotplug event is issued.
     *
     * @param portNo hdmi port number where hot plug event issued.
     * @param connected whether to be plugged in or not
     */
    @ServiceThreadOnly
    void onHotplug(int portNo, boolean connected) {
        assertRunOnServiceThread();
        for (HdmiCecLocalDevice device : mCecController.getLocalDeviceList()) {
            device.onHotplug(portNo, connected);
        }
        announceHotplugEvent(portNo, connected);
    }

    /**
     * Poll all remote devices. It sends &lt;Polling Message&gt; to all remote
     * devices.
     *
     * @param callback an interface used to get a list of all remote devices' address
     * @param sourceAddress a logical address of source device where sends polling message
     * @param pickStrategy strategy how to pick polling candidates
     * @param retryCount the number of retry used to send polling message to remote devices
     * @throw IllegalArgumentException if {@code pickStrategy} is invalid value
     */
    @ServiceThreadOnly
    void pollDevices(DevicePollingCallback callback, int sourceAddress, int pickStrategy,
            int retryCount) {
        assertRunOnServiceThread();
        mCecController.pollDevices(callback, sourceAddress, checkPollStrategy(pickStrategy),
                retryCount);
    }

    private int checkPollStrategy(int pickStrategy) {
        int strategy = pickStrategy & Constants.POLL_STRATEGY_MASK;
        if (strategy == 0) {
            throw new IllegalArgumentException("Invalid poll strategy:" + pickStrategy);
        }
        int iterationStrategy = pickStrategy & Constants.POLL_ITERATION_STRATEGY_MASK;
        if (iterationStrategy == 0) {
            throw new IllegalArgumentException("Invalid iteration strategy:" + pickStrategy);
        }
        return strategy | iterationStrategy;
    }

    List<HdmiCecLocalDevice> getAllLocalDevices() {
        assertRunOnServiceThread();
        return mCecController.getLocalDeviceList();
    }

    Object getServiceLock() {
        return mLock;
    }

    void setAudioStatus(boolean mute, int volume) {
        // TODO: Hook up with AudioManager.
    }

    void announceSystemAudioModeChange(boolean enabled) {
        for (IHdmiSystemAudioModeChangeListener listener : mSystemAudioModeChangeListeners) {
            invokeSystemAudioModeChange(listener, enabled);
        }
    }

    private HdmiCecDeviceInfo createDeviceInfo(int logicalAddress, int deviceType) {
        // TODO: find better name instead of model name.
        String displayName = Build.MODEL;
        return new HdmiCecDeviceInfo(logicalAddress,
                getPhysicalAddress(), deviceType, getVendorId(), displayName);
    }

    // Record class that monitors the event of the caller of being killed. Used to clean up
    // the listener list and record list accordingly.
    private final class HotplugEventListenerRecord implements IBinder.DeathRecipient {
        private final IHdmiHotplugEventListener mListener;

        public HotplugEventListenerRecord(IHdmiHotplugEventListener listener) {
            mListener = listener;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                mHotplugEventListenerRecords.remove(this);
                mHotplugEventListeners.remove(mListener);
            }
        }
    }

    private final class DeviceEventListenerRecord implements IBinder.DeathRecipient {
        private final IHdmiDeviceEventListener mListener;

        public DeviceEventListenerRecord(IHdmiDeviceEventListener listener) {
            mListener = listener;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                mDeviceEventListenerRecords.remove(this);
                mDeviceEventListeners.remove(mListener);
            }
        }
    }

    private final class SystemAudioModeChangeListenerRecord implements IBinder.DeathRecipient {
        private final IHdmiSystemAudioModeChangeListener mListener;

        public SystemAudioModeChangeListenerRecord(IHdmiSystemAudioModeChangeListener listener) {
            mListener = listener;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                mSystemAudioModeChangeListenerRecords.remove(this);
                mSystemAudioModeChangeListeners.remove(mListener);
            }
        }
    }

    class VendorCommandListenerRecord implements IBinder.DeathRecipient {
        private final IHdmiVendorCommandListener mListener;
        private final int mDeviceType;

        public VendorCommandListenerRecord(IHdmiVendorCommandListener listener, int deviceType) {
            mListener = listener;
            mDeviceType = deviceType;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                mVendorCommandListenerRecords.remove(this);
            }
        }
    }

    private void enforceAccessPermission() {
        getContext().enforceCallingOrSelfPermission(PERMISSION, TAG);
    }

    private final class BinderService extends IHdmiControlService.Stub {
        @Override
        public int[] getSupportedTypes() {
            enforceAccessPermission();
            // mLocalDevices is an unmodifiable list - no lock necesary.
            int[] localDevices = new int[mLocalDevices.size()];
            for (int i = 0; i < localDevices.length; ++i) {
                localDevices[i] = mLocalDevices.get(i);
            }
            return localDevices;
        }

        @Override
        public void deviceSelect(final int logicalAddress, final IHdmiControlCallback callback) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiCecLocalDeviceTv tv = tv();
                    if (tv == null) {
                        Slog.w(TAG, "Local tv device not available");
                        invokeCallback(callback, HdmiControlManager.RESULT_SOURCE_NOT_AVAILABLE);
                        return;
                    }
                    tv.deviceSelect(logicalAddress, callback);
                }
            });
        }

        @Override
        public void portSelect(final int portId, final IHdmiControlCallback callback) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiCecLocalDeviceTv tv = tv();
                    if (tv == null) {
                        Slog.w(TAG, "Local tv device not available");
                        invokeCallback(callback, HdmiControlManager.RESULT_SOURCE_NOT_AVAILABLE);
                        return;
                    }
                    tv.doManualPortSwitching(portId, callback);
                }
            });
        }

        @Override
        public void sendKeyEvent(final int keyCode, final boolean isPressed) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    // TODO: sendKeyEvent is for TV device only for now. Allow other
                    //       local devices of different types to use this as well.
                    HdmiCecLocalDeviceTv tv = tv();
                    if (tv == null) {
                        Slog.w(TAG, "Local tv device not available");
                        return;
                    }
                    tv.sendKeyEvent(keyCode, isPressed);
                }
            });
        }

        @Override
        public void oneTouchPlay(final IHdmiControlCallback callback) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiControlService.this.oneTouchPlay(callback);
                }
            });
        }

        @Override
        public void queryDisplayStatus(final IHdmiControlCallback callback) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiControlService.this.queryDisplayStatus(callback);
                }
            });
        }

        @Override
        public void addHotplugEventListener(final IHdmiHotplugEventListener listener) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiControlService.this.addHotplugEventListener(listener);
                }
            });
        }

        @Override
        public void removeHotplugEventListener(final IHdmiHotplugEventListener listener) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiControlService.this.removeHotplugEventListener(listener);
                }
            });
        }

        @Override
        public void addDeviceEventListener(final IHdmiDeviceEventListener listener) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiControlService.this.addDeviceEventListener(listener);
                }
            });
        }

        @Override
        public List<HdmiPortInfo> getPortInfo() {
            enforceAccessPermission();
            return mPortInfo;
        }

        @Override
        public boolean canChangeSystemAudioMode() {
            enforceAccessPermission();
            HdmiCecLocalDeviceTv tv = tv();
            if (tv == null) {
                return false;
            }
            return tv.hasSystemAudioDevice();
        }

        @Override
        public boolean getSystemAudioMode() {
            enforceAccessPermission();
            HdmiCecLocalDeviceTv tv = tv();
            if (tv == null) {
                return false;
            }
            return tv.getSystemAudioMode();
        }

        @Override
        public void setSystemAudioMode(final boolean enabled, final IHdmiControlCallback callback) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiCecLocalDeviceTv tv = tv();
                    if (tv == null) {
                        Slog.w(TAG, "Local tv device not available");
                        invokeCallback(callback, HdmiControlManager.RESULT_SOURCE_NOT_AVAILABLE);
                        return;
                    }
                    tv.changeSystemAudioMode(enabled, callback);
                }
            });
        }

        @Override
        public void addSystemAudioModeChangeListener(
                final IHdmiSystemAudioModeChangeListener listener) {
            enforceAccessPermission();
            HdmiControlService.this.addSystemAudioModeChangeListner(listener);
        }

        @Override
        public void removeSystemAudioModeChangeListener(
                final IHdmiSystemAudioModeChangeListener listener) {
            enforceAccessPermission();
            HdmiControlService.this.removeSystemAudioModeChangeListener(listener);
        }

        @Override
        public void setInputChangeListener(final IHdmiInputChangeListener listener) {
            enforceAccessPermission();
            HdmiControlService.this.setInputChangeListener(listener);
        }

        @Override
        public List<HdmiCecDeviceInfo> getInputDevices() {
            enforceAccessPermission();
            // No need to hold the lock for obtaining TV device as the local device instance
            // is preserved while the HDMI control is enabled.
            HdmiCecLocalDeviceTv tv = tv();
            if (tv == null) {
                return Collections.emptyList();
            }
            return tv.getSafeExternalInputs();
        }

        @Override
        public void setControlEnabled(final boolean enabled) {
            enforceAccessPermission();
            synchronized (mLock) {
                mHdmiControlEnabled = enabled;
            }
            // TODO: Stop the running actions when disabled, and start
            //       address allocation/device discovery when enabled.
            if (!enabled) {
                return;
            }
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiCecLocalDeviceTv tv = tv();
                    if (tv == null) {
                        return;
                    }
                    int value = enabled ? HdmiTvClient.ENABLED : HdmiTvClient.DISABLED;
                    mCecController.setOption(HdmiTvClient.OPTION_CEC_ENABLE, value);
                    if (mMhlController != null) {
                        mMhlController.setOption(HdmiTvClient.OPTION_MHL_ENABLE, value);
                    }
                    tv.launchRoutingControl(false);
                }
            });
        }

        @Override
        public void setSystemAudioVolume(final int oldIndex, final int newIndex,
                final int maxIndex) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiCecLocalDeviceTv tv = tv();
                    if (tv == null) {
                        Slog.w(TAG, "Local tv device not available");
                        return;
                    }
                    tv.changeVolume(oldIndex, newIndex - oldIndex, maxIndex);
                }
            });
        }

        @Override
        public void setSystemAudioMute(final boolean mute) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiCecLocalDeviceTv tv = tv();
                    if (tv == null) {
                        Slog.w(TAG, "Local tv device not available");
                        return;
                    }
                    tv.changeMute(mute);
                }
            });
        }

        @Override
        public void setArcMode(final boolean enabled) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiCecLocalDeviceTv tv = tv();
                    if (tv == null) {
                        Slog.w(TAG, "Local tv device not available to change arc mode.");
                        return;
                    }
                }
            });
        }

        @Override
        public void setOption(final int key, final int value) {
            enforceAccessPermission();
            if (!isTvDevice()) {
                return;
            }
            switch (key) {
                case HdmiTvClient.OPTION_CEC_AUTO_WAKEUP:
                    mCecController.setOption(key, value);
                    break;
                case HdmiTvClient.OPTION_CEC_AUTO_DEVICE_OFF:
                    // No need to pass this option to HAL.
                    tv().setAutoDeviceOff(value == HdmiTvClient.ENABLED);
                    break;
                case HdmiTvClient.OPTION_MHL_INPUT_SWITCHING:  // Fall through
                case HdmiTvClient.OPTION_MHL_POWER_CHARGE:
                    if (mMhlController != null) {
                        mMhlController.setOption(key, value);
                    }
                    break;
            }
        }

        private boolean isTvDevice() {
            return tv() != null;
        }

        @Override
        public void setProhibitMode(final boolean enabled) {
            enforceAccessPermission();
            if (!isTvDevice()) {
                return;
            }
            HdmiControlService.this.setProhibitMode(enabled);
        }

        @Override
        public void addVendorCommandListener(final IHdmiVendorCommandListener listener,
                final int deviceType) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiControlService.this.addVendorCommandListener(listener, deviceType);
                }
            });
        }

        @Override
        public void sendVendorCommand(final int deviceType, final int targetAddress,
                final byte[] params, final boolean hasVendorId) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiCecLocalDevice device = mCecController.getLocalDevice(deviceType);
                    if (device == null) {
                        Slog.w(TAG, "Local device not available");
                        return;
                    }
                    if (hasVendorId) {
                        sendCecCommand(HdmiCecMessageBuilder.buildVendorCommandWithId(
                                device.getDeviceInfo().getLogicalAddress(), targetAddress,
                                getVendorId(), params));
                    } else {
                        sendCecCommand(HdmiCecMessageBuilder.buildVendorCommand(
                                device.getDeviceInfo().getLogicalAddress(), targetAddress, params));
                    }
                }
            });
         }
    }

    @ServiceThreadOnly
    private void oneTouchPlay(final IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        HdmiCecLocalDevicePlayback source = playback();
        if (source == null) {
            Slog.w(TAG, "Local playback device not available");
            invokeCallback(callback, HdmiControlManager.RESULT_SOURCE_NOT_AVAILABLE);
            return;
        }
        source.oneTouchPlay(callback);
    }

    @ServiceThreadOnly
    private void queryDisplayStatus(final IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        HdmiCecLocalDevicePlayback source = playback();
        if (source == null) {
            Slog.w(TAG, "Local playback device not available");
            invokeCallback(callback, HdmiControlManager.RESULT_SOURCE_NOT_AVAILABLE);
            return;
        }
        source.queryDisplayStatus(callback);
    }

    private void addHotplugEventListener(IHdmiHotplugEventListener listener) {
        HotplugEventListenerRecord record = new HotplugEventListenerRecord(listener);
        try {
            listener.asBinder().linkToDeath(record, 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "Listener already died");
            return;
        }
        synchronized (mLock) {
            mHotplugEventListenerRecords.add(record);
            mHotplugEventListeners.add(listener);
        }
    }

    private void removeHotplugEventListener(IHdmiHotplugEventListener listener) {
        synchronized (mLock) {
            for (HotplugEventListenerRecord record : mHotplugEventListenerRecords) {
                if (record.mListener.asBinder() == listener.asBinder()) {
                    listener.asBinder().unlinkToDeath(record, 0);
                    mHotplugEventListenerRecords.remove(record);
                    break;
                }
            }
            mHotplugEventListeners.remove(listener);
        }
    }

    private void addDeviceEventListener(IHdmiDeviceEventListener listener) {
        DeviceEventListenerRecord record = new DeviceEventListenerRecord(listener);
        try {
            listener.asBinder().linkToDeath(record, 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "Listener already died");
            return;
        }
        synchronized (mLock) {
            mDeviceEventListeners.add(listener);
            mDeviceEventListenerRecords.add(record);
        }
    }

    void invokeDeviceEventListeners(HdmiCecDeviceInfo device, boolean activated) {
        synchronized (mLock) {
            for (IHdmiDeviceEventListener listener : mDeviceEventListeners) {
                try {
                    listener.onStatusChanged(device, activated);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to report device event:" + e);
                }
            }
        }
    }

    private void addSystemAudioModeChangeListner(IHdmiSystemAudioModeChangeListener listener) {
        SystemAudioModeChangeListenerRecord record = new SystemAudioModeChangeListenerRecord(
                listener);
        try {
            listener.asBinder().linkToDeath(record, 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "Listener already died");
            return;
        }
        synchronized (mLock) {
            mSystemAudioModeChangeListeners.add(listener);
            mSystemAudioModeChangeListenerRecords.add(record);
        }
    }

    private void removeSystemAudioModeChangeListener(IHdmiSystemAudioModeChangeListener listener) {
        synchronized (mLock) {
            for (SystemAudioModeChangeListenerRecord record :
                    mSystemAudioModeChangeListenerRecords) {
                if (record.mListener.asBinder() == listener) {
                    listener.asBinder().unlinkToDeath(record, 0);
                    mSystemAudioModeChangeListenerRecords.remove(record);
                    break;
                }
            }
            mSystemAudioModeChangeListeners.remove(listener);
        }
    }

    private final class InputChangeListenerRecord implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            synchronized (mLock) {
                mInputChangeListener = null;
            }
        }
    }

    private void setInputChangeListener(IHdmiInputChangeListener listener) {
        synchronized (mLock) {
            mInputChangeListenerRecord = new InputChangeListenerRecord();
            try {
                listener.asBinder().linkToDeath(mInputChangeListenerRecord, 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "Listener already died");
                return;
            }
            mInputChangeListener = listener;
        }
    }

    void invokeInputChangeListener(int activeAddress) {
        synchronized (mLock) {
            if (mInputChangeListener != null) {
                HdmiCecDeviceInfo activeSource = getDeviceInfo(activeAddress);
                try {
                    mInputChangeListener.onChanged(activeSource);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Exception thrown by IHdmiInputChangeListener: " + e);
                }
            }
        }
    }

    private void invokeCallback(IHdmiControlCallback callback, int result) {
        try {
            callback.onComplete(result);
        } catch (RemoteException e) {
            Slog.e(TAG, "Invoking callback failed:" + e);
        }
    }

    private void invokeSystemAudioModeChange(IHdmiSystemAudioModeChangeListener listener,
            boolean enabled) {
        try {
            listener.onStatusChanged(enabled);
        } catch (RemoteException e) {
            Slog.e(TAG, "Invoking callback failed:" + e);
        }
    }

    private void announceHotplugEvent(int portId, boolean connected) {
        HdmiHotplugEvent event = new HdmiHotplugEvent(portId, connected);
        synchronized (mLock) {
            for (IHdmiHotplugEventListener listener : mHotplugEventListeners) {
                invokeHotplugEventListenerLocked(listener, event);
            }
        }
    }

    private void invokeHotplugEventListenerLocked(IHdmiHotplugEventListener listener,
            HdmiHotplugEvent event) {
        try {
            listener.onReceived(event);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to report hotplug event:" + event.toString(), e);
        }
    }

    private static boolean hasSameTopPort(int path1, int path2) {
        return (path1 & Constants.ROUTING_PATH_TOP_MASK)
                == (path2 & Constants.ROUTING_PATH_TOP_MASK);
    }

    private HdmiCecLocalDeviceTv tv() {
        return (HdmiCecLocalDeviceTv) mCecController.getLocalDevice(HdmiCecDeviceInfo.DEVICE_TV);
    }

    private HdmiCecLocalDevicePlayback playback() {
        return (HdmiCecLocalDevicePlayback)
                mCecController.getLocalDevice(HdmiCecDeviceInfo.DEVICE_PLAYBACK);
    }

    AudioManager getAudioManager() {
        return (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
    }

    boolean isControlEnabled() {
        synchronized (mLock) {
            return mHdmiControlEnabled;
        }
    }

    int getPowerStatus() {
        return mPowerStatus;
    }

    boolean isPowerOnOrTransient() {
        return mPowerStatus == HdmiControlManager.POWER_STATUS_ON
                || mPowerStatus == HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON;
    }

    boolean isPowerStandbyOrTransient() {
        return mPowerStatus == HdmiControlManager.POWER_STATUS_STANDBY
                || mPowerStatus == HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY;
    }

    boolean isPowerStandby() {
        return mPowerStatus == HdmiControlManager.POWER_STATUS_STANDBY;
    }

    @ServiceThreadOnly
    void wakeUp() {
        assertRunOnServiceThread();
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        pm.wakeUp(SystemClock.uptimeMillis());
        // PowerManger will send the broadcast Intent.ACTION_SCREEN_ON and after this gets
        // the intent, the sequence will continue at onWakeUp().
    }

    @ServiceThreadOnly
    void standby() {
        assertRunOnServiceThread();
        mStandbyMessageReceived = true;
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        pm.goToSleep(SystemClock.uptimeMillis());
        // PowerManger will send the broadcast Intent.ACTION_SCREEN_OFF and after this gets
        // the intent, the sequence will continue at onStandby().
    }

    @ServiceThreadOnly
    private void onWakeUp() {
        assertRunOnServiceThread();
        mPowerStatus = HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON;
        if (mCecController != null) {
            mCecController.setOption(HdmiTvClient.OPTION_CEC_SERVICE_CONTROL, HdmiTvClient.ENABLED);
            initializeLocalDevices(mLocalDevices);
        } else {
            Slog.i(TAG, "Device does not support HDMI-CEC.");
        }
        // TODO: Initialize MHL local devices.
    }

    @ServiceThreadOnly
    private void onStandby() {
        assertRunOnServiceThread();
        mPowerStatus = HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY;
        for (HdmiCecLocalDevice device : mCecController.getLocalDeviceList()) {
            device.onTransitionToStandby(mStandbyMessageReceived);
        }
    }

    /**
     * Called when there are the outstanding actions in the local devices.
     * This callback is used to wait for when the action queue is empty
     * during the power state transition to standby.
     */
    @ServiceThreadOnly
    void onPendingActionsCleared() {
        assertRunOnServiceThread();
        if (mPowerStatus != HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY) {
            return;
        }
        mPowerStatus = HdmiControlManager.POWER_STATUS_STANDBY;
        for (HdmiCecLocalDevice device : mCecController.getLocalDeviceList()) {
            device.onStandBy(mStandbyMessageReceived);
        }
        mStandbyMessageReceived = false;
        mCecController.setOption(HdmiTvClient.OPTION_CEC_SERVICE_CONTROL, HdmiTvClient.DISABLED);
    }

    private void addVendorCommandListener(IHdmiVendorCommandListener listener, int deviceType) {
        VendorCommandListenerRecord record = new VendorCommandListenerRecord(listener, deviceType);
        try {
            listener.asBinder().linkToDeath(record, 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "Listener already died");
            return;
        }
        synchronized (mLock) {
            mVendorCommandListenerRecords.add(record);
        }
    }

    void invokeVendorCommandListeners(int deviceType, int srcAddress, byte[] params,
            boolean hasVendorId) {
        synchronized (mLock) {
            for (VendorCommandListenerRecord record : mVendorCommandListenerRecords) {
                if (record.mDeviceType != deviceType) {
                    continue;
                }
                try {
                    record.mListener.onReceived(srcAddress, params, hasVendorId);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to notify vendor command reception", e);
                }
            }
        }
    }

    boolean isProhibitMode() {
        synchronized (mLock) {
            return mProhibitMode;
        }
    }

    void setProhibitMode(boolean enabled) {
        synchronized (mLock) {
            mProhibitMode = enabled;
        }
    }
}