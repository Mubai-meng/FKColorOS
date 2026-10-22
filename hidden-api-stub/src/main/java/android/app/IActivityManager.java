package android.app;

import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

public interface IActivityManager extends IInterface {
    
    abstract class Stub extends Binder implements IActivityManager {
        
        public static IActivityManager asInterface(IBinder iBinder) {
            throw new RuntimeException("STUB");
        }
        
    }
    
    void forceStopPackage(String packageName, int userId) throws RemoteException;
    
    UserInfo getCurrentUser() throws RemoteException;
    
}
