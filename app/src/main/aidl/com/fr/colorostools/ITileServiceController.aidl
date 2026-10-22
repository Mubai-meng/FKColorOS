package com.fr.colorostools;

interface ITileServiceController {

    boolean checkDarkMode();
    boolean getDarkMode();
    void setDarkMode(boolean status);

    boolean checkCompatibility(int subId);
    boolean getFiveGStatus(int subId);
    void setFiveGStatus(int subId,boolean enabled);

    boolean checkGlobalDCMode();
    boolean getGlobalDCMode();
    void setGlobalDCMode(boolean status);

    boolean getGoogleStatus();
    void setGoogleStatus(boolean status);

    boolean checkHighBrightnessMode();
    boolean getHighBrightnessMode();
    void setHighBrightnessMode(boolean status);

    boolean checkTouchMode();
    int getTouchMode();
    void setTouchMode(int value);

    boolean checkBypassMode();
    boolean getBypassMode();
    void setBypassMode(boolean status);

    int startMirageWindowMode();
}