package com.fr.colorostools.utils

import android.app.Activity
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.fr.colorostools.R
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
class PermissionUtils(val activity: Activity) {

    fun start() {
        XXPermissions.setCheckMode(false)
        XXPermissions.with(activity).apply {
            permission(PermissionLists.getManageExternalStoragePermission())
            permission(PermissionLists.getRequestInstallPackagesPermission())
            permission(PermissionLists.getGetInstalledAppsPermission())
            permission(PermissionLists.getNotificationServicePermission())
            permission(PermissionLists.getPostNotificationsPermission())
        }.request { grantedList, deniedList ->
            val allGranted = deniedList.isEmpty()
            if (!allGranted) {
                // 判断请求失败的权限是否被用户勾选了不再询问的选项
                val doNotAskAgain =
                    XXPermissions.isDoNotAskAgainPermissions(activity, deniedList)
                // 在这里处理权限请求失败的逻辑
                if (doNotAskAgain) {
                    // 如果是被永久拒绝就跳转到应用权限系统设置页面
                    XXPermissions.startPermissionActivity(activity, deniedList)
                } else {
                    //toast("获取权限失败")
                }
            }
            // 在这里处理权限请求成功的逻辑
        }
    }

    private fun toastDenied(permission: String) {
        activity.showToast(activity.getString(R.string.permission_denied_toast, permission))
    }

    private fun toastError(permission: String) {
        activity.showToast(activity.getString(R.string.permission_error_toast, permission))
    }
}