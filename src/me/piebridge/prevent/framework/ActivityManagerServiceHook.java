package me.piebridge.prevent.framework;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.lang.reflect.Method;
import java.util.Map;

import me.piebridge.prevent.common.GmsUtils;
import me.piebridge.prevent.common.PackageUtils;
import me.piebridge.prevent.framework.util.LogUtils;
import me.piebridge.prevent.framework.util.SafeActionUtils;
import me.piebridge.prevent.framework.util.TaskRecordUtils;

/**
 * Created by thom on 15/8/11.
 */
public class ActivityManagerServiceHook {

    private static Context mContext;
    private static Map<String, Boolean> mPreventPackages;

    private ActivityManagerServiceHook() {

    }

    public static void setContext(Context context, Map<String, Boolean> preventPackages) {
        mContext = context;
        mPreventPackages = preventPackages;
    }

    private static boolean isWantedStartProcessLocked(Class<?>[] types) {
        if (types == null || types.length < 0x6) {
            return false;
        }
        return ApplicationInfo.class.equals(types[0x1])
                && String.class.equals(types[0x4])
                && ComponentName.class.equals(types[0x5]);
    }

    public static Method getStartProcessLocked(Class<?> activityManagerService) {
        Method startProcessLocked = null;
        for (Method method : activityManagerService.getDeclaredMethods()) {
            if (!"startProcessLocked".equals(method.getName()) || !"ProcessRecord".equals(method.getReturnType().getSimpleName()) || !isWantedStartProcessLocked(method.getParameterTypes())) {
                continue;
            }
            if (startProcessLocked == null || startProcessLocked.getParameterTypes().length < method.getParameterTypes().length) {
                startProcessLocked = method;
            }
        }
        return startProcessLocked;
    }

    public static Method getCleanUpRemovedTaskLocked(Class<?> activityManagerService) {
        for (Method method : activityManagerService.getDeclaredMethods()) {
            if ("cleanUpRemovedTaskLocked".equals(method.getName()) && method.getParameterTypes().length == 0x2) {
                return method;
            }
        }
        return null;
    }

    public static boolean hookBeforeStartProcessLocked(Object thiz, Object[] args) {
        ApplicationInfo info = (ApplicationInfo) args[0x1];
        String hostingType = (String) args[0x4];
        ComponentName hostingName = (ComponentName) args[0x5];
        String packageName = info.packageName;

        if ("content provider".equals(hostingType)) {
            SystemHook.retrievePreventsIfNeeded(thiz);
        }

        if (mContext == null) {
            return true;
        }

        boolean prevents = Boolean.TRUE.equals(mPreventPackages.get(packageName));
        if ("activity".equals(hostingType)) {
            SystemHook.updateRunningGapps(packageName, true);
            if (prevents) {
                // never block activity
                mPreventPackages.put(packageName, false);
                prevents = false;
            }
            LogUtils.logStartProcess(packageName, hostingType, hostingName);
        }

        return !prevents || hookDependency(hostingName, hostingType, packageName);
    }

    private static boolean hookDependency(ComponentName hostingName, String hostingType, String packageName) {
        if ("broadcast".equals(hostingType)) {
            // always block broadcast
            return hookBroadcast(hostingName, hostingType, packageName);
        } else if ("service".equals(hostingType)) {
            return hookService(hostingName, hostingType, packageName);
        } else if ("content provider".equals(hostingType)){
            SystemHook.checkRunningServices(packageName, false);
        }

        LogUtils.logStartProcess(packageName, hostingType + "(should safe)", hostingName);
        return true;
    }

    private static boolean hookBroadcast(ComponentName hostingName, String hostingType, String packageName) {
        if (SafeActionUtils.isSafeBroadcast(mContext, hostingName)) {
            SystemHook.checkRunningServices(packageName, false);
            LogUtils.logStartProcess(packageName, hostingType + "(safe)", hostingName);
            return true;
        } else {
            LogUtils.logStartProcess(true, packageName, hostingType, hostingName);
            return false;
        }
    }

    private static boolean hookService(ComponentName hostingName, String hostingType, String packageName) {
        if (SafeActionUtils.isSafeService(mContext, hostingName)) {
            handleSafeService(packageName);
            SystemHook.checkRunningServices(packageName, true);
            LogUtils.logStartProcess(packageName, hostingType + "(safe)", hostingName);
            return true;
        }
        // if there is no gapps, prevent start gms
        if (GmsUtils.isGms(packageName) && GmsUtils.getGmsCount() == 0 && !SystemHook.hasRunningGapps()) {
            LogUtils.logStartProcess(true, packageName, hostingType, hostingName);
            return false;
        }
        // prevent third party app
        try {
            PackageManager pm = mContext.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            if (!PackageUtils.isSystemPackage(info.flags)) {
                LogUtils.logStartProcess(true, packageName, hostingType, hostingName);
                return false;
            }
        } catch (PackageManager.NameNotFoundException e) {
            PreventLog.d("cannot find package " + packageName, e);
        }
        SystemHook.checkRunningServices(packageName, true);
        LogUtils.logStartProcess(packageName, hostingType, hostingName);
        return true;
    }

    private static void handleSafeService(String packageName) {
        if (Boolean.TRUE.equals(mPreventPackages.get(packageName))) {
            PreventLog.w("allow " + packageName + " for next service/broadcast");
            mPreventPackages.put(packageName, false);
            SystemHook.restoreLater(packageName);
        }
    }

    public static void hookAfterCleanUpRemovedTaskLocked(Object[] args) {
        String packageName = TaskRecordUtils.getPackageName(args[0]);
        SystemHook.updateRunningGapps(packageName, false);
        if (packageName != null && mPreventPackages != null && mPreventPackages.containsKey(packageName)) {
            mPreventPackages.put(packageName, true);
            LogUtils.logForceStop("removeTask", packageName, "force in " + SystemHook.TIME_IMMEDIATE + "s");
            SystemHook.forceStopPackageForce(packageName, SystemHook.TIME_IMMEDIATE);
        }
    }

}
