package android.os;

public interface IPowerManager extends IInterface {
    
    abstract class Stub extends Binder implements IPowerManager {
        
        public static IPowerManager asInterface(IBinder iBinder) {
            throw new RuntimeException("STUB");
        }
    }
    
    void reboot(boolean confirm, String reason, boolean wait) throws RemoteException;
    
    void rebootSafeMode(boolean confirm, boolean wait) throws RemoteException;
    
}

