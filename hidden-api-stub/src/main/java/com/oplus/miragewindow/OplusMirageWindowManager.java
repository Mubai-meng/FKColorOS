package com.oplus.miragewindow;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

public class OplusMirageWindowManager {
    
    public static OplusMirageWindowManager getInstance() {
        throw new RuntimeException("STUB");
    }
    
    public boolean registerMirageDisplayObserver(IOplusMirageDisplayObserver observer) {
        throw new RuntimeException("STUB");
    }
    
    public boolean unregisterMirageDisplayObserver(IOplusMirageDisplayObserver observer) {
        throw new RuntimeException("STUB");
    }
    
    public int startMirageWindowMode(Intent intent, Bundle options) {
        throw new RuntimeException("STUB");
    }
    
    public void startMirageWindowMode(ComponentName cpnName, int taskId, int mode, Bundle options) {
        throw new RuntimeException("STUB");
    }
    
    public void stopMirageWindowMode() {
        throw new RuntimeException("STUB");
    }
    
    public void stopMirageWindowMode(Bundle options) {
        throw new RuntimeException("STUB");
    }
    
}
