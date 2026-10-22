package com.fr.colorostools;

import android.content.pm.UserInfo;

interface IUserServiceController {
    List<UserInfo> getUsers();
    UserInfo getUserInfo(int userId);
    boolean isUserUnlocked(int userId);
}