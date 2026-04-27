package com.zcshou.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.elvishew.xlog.XLog;
import com.zcshou.gogogo.MainActivity;
import com.zcshou.gogogo.R;
import com.zcshou.joystick.JoyStick;
import com.zcshou.utils.CampusRunUtils;

import java.util.Random;

public class ServiceGo extends Service {
    // 定位相关变量
    public static final double DEFAULT_LAT = 36.667662;
    public static final double DEFAULT_LNG = 117.027707;
    public static final double DEFAULT_ALT = 55.0D;
    public static final float DEFAULT_BEA = 0.0F;
    private double mCurLat = DEFAULT_LAT;
    private double mCurLng = DEFAULT_LNG;
    private double mCurAlt = DEFAULT_ALT;
    private float mCurBea = DEFAULT_BEA;
    private double mSpeed = 1.2;        /* 默认的速度，单位 m/s */
    private static final int HANDLER_MSG_ID = 0;
    private static final String SERVICE_GO_HANDLER_NAME = "ServiceGoLocation";
    private LocationManager mLocManager;
    private HandlerThread mLocHandlerThread;
    private Handler mLocHandler;
    private boolean isStop = false;
    private long mLastTickElapsedMs = 0L;
    // 通知栏消息
    private static final int SERVICE_GO_NOTE_ID = 1;
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW = "ShowJoyStick";
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE = "HideJoyStick";
    private static final String SERVICE_GO_NOTE_CHANNEL_ID = "SERVICE_GO_NOTE";
    private static final String SERVICE_GO_NOTE_CHANNEL_NAME = "SERVICE_GO_NOTE";
    private NoteActionReceiver mActReceiver;
    // 摇杆相关
    private JoyStick mJoyStick;
    // 校园跑（测试）相关
    private boolean mCampusRunEnabled = false;
    private double[] mCampusRunLngs = null;
    private double[] mCampusRunLats = null;
    private int mCampusRunLapTarget = 0;
    private int mCampusRunLapDone = 0;
    private int mCampusRunTargetIndex = 1;
    private double mCampusRunPaceMin = 5.0;
    private double mCampusRunPaceMax = 8.0;
    private int mCampusRunPaceUpdateSec = 8;
    private double mCampusRunCurrentSpeed = 3.0;
    private boolean mCampusRunPaused = false;
    private long mCampusRunNextPaceUpdateElapsedMs = 0L;
    private double mCampusRunOffsetMeters = 0.0;
    private double mCampusRunNavLat = DEFAULT_LAT;
    private double mCampusRunNavLng = DEFAULT_LNG;
    private int mCampusRunLaneIndex = 0;
    private double mCampusRunLaneOffsetCurrentMeters = 0.0;
    private double mCampusRunLaneOffsetTargetMeters = 0.0;
    private long mCampusRunNextLaneSwitchElapsedMs = 0L;
    private double mCampusRunLapDistanceMeters = 0.0;
    private double mCampusRunMovedMeters = 0.0;
    private double mCampusRunElapsedSec = 0.0;
    private double mLastValidLat = DEFAULT_LAT;
    private double mLastValidLng = DEFAULT_LNG;
    private boolean mAllowLargeJumpOnce = false;
    private boolean mHasAnchor = false;
    private double mAnchorLat = DEFAULT_LAT;
    private double mAnchorLng = DEFAULT_LNG;
    private final Random mRandom = new Random();
    private static final double CAMPUS_LANE_CHANGE_SPEED_MPS = 0.9;
    private long mLastGpsRecoverElapsedMs = 0L;
    private long mLastNetworkRecoverElapsedMs = 0L;

    private final ServiceGoBinder mBinder = new ServiceGoBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mLocManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        removeTestProviderNetwork();
        addTestProviderNetwork();

        removeTestProviderGPS();
        addTestProviderGPS();

        initGoLocation();

        initNotification();

        initJoyStick();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            mCurLng = intent.getDoubleExtra(MainActivity.LNG_MSG_ID, DEFAULT_LNG);
            mCurLat = intent.getDoubleExtra(MainActivity.LAT_MSG_ID, DEFAULT_LAT);
            mCurAlt = intent.getDoubleExtra(MainActivity.ALT_MSG_ID, DEFAULT_ALT);
        }
        mAllowLargeJumpOnce = true;
        if (isValidCoordinate(mCurLat, mCurLng)) {
            mHasAnchor = true;
            mAnchorLat = mCurLat;
            mAnchorLng = mCurLng;
        }
        sanitizeCurrentPosition();

        mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        isStop = true;
        mLocHandler.removeMessages(HANDLER_MSG_ID);
        mLocHandlerThread.quit();

        mJoyStick.destroy();

        removeTestProviderNetwork();
        removeTestProviderGPS();

        unregisterReceiver(mActReceiver);
        stopForeground(STOP_FOREGROUND_REMOVE);

        super.onDestroy();
    }

    private void initNotification() {
        mActReceiver = new NoteActionReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        registerReceiver(mActReceiver, filter);

        NotificationChannel mChannel = new NotificationChannel(SERVICE_GO_NOTE_CHANNEL_ID, SERVICE_GO_NOTE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (notificationManager != null) {
            notificationManager.createNotificationChannel(mChannel);
        }

        //准备intent
        Intent clickIntent = new Intent(this, MainActivity.class);
        PendingIntent clickPI = PendingIntent.getActivity(this, 1, clickIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent showIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        PendingIntent showPendingPI = PendingIntent.getBroadcast(this, 0, showIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent hideIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        PendingIntent hidePendingPI = PendingIntent.getBroadcast(this, 0, hideIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, SERVICE_GO_NOTE_CHANNEL_ID)
                .setChannelId(SERVICE_GO_NOTE_CHANNEL_ID)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(getResources().getString(R.string.app_service_tips))
                .setContentIntent(clickPI)
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_show), showPendingPI))
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_hide), hidePendingPI))
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();

        startForeground(SERVICE_GO_NOTE_ID, notification);
    }

    private void initJoyStick() {
        mJoyStick = new JoyStick(this);
        mJoyStick.setListener(new JoyStick.JoyStickClickListener() {
            @Override
            public void onMoveInfo(double speed, double disLng, double disLat, double angle) {
                if (mCampusRunEnabled) {
                    return;
                }
                mSpeed = speed;
                // 根据当前的经纬度和距离，计算下一个经纬度
                // Latitude: 1 deg = 110.574 km // 纬度的每度的距离大约为 110.574km
                // Longitude: 1 deg = 111.320*cos(latitude) km  // 经度的每度的距离从0km到111km不等
                // 具体见：http://wp.mlab.tw/?p=2200
                double metersPerLngKm = 111.320 * Math.cos(Math.abs(mCurLat) * Math.PI / 180);
                if (Double.isFinite(metersPerLngKm) && Math.abs(metersPerLngKm) > 1e-6) {
                    mCurLng += disLng / metersPerLngKm;
                }
                mCurLat += disLat / 110.574;
                mCurBea = (float) angle;
                sanitizeCurrentPosition();
            }

            @Override
            public void onPositionInfo(double lng, double lat, double alt) {
                if (mCampusRunEnabled) {
                    return;
                }
                if (!isValidCoordinate(lat, lng) || isSuspiciousGuinea(lat, lng)) {
                    XLog.e("SERVICEGO: ignore suspicious onPositionInfo lat=%s lng=%s", lat, lng);
                    return;
                }
                mCurLng = lng;
                mCurLat = lat;
                mCurAlt = alt;
                sanitizeCurrentPosition();
            }
        });
        mJoyStick.show();
    }

    private void initGoLocation() {
        // 创建 HandlerThread 实例，第一个参数是线程的名字
        mLocHandlerThread = new HandlerThread(SERVICE_GO_HANDLER_NAME, Process.THREAD_PRIORITY_FOREGROUND);
        // 启动 HandlerThread 线程
        mLocHandlerThread.start();
        // Handler 对象与 HandlerThread 的 Looper 对象的绑定
        mLocHandler = new Handler(mLocHandlerThread.getLooper()) {
            // 这里的Handler对象可以看作是绑定在HandlerThread子线程中，所以handlerMessage里的操作是在子线程中运行的
            @Override
            public void handleMessage(@NonNull Message msg) {
                try {
                    Thread.sleep(100);

                    if (!isStop) {
                        long now = SystemClock.elapsedRealtime();
                        if (mLastTickElapsedMs == 0L) {
                            mLastTickElapsedMs = now;
                        }
                        double deltaSec = (now - mLastTickElapsedMs) / 1000.0;
                        mLastTickElapsedMs = now;
                        updateCampusRunStep(deltaSec);

                        setLocationNetwork();
                        setLocationGPS();

                        sendEmptyMessage(HANDLER_MSG_ID);
                    }
                } catch (InterruptedException e) {
                    XLog.e("SERVICEGO: ERROR - handleMessage");
                    Thread.currentThread().interrupt();
                }
            }
        };

        mLastTickElapsedMs = SystemClock.elapsedRealtime();
        mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
    }

    private void updateCampusRunStep(double deltaSec) {
        if (!mCampusRunEnabled || mCampusRunLngs == null || mCampusRunLats == null || mCampusRunLngs.length < 2) {
            return;
        }
        if (mCampusRunPaused) {
            return;
        }
        if (deltaSec <= 0.0) {
            return;
        }

        updateCampusRunSpeedIfNeeded(SystemClock.elapsedRealtime());
        updateCampusRunLaneIfNeeded(SystemClock.elapsedRealtime());
        updateCampusRunLaneTransition(deltaSec);
        double plannedMeters = mCampusRunCurrentSpeed * deltaSec;
        double remainMeters = plannedMeters;
        mSpeed = mCampusRunCurrentSpeed;

        while (remainMeters > 0.0 && mCampusRunEnabled) {
            int targetIdx = mCampusRunTargetIndex;
            double targetLng = mCampusRunLngs[targetIdx];
            double targetLat = mCampusRunLats[targetIdx];
            double distanceToTarget = distanceMeters(mCampusRunNavLat, mCampusRunNavLng, targetLat, targetLng);

            if (distanceToTarget < 0.01) {
                moveToNextCampusPoint();
                continue;
            }

            if (remainMeters < distanceToTarget) {
                double ratio = remainMeters / distanceToTarget;
                double nextLat = mCampusRunNavLat + (targetLat - mCampusRunNavLat) * ratio;
                double nextLng = mCampusRunNavLng + (targetLng - mCampusRunNavLng) * ratio;
                mCurBea = bearingDegrees(mCampusRunNavLat, mCampusRunNavLng, nextLat, nextLng);
                mCampusRunNavLat = nextLat;
                mCampusRunNavLng = nextLng;
                remainMeters = 0.0;
            } else {
                mCurBea = bearingDegrees(mCampusRunNavLat, mCampusRunNavLng, targetLat, targetLng);
                mCampusRunNavLat = targetLat;
                mCampusRunNavLng = targetLng;
                remainMeters -= distanceToTarget;
                moveToNextCampusPoint();
            }
        }

        double movedMeters = Math.max(0.0, plannedMeters - remainMeters);
        mCampusRunMovedMeters += movedMeters;
        mCampusRunElapsedSec += deltaSec;
        applyCampusRunOffsetToCurrent();
    }

    private void updateCampusRunSpeedIfNeeded(long nowElapsedMs) {
        if (!mCampusRunEnabled) {
            return;
        }
        if (nowElapsedMs < mCampusRunNextPaceUpdateElapsedMs) {
            return;
        }

        double minPace = Math.min(mCampusRunPaceMin, mCampusRunPaceMax);
        double maxPace = Math.max(mCampusRunPaceMin, mCampusRunPaceMax);
        double pace = minPace + mRandom.nextDouble() * (maxPace - minPace);
        mCampusRunCurrentSpeed = paceMinPerKmToSpeed(pace);
        mCampusRunNextPaceUpdateElapsedMs = nowElapsedMs + mCampusRunPaceUpdateSec * 1000L;
    }

    private static double paceMinPerKmToSpeed(double paceMinPerKm) {
        if (paceMinPerKm <= 0.0) {
            return 0.8;
        }
        return 1000.0 / (paceMinPerKm * 60.0);
    }

    private void updateCampusRunLaneIfNeeded(long nowElapsedMs) {
        if (!mCampusRunEnabled) {
            return;
        }
        if (nowElapsedMs < mCampusRunNextLaneSwitchElapsedMs) {
            return;
        }

        int nextLane;
        if (mCampusRunLaneIndex <= 0) {
            nextLane = 1;
        } else if (mCampusRunLaneIndex >= CampusRunUtils.TRACK_LANE_COUNT - 1) {
            nextLane = CampusRunUtils.TRACK_LANE_COUNT - 2;
        } else {
            nextLane = mCampusRunLaneIndex + (mRandom.nextBoolean() ? 1 : -1);
        }
        mCampusRunLaneIndex = nextLane;
        mCampusRunLaneOffsetTargetMeters = mCampusRunLaneIndex * CampusRunUtils.TRACK_LANE_WIDTH_M;
        mCampusRunNextLaneSwitchElapsedMs = nowElapsedMs + mCampusRunPaceUpdateSec * 1000L;
    }

    private void updateCampusRunLaneTransition(double deltaSec) {
        double diff = mCampusRunLaneOffsetTargetMeters - mCampusRunLaneOffsetCurrentMeters;
        if (Math.abs(diff) < 1e-4) {
            mCampusRunLaneOffsetCurrentMeters = mCampusRunLaneOffsetTargetMeters;
            return;
        }
        double maxStep = CAMPUS_LANE_CHANGE_SPEED_MPS * deltaSec;
        if (Math.abs(diff) <= maxStep) {
            mCampusRunLaneOffsetCurrentMeters = mCampusRunLaneOffsetTargetMeters;
        } else {
            mCampusRunLaneOffsetCurrentMeters += Math.signum(diff) * maxStep;
        }
    }

    private void moveToNextCampusPoint() {
        if (mCampusRunLngs == null || mCampusRunLats == null || mCampusRunLngs.length < 2) {
            mCampusRunEnabled = false;
            return;
        }

        mCampusRunTargetIndex++;
        if (mCampusRunTargetIndex >= mCampusRunLngs.length) {
            mCampusRunTargetIndex = 1;
            mCampusRunLapDone++;
            if (mCampusRunLapTarget > 0 && mCampusRunLapDone >= mCampusRunLapTarget) {
                mCampusRunEnabled = false;
            }
        }
    }

    private static float bearingDegrees(double fromLat, double fromLng, double toLat, double toLng) {
        double fromLatRad = Math.toRadians(fromLat);
        double toLatRad = Math.toRadians(toLat);
        double deltaLngRad = Math.toRadians(toLng - fromLng);
        double y = Math.sin(deltaLngRad) * Math.cos(toLatRad);
        double x = Math.cos(fromLatRad) * Math.sin(toLatRad)
                - Math.sin(fromLatRad) * Math.cos(toLatRad) * Math.cos(deltaLngRad);
        double brng = Math.toDegrees(Math.atan2(y, x));
        brng = (brng + 360.0) % 360.0;
        return (float) brng;
    }

    private static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6378137.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2.0) * Math.sin(dLng / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return r * c;
    }

    private static double polylineDistanceMeters(double[] lats, double[] lngs) {
        if (lats == null || lngs == null || lats.length != lngs.length || lats.length < 2) {
            return 0.0;
        }
        double total = 0.0;
        for (int i = 1; i < lats.length; i++) {
            total += distanceMeters(lats[i - 1], lngs[i - 1], lats[i], lngs[i]);
        }
        return total;
    }

    private void applyCampusRunOffsetToCurrent() {
        double laneOffsetMeters = mCampusRunLaneOffsetCurrentMeters;
        double bearingRad = Math.toRadians(mCurBea);
        double headingEast = Math.sin(bearingRad);
        double headingNorth = Math.cos(bearingRad);
        double rightEast = headingNorth;
        double rightNorth = -headingEast;

        double eastOffset = rightEast * laneOffsetMeters;
        double northOffset = rightNorth * laneOffsetMeters;
        if (mCampusRunOffsetMeters > 0.0) {
            northOffset += (mRandom.nextDouble() * 2.0 - 1.0) * mCampusRunOffsetMeters;
            eastOffset += (mRandom.nextDouble() * 2.0 - 1.0) * mCampusRunOffsetMeters;
        }

        mCurLat = mCampusRunNavLat + northOffset / 110574.0;
        double metersPerLngDeg = 111320.0 * Math.cos(Math.toRadians(mCampusRunNavLat));
        if (Math.abs(metersPerLngDeg) < 1e-6) {
            mCurLng = mCampusRunNavLng;
        } else {
            mCurLng = mCampusRunNavLng + eastOffset / metersPerLngDeg;
        }
    }

    private void removeTestProviderGPS() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.GPS_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderGPS");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderGPS() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实GPS参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
            } else {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderGPS");
        }
    }

    private void setLocationGPS() {
        try {
            sanitizeCurrentPosition();
            XLog.e("SERVICEGO: before GPS inject lat=%s lng=%s alt=%s", mCurLat, mCurLng, mCurAlt);
            // 尽可能模拟真实的 GPS 数据
            Location loc = new Location(LocationManager.GPS_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_FINE);    // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(mCurBea);                       // 方向（度）
            loc.setLatitude(mCurLat);                   // 纬度（度）
            loc.setLongitude(mCurLng);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            Bundle bundle = new Bundle();
            bundle.putInt("satellites", 7);
            loc.setExtras(bundle);

            mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
            XLog.e("SERVICEGO: after GPS inject lat=%s lng=%s", mCurLat, mCurLng);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationGPS", e);
            recoverGpsProviderIfNeeded();
        }
    }

    private void removeTestProviderNetwork() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderNetwork");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderNetwork() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实NETWORK参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE);
            } else {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            }
        } catch (SecurityException e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderNetwork");
        }
    }

    private void setLocationNetwork() {
        try {
            sanitizeCurrentPosition();
            XLog.e("SERVICEGO: before NETWORK inject lat=%s lng=%s alt=%s", mCurLat, mCurLng, mCurAlt);
            // 尽可能模拟真实的 NETWORK 数据
            Location loc = new Location(LocationManager.NETWORK_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_COARSE);  // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(mCurBea);                       // 方向（度）
            loc.setLatitude(mCurLat);                   // 纬度（度）
            loc.setLongitude(mCurLng);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

            mLocManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc);
            XLog.e("SERVICEGO: after NETWORK inject lat=%s lng=%s", mCurLat, mCurLng);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationNetwork", e);
            recoverNetworkProviderIfNeeded();
        }
    }

    private void recoverGpsProviderIfNeeded() {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastGpsRecoverElapsedMs < 2000L) {
            return;
        }
        mLastGpsRecoverElapsedMs = now;
        try {
            removeTestProviderGPS();
            addTestProviderGPS();
            XLog.w("SERVICEGO: recovered GPS provider");
        } catch (Exception e) {
            XLog.e("SERVICEGO: recover GPS provider failed", e);
        }
    }

    private void recoverNetworkProviderIfNeeded() {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastNetworkRecoverElapsedMs < 2000L) {
            return;
        }
        mLastNetworkRecoverElapsedMs = now;
        try {
            removeTestProviderNetwork();
            addTestProviderNetwork();
            XLog.w("SERVICEGO: recovered NETWORK provider");
        } catch (Exception e) {
            XLog.e("SERVICEGO: recover NETWORK provider failed", e);
        }
    }

    public class NoteActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW)) {
                    mJoyStick.show();
                }

                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE)) {
                    mJoyStick.hide();
                }
            }
        }
    }

    public class ServiceGoBinder extends Binder {
        public class CampusRunStatus {
            public final int laneNumber;
            public final double currentPaceMinPerKm;
            public final double averagePaceMinPerKm;
            public final int lapDone;
            public final int lapTarget;
            public final double totalDistanceMeters;
            public final double completionPercent;
            public final double elapsedSeconds;
            public final boolean running;
            public final boolean paused;

            public CampusRunStatus(int laneNumber, double currentPaceMinPerKm, double averagePaceMinPerKm,
                                   int lapDone, int lapTarget, double totalDistanceMeters, double completionPercent,
                                   double elapsedSeconds, boolean running, boolean paused) {
                this.laneNumber = laneNumber;
                this.currentPaceMinPerKm = currentPaceMinPerKm;
                this.averagePaceMinPerKm = averagePaceMinPerKm;
                this.lapDone = lapDone;
                this.lapTarget = lapTarget;
                this.totalDistanceMeters = totalDistanceMeters;
                this.completionPercent = completionPercent;
                this.elapsedSeconds = elapsedSeconds;
                this.running = running;
                this.paused = paused;
            }
        }

        public void setPosition(double lng, double lat, double alt) {
            mLocHandler.removeMessages(HANDLER_MSG_ID);
            mCampusRunEnabled = false;
            mCampusRunPaused = false;
            if (isValidCoordinate(lat, lng) && !isSuspiciousGuinea(lat, lng)) {
                mCurLng = lng;
                mCurLat = lat;
                mHasAnchor = true;
                mAnchorLat = lat;
                mAnchorLng = lng;
            }
            mCurAlt = alt;
            mAllowLargeJumpOnce = true;
            sanitizeCurrentPosition();
            mLastTickElapsedMs = SystemClock.elapsedRealtime();
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
        }

        public boolean startCampusRun(double[] wgsLngs, double[] wgsLats, int laps, double paceMin, double paceMax, int paceUpdateSec, double offsetMeters) {
            if (wgsLngs == null || wgsLats == null || wgsLngs.length != wgsLats.length || wgsLngs.length < 2) {
                return false;
            }
            if (paceMin <= 0.0 || paceMax <= 0.0 || paceUpdateSec < 1) {
                return false;
            }

            mCampusRunLngs = wgsLngs;
            mCampusRunLats = wgsLats;
            mCampusRunLapTarget = Math.max(1, laps);
            mCampusRunLapDone = 0;
            mCampusRunTargetIndex = 1;
            mCampusRunPaceMin = Math.min(paceMin, paceMax);
            mCampusRunPaceMax = Math.max(paceMin, paceMax);
            mCampusRunPaceUpdateSec = paceUpdateSec;
            mCampusRunOffsetMeters = Math.max(0.0, offsetMeters);
            mCampusRunEnabled = true;
            mCampusRunPaused = false;
            mCampusRunNavLng = mCampusRunLngs[0];
            mCampusRunNavLat = mCampusRunLats[0];
            mHasAnchor = true;
            mAnchorLat = mCampusRunNavLat;
            mAnchorLng = mCampusRunNavLng;
            mCampusRunLaneIndex = 0;
            mCampusRunLaneOffsetCurrentMeters = 0.0;
            mCampusRunLaneOffsetTargetMeters = 0.0;
            mCampusRunLapDistanceMeters = polylineDistanceMeters(mCampusRunLats, mCampusRunLngs);
            mCampusRunMovedMeters = 0.0;
            mCampusRunElapsedSec = 0.0;
            mLastTickElapsedMs = SystemClock.elapsedRealtime();
            mCampusRunNextPaceUpdateElapsedMs = 0L;
            mCampusRunNextLaneSwitchElapsedMs = mLastTickElapsedMs + mCampusRunPaceUpdateSec * 1000L;
            updateCampusRunSpeedIfNeeded(mLastTickElapsedMs);
            mSpeed = mCampusRunCurrentSpeed;
            applyCampusRunOffsetToCurrent();
            mAllowLargeJumpOnce = true;
            sanitizeCurrentPosition();
            mLocHandler.removeMessages(HANDLER_MSG_ID);
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
            return true;
        }

        public void stopCampusRun() {
            mCampusRunEnabled = false;
            mCampusRunPaused = false;
        }

        public void pauseCampusRun() {
            if (!mCampusRunEnabled) {
                return;
            }
            mCampusRunPaused = true;
        }

        public void resumeCampusRun() {
            if (!mCampusRunEnabled) {
                return;
            }
            mCampusRunPaused = false;
            mLastTickElapsedMs = SystemClock.elapsedRealtime();
        }

        public boolean isCampusRunRunning() {
            return mCampusRunEnabled;
        }

        public boolean isCampusRunPaused() {
            return mCampusRunEnabled && mCampusRunPaused;
        }

        public CampusRunStatus getCampusRunStatus() {
            double currentPace = mCampusRunCurrentSpeed > 1e-6 ? 1000.0 / (mCampusRunCurrentSpeed * 60.0) : 0.0;
            double avgPace = mCampusRunMovedMeters > 1e-3 ? (mCampusRunElapsedSec / 60.0) / (mCampusRunMovedMeters / 1000.0) : 0.0;
            double totalMeters = mCampusRunLapDistanceMeters * Math.max(1, mCampusRunLapTarget);
            double completion = totalMeters > 1e-3 ? Math.min(100.0, (mCampusRunMovedMeters / totalMeters) * 100.0) : 0.0;
            return new CampusRunStatus(
                    Math.max(1, Math.min(CampusRunUtils.TRACK_LANE_COUNT,
                            (int) Math.round(mCampusRunLaneOffsetCurrentMeters / CampusRunUtils.TRACK_LANE_WIDTH_M) + 1)),
                    currentPace,
                    avgPace,
                    mCampusRunLapDone,
                    Math.max(1, mCampusRunLapTarget),
                    mCampusRunMovedMeters,
                    completion,
                    mCampusRunElapsedSec,
                    mCampusRunEnabled,
                    mCampusRunEnabled && mCampusRunPaused
            );
        }
    }

    private static boolean isValidCoordinate(double lat, double lng) {
        if (Double.isNaN(lat) || Double.isNaN(lng) || Double.isInfinite(lat) || Double.isInfinite(lng)) {
            return false;
        }
        return lat >= -90.0 && lat <= 90.0 && lng >= -180.0 && lng <= 180.0;
    }

    private static boolean isSuspiciousGuinea(double lat, double lng) {
        return Math.abs(lat) < 1.0 && Math.abs(lng) < 1.0;
    }

    private void sanitizeCurrentPosition() {
        if (!isValidCoordinate(mCurLat, mCurLng)) {
            XLog.e("SERVICEGO: rollback invalid coord lat=%s lng=%s -> lat=%s lng=%s",
                    mCurLat, mCurLng, mLastValidLat, mLastValidLng);
            mCurLat = mLastValidLat;
            mCurLng = mLastValidLng;
            mAllowLargeJumpOnce = false;
            return;
        }
        if (isSuspiciousGuinea(mCurLat, mCurLng) && !isSuspiciousGuinea(mLastValidLat, mLastValidLng)) {
            XLog.e("SERVICEGO: rollback suspicious gulf coord lat=%s lng=%s -> lat=%s lng=%s",
                    mCurLat, mCurLng, mLastValidLat, mLastValidLng);
            mCurLat = mLastValidLat;
            mCurLng = mLastValidLng;
            mAllowLargeJumpOnce = false;
            return;
        }
        if (!mAllowLargeJumpOnce) {
            double jumpMeters = distanceMeters(mLastValidLat, mLastValidLng, mCurLat, mCurLng);
            if (jumpMeters > 5000.0) {
                XLog.e("SERVICEGO: rollback large jump %.1fm lat=%s lng=%s -> lat=%s lng=%s",
                        jumpMeters, mCurLat, mCurLng, mLastValidLat, mLastValidLng);
                mCurLat = mLastValidLat;
                mCurLng = mLastValidLng;
                return;
            }
            if (mHasAnchor) {
                double anchorMeters = distanceMeters(mAnchorLat, mAnchorLng, mCurLat, mCurLng);
                if (anchorMeters > 200000.0) {
                    XLog.e("SERVICEGO: rollback out-of-anchor %.1fm lat=%s lng=%s anchorLat=%s anchorLng=%s",
                            anchorMeters, mCurLat, mCurLng, mAnchorLat, mAnchorLng);
                    mCurLat = mLastValidLat;
                    mCurLng = mLastValidLng;
                    return;
                }
            }
        }
        mLastValidLat = mCurLat;
        mLastValidLng = mCurLng;
        mAllowLargeJumpOnce = false;
    }
}
