package com.oplus.media;

import android.os.RemoteException;

import java.util.List;

public interface IOplusMediaControlManager {
    
    void setMediaControlDenyList(List<String> list) throws RemoteException;
    
}
