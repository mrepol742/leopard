/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */
package de.blinkt.openvpn.core;

import android.Manifest.permission;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;
import android.app.NotificationChannel;
import android.graphics.Color;

import com.leopard.vpn.BuildConfig;
import com.leopard.vpn.HomeActivity;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Locale;
import java.util.Vector;

import de.blinkt.openvpn.LaunchVPN;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.VpnStatus.ByteCountListener;
import de.blinkt.openvpn.core.VpnStatus.StateListener;

import static de.blinkt.openvpn.core.ConnectionStatus.LEVELCONNECTED;
import static de.blinkt.openvpn.core.ConnectionStatus.LEVELWAITINGFORUSERINPUT;
import static de.blinkt.openvpn.core.NetworkSpace.ipAddress;

public class OpenVPNService extends VpnService implements StateListener, Callback, ByteCountListener, IOpenVPNServiceInternal {
    public static final String STARTSERVICE = "de.blinkt.openvpn.STARTSERVICE";
    public static final String STARTSERVICESTICKY = "de.blinkt.openvpn.STARTSERVICESTICKY";
    public static final String ALWAYSSHOWNOTIFICATION = "de.blinkt.openvpn.NOTIFICATIONALWAYSVISIBLE";
    public static final String DISCONNECTVPN = "de.blinkt.openvpn.DISCONNECTVPN";
    public static final String NOTIFICATIONCHANNELBGID = "vpnbg";
    public static final String NOTIFICATIONCHANNELNEWSTATUSID = "openvpnnewstat";
    private static final String PAUSEVPN = "de.blinkt.openvpn.PAUSEVPN";
    private static final String RESUMEVPN = "com.leopard.vpn.RESUMEVPN";
    private static final int PRIORITYMIN = -2;
    private static final int PRIORITYDEFAULT = 0;
    private static final int PRIORITYMAX = 2;
    private static boolean mNotificationAlwaysVisible = false;
    private static Class mNotificationActivityClass;
    private final Vector<String> mDnslist = new Vector<>();
    private final NetworkSpace mRoutes = new NetworkSpace();
    private final NetworkSpace mRoutesv6 = new NetworkSpace();
    private final Object mProcessLock = new Object();
    private String lastChannel;
    private Thread mProcessThread = null;
    private VpnProfile mProfile;
    private String mDomain = null;
    private CIDRIP mLocalIP = null;
    private int mMtu;
    private String mLocalIPv6 = null;
    private DeviceStateReceiver mDeviceStateReceiver;
    private boolean mDisplayBytecount = false;
    private boolean mStarting = false;
    private long mConnecttime;
    private boolean mOvpn3 = false;
    private OpenVPNManagement mManagement;
    private final IBinder mBinder = new IOpenVPNServiceInternal.Stub() {
        @Override
        public boolean protect(int fd) throws RemoteException {
            return OpenVPNService.this.protect(fd);
        }

        @Override
        public void userPause(boolean shouldbePaused) throws RemoteException {
            OpenVPNService.this.userPause(shouldbePaused);
        }

        @Override
        public boolean stopVPN(boolean replaceConnection) throws RemoteException {
            return OpenVPNService.this.stopVPN(replaceConnection);
        }
    };
    private String mLastTunCfg;
    private String mRemoteGW;
    private Handler guiHandler;
    private Toast mlastToast;
    private Runnable mOpenVPNThread;

    // From: http://stackoverflow.com/questions/3758606/how-to-convert-byte-size-into-human-readable-format-in-java
    public static String humanReadableByteCount(long bytes, boolean speed, Resources res) {
        if (speed) bytes = bytes * 8;
        int unit = speed ? 1000 : 1024;
        int exp = Math.max(0, Math.min((int) (Math.log(bytes) / Math.log(unit)), 3));
        float bytesUnit = (float) (bytes / Math.pow(unit, exp));
        if (speed) switch (exp) {
            case 0:
                return res.getString(R.string.bitspersecond, bytesUnit);
            case 1:
                return res.getString(R.string.kbitspersecond, bytesUnit);
            case 2:
                return res.getString(R.string.mbitspersecond, bytesUnit);
            default:
                return res.getString(R.string.gbitspersecond, bytesUnit);
        }
        else switch (exp) {
            case 0:
                return res.getString(R.string.volumebyte, bytesUnit);
            case 1:
                return res.getString(R.string.volumekbyte, bytesUnit);
            case 2:
                return res.getString(R.string.volumembyte, bytesUnit);
            default:
                return res.getString(R.string.volumegbyte, bytesUnit);
        }
    }

    /**
     * Sets the activity which should be opened when tapped on the permanent notification tile.
     *
     * @param activityClass The activity class to open
     */
    public static void setNotificationActivityClass(Class<? extends Activity> activityClass) {
        mNotificationActivityClass = activityClass;
    }

    @Override
    public IBinder onBind(Intent intent) {
        String action = intent.getAction();
        if (action != null && action.equals(STARTSERVICE)) return mBinder;
        else return super.onBind(intent);
    }

    @Override
    public void onRevoke() {
        VpnStatus.logError(R.string.permissionrevoked);
        mManagement.stopVPN(false);
        endVpnService();
    }

    // Similar to revoke but do not try to stop process
    public void processDied() {
        endVpnService();
    }

    private void endVpnService() {
        synchronized (mProcessLock) {
            mProcessThread = null;
        }
        VpnStatus.removeByteCountListener(this);
        unregisterDeviceStateReceiver();
        ProfileManager.setConntectedVpnProfileDisconnected(this);
        mOpenVPNThread = null;
        if (!mStarting) {
            stopForeground(!mNotificationAlwaysVisible);
            if (!mNotificationAlwaysVisible) {
                stopSelf();
                VpnStatus.removeStateListener(this);
            }
        }
    }

    private void showNotification(final String msg, String tickerText, @NonNull String channel, long when, ConnectionStatus status) {
    
        NotificationManager cman = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        CharSequence name = channel;
        NotificationChannel mChannel = new NotificationChannel(channel, name, NotificationManager.IMPORTANCE_MIN);
        mChannel.setDescription("desc");
        mChannel.enableLights(false);
        mChannel.setLightColor(Color.DKGRAY);
        cman.createNotificationChannel(mChannel);
        
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        int icon = getIconByConnectionStatus(status);
        android.app.Notification.Builder nbuilder = new Notification.Builder(this);
        int priority;
        if (channel.equals(NOTIFICATIONCHANNELBGID)) priority = PRIORITYMIN;
        else priority = PRIORITYDEFAULT;
        if (mProfile != null) {
            nbuilder.setContentTitle(getString(R.string.notifcationtitle, getApplicationContext().getString(R.string.appname), Build.MODEL));
        } else {
            nbuilder.setContentTitle(getString(R.string.notifcationtitlenotconnect));
        }
        nbuilder.setContentText(msg);
        nbuilder.setOnlyAlertOnce(true);
        nbuilder.setOngoing(true);
        nbuilder.setSmallIcon(icon);
        if (status == LEVELWAITINGFORUSERINPUT) nbuilder.setContentIntent(getUserInputIntent(msg));
        else nbuilder.setContentIntent(getGraphPendingIntent());
        if (when != 0) nbuilder.setWhen(when);
        // Try to set the priority available since API 16 (JELLY_BEAN)
      /*  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) jbNotificationExtras(priority, nbuilder);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) lpNotificationExtras(nbuilder);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          */  //noinspection NewApi
            nbuilder.setChannelId(channel);
            if (mProfile != null)
                //noinspection NewApi
                nbuilder.setShortcutId(mProfile.getUUIDString());
        
        if (tickerText != null && !tickerText.equals("")) nbuilder.setTicker(tickerText);
        @SuppressWarnings("deprecation") Notification notification = nbuilder.getNotification();
        int notificationId = channel.hashCode();
        mNotificationManager.notify(notificationId, notification);
        startForeground(notificationId, notification);
        if (lastChannel != null && !channel.equals(lastChannel)) {
            // Cancel old notification
            mNotificationManager.cancel(lastChannel.hashCode());
        }
        // Check if running on a TV
        if (runningOnAndroidTV() && !(priority < 0)) guiHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mlastToast != null) mlastToast.cancel();
                String toastText = String.format(Locale.getDefault(), "%s - %s", mProfile.mConnections[0].mServerName, msg);
                mlastToast = Toast.makeText(getBaseContext(), toastText, Toast.LENGTH_SHORT);
                mlastToast.show();
            }
        });
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void lpNotificationExtras(Notification.Builder nbuilder) {
        nbuilder.setCategory(Notification.CATEGORY_SERVICE);
        nbuilder.setLocalOnly(true);
    }

    private boolean runningOnAndroidTV() {
        UiModeManager uiModeManager = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        return uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private int getIconByConnectionStatus(ConnectionStatus level) {
        /*switch (level) {
            case LEVELCONNECTED:
                return R.drawable.icstatvpn;
            case LEVELAUTHFAILED:
            case LEVELNONETWORK:
            case LEVELNOTCONNECTED:
                return R.drawable.icstatvpnoffline;
            case LEVELCONNECTINGNOSERVERREPLYYET:
            case LEVELWAITINGFORUSERINPUT:
                return R.drawable.icstatvpnoutline;
            case LEVELCONNECTINGSERVERREPLIED:
                return R.drawable.icstatvpnemptyhalo;
            case LEVELVPNPAUSED:
                return android.R.drawable.icmediapause;
            case UNKNOWNLEVEL:
            default:
                return R.drawable.icstatvpn;
        }*/
        return R.drawable.icconnectionicon;
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void jbNotificationExtras(int priority, android.app.Notification.Builder nbuilder) {
        try {
            if (priority != 0) {
                Method setpriority = nbuilder.getClass().getMethod("setPriority", int.class);
                setpriority.invoke(nbuilder, priority);
                Method setUsesChronometer = nbuilder.getClass().getMethod("setUsesChronometer", boolean.class);
                setUsesChronometer.invoke(nbuilder, true);
            }
            //ignore exception
        } catch (NoSuchMethodException | IllegalArgumentException | InvocationTargetException | IllegalAccessException e) {
            VpnStatus.logException(e);
        }
    }

    PendingIntent getUserInputIntent(String needed) {
        Intent intent = new Intent(getApplicationContext(), LaunchVPN.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        intent.putExtra("need", needed);
        Bundle b = new Bundle();
        b.putString("need", needed);
        PendingIntent pIntent = PendingIntent.getActivity(this, 12, intent, 0);
        return pIntent;
    }

    PendingIntent getGraphPendingIntent() {
        // Let the configure Button show the Log
        // Editor : I'm not sure about this but
        // TODO : Check what the fuck is this.
        Class activityClass = HomeActivity.class;
        if (mNotificationActivityClass != null) {
            activityClass = mNotificationActivityClass;
        }
        Intent intent = new Intent(getBaseContext(), activityClass);
        intent.putExtra("PAGE", "graph");
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent startLW = PendingIntent.getActivity(this, 0, intent, 0);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return startLW;
    }

    synchronized void registerDeviceStateReceiver(OpenVPNManagement magnagement) {
        // Registers BroadcastReceiver to track network connection changes.
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        mDeviceStateReceiver = new DeviceStateReceiver(magnagement);
        // Fetch initial network state
        mDeviceStateReceiver.networkStateChange(this);
        registerReceiver(mDeviceStateReceiver, filter);
        VpnStatus.addByteCountListener(mDeviceStateReceiver);
        /*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            addLollipopCMListener(); */
    }

    synchronized void unregisterDeviceStateReceiver() {
        if (mDeviceStateReceiver != null) try {
            VpnStatus.removeByteCountListener(mDeviceStateReceiver);
            this.unregisterReceiver(mDeviceStateReceiver);
        } catch (IllegalArgumentException iae) {
            // I don't know why  this happens:
            // java.lang.IllegalArgumentException: Receiver not registered: de.blinkt.openvpn.NetworkSateReceiver@41a61a10
            // Ignore for now ...
            iae.printStackTrace();
        }
        mDeviceStateReceiver = null;
        /*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            removeLollipopCMListener();*/
    }

    public void userPause(boolean shouldBePaused) {
        if (mDeviceStateReceiver != null) mDeviceStateReceiver.userPause(shouldBePaused);
    }

    @Override
    public boolean stopVPN(boolean replaceConnection) throws RemoteException {
        if (getManagement() != null) return getManagement().stopVPN(replaceConnection);
        else return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra(ALWAYSSHOWNOTIFICATION, false)) mNotificationAlwaysVisible = true;
        VpnStatus.addStateListener(this);
        VpnStatus.addByteCountListener(this);
        guiHandler = new Handler(getMainLooper());
        if (intent != null && PAUSEVPN.equals(intent.getAction())) {
            if (mDeviceStateReceiver != null) mDeviceStateReceiver.userPause(true);
            return START_NOT_STICKY;
        }
        if (intent != null && RESUMEVPN.equals(intent.getAction())) {
            if (mDeviceStateReceiver != null) mDeviceStateReceiver.userPause(false);
            return START_NOT_STICKY;
        }
        if (intent != null && STARTSERVICE.equals(intent.getAction())) return START_NOT_STICKY;
        if (intent != null && STARTSERVICESTICKY.equals(intent.getAction())) {
            return START_REDELIVER_INTENT;
        }
        if (intent != null && intent.hasExtra(getPackageName() + ".profileUUID")) {
            String profileUUID = intent.getStringExtra(getPackageName() + ".profileUUID");
            int profileVersion = intent.getIntExtra(getPackageName() + ".profileVersion", 0);
            // Try for 10s to get current version of the profile
            mProfile = ProfileManager.get(this, profileUUID, profileVersion, 100);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                updateShortCutUsage(mProfile);
            }
        } else {
            /* The intent is null when we are set as always-on or the service has been restarted. */
            mProfile = ProfileManager.getLastConnectedProfile(this);
            VpnStatus.logInfo(R.string.servicerestarted);
            /* Got no profile, just stop */
            if (mProfile == null) {
                Log.d("OpenVPN", "Got no last connected profile on null intent. Assuming always on.");
                mProfile = ProfileManager.getAlwaysOnVPN(this);
                if (mProfile == null) {
                    stopSelf(startId);
                    return START_NOT_STICKY;
                }
            }
            /* Do the asynchronous keychain certificate stuff */
            mProfile.checkForRestart(this);
        }
        /* start the OpenVPN process itself in a background thread */
        new Thread(new Runnable() {
            @Override
            public void run() {
                startOpenVPN();
            }
        }).start();
        ProfileManager.setConnectedVpnProfile(this, mProfile);
        VpnStatus.setConnectedVPNProfile(mProfile.getUUIDString());
        return START_STICKY;
    }

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private void updateShortCutUsage(VpnProfile profile) {
        if (profile == null) return;
        ShortcutManager shortcutManager = getSystemService(ShortcutManager.class);
        shortcutManager.reportShortcutUsed(profile.getUUIDString());
    }

    private void startOpenVPN() {
        VpnStatus.logInfo(R.string.buildingconfigration);
        VpnStatus.updateStateString("VPNGENERATECONFIG", "", R.string.buildingconfigration, ConnectionStatus.LEVELSTART);
        try {
            mProfile.writeConfigFile(this);
        } catch (IOException e) {
            VpnStatus.logException("Error writing config file", e);
            endVpnService();
            return;
        }
        String nativeLibraryDirectory = getApplicationInfo().nativeLibraryDir;
        // Write OpenVPN binary
        String[] argv = VPNLaunchHelper.buildOpenvpnArgv(this);
        // Set a flag that we are starting a new VPN
        mStarting = true;
        // Stop the previous session by interrupting the thread.
        stopOldOpenVPNProcess();
        // An old running VPN should now be exited
        mStarting = false;
        // Start a new session by creating a new thread.
        SharedPreferences prefs = Preferences.getDefaultSharedPreferences(this);
        mOvpn3 = prefs.getBoolean("ovpn3", false);
        if (!"ovpn3".equals(BuildConfig.FLAVOR)) mOvpn3 = false;
        // Open the Management Interface
        if (!mOvpn3) {
            // start a Thread that handles incoming messages of the managment socket
            OpenVpnManagementThread ovpnManagementThread = new OpenVpnManagementThread(mProfile, this);
            if (ovpnManagementThread.openManagementInterface(this)) {
                Thread mSocketManagerThread = new Thread(ovpnManagementThread, "OpenVPNManagementThread");
                mSocketManagerThread.start();
                mManagement = ovpnManagementThread;
                VpnStatus.logInfo("started Socket Thread");
            } else {
                endVpnService();
                return;
            }
        }
        Runnable processThread;
        if (mOvpn3) {
            OpenVPNManagement mOpenVPN3 = instantiateOpenVPN3Core();
            processThread = (Runnable) mOpenVPN3;
            mManagement = mOpenVPN3;
        } else {
            processThread = new OpenVPNThread(this, argv, nativeLibraryDirectory);
            mOpenVPNThread = processThread;
        }
        synchronized (mProcessLock) {
            mProcessThread = new Thread(processThread, "OpenVPNProcessThread");
            mProcessThread.start();
        }
        new Handler(getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (mDeviceStateReceiver != null) unregisterDeviceStateReceiver();
                registerDeviceStateReceiver(mManagement);
            }
        });
    }

    private void stopOldOpenVPNProcess() {
        if (mManagement != null) {
            if (mOpenVPNThread != null) {
                ((OpenVPNThread) mOpenVPNThread).setReplaceConnection();
            }
            if (mManagement.stopVPN(true)) {
                // an old was asked to exit, wait 1s
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    //ignore
                }
            }
        }
        forceStopOpenVpnProcess();
    }

    public void forceStopOpenVpnProcess() {
        synchronized (mProcessLock) {
            if (mProcessThread != null) {
                mProcessThread.interrupt();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    //ignore
                }
            }
        }
    }

    private OpenVPNManagement instantiateOpenVPN3Core() {
        try {
            Class cl = Class.forName("de.blinkt.openvpn.core.OpenVPNThreadv3");
            return (OpenVPNManagement) cl.getConstructor(OpenVPNService.class, VpnProfile.class).newInstance(this, mProfile);
        } catch (IllegalArgumentException | InstantiationException | InvocationTargetException | NoSuchMethodException | ClassNotFoundException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public IBinder asBinder() {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        synchronized (mProcessLock) {
            if (mProcessThread != null) {
                mManagement.stopVPN(true);
            }
        }
        if (mDeviceStateReceiver != null) {
            this.unregisterReceiver(mDeviceStateReceiver);
        }
        // Just in case unregister for state
        VpnStatus.removeStateListener(this);
        VpnStatus.flushLog();
    }

    private String getTunConfigString() {
        // The format of the string is not important, only that
        // two identical configurations produce the same result
        String cfg = "TUNCFG UNQIUE STRING ips:";
        if (mLocalIP != null) cfg += mLocalIP.toString();
        if (mLocalIPv6 != null) cfg += mLocalIPv6;
        cfg += "routes: " + TextUtils.join("|", mRoutes.getNetworks(true)) + TextUtils.join("|", mRoutesv6.getNetworks(true));
        cfg += "excl. routes:" + TextUtils.join("|", mRoutes.getNetworks(false)) + TextUtils.join("|", mRoutesv6.getNetworks(false));
        cfg += "dns: " + TextUtils.join("|", mDnslist);
        cfg += "domain: " + mDomain;
        cfg += "mtu: " + mMtu;
        return cfg;
    }

    public ParcelFileDescriptor openTun() {
        //Debug.startMethodTracing(getExternalFilesDir(null).toString() + "/opentun.trace", 40* 1024 * 1024);
        Builder builder = new Builder();
        VpnStatus.logInfo(R.string.lastopenvpntunconfig);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mProfile.mAllowLocalLAN) {
            allowAllAFFamilies(builder);
        }
        if (mLocalIP == null && mLocalIPv6 == null) {
            VpnStatus.logError(getString(R.string.opentunnoipaddr));
            return null;
        }
        if (mLocalIP != null) {
            addLocalNetworksToRoutes();
            try {
                builder.addAddress(mLocalIP.mIp, mLocalIP.len);
            } catch (IllegalArgumentException iae) {
                VpnStatus.logError(R.string.dnsadderror, mLocalIP, iae.getLocalizedMessage());
                return null;
            }
        }
        if (mLocalIPv6 != null) {
            String[] ipv6parts = mLocalIPv6.split("/");
            try {
                builder.addAddress(ipv6parts[0], Integer.parseInt(ipv6parts[1]));
            } catch (IllegalArgumentException iae) {
                VpnStatus.logError(R.string.ipadderror, mLocalIPv6, iae.getLocalizedMessage());
                return null;
            }
        }
        for (String dns : mDnslist) {
            try {
                builder.addDnsServer(dns);
            } catch (IllegalArgumentException iae) {
                VpnStatus.logError(R.string.dnsadderror, dns, iae.getLocalizedMessage());
            }
        }
        String release = Build.VERSION.RELEASE;
        if ((Build.VERSION.SDK_INT == Build.VERSION_CODES.KITKAT && !release.startsWith("4.4.3") && !release.startsWith("4.4.4") && !release.startsWith("4.4.5") && !release.startsWith("4.4.6")) && mMtu < 1280) {
            VpnStatus.logInfo(String.format(Locale.US, "Forcing MTU to 1280 instead of %d to workaround Android Bug #70916", mMtu));
            builder.setMtu(1280);
        } else {
            builder.setMtu(mMtu);
        }
        Collection<ipAddress> positiveIPv4Routes = mRoutes.getPositiveIPList();
        Collection<ipAddress> positiveIPv6Routes = mRoutesv6.getPositiveIPList();
        if ("samsung".equals(Build.BRAND) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mDnslist.size() >= 1) {
            // Check if the first DNS Server is in the VPN range
            try {
                ipAddress dnsServer = new ipAddress(new CIDRIP(mDnslist.get(0), 32), true);
                boolean dnsIncluded = false;
                for (ipAddress net : positiveIPv4Routes) {
                    if (net.containsNet(dnsServer)) {
                        dnsIncluded = true;
                    }
                }
                if (!dnsIncluded) {
                    String samsungwarning = String.format("Warning Samsung Android 5.0+ devices ignore DNS servers outside the VPN range. To enable DNS resolution a route to your DNS Server (%s) has been added.", mDnslist.get(0));
                    VpnStatus.logWarning(samsungwarning);
                    positiveIPv4Routes.add(dnsServer);
                }
            } catch (Exception e) {
                VpnStatus.logError("Error parsing DNS Server IP: " + mDnslist.get(0));
            }
        }
        ipAddress multicastRange = new ipAddress(new CIDRIP("224.0.0.0", 3), true);
        for (NetworkSpace.ipAddress route : positiveIPv4Routes) {
            try {
                if (multicastRange.containsNet(route)) VpnStatus.logDebug(R.string.ignoremulticastroute, route.toString());
                else builder.addRoute(route.getIPv4Address(), route.networkMask);
            } catch (IllegalArgumentException ia) {
                VpnStatus.logError(getString(R.string.routerejected) + route + " " + ia.getLocalizedMessage());
            }
        }
        for (NetworkSpace.ipAddress route6 : positiveIPv6Routes) {
            try {
                builder.addRoute(route6.getIPv6Address(), route6.networkMask);
            } catch (IllegalArgumentException ia) {
                VpnStatus.logError(getString(R.string.routerejected) + route6 + " " + ia.getLocalizedMessage());
            }
        }
        if (mDomain != null) builder.addSearchDomain(mDomain);
        VpnStatus.logInfo(R.string.localipinfo, mLocalIP.mIp, mLocalIP.len, mLocalIPv6, mMtu);
        VpnStatus.logInfo(R.string.dnsserverinfo, TextUtils.join(", ", mDnslist), mDomain);
        VpnStatus.logInfo(R.string.routesinfoincl, TextUtils.join(", ", mRoutes.getNetworks(true)), TextUtils.join(", ", mRoutesv6.getNetworks(true)));
        VpnStatus.logInfo(R.string.routesinfoexcl, TextUtils.join(", ", mRoutes.getNetworks(false)), TextUtils.join(", ", mRoutesv6.getNetworks(false)));
        VpnStatus.logDebug(R.string.routesdebug, TextUtils.join(", ", positiveIPv4Routes), TextUtils.join(", ", positiveIPv6Routes));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setAllowedVpnPackages(builder);
        }
        String session = mProfile.mConnections[0].mServerName;
        if (mLocalIP != null && mLocalIPv6 != null) session = getString(R.string.sessionipv6string, session, mLocalIP, mLocalIPv6);
        else if (mLocalIP != null) session = getString(R.string.sessionipv4string, session, mLocalIP);
        builder.setSession(session);
        // No DNS Server, log a warning
        if (mDnslist.size() == 0) VpnStatus.logInfo(R.string.warnnodns);
        mLastTunCfg = getTunConfigString();
        // Reset information
        mDnslist.clear();
        mRoutes.clear();
        mRoutesv6.clear();
        mLocalIP = null;
        mLocalIPv6 = null;
        mDomain = null;
        builder.setConfigureIntent(getGraphPendingIntent());
        try {
            //Debug.stopMethodTracing();
            ParcelFileDescriptor tun = builder.establish();
            if (tun == null) throw new NullPointerException("Android establish() method returned null (Really broken network configuration?)");
            return tun;
        } catch (Exception e) {
            VpnStatus.logError(R.string.tunopenerror);
            VpnStatus.logError(getString(R.string.error) + e.getLocalizedMessage());
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                VpnStatus.logError(R.string.tunerrorhelpful);
            }
            return null;
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void allowAllAFFamilies(Builder builder) {
        builder.allowFamily(OsConstants.AF_INET);
        builder.allowFamily(OsConstants.AF_INET6);
    }

    private void addLocalNetworksToRoutes() {
        // Add local network interfaces
        String[] localRoutes = NativeUtils.getIfconfig();
        // The format of mLocalRoutes is kind of broken because I don't really like JNI
        for (int i = 0; i < localRoutes.length; i += 3) {
            String intf = localRoutes[i];
            String ipAddr = localRoutes[i + 1];
            String netMask = localRoutes[i + 2];
            if (intf == null || intf.equals("lo") || intf.startsWith("tun") || intf.startsWith("rmnet")) continue;
            if (ipAddr == null || netMask == null) {
                VpnStatus.logError("Local routes are broken?! (Report to author) " + TextUtils.join("|", localRoutes));
                continue;
            }
            if (ipAddr.equals(mLocalIP.mIp)) continue;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT && !mProfile.mAllowLocalLAN) {
                mRoutes.addIPSplit(new CIDRIP(ipAddr, netMask), true);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && mProfile.mAllowLocalLAN) mRoutes.addIP(new CIDRIP(ipAddr, netMask), false);
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void setAllowedVpnPackages(Builder builder) {
        boolean atLeastOneAllowedApp = false;
        for (String pkg : mProfile.mAllowedAppsVpn) {
            try {
                if (mProfile.mAllowedAppsVpnAreDisallowed) {
                    builder.addDisallowedApplication(pkg);
                } else {
                    builder.addAllowedApplication(pkg);
                    atLeastOneAllowedApp = true;
                }
            } catch (PackageManager.NameNotFoundException e) {
                mProfile.mAllowedAppsVpn.remove(pkg);
                VpnStatus.logInfo(R.string.appnolongerexists, pkg);
            }
        }
        if (!mProfile.mAllowedAppsVpnAreDisallowed && !atLeastOneAllowedApp) {
            VpnStatus.logDebug(R.string.noallowedapp, getPackageName());
            try {
                builder.addAllowedApplication(getPackageName());
            } catch (PackageManager.NameNotFoundException e) {
                VpnStatus.logError("This should not happen: " + e.getLocalizedMessage());
            }
        }
        if (mProfile.mAllowedAppsVpnAreDisallowed) {
            VpnStatus.logDebug(R.string.disallowedvpnappsinfo, TextUtils.join(", ", mProfile.mAllowedAppsVpn));
        } else {
            VpnStatus.logDebug(R.string.allowedvpnappsinfo, TextUtils.join(", ", mProfile.mAllowedAppsVpn));
        }
    }

    public void addDNS(String dns) {
        mDnslist.add(dns);
    }

    public void setDomain(String domain) {
        if (mDomain == null) {
            mDomain = domain;
        }
    }

    /**
     * Route that is always included, used by the v3 core
     */
    public void addRoute(CIDRIP route) {
        mRoutes.addIP(route, true);
    }

    public void addRoute(String dest, String mask, String gateway, String device) {
        CIDRIP route = new CIDRIP(dest, mask);
        boolean include = isAndroidTunDevice(device);
        NetworkSpace.ipAddress gatewayIP = new NetworkSpace.ipAddress(new CIDRIP(gateway, 32), false);
        if (mLocalIP == null) {
            VpnStatus.logError("Local IP address unset and received. Neither pushed server config nor local config specifies an IP addresses. Opening tun device is most likely going to fail.");
            return;
        }
        NetworkSpace.ipAddress localNet = new NetworkSpace.ipAddress(mLocalIP, true);
        if (localNet.containsNet(gatewayIP)) include = true;
        if (gateway != null && (gateway.equals("255.255.255.255") || gateway.equals(mRemoteGW))) include = true;
        if (route.len == 32 && !mask.equals("255.255.255.255")) {
            VpnStatus.logWarning(R.string.routenotcidr, dest, mask);
        }
        if (route.normalise()) VpnStatus.logWarning(R.string.routenotnetip, dest, route.len, route.mIp);
        mRoutes.addIP(route, include);
    }

    public void addRoutev6(String network, String device) {
        String[] v6parts = network.split("/");
        boolean included = isAndroidTunDevice(device);
        // Tun is opened after ROUTE6, no device name may be present
        try {
            Inet6Address ip = (Inet6Address) InetAddress.getAllByName(v6parts[0])[0];
            int mask = Integer.parseInt(v6parts[1]);
            mRoutesv6.addIPv6(ip, mask, included);
        } catch (UnknownHostException e) {
            VpnStatus.logException(e);
        }
    }

    private boolean isAndroidTunDevice(String device) {
        return device != null && (device.startsWith("tun") || "(null)".equals(device) || "vpnservice-tun".equals(device));
    }

    public void setMtu(int mtu) {
        mMtu = mtu;
    }

    public void setLocalIP(CIDRIP cdrip) {
        mLocalIP = cdrip;
    }

    public void setLocalIP(String local, String netmask, int mtu, String mode) {
        mLocalIP = new CIDRIP(local, netmask);
        mMtu = mtu;
        mRemoteGW = null;
        long netMaskAsInt = CIDRIP.getInt(netmask);
        if (mLocalIP.len == 32 && !netmask.equals("255.255.255.255")) {
            // get the netmask as IP
            int masklen;
            long mask;
            if ("net30".equals(mode)) {
                masklen = 30;
                mask = 0xfffffffc;
            } else {
                masklen = 31;
                mask = 0xfffffffe;
            }
            // Netmask is Ip address +/-1, assume net30/p2p with small net
            if ((netMaskAsInt & mask) == (mLocalIP.getInt() & mask)) {
                mLocalIP.len = masklen;
            } else {
                mLocalIP.len = 32;
                if (!"p2p".equals(mode)) VpnStatus.logWarning(R.string.ipnotcidr, local, netmask, mode);
            }
        }
        if (("p2p".equals(mode) && mLocalIP.len < 32) || ("net30".equals(mode) && mLocalIP.len < 30)) {
            VpnStatus.logWarning(R.string.iplookslikesubnet, local, netmask, mode);
        }
        /* Workaround for Lollipop, it  does not route traffic to the VPNs own network mask */
        if (mLocalIP.len <= 31 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CIDRIP interfaceRoute = new CIDRIP(mLocalIP.mIp, mLocalIP.len);
            interfaceRoute.normalise();
            addRoute(interfaceRoute);
        }
        // Configurations are sometimes really broken...
        mRemoteGW = netmask;
    }

    public void setLocalIPv6(String ipv6addr) {
        mLocalIPv6 = ipv6addr;
    }

    @Override
    public void updateState(String state, String logmessage, int resid, ConnectionStatus level) {
        // If the process is not running, ignore any state,
        // Notification should be invisible in this state
        doSendBroadcast(state, level);
        if (mProcessThread == null && !mNotificationAlwaysVisible) return;
        String channel = NOTIFICATIONCHANNELNEWSTATUSID;
        // Display byte count only after being connected
        {
            if (level == LEVELWAITINGFORUSERINPUT) {
                // The user is presented a dialog of some kind, no need to inform the user
                // with a notifcation
                return;
            } else if (level == LEVELCONNECTED) {
                mDisplayBytecount = true;
                mConnecttime = System.currentTimeMillis();
                if (!runningOnAndroidTV()) channel = NOTIFICATIONCHANNELBGID;
            } else {
                mDisplayBytecount = false;
            }
            // Other notifications are shown,
            // This also mean we are no longer connected, ignore bytecount messages until next
            // CONNECTED
            // Does not work :(
            showNotification(VpnStatus.getLastCleanLogMessage(this), VpnStatus.getLastCleanLogMessage(this), channel, 0, level);
        }
    }

    @Override
    public void setConnectedVPN(String uuid) {
    }

    private void doSendBroadcast(String state, ConnectionStatus level) {
        Intent vpnstatus = new Intent();
        vpnstatus.setAction("de.blinkt.openvpn.VPNSTATUS");
        vpnstatus.putExtra("status", level.toString());
        vpnstatus.putExtra("detailstatus", state);
        sendBroadcast(vpnstatus, permission.ACCESS_NETWORK_STATE);
    }

    @Override
    public void updateByteCount(long in, long out, long diffIn, long diffOut) {
        if (mDisplayBytecount) {
            String netstat = String.format(getString(R.string.statuslinebytecount), humanReadableByteCount(in, false, getResources()), humanReadableByteCount(diffIn / OpenVPNManagement.mBytecountInterval, true, getResources()), humanReadableByteCount(out, false, getResources()), humanReadableByteCount(diffOut / OpenVPNManagement.mBytecountInterval, true, getResources()));
            showNotification(netstat, getString(R.string.appname), NOTIFICATIONCHANNELBGID, mConnecttime, LEVELCONNECTED);
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        Runnable r = msg.getCallback();
        if (r != null) {
            r.run();
            return true;
        } else {
            return false;
        }
    }

    public OpenVPNManagement getManagement() {
        return mManagement;
    }

    public String getTunReopenStatus() {
        String currentConfiguration = getTunConfigString();
        if (currentConfiguration.equals(mLastTunCfg)) {
            return "NOACTION";
        } else {
            String release = Build.VERSION.RELEASE;
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.KITKAT && !release.startsWith("4.4.3") && !release.startsWith("4.4.4") && !release.startsWith("4.4.5") && !release.startsWith("4.4.6"))
                // There will be probably no 4.4.4 or 4.4.5 version, so don't waste effort to do parsing here
                return "OPENAFTERCLOSE";
            else return "OPENBEFORECLOSE";
        }
    }

    public void requestInputFromUser(int resid, String needed) {
        VpnStatus.updateStateString("NEED", "need " + needed, resid, LEVELWAITINGFORUSERINPUT);
        showNotification(getString(resid), getString(resid), NOTIFICATIONCHANNELNEWSTATUSID, 0, LEVELWAITINGFORUSERINPUT);
    }
}