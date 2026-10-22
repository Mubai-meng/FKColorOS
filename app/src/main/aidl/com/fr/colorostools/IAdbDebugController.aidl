package com.fr.colorostools;

interface IAdbDebugController {
    int getAdbPort();
    void setAdbPort(int port);
    String getWifiIP();
    void restartAdb();
}