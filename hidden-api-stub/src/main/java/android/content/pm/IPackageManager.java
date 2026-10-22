package android.content.pm;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

public interface IPackageManager extends IInterface {
    
    abstract class Stub extends Binder implements IPackageManager {
        
        public static IPackageManager asInterface(IBinder iBinder) {
            throw new RuntimeException("STUB");
        }
        
    }
    
    void clearApplicationProfileData(String str) throws RemoteException;
    
    boolean performDexOptMode(String packageName, boolean checkProfiles, String targetCompilerFilter, boolean force, boolean bootComplete, String splitName) throws RemoteException;
    
}
