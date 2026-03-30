package com.appiconchanger

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.annotation.NonNull
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableArray
import com.facebook.react.module.annotations.ReactModule
import java.util.Collections

@ReactModule(name = AppIconChangerModule.NAME)
class AppIconChangerModule(
    reactContext: ReactApplicationContext,
    private val packageName: String
) : ReactContextBaseJavaModule(reactContext), Application.ActivityLifecycleCallbacks {

    companion object {
        const val NAME = "DynamicIconManager"
        private const val MAIN_ACTIVITY_BASE_NAME = ".MainActivity"
        private const val TAG = "AppIconChanger"
    }

    // Thread-safe collections
    private val classesToKill: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    
    @Volatile
    private var componentClass: String = ""

    @Volatile
    private var pendingNewClass: String? = null
    
    @Volatile
    private var isCallbackRegistered: Boolean = false
    
    @Volatile
    private var isChangingIcon: Boolean = false
    
    @Volatile
    private var registeredApplication: Application? = null

    override fun getName(): String {
        return NAME
    }

    @ReactMethod
    fun getActiveIcon(promise: Promise) {
        val activity: Activity? = reactApplicationContext.currentActivity
        if (activity == null) {
            promise.reject("ACTIVITY_NOT_FOUND", "Activity was not found")
            return
        }

        val activityName = activity.componentName.className

        if (activityName.endsWith(MAIN_ACTIVITY_BASE_NAME)) {
            promise.resolve("Default")
            return
        }

        val activityNameSplit = activityName.split("MainActivity").toTypedArray()
        if (activityNameSplit.size != 2) {
            promise.reject("ANDROID:UNEXPECTED_COMPONENT_CLASS", componentClass)
            return
        }
        promise.resolve(activityNameSplit[1])
    }

    @ReactMethod
    fun getAllAlternativeIcons(promise: Promise) {
        try {
            val packageManager = reactApplicationContext.packageManager
            val packageInfo = packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA or PackageManager.GET_DISABLED_COMPONENTS
            )

            val aliasList: WritableArray = Arguments.createArray()

            packageInfo.activities?.forEach { activityInfo ->
                if (activityInfo.targetActivity != null) {
                    aliasList.pushString(activityInfo.name.replace("$packageName$MAIN_ACTIVITY_BASE_NAME", ""))
                }
            }

            promise.resolve(aliasList)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get alternative icons", e)
            promise.reject("ERROR", e)
        }
    }

    @Synchronized
    private fun unregisterCallbackIfNeeded() {
        if (isCallbackRegistered && registeredApplication != null) {
            try {
                registeredApplication?.unregisterActivityLifecycleCallbacks(this)
                isCallbackRegistered = false
                registeredApplication = null
                Log.d(TAG, "Lifecycle callbacks unregistered successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister lifecycle callbacks", e)
            }
        }
    }

    @Synchronized
    private fun completeIconChange() {
        if (isChangingIcon) {
            Log.d(TAG, "Icon change already in progress, skipping")
            return
        }

        if (pendingNewClass == null && classesToKill.isEmpty()) {
            Log.d(TAG, "No pending icon changes, cleaning up callbacks")
            unregisterCallbackIfNeeded()
            return
        }
        
        isChangingIcon = true
        
        try {
            val activity = reactApplicationContext.currentActivity
            if (activity == null) {
                Log.w(TAG, "Activity is null, cannot complete icon change")
                return
            }
            
            val packageManager = activity.packageManager

            pendingNewClass?.let { newClass ->
                try {
                    packageManager.setComponentEnabledSetting(
                        ComponentName(packageName, newClass),
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    componentClass = newClass
                    Log.d(TAG, "Icon enabled successfully: $newClass")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to enable component: $newClass", e)
                }
                pendingNewClass = null
            }

            synchronized(classesToKill) {
                for (className in classesToKill) {
                    try {
                        packageManager.setComponentEnabledSetting(
                            ComponentName(packageName, className),
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                        Log.d(TAG, "Icon disabled successfully: $className")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to disable component: $className", e)
                    }
                }
                classesToKill.clear()
            }
            
            Log.d(TAG, "Icon change completed successfully")
            
        } finally {
            isChangingIcon = false
            unregisterCallbackIfNeeded()
        }
    }

    @ReactMethod
    @Synchronized
    fun setIcon(iconName: String?, promise: Promise) {
        val activity = reactApplicationContext.currentActivity
        if (activity == null) {
            promise.reject("ACTIVITY_NOT_FOUND", "The activity is null. Check if the app is running properly.")
            return
        }

        if (iconName.isNullOrEmpty()) {
            promise.reject("EMPTY_ICON_STRING", "Icon name is missing i.e. setIcon('YOUR_ICON_NAME_HERE')")
            return
        }

        if (componentClass.isEmpty()) {
            componentClass = activity.componentName.className
            Log.d(TAG, "Initial component class set to: $componentClass")
        }

        val newIconName = if (iconName.isEmpty()) "Default" else iconName
        val activeClass = "$packageName$MAIN_ACTIVITY_BASE_NAME$newIconName"

        if (componentClass == activeClass && pendingNewClass == null) {
            Log.d(TAG, "Icon already active: $componentClass")
            promise.reject("ICON_ALREADY_USED", "This icon is the current active icon. $componentClass")
            return
        }

        pendingNewClass?.let { oldPending ->
            if (oldPending != activeClass && oldPending != componentClass) {
                classesToKill.add(oldPending)
                Log.d(TAG, "Added old pending class to kill list: $oldPending")
            }
        }

        pendingNewClass = activeClass
        
        if (componentClass.isNotEmpty() && componentClass != activeClass) {
            classesToKill.add(componentClass)
            Log.d(TAG, "Added current class to kill list: $componentClass")
        }

        if (!isCallbackRegistered) {
            try {
                unregisterCallbackIfNeeded()
                
                activity.application.registerActivityLifecycleCallbacks(this)
                registeredApplication = activity.application
                isCallbackRegistered = true
                Log.d(TAG, "Lifecycle callbacks registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register lifecycle callbacks", e)
                promise.reject("CALLBACK_REGISTRATION_FAILED", "Failed to register lifecycle callbacks", e)
                return
            }
        }

        Log.d(TAG, "Icon change scheduled: $iconName")
        promise.resolve("Your icon will change to $iconName")
    }

    @ReactMethod
    fun resetIcon(promise: Promise) {
        setIcon("Default", promise)
    }

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {
        Log.d(TAG, "Activity stopped, attempting icon change")
        completeIconChange()
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {
        Log.d(TAG, "Activity destroyed, attempting icon change")
        completeIconChange()
    }
}